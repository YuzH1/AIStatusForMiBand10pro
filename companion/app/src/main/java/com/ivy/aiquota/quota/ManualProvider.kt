package com.ivy.aiquota.quota

import com.ivy.aiquota.config.AccountConfig

/**
 * 手动额度账户：没有查询 API 的平台（如 ChatGPT Plus 订阅）用，
 * 用户直接在 App 里维护数字，不发起网络请求。
 */
class ManualProvider : QuotaProvider {

    override suspend fun fetch(cfg: AccountConfig): QuotaAccount {
        val total = cfg.manualTotal
        return QuotaAccount(
            id = cfg.id,
            name = cfg.name,
            type = "manual",
            remaining = cfg.manualRemaining,
            total = total,
            unit = cfg.manualUnit.ifEmpty { "USD" },
            expiredAt = null,
            group = "手动",
            status = "ok",
            error = null,
            updatedAt = System.currentTimeMillis(),
            used = if (total != null) (total - cfg.manualRemaining).coerceAtLeast(0.0) else null,
            detail = "手动维护"
        )
    }
}