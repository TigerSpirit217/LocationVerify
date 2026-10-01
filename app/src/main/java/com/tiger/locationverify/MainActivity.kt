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
import com.tiger.locationverify.location.TencentLocationChecker
import com.tiger.locationverify.location.buildText
import com.tiger.locationverify.util.LogSaver
import com.tiger.locationverify.util.applySystemBarInsets

/**
 * 主界面：从上到下三个区域分别展示百度地图 SDK、高德地图 SDK、腾讯定位 SDK 的检测结果。
 * 检测流程：先百度、再高德、后腾讯，各自结果实时渲染到对应区域；未配置 Key 的 SDK 自动跳过。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvKeyState: TextView
    private lateinit var tvBaiduResult: TextView
    private lateinit var tvAmapResult: TextView
    private lateinit var tvTencentResult: TextView
    private lateinit var btnStart: Button

    private lateinit var baiduChecker: BaiduLocationChecker
    private lateinit var amapChecker: AmapLocationChecker
    private lateinit var tencentChecker: TencentLocationChecker
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
        applySystemBarInsets()

        tvKeyState = findViewById(R.id.tv_key_state)
        tvBaiduResult = findViewById(R.id.tv_baidu_result)
        tvAmapResult = findViewById(R.id.tv_amap_result)
        tvTencentResult = findViewById(R.id.tv_tencent_result)
        btnStart = findViewById(R.id.btn_start)

        baiduChecker = BaiduLocationChecker(this)
        amapChecker = AmapLocationChecker(this)
        tencentChecker = TencentLocationChecker(this)

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
        tencentChecker.release()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (running) return
        // Reports contain localized text. After recreation, show fresh placeholders
        // rather than restoring text from the previous language or an interrupted check.
        renderZone(tvBaiduResult, getString(R.string.zone_placeholder))
        renderZone(tvAmapResult, getString(R.string.zone_placeholder))
        renderZone(tvTencentResult, getString(R.string.zone_placeholder))
        btnStart.isEnabled = true
        refreshKeyState()
    }

    private fun refreshKeyState() {
        tvKeyState.text = getString(
            R.string.key_state,
            if (Prefs.getBaiduAk(this).isNotBlank()) getString(R.string.filled) else getString(R.string.not_filled),
            if (Prefs.getAmapKey(this).isNotBlank()) getString(R.string.filled) else getString(R.string.not_filled),
            if (Prefs.getTencentKey(this).isNotBlank()) getString(R.string.filled) else getString(R.string.not_filled)
        )
    }

    private fun onStartCheckClick() {
        if (running) return
        // 允许只填部分 Key：仅当三个 Key 都未填写时才引导去配置页；
        // 未填写 Key 的 SDK 会在其检测器中自动跳过（NO_KEY），不初始化。
        if (Prefs.getBaiduAk(this).isBlank() &&
            Prefs.getAmapKey(this).isBlank() &&
            Prefs.getTencentKey(this).isBlank()
        ) {
            Toast.makeText(this, R.string.need_keys, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, KeyConfigActivity::class.java))
            return
        }
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (permissions.none {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }) {
            permissionLauncher.launch(permissions)
            return
        }
        startCheckSequence()
    }

    /** 依次执行：先百度 SDK，再高德 SDK，最后腾讯 SDK */
    private fun startCheckSequence() {
        if (running || isFinishing || isDestroyed) return
        baiduChecker.release()
        amapChecker.release()
        tencentChecker.release()
        baiduChecker = BaiduLocationChecker(this)
        amapChecker = AmapLocationChecker(this)
        tencentChecker = TencentLocationChecker(this)
        running = true
        btnStart.isEnabled = false
        LogSaver.d("Flow", getString(R.string.text_starting_checks_baidu_amap_tencent))
        renderZone(tvBaiduResult, textWithStatus(getString(R.string.zone_baidu_running)))
        renderZone(tvAmapResult, textWithStatus(getString(R.string.zone_amap_waiting)))
        renderZone(tvTencentResult, textWithStatus(getString(R.string.zone_tencent_waiting)))

        baiduChecker.start { report ->
            if (isFinishing || isDestroyed) return@start
            LogSaver.d("Flow", getString(R.string.text_baidu_completed_risk, report.risk.label(this), report.riskReason))
            renderZone(tvBaiduResult, buildText(this, report), report.risk)
            renderZone(tvAmapResult, textWithStatus(getString(R.string.zone_amap_running)))
            amapChecker.start amap@{ r ->
                if (isFinishing || isDestroyed) return@amap
                LogSaver.d("Flow", getString(R.string.text_amap_completed_risk, r.risk.label(this), r.riskReason))
                renderZone(tvAmapResult, buildText(this, r), r.risk)
                renderZone(tvTencentResult, textWithStatus(getString(R.string.zone_tencent_running)))
                tencentChecker.start tencent@{ rt ->
                    if (isFinishing || isDestroyed) return@tencent
                    LogSaver.d("Flow", getString(R.string.text_tencent_completed_risk, rt.risk.label(this), rt.riskReason))
                    renderZone(tvTencentResult, buildText(this, rt), rt.risk)
                    running = false
                    btnStart.isEnabled = true
                }
            }
        }
    }

    private fun textWithStatus(text: String) = buildString {
        appendLine(getString(R.string.text_status_checking))
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
        val content = LogSaver.lastLines(this, 300)
        AlertDialog.Builder(this)
            .setTitle(R.string.log_title)
            .setMessage(getString(R.string.log_path, LogSaver.currentFile.absolutePath) + "\n\n" + content)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
