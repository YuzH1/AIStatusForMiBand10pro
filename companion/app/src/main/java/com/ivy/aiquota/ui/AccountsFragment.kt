package com.ivy.aiquota.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ivy.aiquota.AppLog
import com.ivy.aiquota.R
import com.ivy.aiquota.WearBridgeService
import com.ivy.aiquota.config.AccountConfig
import com.ivy.aiquota.config.AccountRepository
import com.ivy.aiquota.quota.QuotaProvider
import kotlinx.coroutines.launch

class AccountsFragment : Fragment() {

    private lateinit var repo: AccountRepository
    private lateinit var containerAccounts: LinearLayout
    private lateinit var txtServiceStatus: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_accounts, container, false)
        repo = AccountRepository(requireContext())

        txtServiceStatus = root.findViewById(R.id.txtServiceStatus)
        containerAccounts = root.findViewById(R.id.containerAccounts)

        root.findViewById<Button>(R.id.btnStartService).setOnClickListener {
            ContextCompat.startForegroundService(
                requireContext(),
                Intent(requireContext(), WearBridgeService::class.java)
            )
            AppLog.log("UI", "桥接服务启动中...")
            updateServiceStatus()
        }
        root.findViewById<Button>(R.id.btnStopService).setOnClickListener {
            requireContext().stopService(Intent(requireContext(), WearBridgeService::class.java))
            AppLog.log("UI", "桥接服务已停止")
            updateServiceStatus()
        }
        root.findViewById<Button>(R.id.btnAddAccount).setOnClickListener {
            showAccountDialog(null)
        }

        renderAccounts()
        return root
    }

    override fun onResume() {
        super.onResume()
        renderAccounts()
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        val running = WearBridgeService.isRunning
        txtServiceStatus.text = if (running) "桥接服务运行中" else "服务未启动"
        txtServiceStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (running) R.color.accent else android.R.color.darker_gray
            )
        )
    }

    private fun renderAccounts() {
        containerAccounts.removeAllViews()
        val accounts = repo.loadAccounts()
        if (accounts.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = "暂无账户，点击右上角「+ 添加」"
                textSize = 14f
                setTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
                val p = dp(8).toInt()
                setPadding(p, p, p, p)
            }
            containerAccounts.addView(empty)
            return
        }
        accounts.forEach { cfg -> containerAccounts.addView(buildAccountRow(cfg)) }
    }

    private fun buildAccountRow(cfg: AccountConfig): View {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.card_bg))
        }
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(requireContext()).apply {
            text = "${cfg.name}  (${QuotaProvider.label(cfg.type)})"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(Button(requireContext()).apply {
            text = "测试"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { testAccount(cfg) }
        })
        header.addView(Button(requireContext()).apply {
            text = "编辑"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { showAccountDialog(cfg) }
        })
        header.addView(Button(requireContext()).apply {
            text = "删除"
            textSize = 12f
            isAllCaps = false
            setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("删除账户")
                    .setMessage("确定删除「${cfg.name}」？")
                    .setPositiveButton("删除") { _, _ ->
                        repo.removeAccount(cfg.id)
                        renderAccounts()
                        AppLog.log("UI", "已删除账户: ${cfg.name}")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })
        card.addView(header)

        card.addView(TextView(requireContext()).apply {
            text = if (cfg.type == "manual") {
                val total = cfg.manualTotal?.let { "/$it" } ?: ""
                "剩余: ${cfg.manualRemaining}${cfg.manualUnit}$total"
            } else {
                "BaseUrl: ${cfg.baseUrl.ifEmpty { "(默认)" }}"
            }
            textSize = 12f
            setTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
        })
        card.addView(TextView(requireContext()).apply {
            text = if (cfg.type == "manual") {
                "手动维护 · 提醒阈值: ${cfg.threshold}%"
            } else {
                "Key: ${cfg.apiKey.take(6)}****  ·  提醒阈值: ${cfg.threshold}%"
            }
            textSize = 12f
            setTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
        })

        val margin = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
        card.layoutParams = margin
        return card
    }

    private fun testAccount(cfg: AccountConfig) {
        AppLog.log("UI", "测试 ${cfg.name} ...")
        lifecycleScope.launch {
            val acc = QuotaProvider.create(cfg.type).fetch(cfg)
            if (acc.status == "ok") {
                val total = acc.total?.let { "/$it" } ?: ""
                AppLog.log("UI", "${acc.name}: 剩余 ${acc.remaining}${acc.unit}$total (${acc.group})")
            } else {
                AppLog.log("UI", "${acc.name} 查询失败: ${acc.error}")
            }
            Toast.makeText(requireContext(), if (acc.status == "ok") "查询成功" else "查询失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAccountDialog(edit: AccountConfig?) {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        val edtName = EditText(requireContext()).apply { hint = "名称（如：中转站 / 官方）"; setText(edit?.name ?: "") }
        val spinnerType = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                listOf("oneapi", "openai", "deepseek", "codex", "opencodego", "sub2api", "manual")
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(listOf("oneapi", "openai", "deepseek", "codex", "opencodego", "sub2api", "manual").indexOf(edit?.type ?: "oneapi").coerceAtLeast(0))
        }
        val edtBase = EditText(requireContext()).apply {
            hint = "BaseUrl：中转站填根地址（如 https://xxx.com，不要带 /v1）；其余可留空"
            setText(edit?.baseUrl ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val edtKey = EditText(requireContext()).apply {
            hint = if (edit?.type == "codex") "access_token（~/.codex/auth.json）" else "API Key / Token"
            setText(edit?.apiKey ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val edtAccountId = EditText(requireContext()).apply {
            hint = "ChatGPT-Account-Id（Codex 多账户时可选）"
            setText(edit?.accountId ?: "")
        }
        val edtThreshold = EditText(requireContext()).apply {
            hint = "低额度提醒阈值 %（默认 10）"
            setText(edit?.threshold?.toString() ?: "10")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val edtManualRemaining = EditText(requireContext()).apply {
            hint = "当前剩余额度"
            setText(if (edit?.type == "manual") edit.manualRemaining.toString() else "")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val edtManualTotal = EditText(requireContext()).apply {
            hint = "总额度（可留空）"
            setText(if (edit?.type == "manual") edit.manualTotal?.toString() ?: "" else "")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val edtManualUnit = EditText(requireContext()).apply {
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
        spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateVisibility()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        container.addView(TextView(requireContext()).apply { text = "类型" })
        container.addView(spinnerType)
        container.addView(edtName)
        container.addView(edtBase)
        container.addView(edtKey)
        container.addView(edtAccountId)
        container.addView(edtManualRemaining)
        container.addView(edtManualTotal)
        container.addView(edtManualUnit)
        container.addView(edtThreshold)

        AlertDialog.Builder(requireContext())
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
                    AppLog.log("UI", "已保存账户: ${cfg.name} (手动)")
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
                        Toast.makeText(requireContext(), "API Key 不能为空", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    repo.upsertAccount(cfg)
                    renderAccounts()
                    AppLog.log("UI", "已保存账户: ${cfg.name} (${QuotaProvider.label(cfg.type)})")
                }
            }
            .setNegativeButton("取消", null)
            .show()

        updateVisibility()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}