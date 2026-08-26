package com.ivy.aiquota.quota

import com.ivy.aiquota.config.AccountConfig

interface QuotaProvider {
    suspend fun fetch(cfg: AccountConfig): QuotaAccount

    companion object {
        fun create(type: String): QuotaProvider = when (type) {
            "openai" -> OpenAIProvider()
            "deepseek" -> DeepSeekProvider()
            "codex" -> CodexProvider()
            "sub2api" -> Sub2ApiProvider()
            "manual" -> ManualProvider()
            else -> OneApiProvider()
        }

        fun label(type: String): String = when (type) {
            "oneapi" -> "中转"
            "openai" -> "OpenAI"
            "deepseek" -> "DeepSeek"
            "codex" -> "Codex"
            "sub2api" -> "Sub2API"
            "manual" -> "手动"
            else -> type
        }
    }
}