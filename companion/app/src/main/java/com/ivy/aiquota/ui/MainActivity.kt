package com.ivy.aiquota.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.ivy.aiquota.AppLog
import com.ivy.aiquota.R
import com.ivy.aiquota.WearBridgeService
import com.ivy.aiquota.config.AccountConfig
import com.ivy.aiquota.config.AccountRepository
import com.ivy.aiquota.quota.QuotaProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var repo: AccountRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var txtServiceStatus: TextView
    private lateinit var edtInterval: EditText
    private lateinit var containerAccounts: LinearLayout
    private lateinit var txtLog: TextView

    private val logHandler = Handler(Looper.getMainLooper())
    private val logRefresher = object : Runnable {
        override fun run() {
            refreshLogView()
            logHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        repo = AccountRepository(this)

        txtServiceStatus = findViewById(R.id.txtServiceStatus)
        edtInterval = findViewById(R.id.edtInterval)
        containerAccounts = findViewById(R.id.containerAccounts)
        txtLog = findViewById(R.id.txtLog)

        findViewById<Button>(R.id.btnStartService).setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, WearBridgeService::class.java))
            log("桥接服务启动中...")
            updateServiceStatus()
        }
        findViewById<Button>(R.id.btnStopService).setOnClickListener {
            stopService(Intent(this, WearBridgeService::class.java))
            log("桥接服务已停止")
            updateServiceStatus()
        }
        findViewById<Button>(R.id.btnSaveInterval).setOnClickListener {
            val v = edtInterval.text.toString().toIntOrNull()
            if (v == null || v < 1) {
                toast("请输入有效间隔（1-1440 分钟）")
                return@setOnClickListener
            }
            repo.pollIntervalMin = v
            log("轮询间隔已设为 ${v} 分钟")
        }
        findViewById<Button>(R.id.btnAddAccount).setOnClickListener {
            showAccountDialog(null)
        }

        findViewById<SwitchMaterial>(R.id.swNotifyChange).apply {
            isChecked = repo.notifyOnChange
            setOnCheckedChangeListener { _, checked ->
                repo.notifyOnChange = checked
                log("额度变化通知已${if (checked) "开启" else "关闭"}")
            }
        }
        findViewById<SwitchMaterial>(R.id.swNotifyLow).apply {
            isChecked = repo.notifyLow
            setOnCheckedChangeListener { _, checked ->
                repo.notifyLow = checked
                log("低额度提醒已${if (checked) "开启" else "关闭"}")
            }
        }
        findViewById<Button>(R.id.btnTestNotify).setOnClickListener {
            ContextCompat.startForegroundService(
                this,
                Intent(this, WearBridgeService::class.java).setAction(WearBridgeService.ACTION_TEST_NOTIFY)
            )
            log("已请求发送测试通知，请查看手环通知")
        }

        edtInterval.setText(repo.pollIntervalMin.toString())
        renderAccounts()
        updateServiceStatus()
        log("AI额度伴侣 v1.0.1 就绪。请在下方添加账户。")
        logHandler.post(logRefresher)
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    override fun onDestroy() {
        logHandler.removeCallbacks(logRefresher)
        scope.cancel()
        super.onDestroy()
    }

    private fun updateServiceStatus() {
        val running = WearBridgeService.isRunning
        txtServiceStatus.text = if (running) "桥接服务运行中" else "服务未启动"
        txtServiceStatus.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.accent else android.R.color.darker_gray)
        )
    }

    private fun renderAccounts() {
        containerAccounts.removeAllViews()
        repo.loadAccounts().forEach { cfg ->
            containerAccounts.addView(buildAccountRow(cfg))
        }
        if (repo.loadAccounts().isEmpty()) {
            val empty = TextView(this).apply {
                text = "暂无账户，点击右上角添加"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
                val p = dp(8).toInt()
                setPadding(p, p, p, p)
            }
            containerAccounts.addView(empty)
        }
    }

    private fun buildAccountRow(cfg: AccountConfig): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.card_bg))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "${cfg.name}  (${QuotaProvider.label(cfg.type)})"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(Button(this).apply {
            text = "测试"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { testAccount(cfg) }
        })
        header.addView(Button(this).apply {
            text = "编辑"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { showAccountDialog(cfg) }
        })
        header.addView(Button(this).apply {
            text = "删除"
            textSize = 12f
            isAllCaps = false
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("删除账户")
                    .setMessage("确定删除「${cfg.name}」？")
                    .setPositiveButton("删除") { _, _ ->
                        repo.removeAccount(cfg.id)
                        renderAccounts()
                        log("已删除账户: ${cfg.name}")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })
        card.addView(header)

        card.addView(TextView(this).apply {
            text = if (cfg.type == "manual") {
                val total = cfg.manualTotal?.let { "/$it" } ?: ""
                "剩余: ${cfg.manualRemaining}${cfg.manualUnit}$total"
            } else {
                "BaseUrl: ${cfg.baseUrl.ifEmpty { "(默认)" }}"
            }
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
        })
        card.addView(TextView(this).apply {
            text = if (cfg.type == "manual") {
                "手动维护 · 提醒阈值: ${cfg.threshold}%"
            } else {
                "Key: ${cfg.apiKey.take(6)}****  ·  提醒阈值: ${cfg.threshold}%"
            }
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
        })

        val margin = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
        card.layoutParams = margin
        return card
    }

    private fun testAccount(cfg: AccountConfig) {
        log("测试 ${cfg.name} ...")
        scope.launch {
            val acc = QuotaProvider.create(cfg.type).fetch(cfg)
            if (acc.status == "ok") {
                val total = acc.total?.let { "/$it" } ?: ""
                log("${acc.name}: 剩余 ${acc.remaining}${acc.unit}$total (${acc.group})")
            } else {
                log("${acc.name} 查询失败: ${acc.error}")
            }
            toast(if (acc.status == "ok") "查询成功" else "查询失败")
        }
    }

    private fun showAccountDialog(edit: AccountConfig?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        val edtName = EditText(this).apply { hint = "名称（如：中转站 / 官方）"; setText(edit?.name ?: "") }
        val spinnerType = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                listOf("oneapi", "openai", "deepseek", "codex", "sub2api", "manual")
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(listOf("oneapi", "openai", "deepseek", "codex", "sub2api", "manual").indexOf(edit?.type ?: "oneapi").coerceAtLeast(0))
        }
        val edtBase = EditText(this).apply {
            hint = "BaseUrl：中转站填根地址（如 https://xxx.com，不要带 /v1）；OpenAI/DeepSeek/Codex 可留空"
            setText(edit?.baseUrl ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val edtKey = EditText(this).apply {
            hint = if (edit?.type == "codex") "access_token（~/.codex/auth.json）" else "API Key / Token"
            setText(edit?.apiKey ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val edtAccountId = EditText(this).apply {
            hint = "ChatGPT-Account-Id（多账户时可选）"
            setText(edit?.accountId ?: "")
        }
        val edtThreshold = EditText(this).apply {
            hint = "低额度提醒阈值 %（默认 10）"
            setText(edit?.threshold?.toString() ?: "10")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val edtManualRemaining = EditText(this).apply {
            hint = "当前剩余额度"
            setText(if (edit?.type == "manual") edit.manualRemaining.toString() else "")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val edtManualTotal = EditText(this).apply {
            hint = "总额度（可留空）"
            setText(if (edit?.type == "manual") edit.manualTotal?.toString() ?: "" else "")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val edtManualUnit = EditText(this).apply {
            hint = "单位（如 USD / CNY / 元）"
            setText(if (edit?.type == "manual") edit.manualUnit else "USD")
        }

        val manualFields = listOf(edtManualRemaining, edtManualTotal, edtManualUnit)
        val apiFields = listOf(edtBase, edtKey)

        fun updateVisibility() {
            val isManual = spinnerType.selectedItem == "manual"
            val isCodex = spinnerType.selectedItem == "codex"
            apiFields.forEach { it.visibility = if (isManual) View.GONE else View.VISIBLE }
            manualFields.forEach { it.visibility = if (isManual) View.VISIBLE else View.GONE }
            edtAccountId.visibility = if (isCodex) View.VISIBLE else View.GONE
        }
        spinnerType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateVisibility()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        container.addView(TextView(this).apply { text = "类型" })
        container.addView(spinnerType)
        container.addView(edtName)
        container.addView(edtBase)
        container.addView(edtKey)
        container.addView(edtAccountId)
        container.addView(edtManualRemaining)
        container.addView(edtManualTotal)
        container.addView(edtManualUnit)
        container.addView(edtThreshold)

        AlertDialog.Builder(this)
            .setTitle(if (edit == null) "添加账户" else "编辑账户")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val name = edtName.text.toString().trim().ifEmpty { "未命名" }
                val type = spinnerType.selectedItem.toString()
                val threshold = edtThreshold.text.toString().toDoubleOrNull() ?: 10.0
                if (type == "manual") {
                    val cfg = AccountConfig(
                        id = edit?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name,
                        type = type,
                        baseUrl = "",
                        apiKey = "",
                        threshold = threshold,
                        manualRemaining = edtManualRemaining.text.toString().toDoubleOrNull() ?: 0.0,
                        manualTotal = edtManualTotal.text.toString().toDoubleOrNull(),
                        manualUnit = edtManualUnit.text.toString().trim().ifEmpty { "USD" }
                    )
                    repo.upsertAccount(cfg)
                    renderAccounts()
                    log("已保存账户: ${cfg.name} (手动)")
                } else {
                    val cfg = AccountConfig(
                        id = edit?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name,
                        type = type,
                        baseUrl = edtBase.text.toString().trim(),
                        apiKey = edtKey.text.toString().trim(),
                        threshold = threshold,
                        accountId = edtAccountId.text.toString().trim()
                    )
                    if (cfg.apiKey.isEmpty()) {
                        toast("API Key 不能为空")
                        return@setPositiveButton
                    }
                    repo.upsertAccount(cfg)
                    renderAccounts()
                    log("已保存账户: ${cfg.name} (${QuotaProvider.label(cfg.type)})")
                }
            }
            .setNegativeButton("取消", null)
            .show()

        updateVisibility()
    }

    private fun log(msg: String) {
        AppLog.log("UI", msg)
        refreshLogView()
    }

    private fun refreshLogView() {
        val text = AppLog.dump().joinToString("\n")
        if (txtLog.text.toString() != text) txtLog.text = text
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}