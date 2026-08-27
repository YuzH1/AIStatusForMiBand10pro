package com.ivy.aiquota.config

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AccountConfig(
    val id: String,
    val name: String,
    val type: String,
    val baseUrl: String,
    val apiKey: String,
    val threshold: Double,
    val manualRemaining: Double = 0.0,
    val manualTotal: Double? = null,
    val manualUnit: String = "USD",
    val accountId: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", type)
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("threshold", threshold)
        put("manualRemaining", manualRemaining)
        if (manualTotal != null) put("manualTotal", manualTotal)
        put("manualUnit", manualUnit)
        put("accountId", accountId)
    }

    companion object {
        fun fromJson(o: JSONObject): AccountConfig = AccountConfig(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", "未命名"),
            type = o.optString("type", "oneapi"),
            baseUrl = o.optString("baseUrl", ""),
            apiKey = o.optString("apiKey", ""),
            threshold = o.optDouble("threshold", 10.0),
            manualRemaining = o.optDouble("manualRemaining", 0.0),
            manualTotal = if (o.has("manualTotal") && !o.isNull("manualTotal")) o.optDouble("manualTotal", 0.0) else null,
            manualUnit = o.optString("manualUnit", "USD"),
            accountId = o.optString("accountId", "")
        )
    }
}

class AccountRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("ai_quota", Context.MODE_PRIVATE)

    fun loadAccounts(): List<AccountConfig> {
        val raw = prefs.getString(KEY_ACCOUNTS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { AccountConfig.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveAccounts(list: List<AccountConfig>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit()
            .putString(KEY_ACCOUNTS, arr.toString())
            .putLong(KEY_CONFIG_MODIFIED_AT, System.currentTimeMillis())
            .apply()
    }

    fun upsertAccount(cfg: AccountConfig) {
        val list = loadAccounts().toMutableList()
        val idx = list.indexOfFirst { it.id == cfg.id }
        if (idx >= 0) list[idx] = cfg else list.add(cfg)
        saveAccounts(list)
    }

    fun removeAccount(id: String) {
        saveAccounts(loadAccounts().filterNot { it.id == id })
    }

    var pollIntervalMin: Int
        get() = prefs.getInt(KEY_INTERVAL, 10).coerceIn(1, 1440)
        set(value) = prefs.edit().putInt(KEY_INTERVAL, value.coerceIn(1, 1440)).apply()

    var notifyOnChange: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_CHANGE, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_CHANGE, value).apply()

    var notifyLow: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_LOW, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_LOW, value).apply()

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    var configModifiedAt: Long
        get() = prefs.getLong(KEY_CONFIG_MODIFIED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_CONFIG_MODIFIED_AT, value).apply()

    var notifyCooldownMin: Int
        get() = prefs.getInt(KEY_NOTIFY_COOLDOWN, 30).coerceIn(1, 1440)
        set(value) = prefs.edit().putInt(KEY_NOTIFY_COOLDOWN, value.coerceIn(1, 1440)).apply()

    companion object {
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_INTERVAL = "poll_interval_min"
        private const val KEY_NOTIFY_CHANGE = "notify_on_change"
        private const val KEY_NOTIFY_LOW = "notify_low"
        private const val KEY_AUTO_START = "auto_start_on_boot"
        private const val KEY_CONFIG_MODIFIED_AT = "config_modified_at"
        private const val KEY_NOTIFY_COOLDOWN = "notify_cooldown_min"
    }
}