package com.ivy.aiquota

import android.util.Log
import com.ivy.aiquota.quota.QuotaManager
import org.json.JSONObject

/**
 * JSON-RPC 路由器：解析手表端请求，路由到对应处理逻辑并回包。
 */
class BridgeRouter(
    private val channel: WearChannel,
    private val quotaManager: QuotaManager,
    private val pollIntervalProvider: () -> Int
) {

    private val tag = "AIQuota/Router"

    fun handle(raw: String) {
        try {
            val obj = JSONObject(raw)
            if (!obj.optBoolean(WearConstants.RPC_MARKER, false)) return
            val id = obj.optLong(WearConstants.FIELD_ID)
            val method = obj.optString(WearConstants.FIELD_METHOD)
            val params = obj.optJSONObject(WearConstants.FIELD_PARAMS) ?: JSONObject()
            handleRpc(id, method, params)
        } catch (e: Exception) {
            Log.w(tag, "handle failed: $raw", e)
        }
    }

    private fun handleRpc(id: Long, method: String, params: JSONObject) {
        AppLog.log(tag, "RPC 请求: #$id $method")
        when (method) {
            WearConstants.Method.QUOTA_LIST -> {
                val result = JSONObject().put("accounts", quotaManager.accountsJson())
                AppLog.log(tag, "quota.list -> ${quotaManager.accountsCount()} 条")
                reply(id, result)
            }

            WearConstants.Method.QUOTA_REFRESH -> {
                quotaManager.refreshAsync { ok, err ->
                    if (ok) {
                        val result = JSONObject().put("accounts", quotaManager.accountsJson())
                        AppLog.log(tag, "quota.refresh 完成 -> ${quotaManager.accountsCount()} 条")
                        reply(id, result)
                    } else {
                        AppLog.log(tag, "quota.refresh 失败: $err")
                        replyError(id, WearConstants.Code.FETCH_FAILED, err ?: "fetch failed")
                    }
                }
            }

            WearConstants.Method.CONN_TEST -> {
                val result = JSONObject()
                    .put("ok", true)
                    .put("ts", System.currentTimeMillis())
                reply(id, result)
            }

            WearConstants.Method.CONFIG_GET -> {
                val result = JSONObject().put("pollIntervalMin", pollIntervalProvider())
                reply(id, result)
            }

            else -> replyError(id, WearConstants.Code.METHOD_NOT_FOUND, "method not found: $method")
        }
    }

    private fun reply(id: Long, result: JSONObject) {
        channel.send(
            JSONObject()
                .put(WearConstants.FIELD_ID, id)
                .put(WearConstants.FIELD_RESULT, result)
                .toString()
        )
    }

    private fun replyError(id: Long, code: Int, msg: String) {
        channel.send(
            JSONObject()
                .put(WearConstants.FIELD_ID, id)
                .put(
                    WearConstants.FIELD_ERROR,
                    JSONObject()
                        .put(WearConstants.FIELD_CODE, code)
                        .put(WearConstants.FIELD_MSG, msg)
                )
                .toString()
        )
    }
}