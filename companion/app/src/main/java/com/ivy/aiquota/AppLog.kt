package com.ivy.aiquota

import android.util.Log

/**
 * 全局内存日志缓冲：服务/路由器/额度管理器写入，MainActivity 定期回显到界面。
 * 同时写 logcat，方便 adb 查看。
 */
object AppLog {

    private val buffer = ArrayDeque<String>()

    @Synchronized
    fun log(tag: String, msg: String) {
        val line = "[$tag] $msg"
        buffer.addFirst(line)
        while (buffer.size > 300) buffer.removeLast()
        Log.i("AIQuota/$tag", msg)
    }

    @Synchronized
    fun dump(): List<String> = buffer.toList()

    @Synchronized
    fun clear() {
        buffer.clear()
    }
}