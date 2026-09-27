package com.tiger.locationverify.consent

import android.content.Intent
import android.os.Bundle
import android.text.util.Linkify
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.amap.api.location.AMapLocationClient
import com.baidu.location.LocationClient
import com.tiger.locationverify.MainActivity
import com.tiger.locationverify.R
import com.tiger.locationverify.data.Prefs

/**
 * 首次打开的介绍与授权页面：
 * 展示功能介绍、隐私授权提示（两个定位 SDK 的合规要求），
 * 用户同意后才允许进入主界面并使用定位 SDK。
 */
class ConsentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_consent)

        val cbAgree = findViewById<CheckBox>(R.id.cb_agree)
        val btnAgree = findViewById<Button>(R.id.btn_agree)
        val btnExit = findViewById<Button>(R.id.btn_exit)

        // 隐私政策链接可点击
        findViewById<TextView>(R.id.tv_policy_links).let {
            Linkify.addLinks(it, Linkify.WEB_URLS)
        }

        btnAgree.isEnabled = false
        cbAgree.setOnCheckedChangeListener { _, checked -> btnAgree.isEnabled = checked }

        btnAgree.setOnClickListener {
            Prefs.setPrivacyAgreed(this, true)
            // 立即将授权结果同步给两个 SDK 的合规开关（无需重启进程）
            LocationClient.setAgreePrivacy(true)
            AMapLocationClient.updatePrivacyAgree(this, true)
            Toast.makeText(this, R.string.consent_saved, Toast.LENGTH_SHORT).show()
            goMain()
        }

        btnExit.setOnClickListener {
            Prefs.setPrivacyAgreed(this, false)
            finishAffinity()
        }
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}