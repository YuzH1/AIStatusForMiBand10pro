package com.ivy.aiquota.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.ivy.aiquota.AppLog
import com.ivy.aiquota.R

class LogsFragment : Fragment() {

    private lateinit var txtLog: TextView
    private val logHandler = Handler(Looper.getMainLooper())
    private val logRefresher = object : Runnable {
        override fun run() {
            refreshLogView()
            logHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_logs, container, false)
        txtLog = root.findViewById(R.id.txtLog)
        root.findViewById<Button>(R.id.btnClearLogs).setOnClickListener {
            AppLog.clear()
            refreshLogView()
        }
        logHandler.post(logRefresher)
        return root
    }

    override fun onDestroyView() {
        logHandler.removeCallbacks(logRefresher)
        super.onDestroyView()
    }

    private fun refreshLogView() {
        val text = AppLog.dump().joinToString("\n")
        if (txtLog.text.toString() != text) txtLog.text = text
    }
}