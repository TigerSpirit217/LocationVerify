package com.tiger.locationverify.keys

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.util.Linkify
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tiger.locationverify.R
import com.tiger.locationverify.data.Prefs
import com.tiger.locationverify.util.LogSaver
import com.tiger.locationverify.util.applySystemBarInsets
import java.security.MessageDigest

/**
 * 密钥配置页：用户自行填写百度 AK、高德 Key 与腾讯 Key（至少其一）。
 * 顶部展示本应用的包名与签名 SHA-1（申请各家 Key 时都需要填写）。
 * 百度与高德 Android Key 需绑定包名与签名；腾讯按控制台要求配置。
 * （检测器在每次启动检测时通过 LocationClient.setKey / AMapLocationClient.setApiKey /
 * TencentLocationManagerOptions.setKey 注入）。
 */
class KeyConfigActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_key_config)
        applySystemBarInsets()

        // 顶部：展示自身包名与签名 SHA-1，方便用户到各家控制台申请 Key
        findViewById<TextView>(R.id.tv_pkg_sha1).text =
            getString(R.string.pkg_sha1_format, packageName, getSignSha1())

        val etBaidu = findViewById<EditText>(R.id.et_baidu_ak)
        val etAmap = findViewById<EditText>(R.id.et_amap_key)
        val etTencent = findViewById<EditText>(R.id.et_tencent_key)

        etBaidu.setText(Prefs.getBaiduAk(this))
        etAmap.setText(Prefs.getAmapKey(this))
        etTencent.setText(Prefs.getTencentKey(this))

        findViewById<TextView>(R.id.tv_key_hint).let {
            Linkify.addLinks(it, Linkify.WEB_URLS)
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val baiduAk = etBaidu.text.toString().trim()
            val amapKey = etAmap.text.toString().trim()
            val tencentKey = etTencent.text.toString().trim()
            if (baiduAk.isBlank() && amapKey.isBlank() && tencentKey.isBlank()) {
                Toast.makeText(this, R.string.key_at_least_one, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.saveKeys(this, baiduAk, amapKey, tencentKey)
            Toast.makeText(this, R.string.key_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // Keep restored key inputs, but refresh the localized package/signature label.
        findViewById<TextView>(R.id.tv_pkg_sha1).text =
            getString(R.string.pkg_sha1_format, packageName, getSignSha1())
    }

    /** 读取当前 APK 签名证书的 SHA-1（支持多签名，每个签名为一行） */
    @Suppress("DEPRECATION")
    private fun getSignSha1(): String {
        return try {
            val pm = packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }
            val certs: List<ByteArray> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners?.map { it.toByteArray() } ?: emptyList()
            } else {
                info.signatures?.map { it.toByteArray() } ?: emptyList()
            }
            if (certs.isEmpty()) return getString(R.string.text_unavailable_no_signing_certificate_found)
            val md = MessageDigest.getInstance("SHA-1")
            certs.joinToString("\n") { cert ->
                md.digest(cert).joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
            }
        } catch (t: Throwable) {
            LogSaver.d("KeyConfig", getString(R.string.text_failed_to_read_signing_sha_1, t))
            getString(R.string.text_unavailable, t.message)
        }
    }
}
