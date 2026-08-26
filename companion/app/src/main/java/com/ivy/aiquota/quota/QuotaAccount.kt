package com.ivy.aiquota.quota

import org.json.JSONObject

data class QuotaAccount(
    val id: String,
    val name: String,
    val type: String,
    val remaining: Double,
    val total: Double?,
    val unit: String,
    val expiredAt: Long?,
    val group: String,
    val status: String,
    val error: String?,
    val updatedAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", type)
        put("remaining", remaining)
        if (total != null) put("total", total)
        put("unit", unit)
        if (expiredAt != null) put("expiredAt", expiredAt)
        put("group", group)
        put("status", status)
        if (error != null) put("error", error)
        put("updatedAt", updatedAt)
    }
}