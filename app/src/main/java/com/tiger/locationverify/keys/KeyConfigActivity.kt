package com.tiger.locationverify.keys

import android.os.Bundle
import android.text.util.Linkify
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tiger.locationverify.R
import com.tiger.locationverify.data.Prefs

/**
 * 密钥配置页：用户自行填写百度 AK 与高德 Key。
 * 两个 Key 均与「包名 + 签名 SHA1」绑定，保存后下次检测生效
 * （检测器在每次启动检测时通过 LocationClient.setKey / AMapLocationClient.setApiKey 注入）。
 */
class KeyConfigActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_key_config)

        val etBaidu = findViewById<EditText>(R.id.et_baidu_ak)
        val etAmap = findViewById<EditText>(R.id.et_amap_key)

        etBaidu.setText(Prefs.getBaiduAk(this))
        etAmap.setText(Prefs.getAmapKey(this))

        findViewById<TextView>(R.id.tv_key_hint).let {
            Linkify.addLinks(it, Linkify.WEB_URLS)
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val baiduAk = etBaidu.text.toString().trim()
            val amapKey = etAmap.text.toString().trim()
            if (baiduAk.isBlank() && amapKey.isBlank()) {
                Toast.makeText(this, R.string.key_at_least_one, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.saveKeys(this, baiduAk, amapKey)
            Toast.makeText(this, R.string.key_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}