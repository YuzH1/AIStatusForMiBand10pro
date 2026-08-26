package com.ivy.aiquota.quota

import com.ivy.aiquota.config.AccountConfig
import com.ivy.aiquota.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * DeepSeek 开放平台余额。
 * GET {base}/user/balance，Authorization: Bearer <key>
 * 返回 balance_infos[0].total_balance / currency。
 */
class DeepSeekProvider : QuotaProvider {

    override suspend fun fetch(cfg: AccountConfig): QuotaAccount =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            try {
                val base = Http.normalizeApiBase(cfg.baseUrl).ifEmpty { "https://api.deepseek.com" }
                val resp = Http.getJson(
                    "$base/user/balance",
                    mapOf("Authorization" to "Bearer ${cfg.apiKey}")
                )
                if (!resp.optBoolean("is_available", false)) {
                    throw IOException("账户不可用: ${resp.optString("message", "unknown")}")
                }
                val infos = resp.optJSONArray("balance_infos")
                val info = infos?.optJSONObject(0) ?: org.json.JSONObject()
                val remaining = info.optDouble("total_balance", 0.0)
                val unit = info.optString("currency", "CNY").ifEmpty { "CNY" }

                QuotaAccount(
                    id = cfg.id,
                    name = cfg.name,
                    type = "deepseek",
                    remaining = remaining,
                    total = null,
                    unit = unit,
                    expiredAt = null,
                    group = "",
                    status = "ok",
                    error = null,
                    updatedAt = now
                )
            } catch (e: Exception) {
                QuotaAccount(
                    id = cfg.id,
                    name = cfg.name,
                    type = "deepseek",
                    remaining = 0.0,
                    total = null,
                    unit = "CNY",
                    expiredAt = null,
                    group = "",
                    status = "error",
                    error = e.message ?: "查询失败",
                    updatedAt = now
                )
            }
        }
}