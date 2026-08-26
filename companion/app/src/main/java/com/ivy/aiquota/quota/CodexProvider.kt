package com.ivy.aiquota.quota

import com.ivy.aiquota.AppLog
import com.ivy.aiquota.config.AccountConfig
import com.ivy.aiquota.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Codex（ChatGPT 订阅）额度。
 * GET https://chatgpt.com/backend-api/wham/usage
 * Authorization: Bearer <~/.codex/auth.json 的 access_token>
 * 返回 rate_limit.primary_window（5小时已用%）/ secondary_window（7天已用%）/ credits.balance
 */
class CodexProvider : QuotaProvider {

    private val tag = "AIQuota/Codex"

    override suspend fun fetch(cfg: AccountConfig): QuotaAccount =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            try {
                val base = cfg.baseUrl.trimEnd('/').ifEmpty { "https://chatgpt.com" }
                val url = when {
                    base.contains("/backend-api") -> "$base/wham/usage"
                    base == "https://chatgpt.com" || base == "https://chat.openai.com" ->
                        "$base/backend-api/wham/usage"
                    else -> "$base/api/codex/usage"
                }
                val headers = mutableMapOf("Authorization" to "Bearer ${cfg.apiKey}")
                if (cfg.accountId.isNotEmpty()) {
                    headers["ChatGPT-Account-Id"] = cfg.accountId
                }
                val resp = Http.getJson(url, headers)

                val plan = resp.optString("plan_type", "")
                val rateLimit = resp.optJSONObject("rate_limit") ?: org.json.JSONObject()
                val primary = rateLimit.optJSONObject("primary_window")
                val usedPct = primary?.optInt("used_percent", 0) ?: 0
                val resetAt = primary?.optLong("reset_at", 0)
                val secondary = rateLimit.optJSONObject("secondary_window")
                val secPct = secondary?.optInt("used_percent", 0) ?: 0

                val credits = resp.optJSONObject("credits")
                val hasCredits = credits?.optBoolean("has_credits", false) ?: false
                val balance = if (credits != null) credits.optDouble("balance", 0.0) else 0.0

                val group = buildString {
                    append("5小时已用 ${usedPct}% · 7天已用 ${secPct}%")
                    if (hasCredits) append(" · 余额 $balance")
                    if (plan.isNotEmpty()) append(" · ${plan}")
                }

                QuotaAccount(
                    id = cfg.id,
                    name = cfg.name,
                    type = "codex",
                    remaining = (100 - usedPct).toDouble(),
                    total = 100.0,
                    unit = "%",
                    expiredAt = if (resetAt != null && resetAt > 0) resetAt * 1000 else null,
                    group = group,
                    status = "ok",
                    error = null,
                    updatedAt = now
                )
            } catch (e: Exception) {
                AppLog.log(tag, "查询失败: ${e.message}")
                QuotaAccount(
                    id = cfg.id,
                    name = cfg.name,
                    type = "codex",
                    remaining = 0.0,
                    total = 100.0,
                    unit = "%",
                    expiredAt = null,
                    group = "",
                    status = "error",
                    error = e.message ?: "查询失败",
                    updatedAt = now
                )
            }
        }
}