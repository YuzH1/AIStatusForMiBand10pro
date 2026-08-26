package com.ivy.aiquota.quota

import android.util.Log
import com.ivy.aiquota.AppLog
import com.ivy.aiquota.WearChannel
import com.ivy.aiquota.WearConstants
import com.ivy.aiquota.config.AccountConfig
import com.ivy.aiquota.config.AccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 额度轮询管理器：按配置间隔拉取所有账户额度，
 * 结果推送 update.quota 事件给手表端，并在额度低于阈值时触发提醒。
 */
class QuotaManager(
    private val repo: AccountRepository,
    private val channel: WearChannel,
    private val onThreshold: (AccountConfig, QuotaAccount) -> Unit,
    private val onRefreshed: (List<QuotaAccount>) -> Unit
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tag = "AIQuota/Manager"

    @Volatile
    private var last: List<QuotaAccount> = emptyList()

    private val notifiedLow = HashSet<String>()

    private var lastSnapshot: String? = null

    fun start() {
        scope.launch {
            while (isActive) {
                val intervalMs = repo.pollIntervalMin * 60_000L
                refreshInternal()
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    fun accountsJson(): JSONArray = JSONArray().apply {
        last.forEach { put(it.toJson()) }
    }

    fun accountsCount(): Int = last.size

    fun refreshAsync(callback: (Boolean, String?) -> Unit) {
        scope.launch {
            val ok = try {
                refreshInternal()
            } catch (e: Exception) {
                AppLog.log(tag, "refresh 异常: ${e.message}")
                false
            }
            callback(ok, if (ok) null else "fetch failed")
        }
    }

    private suspend fun refreshInternal(): Boolean {
        val cfgs = repo.loadAccounts()
        AppLog.log(tag, "读取到 ${cfgs.size} 个账户配置")
        if (cfgs.isEmpty()) {
            last = emptyList()
            pushUpdate()
            return true
        }
        var allOk = true
        val results = cfgs.map { cfg ->
            val provider = QuotaProvider.create(cfg.type)
            val acc = provider.fetch(cfg)
            AppLog.log(
                tag,
                "${cfg.name}(${cfg.type}) -> status=${acc.status} remaining=${acc.remaining}${acc.unit} err=${acc.error ?: "-"}"
            )
            if (acc.status != "ok") allOk = false
            acc
        }
        last = results
        checkThresholds(cfgs, results)
        pushUpdate()
        notifyOnChange(results)
        AppLog.log(tag, "刷新完成: ${results.size} 个账户, allOk=$allOk")
        return allOk
    }

    private fun notifyOnChange(results: List<QuotaAccount>) {
        if (results.isEmpty()) return
        val snapshot = results.joinToString("|") { "${it.id}:${it.remaining}:${it.status}" }
        if (snapshot == lastSnapshot) return
        lastSnapshot = snapshot
        AppLog.log(tag, "额度有变化，触发通知回调")
        onRefreshed(results)
    }

    private fun checkThresholds(cfgs: List<AccountConfig>, results: List<QuotaAccount>) {
        results.forEach { acc ->
            val cfg = cfgs.find { it.id == acc.id } ?: return@forEach
            val pct = if (acc.total != null && acc.total > 0) {
                acc.remaining / acc.total * 100
            } else null
            if (acc.status == "ok" && pct != null && pct <= cfg.threshold) {
                if (notifiedLow.add(acc.id)) {
                    Log.i(tag, "threshold hit: ${acc.name} $pct%")
                    onThreshold(cfg, acc)
                }
            } else if (pct != null && pct > cfg.threshold) {
                notifiedLow.remove(acc.id)
            }
        }
    }

    private fun pushUpdate() {
        if (!channel.isReady()) {
            AppLog.log(tag, "推送 update.quota 跳过（手环未连接）")
            return
        }
        val payload = JSONObject()
            .put(WearConstants.EVENT_MARKER, true)
            .put(WearConstants.FIELD_EVENT, WearConstants.Event.QUOTA_UPDATE)
            .put(
                WearConstants.FIELD_DATA,
                JSONObject().put("accounts", accountsJson())
            )
        channel.send(payload.toString())
        AppLog.log(tag, "已推送 update.quota (${last.size} 条)")
    }

    fun destroy() {
        stop()
        notifiedLow.clear()
    }
}