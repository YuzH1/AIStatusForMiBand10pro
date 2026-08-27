package com.ivy.aiquota

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ivy.aiquota.config.AccountConfig
import com.ivy.aiquota.config.AccountRepository
import com.ivy.aiquota.quota.QuotaAccount
import com.ivy.aiquota.quota.QuotaManager
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.MessageApi
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.NodeApi
import com.xiaomi.xms.wearable.notify.NotifyApi
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener
import com.xiaomi.xms.wearable.service.ServiceApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 桥接服务：连接小米穿戴 SDK，接收手环快应用消息并路由，
 * 同时驱动额度轮询并把结果推送给手环。
 */
class WearBridgeService : Service() {

    companion object {
        private const val TAG = "AIQuota/Service"
        private const val CHANNEL_ID = "ai_quota_bridge"
        private const val NOTIFICATION_ID = 0x4711
        private const val NODE_RETRY_MS = 5000L

        const val ACTION_TEST_NOTIFY = "com.ivy.aiquota.action.TEST_NOTIFY"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var nodeApi: NodeApi? = null
    private var messageApi: MessageApi? = null
    private var notifyApi: NotifyApi? = null
    private var serviceApi: ServiceApi? = null

    @Volatile
    private var nodeId: String? = null

    private var lastReady = false
    private var lastListenerRegister = 0L

    private lateinit var repo: AccountRepository
    private var manager: QuotaManager? = null
    private var router: BridgeRouter? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var connectJob: Job? = null

    private val channel = object : WearChannel {
        override fun send(json: String) {
            sendToWatch(json)
        }

        override fun isReady(): Boolean = nodeId != null
    }

    private val messageListener = OnMessageReceivedListener { did, message ->
        try {
            val raw = String(message, Charsets.UTF_8)
            AppLog.log(TAG, "收到手环消息: $raw")
            router?.handle(raw)
        } catch (e: Exception) {
            AppLog.log(TAG, "onMessage failed: ${e.message}")
        }
    }

    private val serviceConnectionListener = object : OnServiceConnectionListener {
        override fun onServiceConnected() {
            AppLog.log(TAG, "小米穿戴服务已连接（重新握手）")
            lastReady = false
            connectToBand()
        }

        override fun onServiceDisconnected() {
            AppLog.log(TAG, "小米穿戴服务断开")
            lastReady = false
            nodeId = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.log(TAG, "onCreate")
        isRunning = true
        ensureChannel()

        repo = AccountRepository(this)
        AppLog.log(TAG, "账户数: ${repo.loadAccounts().size}")
        try {
            nodeApi = Wearable.getNodeApi(this)
            messageApi = Wearable.getMessageApi(this)
            notifyApi = Wearable.getNotifyApi(this)
            serviceApi = Wearable.getServiceApi(this)
            serviceApi?.registerServiceConnectionListener(serviceConnectionListener)
            AppLog.log(TAG, "SDK 初始化成功（依赖小米运动健康）")
        } catch (e: Exception) {
            AppLog.log(TAG, "SDK init failed（请确认已安装小米运动健康）: ${e.message}")
        }

        val mgr = QuotaManager(
            repo,
            channel,
            { cfg, acc -> sendLowQuotaNotify(cfg, acc) },
            { results -> onQuotaRefreshed(results) },
            { cfg, acc, hours -> sendExpiryNotify(cfg, acc, hours) }
        )
        manager = mgr
        router = BridgeRouter(channel, mgr) { repo.pollIntervalMin }
        mgr.start()

        connectJob = scope.launch {
            while (true) {
                connectToBand()
                delay(NODE_RETRY_MS)
            }
        }
        AppLog.log(TAG, "service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (intent?.action == ACTION_TEST_NOTIFY) {
            AppLog.log(TAG, "收到测试通知请求")
            sendBandNotify("AI额度", "通知通道测试成功")
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI额度桥接")
            .setContentText("正在与手环同步额度数据")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AI额度桥接", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "手环额度数据同步" }
            )
        }
    }

    private fun connectToBand() {
        val nodeApi = this.nodeApi ?: return
        nodeApi.connectedNodes
            ?.addOnSuccessListener { nodes ->
                if (nodes.isNotEmpty()) {
                    val node = nodes[0]
                    nodeId = node.id
                    if (!lastReady) {
                        lastReady = true
                        AppLog.log(TAG, "手环节点已连接: ${node.id}")
                        requestPermissions(node.id)
                        registerMessageListener(node.id)
                        pushConnectionState(true)
                    } else {
                        // SDK 服务重启/运动健康重连后监听可能失效，周期性重挂（每 60s）
                        val now = System.currentTimeMillis()
                        if (now - lastListenerRegister > 60_000L) {
                            registerMessageListener(node.id)
                        }
                    }
                } else {
                    if (lastReady) {
                        AppLog.log(TAG, "手环节点断开")
                        pushConnectionState(false)
                    }
                    lastReady = false
                    nodeId = null
                }
            }
            ?.addOnFailureListener { e ->
                AppLog.log(TAG, "connectedNodes failed: ${e.message}")
            }
    }

    private fun requestPermissions(nodeId: String) {
        val authApi = try {
            Wearable.getAuthApi(this)
        } catch (e: Exception) {
            return
        }
        authApi.checkPermissions(nodeId, arrayOf(Permission.DEVICE_MANAGER, Permission.NOTIFY))
            ?.addOnSuccessListener { results ->
                val missing = results.withIndex()
                    .filter { it.value != true }
                    .map { arrayOf(Permission.DEVICE_MANAGER, Permission.NOTIFY)[it.index].name }
                if (missing.isEmpty()) {
                    AppLog.log(TAG, "权限已授予")
                    return@addOnSuccessListener
                }
                authApi.requestPermission(nodeId, Permission.DEVICE_MANAGER, Permission.NOTIFY)
                    ?.addOnSuccessListener { granted ->
                        AppLog.log(TAG, "权限授予成功: ${granted.joinToString { it.name }}")
                    }
                    ?.addOnFailureListener { e ->
                        AppLog.log(TAG, "requestPermission failed: ${e.message}")
                    }
            }
            ?.addOnFailureListener { e ->
                AppLog.log(TAG, "checkPermissions failed: ${e.message}")
            }
    }

    private fun registerMessageListener(nodeId: String) {
        lastListenerRegister = System.currentTimeMillis()
        messageApi?.addListener(nodeId, messageListener)
            ?.addOnSuccessListener { AppLog.log(TAG, "消息监听注册成功") }
            ?.addOnFailureListener { e -> AppLog.log(TAG, "addListener failed: ${e.message}") }
    }

    private fun pushConnectionState(ready: Boolean) {
        val id = nodeId ?: return
        val payload = org.json.JSONObject()
            .put(WearConstants.EVENT_MARKER, true)
            .put(WearConstants.FIELD_EVENT, WearConstants.Event.CONNECTION_STATE)
            .put(WearConstants.FIELD_DATA, org.json.JSONObject().put("ready", ready))
        try {
            messageApi?.sendMessage(id, payload.toString().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "push connection state failed", e)
        }
    }

    private fun sendToWatch(json: String) {
        val id = nodeId ?: run {
            AppLog.log(TAG, "发送失败：手环节点未连接")
            return
        }
        try {
            messageApi?.sendMessage(id, json.toByteArray(Charsets.UTF_8))
                ?.addOnSuccessListener { AppLog.log(TAG, "已发送给手环: ${json.take(100)}") }
                ?.addOnFailureListener { e -> AppLog.log(TAG, "发送失败: ${e.message}") }
        } catch (e: Exception) {
            AppLog.log(TAG, "发送异常: ${e.message}")
        }
    }

    private fun onQuotaRefreshed(results: List<QuotaAccount>) {
        if (!repo.notifyOnChange) {
            AppLog.log(TAG, "额度变化通知已关闭，跳过")
            return
        }
        val summary = results.joinToString(" · ") {
            "${it.name} ${"%.2f".format(it.remaining)}${it.unit}"
        }
        sendBandNotify("AI额度", summary)
    }

    private fun sendBandNotify(title: String, message: String) {
        val id = nodeId ?: run {
            AppLog.log(TAG, "通知发送失败：手环未连接")
            return
        }
        val notifyApi = this.notifyApi ?: run {
            AppLog.log(TAG, "通知发送失败：NotifyApi 未初始化")
            return
        }
        try {
            notifyApi.sendNotify(id, title, message)
                ?.addOnSuccessListener { status ->
                    if (status.isSuccess) AppLog.log(TAG, "手环通知已发送: $title")
                    else AppLog.log(TAG, "手环通知发送失败: $title")
                }
                ?.addOnFailureListener { e ->
                    AppLog.log(TAG, "sendNotify failed: ${e.message}")
                }
        } catch (e: Exception) {
            AppLog.log(TAG, "sendNotify 异常: ${e.message}")
        }
    }

    private fun sendLowQuotaNotify(cfg: AccountConfig, acc: QuotaAccount) {
        if (!repo.notifyLow) {
            AppLog.log(TAG, "低额度提醒已关闭，跳过 ${acc.name}")
            return
        }
        val title = "额度不足: ${acc.name}"
        val msg = "剩余 ${"%.2f".format(acc.remaining)}${acc.unit}"
        sendBandNotify(title, msg)
    }

    private fun sendExpiryNotify(cfg: AccountConfig, acc: QuotaAccount, hoursLeft: Double) {
        if (!repo.notifyLow) {
            AppLog.log(TAG, "到期提醒已关闭（跟随低额度开关），跳过 ${acc.name}")
            return
        }
        val word = if (acc.type == "codex") "重置" else "到期"
        val remain = if (hoursLeft >= 24) {
            "${(hoursLeft / 24).toInt()} 天后$word"
        } else {
            "${hoursLeft.toInt()} 小时后$word"
        }
        sendBandNotify("额度提醒: ${acc.name}", "剩余 ${"%.2f".format(acc.remaining)}${acc.unit} · $remain")
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        isRunning = false
        connectJob?.cancel()
        manager?.destroy()
        manager = null
        nodeId?.let { id ->
            try {
                messageApi?.removeListener(id)
            } catch (e: Exception) {
                Log.w(TAG, "removeListener failed", e)
            }
        }
        try {
            serviceApi?.unregisterServiceConnectionListener(serviceConnectionListener)
        } catch (e: Exception) {
            Log.w(TAG, "unregister failed", e)
        }
        scope.cancel()
        nodeId = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}