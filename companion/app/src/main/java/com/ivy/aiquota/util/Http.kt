package com.ivy.aiquota.util

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {

    /** 去掉 BaseUrl 结尾的 /v1（各 Provider 再拼自己的路径） */
    fun normalizeApiBase(url: String): String {
        var b = url.trimEnd('/')
        if (b.endsWith("/v1")) b = b.dropLast(3)
        return b
    }

    fun getJson(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 20000): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IOException("HTTP $code: ${body.take(200)}")
            }
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }
}