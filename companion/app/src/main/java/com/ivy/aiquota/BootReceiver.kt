package com.ivy.aiquota

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.ivy.aiquota.config.AccountRepository

/**
 * 开机自启：系统启动完成后自动拉起桥接服务（开关在设置页）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val repo = AccountRepository(context)
        if (!repo.autoStartOnBoot) {
            AppLog.log("Boot", "开机自启已关闭，跳过")
            return
        }
        if (WearBridgeService.isRunning) {
            AppLog.log("Boot", "桥接服务已在运行，跳过")
            return
        }
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WearBridgeService::class.java)
            )
            AppLog.log("Boot", "开机自启：已拉起桥接服务")
        } catch (e: Exception) {
            AppLog.log("Boot", "开机自启失败: ${e.message}")
        }
    }
}