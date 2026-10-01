package com.tiger.locationverify.data

import android.content.Context

/**
 * 应用配置与用户密钥的持久化（SharedPreferences）。
 * 两个 SDK 的 Key 均与「包名 + 签名 SHA1」绑定，因此允许用户自行填写。
 */
object Prefs {

    private const val NAME = "location_verify"
    private const val KEY_AGREED = "privacy_agreed"
    private const val KEY_BAIDU_AK = "baidu_ak"
    private const val KEY_AMAP_KEY = "amap_key"
    private const val KEY_TENCENT_KEY = "tencent_key"

    private fun sp(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ---- 隐私授权 ----
    fun getPrivacyAgreed(context: Context): Boolean =
        sp(context).getBoolean(KEY_AGREED, false)

    fun setPrivacyAgreed(context: Context, agreed: Boolean) {
        sp(context).edit().putBoolean(KEY_AGREED, agreed).apply()
    }

    // ---- 密钥 ----
    fun getBaiduAk(context: Context): String =
        sp(context).getString(KEY_BAIDU_AK, "") ?: ""

    fun getAmapKey(context: Context): String =
        sp(context).getString(KEY_AMAP_KEY, "") ?: ""

    fun getTencentKey(context: Context): String =
        sp(context).getString(KEY_TENCENT_KEY, "") ?: ""

    fun saveKeys(context: Context, baiduAk: String, amapKey: String, tencentKey: String) {
        sp(context).edit()
            .putString(KEY_BAIDU_AK, baiduAk.trim())
            .putString(KEY_AMAP_KEY, amapKey.trim())
            .putString(KEY_TENCENT_KEY, tencentKey.trim())
            .apply()
    }
}