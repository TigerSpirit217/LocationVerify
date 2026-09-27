package com.tiger.locationverify

import android.app.Application
import com.amap.api.location.AMapLocationClient
import com.baidu.location.LocationClient
import com.tiger.locationverify.data.Prefs
import com.tiger.locationverify.util.LogSaver

/**
 * 应用入口。
 *
 * 隐私合规：两个定位 SDK 都要求在初始化前按用户授权结果设置合规开关，
 * 授权决定由首次启动的引导页（ConsentActivity）收集并持久化。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        LogSaver.init(this)
        val agreed = Prefs.getPrivacyAgreed(this)
        // 百度：必须在实例化 LocationClient 之前调用
        LocationClient.setAgreePrivacy(agreed)
        // 高德：调用 SDK 任何接口前必须先调用隐私合规接口
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, agreed)
        LogSaver.d("App", "onCreate, privacyAgreed=$agreed")
    }
}