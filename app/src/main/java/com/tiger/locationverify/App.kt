package com.tiger.locationverify

import android.app.Application
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.os.Process
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.amap.api.location.AMapLocationClient
import com.baidu.location.LocationClient
import com.tencent.map.geolocation.TencentLocationManager
import com.tiger.locationverify.data.Prefs
import com.tiger.locationverify.util.LogSaver

/**
 * 应用入口。
 *
 * 隐私合规：三个定位 SDK 都要求在初始化前按用户授权结果设置合规开关，
 * 授权决定由首次启动的引导页（ConsentActivity）收集并持久化。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        // Remove an earlier app language override when upgrading from the language picker.
        // On older Android, locale storage is no longer opted in, so it is not loaded.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSystemService(LocaleManager::class.java)?.let { manager ->
                if (!manager.applicationLocales.isEmpty) {
                    manager.applicationLocales = LocaleList.getEmptyLocaleList()
                }
            }
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
        LogSaver.init(this)
        val agreed = Prefs.getPrivacyAgreed(this)
        // 百度：必须在实例化 LocationClient 之前调用
        LocationClient.setAgreePrivacy(agreed)
        // 高德：调用 SDK 任何接口前必须先调用隐私合规接口
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, agreed)
        // 腾讯：必须在构造 TencentLocationManager 实例及调用任何定位接口之前设置
        TencentLocationManager.setUserAgreePrivacy(agreed)
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            "pid:${Process.myPid()}"
        }
        LogSaver.d("App", "onCreate, pid=${Process.myPid()} process=$processName privacyAgreed=$agreed")
    }
}
