package com.ivy.aiquota.quota

import com.ivy.aiquota.AppLog
import com.ivy.aiquota.config.AccountConfig
import com.ivy.aiquota.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * OpenCode Go 订阅额度（opencode.ai/zen/go，$10/月）。
 * GET https://opencode.ai/zen/go/v1/usage，Authorization: Bearer <apiKey>
 * 返回 { usage: { rolling: {status, percent, resetsAt}, weekly: {...}, monthly: {...} } }
 * percent 为已用百分比（与官方控制台一致）。
 */
class OpenCodeGoProvider : QuotaProvider {

    private val tag = "AIQuota/OpenCodeGo"

    override suspend fun fetch(cfg: AccountConfig): QuotaAccount =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            try {
                val base = cfg.baseUrl.trimEnd('/').ifEmpty { "https://opencode.ai" }

                val candidates = mutableListOf("$base/zen/go/v1/usage")
                if (base.endsWith("/v1")) {
                    candidates.add("$base/usage")
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

                val usage = finalResp.optJSONObject("usage")
                    ?: throw IOException("响应缺少 usage 字段")

                val rolling = usage.optJSONObject("rolling")
                    ?: throw IOException("缺少 rolling（5小时）窗口")

                fun windowPercent(w: JSONObject?): Int? {
                    if (w == null) return null
                    if (w.optString("status", "") != "ok") return null
                    return if (w.has("percent")) w.optInt("percent", 0) else null
                }

                val rPct = windowPercent(rolling) ?: throw IOException("rolling 窗口不可用")
                val wPct = windowPercent(usage.optJSONObject("weekly"))
                val mPct = windowPercent(usage.optJSONObject("monthly"))

                val detail = buildString {
                    append("5小时已用 $rPct%")
                    if (wPct != null) append(" · 周已用 $wPct%")
                    if (mPct != null) append(" · 月已用 $mPct%")
                }

                QuotaAccount(
                    id = cfg.id,
                    name = cfg.name,
                    type = "opencodego",
                    remaining = (100 - rPct).toDouble(),
                    total = 100.0,
                    unit = "%",
                    expiredAt = null,
                    group = "OpenCode Go",
                    status = "ok",
                    error = null,
                    updatedAt = now,
                    used = rPct.toDouble(),
                    detail = detail
                )
            } catch (e: Exception) {
                AppLog.log(tag, "查询失败: ${e.message}")
                QuotaAccount(
                    id = cfg.id,
                    name = cfg.name,
                    type = "opencodego",
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