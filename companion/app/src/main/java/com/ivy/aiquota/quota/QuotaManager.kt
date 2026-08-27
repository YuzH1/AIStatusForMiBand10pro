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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 额度轮询管理器：按配置间隔拉取所有账户额度，
 * 结果推送 update.quota 事件给手表端，并在额度低于阈值/临近到期时触发提醒。
 */
class QuotaManager(
    private val repo: AccountRepository,
    private val channel: WearChannel,
    private val onThreshold: (AccountConfig, QuotaAccount) -> Unit,
    private val onRefreshed: (List<QuotaAccount>) -> Unit,
    private val onExpiry: (AccountConfig, QuotaAccount, Double) -> Unit
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tag = "AIQuota/Manager"

    /** 到期/重置提醒阈值（小时），进入该窗口内每天提醒一次 */
    private val expiryRemindHours = 72.0

    /** 首轮失败的账户延迟多久重试一次（毫秒） */
    private val retryDelayMs = 10_000L

    /** 连续失败多少次后自动停用该账户 */
    private val maxFailures = 3

    /** 停用账户每多少轮探测一次是否恢复 */
    private val probeCycles = 12

    @Volatile
    private var last: List<QuotaAccount> = emptyList()

    private val notifiedLow = HashSet<String>()

    private val notifiedExpiry = HashMap<String, String>()

    private val consecutiveFailures = HashMap<String, Int>()

    private val disabledIds = HashSet<String>()

    private val probeCounters = HashMap<String, Int>()

    private var lastConfigModTs = 0L

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

        val cfgTs = repo.configModifiedAt
        if (cfgTs != lastConfigModTs) {
            lastConfigModTs = cfgTs
            if (disabledIds.isNotEmpty() || consecutiveFailures.isNotEmpty()) {
                AppLog.log(tag, "配置已变更，重置停用/失败计数")
                disabledIds.clear()
                consecutiveFailures.clear()
                probeCounters.clear()
            }
        }

        val results = cfgs.map { cfg ->
            val acc = fetchAccount(cfg)
            if (acc.status == "ok") {
                consecutiveFailures.remove(cfg.id)
                if (disabledIds.remove(cfg.id)) {
                    AppLog.log(tag, "${cfg.name} 已恢复启用")
                }
            }
            AppLog.log(
                tag,
                "${cfg.name}(${cfg.type}) -> status=${acc.status} remaining=${acc.remaining}${acc.unit} err=${acc.error ?: "-"}"
            )
            acc
        }.toMutableList()

        last = results
        val allOk = results.filter { it.status != "disabled" }.all { it.status == "ok" }
        checkThresholds(cfgs, results)
        checkExpiry(cfgs, results)
        pushUpdate()
        notifyOnChange(results)
        AppLog.log(tag, "刷新完成: ${results.size} 个账户, allOk=$allOk")
        return allOk
    }

    private suspend fun fetchAccount(cfg: AccountConfig): QuotaAccount {
        val now = System.currentTimeMillis()
        if (disabledIds.contains(cfg.id)) {
            val cycles = probeCounters[cfg.id] ?: 0
            if (cycles < probeCycles) {
                probeCounters[cfg.id] = cycles + 1
                return QuotaAccount(
                    id = cfg.id,
                    name = cfg.name,
                    type = cfg.type,
                    remaining = 0.0,
                    total = null,
                    unit = "",
                    expiredAt = null,
                    group = "",
                    status = "disabled",
                    error = "连续失败已停用",
                    updatedAt = now
                )
            }
            probeCounters.remove(cfg.id)
            AppLog.log(tag, "探测停用账户 ${cfg.name} ...")
        }

        val provider = QuotaProvider.create(cfg.type)
        var acc = provider.fetch(cfg)
        if (acc.status != "ok") {
            AppLog.log(tag, "${cfg.name}(${cfg.type}) 首轮失败，${retryDelayMs / 1000}s 后重试一次")
            delay(retryDelayMs)
            acc = provider.fetch(cfg)
            AppLog.log(tag, "重试 ${cfg.name}(${cfg.type}) -> status=${acc.status} err=${acc.error ?: "-"}")
        }
        if (acc.status != "ok") {
            val n = (consecutiveFailures[cfg.id] ?: 0) + 1
            consecutiveFailures[cfg.id] = n
            if (n >= maxFailures) {
                disabledIds.add(cfg.id)
                probeCounters[cfg.id] = 0
                AppLog.log(tag, "${cfg.name} 连续失败 $n 次，已自动停用（每 ${probeCycles} 轮探测一次）")
            }
        }
        return acc
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

    private fun checkExpiry(cfgs: List<AccountConfig>, results: List<QuotaAccount>) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        results.forEach { acc ->
            val expiredAt = acc.expiredAt ?: return@forEach
            val cfg = cfgs.find { it.id == acc.id } ?: return@forEach
            if (acc.status != "ok") return@forEach
            val hoursLeft = (expiredAt - System.currentTimeMillis()) / 3_600_000.0
            if (hoursLeft <= 0 || hoursLeft > expiryRemindHours) return@forEach
            if (notifiedExpiry[acc.id] == today) return@forEach
            notifiedExpiry[acc.id] = today
            AppLog.log(tag, "到期提醒: ${acc.name} 剩余 ${"%.1f".format(hoursLeft)} 小时")
            onExpiry(cfg, acc, hoursLeft)
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
        notifiedExpiry.clear()
        consecutiveFailures.clear()
        disabledIds.clear()
        probeCounters.clear()
    }
}