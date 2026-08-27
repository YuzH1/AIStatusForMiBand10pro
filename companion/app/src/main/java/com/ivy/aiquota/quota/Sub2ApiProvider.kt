package com.ivy.aiquota.quota

import com.ivy.aiquota.AppLog
import com.ivy.aiquota.config.AccountConfig
import com.ivy.aiquota.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * Sub2API 中转网关额度。
 * GET {base}/v1/usage，Authorization: Bearer <apiKey>
 * 响应支持四种模式：
 *   1. subscription  日/周/月限额（daily/weekly/monthly_usage_usd + _limit_usd）
 *   2. quota         总额度（used / limit / remaining）
 *   3. rate_limits   仅速率限制窗口
 *   4. 兜底          仅 remaining / unit（余额制）
 * 另有 planName / unit / isValid / error 字段。
 */
class Sub2ApiProvider : QuotaProvider {

    private val tag = "AIQuota/Sub2API"

    override suspend fun fetch(cfg: AccountConfig): QuotaAccount =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            try {
                val base = cfg.baseUrl.trimEnd('/')
                if (base.isEmpty()) throw IOException("未配置 BaseUrl")

                val candidates = mutableListOf("$base/v1/usage")
                if (base.endsWith("/v1")) candidates.add("${base.dropLast(3)}/v1/usage")

                var resp: JSONObject? = null
                var lastErr: Exception? = null
                for (url in candidates) {
                    try {
                        resp = Http.getJson(url, mapOf("Authorization" to "Bearer ${cfg.apiKey}"))
                        break
                    } catch (e: Exception) {
                        lastErr = e
                        AppLog.log(tag, "尝试 $url 失败: ${e.message}")
                    }
                }
                val r = resp ?: throw (lastErr ?: IOException("查询失败"))

                if (!r.optBoolean("isValid", true)) {
                    val err = r.optJSONObject("error")?.optString("message")
                        ?: r.optString("message", "API Key 无效或查询失败")
                    throw IOException(err)
                }

                val plan = r.optString("planName", "")
                val unit = r.optString("unit", "USD").ifEmpty { "USD" }
                var remaining = r.optDouble("remaining", -1.0)
                var used: Double? = null
                var total: Double? = null
                val extra = StringBuilder()

                val sub = r.optJSONObject("subscription")
                if (sub != null) {
                    val dU = sub.optDouble("daily_usage_usd", -1.0)
                    val dL = sub.optDouble("daily_limit_usd", -1.0)
                    val wU = sub.optDouble("weekly_usage_usd", -1.0)
                    val wL = sub.optDouble("weekly_limit_usd", -1.0)
                    val mU = sub.optDouble("monthly_usage_usd", -1.0)
                    val mL = sub.optDouble("monthly_limit_usd", -1.0)
                    fun pct(used: Double, limit: Double): String? {
                        if (limit <= 0) return null
                        return "${((used / limit) * 100).toInt().coerceIn(0, 100)}%"
                    }
                    val parts = mutableListOf<String>()
                    pct(dU, dL)?.let { parts.add("日$it") }
                    pct(wU, wL)?.let { parts.add("周$it") }
                    pct(mU, mL)?.let { parts.add("月$it") }
                    if (parts.isNotEmpty()) extra.append(parts.joinToString(" · "))
                    val limit = if (mL > 0) mL else if (wL > 0) wL else if (dL > 0) dL else 0.0
                    val usedV = if (mU > 0) mU else if (wU > 0) wU else if (dU > 0) dU else 0.0
                    if (limit > 0) total = limit
                    if (remaining < 0) remaining = (limit - usedV).coerceAtLeast(0.0)
                    used = usedV
                }

                val quota = r.optJSONObject("quota")
                if (quota != null && total == null) {
                    val limit = quota.optDouble("limit", 0.0)
                    val usedV = quota.optDouble("used", 0.0)
                    val rem = quota.optDouble("remaining", -1.0)
                    if (limit > 0) {
                        total = limit
                        val p = "${((usedV / limit) * 100).toInt().coerceIn(0, 100)}%"
                        extra.append(if (extra.isEmpty()) p else " · $p")
                    }
                    remaining = if (rem >= 0) rem else (limit - usedV).coerceAtLeast(0.0)
                    used = usedV
                }

                val rls = r.optJSONArray("rate_limits")
                if (rls != null && rls.length() > 0 && total == null) {
                    var bestPct = -1
                    var bestRemaining = -1.0
                    var bestLimit = 0.0
                    val winParts = mutableListOf<String>()
                    for (i in 0 until rls.length()) {
                        val x = rls.optJSONObject(i) ?: continue
                        val win = x.optString("window", "")
                        val lim = x.optDouble("limit", 0.0)
                        val used = x.optDouble("used", 0.0)
                        val p = if (lim > 0) ((used / lim) * 100).toInt().coerceIn(0, 100) else null
                        winParts.add("$win ${p?.let { "${it}%" } ?: "∞"}")
                        if (p != null && p > bestPct) {
                            bestPct = p
                            bestRemaining = x.optDouble("remaining", -1.0)
                            bestLimit = lim
                        }
                    }
                    if (winParts.isNotEmpty()) {
                        extra.append(if (extra.isEmpty()) winParts.joinToString(" · ") else " · " + winParts.joinToString(" · "))
                    }
                    if (bestPct >= 0) {
                        total = bestLimit
                        remaining = if (bestRemaining >= 0) bestRemaining else (100 - bestPct).toDouble()
                    }
                }

                if (remaining < 0) remaining = 0.0

                QuotaAccount(
                    id = cfg.id,
                    name = cfg.name,
                    type = "sub2api",
                    remaining = remaining,
                    total = total,
                    unit = unit,
                    expiredAt = null,
                    group = plan,
                    status = "ok",
                    error = null,
                    updatedAt = now,
                    used = used,
                    detail = extra.toString()
                )
            } catch (e: Exception) {
                AppLog.log(tag, "查询失败: ${e.message}")
                QuotaAccount(
                    id = cfg.id,
                    name = cfg.name,
                    type = "sub2api",
                    remaining = 0.0,
                    total = null,
                    unit = "USD",
                    expiredAt = null,
                    group = "",
                    status = "error",
                    error = e.message ?: "查询失败",
                    updatedAt = now
                )
            }
        }
}