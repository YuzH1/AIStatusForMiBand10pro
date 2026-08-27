package com.ivy.aiquota.quota

import com.ivy.aiquota.AppLog
import com.ivy.aiquota.config.AccountConfig
import com.ivy.aiquota.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * OpenAI 官方额度。
 * GET /v1/dashboard/billing/subscription -> hard_limit_usd / system_hard_limit_usd
 * GET /v1/dashboard/billing/usage?start_date&end_date -> total_usage（美元）
 * remaining = 限额 - 本期已用。
 */
class OpenAIProvider : QuotaProvider {

    private val tag = "AIQuota/OpenAI"

    override suspend fun fetch(cfg: AccountConfig): QuotaAccount =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            try {
                val base = Http.normalizeApiBase(cfg.baseUrl).ifEmpty { "https://api.openai.com" }
                val auth = mapOf("Authorization" to "Bearer ${cfg.apiKey}")

                val sub = Http.getJson("$base/v1/dashboard/billing/subscription", auth)
                val hard = if (sub.isNull("hard_limit_usd")) 0.0 else sub.optDouble("hard_limit_usd", 0.0)
                val sysHard = if (sub.isNull("system_hard_limit_usd")) 0.0 else sub.optDouble("system_hard_limit_usd", 0.0)
                val soft = if (sub.isNull("soft_limit_usd")) 0.0 else sub.optDouble("soft_limit_usd", 0.0)
                val totalLimit0 = when {
                    hard > 0 -> hard
                    sysHard > 0 -> sysHard
                    else -> soft
                }
                val accessUntil = if (sub.has("access_until") && !sub.isNull("access_until")) {
                    sub.getLong("access_until")
                } else null

                val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val today = dateFmt.format(Date())
                val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
                val monthStart = dateFmt.format(cal.time)

                var totalLimit: Double
                var remaining: Double
                var totalUsage: Double

                if (totalLimit0 > 0) {
                    // 订阅制：限额 - 本期已用
                    val usage = Http.getJson(
                        "$base/v1/dashboard/billing/usage?start_date=$monthStart&end_date=$today",
                        auth
                    )
                    totalUsage = usage.optDouble("total_usage", 0.0)
                    totalLimit = totalLimit0
                    remaining = (totalLimit - totalUsage).coerceAtLeast(0.0)
                } else {
                    // prepaid 充值制：尝试 credit_grants（total_granted / total_used / total_available）
                    totalLimit = 0.0
                    totalUsage = 0.0
                    try {
                        val grants = Http.getJson("$base/v1/dashboard/billing/credit_grants", auth)
                        val granted = grants.optDouble("total_granted", 0.0)
                        val available = grants.optDouble("total_available", 0.0)
                        if (granted > 0) {
                            totalLimit = granted
                            totalUsage = (granted - available).coerceAtLeast(0.0)
                            remaining = available
                        } else {
                            remaining = 0.0
                        }
                    } catch (e: Exception) {
                        AppLog.log(tag, "credit_grants 查询失败: ${e.message}")
                        remaining = 0.0
                    }
                }

                QuotaAccount(
                    id = cfg.id,
                    name = cfg.name,
                    type = "openai",
                    remaining = remaining,
                    total = if (totalLimit > 0) totalLimit else null,
                    unit = "$",
                    expiredAt = if (accessUntil != null && accessUntil > 0) accessUntil * 1000 else null,
                    group = sub.optString("plan", ""),
                    status = "ok",
                    error = null,
                    updatedAt = now,
                    used = if (totalLimit > 0) totalUsage else null
                )
            } catch (e: Exception) {
                QuotaAccount(
                    id = cfg.id,
                    name = cfg.name,
                    type = "openai",
                    remaining = 0.0,
                    total = null,
                    unit = "$",
                    expiredAt = null,
                    group = "",
                    status = "error",
                    error = e.message ?: "查询失败",
                    updatedAt = now
                )
            }
        }
}