package com.ivy.aiquota.quota

import com.ivy.aiquota.AppLog
import com.ivy.aiquota.config.AccountConfig
import com.ivy.aiquota.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * one-api / new-api 中转站额度。
 * GET {base}/api/status，Authorization: Bearer <token>
 * data.quota 为积分（500000 积分 = $5），data.balance 为余额字符串（老版本）。
 * 注意：/api/status 位于站点根路径，自动兼容填了 /v1 后缀的情况。
 */
class OneApiProvider : QuotaProvider {

    private val tag = "AIQuota/OneApi"

    override suspend fun fetch(cfg: AccountConfig): QuotaAccount =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            try {
                val base = cfg.baseUrl.trimEnd('/')
                if (base.isEmpty()) throw IOException("未配置 BaseUrl")

                val candidates = mutableListOf("$base/api/status")
                if (base.endsWith("/v1")) {
                    candidates.add("${base.dropLast(3)}/api/status")
                }
                candidates.add("$base/api/user/self")
                if (base.endsWith("/v1")) {
                    candidates.add("${base.dropLast(3)}/api/user/self")
                }

                var resp: JSONObject? = null
                var lastErr: Exception? = null
                for (url in candidates) {
                    try {
                        resp = Http.getJson(
                            url,
                            mapOf("Authorization" to "Bearer ${cfg.apiKey}")
                        )
                        break
                    } catch (e: Exception) {
                        lastErr = e
                        AppLog.log(tag, "尝试 $url 失败: ${e.message}")
                    }
                }
                val finalResp = resp ?: throw (lastErr ?: IOException("查询失败"))

                if (!finalResp.optBoolean("success", false)) {
                    throw IOException("接口返回失败: ${finalResp.optString("message", "unknown")}")
                }
                val data = finalResp.optJSONObject("data") ?: JSONObject()

                var remaining = 0.0
                val quota = data.optLong("quota", 0)
                if (quota > 0) {
                    remaining = quota / 500000.0
                } else {
                    val balance = data.optString("balance", "0").toDoubleOrNull() ?: 0.0
                    remaining = balance
                }
                val unit = data.optString("currency", "USD").ifEmpty { "USD" }
                val group = data.optString("default_model", "")

                QuotaAccount(
                    id = cfg.id,
                    name = cfg.name,
                    type = "oneapi",
                    remaining = remaining,
                    total = null,
                    unit = unit,
                    expiredAt = null,
                    group = group,
                    status = "ok",
                    error = null,
                    updatedAt = now
                )
            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("404") == true ->
                        "接口 404：该中转站没有 /api/status 或 /api/user/self。若为纯 Codex 中转，请改用「手动」类型"
                    else -> e.message ?: "查询失败"
                }
                AppLog.log(tag, "查询失败: $msg")
                QuotaAccount(
                    id = cfg.id,
                    name = cfg.name,
                    type = "oneapi",
                    remaining = 0.0,
                    total = null,
                    unit = "USD",
                    expiredAt = null,
                    group = "",
                    status = "error",
                    error = msg,
                    updatedAt = now
                )
            }
        }
}