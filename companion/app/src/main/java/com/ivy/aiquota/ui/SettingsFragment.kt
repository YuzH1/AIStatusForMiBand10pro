package com.ivy.aiquota.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.ivy.aiquota.AppLog
import com.ivy.aiquota.R
import com.ivy.aiquota.WearBridgeService
import com.ivy.aiquota.config.AccountRepository

class SettingsFragment : Fragment() {

    private lateinit var repo: AccountRepository
    private lateinit var txtServiceStatus: TextView
    private lateinit var edtInterval: EditText

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_settings, container, false)
        repo = AccountRepository(requireContext())

        txtServiceStatus = root.findViewById(R.id.txtServiceStatus)
        edtInterval = root.findViewById(R.id.edtInterval)

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
        root.findViewById<Button>(R.id.btnSaveInterval).setOnClickListener {
            val v = edtInterval.text.toString().toIntOrNull()
            if (v == null || v < 1) {
                Toast.makeText(requireContext(), "请输入有效间隔（1-1440 分钟）", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            repo.pollIntervalMin = v
            AppLog.log("UI", "轮询间隔已设为 ${v} 分钟")
        }
        root.findViewById<Button>(R.id.btnSaveCooldown).setOnClickListener {
            val v = root.findViewById<EditText>(R.id.edtCooldown).text.toString().toIntOrNull()
            if (v == null || v < 1) {
                Toast.makeText(requireContext(), "请输入有效冷却时间（1-1440 分钟）", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            repo.notifyCooldownMin = v
            AppLog.log("UI", "变化通知冷却已设为 ${v} 分钟")
        }
        root.findViewById<SwitchMaterial>(R.id.swNotifyChange).apply {
            isChecked = repo.notifyOnChange
            setOnCheckedChangeListener { _, checked ->
                repo.notifyOnChange = checked
                AppLog.log("UI", "额度变化通知已${if (checked) "开启" else "关闭"}")
            }
        }
        root.findViewById<SwitchMaterial>(R.id.swNotifyLow).apply {
            isChecked = repo.notifyLow
            setOnCheckedChangeListener { _, checked ->
                repo.notifyLow = checked
                AppLog.log("UI", "低额度提醒已${if (checked) "开启" else "关闭"}")
            }
        }
        root.findViewById<SwitchMaterial>(R.id.swAutoStart).apply {
            isChecked = repo.autoStartOnBoot
            setOnCheckedChangeListener { _, checked ->
                repo.autoStartOnBoot = checked
                AppLog.log("UI", "开机自启已${if (checked) "开启" else "关闭"}")
            }
        }
        root.findViewById<Button>(R.id.btnTestNotify).setOnClickListener {
            ContextCompat.startForegroundService(
                requireContext(),
                Intent(requireContext(), WearBridgeService::class.java)
                    .setAction(WearBridgeService.ACTION_TEST_NOTIFY)
            )
            AppLog.log("UI", "已请求发送测试通知，请查看手环通知")
        }

        edtInterval.setText(repo.pollIntervalMin.toString())
        root.findViewById<EditText>(R.id.edtCooldown).setText(repo.notifyCooldownMin.toString())
        updateServiceStatus()
        return root
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        val running = WearBridgeService.isRunning
        txtServiceStatus.text = if (running) "运行中" else "未启动"
        txtServiceStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (running) R.color.accent else android.R.color.darker_gray
            )
        )
    }
}