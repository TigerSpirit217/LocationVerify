package com.tiger.locationverify

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tiger.locationverify.R
import com.tiger.locationverify.consent.ConsentActivity
import com.tiger.locationverify.data.Prefs
import com.tiger.locationverify.keys.KeyConfigActivity
import com.tiger.locationverify.location.AmapLocationChecker
import com.tiger.locationverify.location.BaiduLocationChecker
import com.tiger.locationverify.location.RiskLevel
import com.tiger.locationverify.location.buildText
import com.tiger.locationverify.util.LogSaver

/**
 * 主界面：上下两区分别展示百度地图 SDK 与高德地图 SDK 的检测结果。
 * 检测流程：先百度、后高德，各自结果实时渲染到对应区域。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvKeyState: TextView
    private lateinit var tvBaiduResult: TextView
    private lateinit var tvAmapResult: TextView
    private lateinit var btnStart: Button

    private lateinit var baiduChecker: BaiduLocationChecker
    private lateinit var amapChecker: AmapLocationChecker
    private var running = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            startCheckSequence()
        } else {
            running = false
            btnStart.isEnabled = true
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvKeyState = findViewById(R.id.tv_key_state)
        tvBaiduResult = findViewById(R.id.tv_baidu_result)
        tvAmapResult = findViewById(R.id.tv_amap_result)
        btnStart = findViewById(R.id.btn_start)

        baiduChecker = BaiduLocationChecker(applicationContext)
        amapChecker = AmapLocationChecker(applicationContext)

        btnStart.setOnClickListener { onStartCheckClick() }
        findViewById<Button>(R.id.btn_config_keys).setOnClickListener {
            startActivity(Intent(this, KeyConfigActivity::class.java))
        }
        findViewById<Button>(R.id.btn_view_log).setOnClickListener { viewLog() }
        findViewById<Button>(R.id.btn_clear_log).setOnClickListener {
            LogSaver.clear()
            Toast.makeText(this, R.string.log_cleared, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_privacy).setOnClickListener {
            startActivity(Intent(this, ConsentActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        // 首次打开：弹出功能介绍与授权提示
        if (!Prefs.getPrivacyAgreed(this)) {
            startActivity(Intent(this, ConsentActivity::class.java))
            finish()
            return
        }
        refreshKeyState()
    }

    override fun onDestroy() {
        super.onDestroy()
        baiduChecker.release()
        amapChecker.release()
    }

    private fun refreshKeyState() {
        val bOk = Prefs.getBaiduAk(this).isNotBlank()
        val aOk = Prefs.getAmapKey(this).isNotBlank()
        tvKeyState.text = getString(
            R.string.key_state,
            if (bOk) getString(R.string.filled) else getString(R.string.not_filled),
            if (aOk) getString(R.string.filled) else getString(R.string.not_filled)
        )
    }

    private fun onStartCheckClick() {
        if (running) return
        // 允许只填一个 Key：仅当两个 Key 都未填写时才引导去配置页；
        // 只填一个时，未填写 Key 的 SDK 会在其检测器中自动跳过（NO_KEY），不初始化。
        if (Prefs.getBaiduAk(this).isBlank() && Prefs.getAmapKey(this).isBlank()) {
            Toast.makeText(this, R.string.need_keys, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, KeyConfigActivity::class.java))
            return
        }
        val missing = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        startCheckSequence()
    }

    /** 依次执行：先百度 SDK，回调完成后再执行高德 SDK */
    private fun startCheckSequence() {
        running = true
        btnStart.isEnabled = false
        LogSaver.d("Flow", "开始检测：百度 → 高德")
        renderZone(tvBaiduResult, textWithStatus(getString(R.string.zone_baidu_running)))
        renderZone(tvAmapResult, textWithStatus(getString(R.string.zone_amap_waiting)))

        baiduChecker.start { report ->
            LogSaver.d("Flow", "百度完成: 风险=${report.risk.label} ${report.riskReason}")
            renderZone(tvBaiduResult, buildText(report), report.risk)
            if (isFinishing || isDestroyed) return@start
            renderZone(tvAmapResult, textWithStatus(getString(R.string.zone_amap_running)))
            amapChecker.start { r ->
                LogSaver.d("Flow", "高德完成: 风险=${r.risk.label} ${r.riskReason}")
                renderZone(tvAmapResult, buildText(r), r.risk)
                running = false
                btnStart.isEnabled = true
            }
        }
    }

    private fun textWithStatus(text: String) = buildString {
        appendLine("状态: 检测中…")
        appendLine()
        append(text)
    }

    /** 渲染某一区域的报告文本，并按风险等级着色 */
    private fun renderZone(view: TextView, text: String, risk: RiskLevel? = null) {
        view.text = text
        view.setTextColor(ContextCompat.getColor(this, when (risk) {
            RiskLevel.HIGH -> R.color.risk_high
            RiskLevel.MIDDLE -> R.color.risk_mid
            RiskLevel.LOW -> R.color.risk_low
            RiskLevel.UNKNOWN -> R.color.risk_unknown
            null -> R.color.text_main
        }))
    }

    private fun viewLog() {
        val content = LogSaver.lastLines(300)
        AlertDialog.Builder(this)
            .setTitle(R.string.log_title)
            .setMessage(getString(R.string.log_path, LogSaver.currentFile.absolutePath) + "\n\n" + content)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}