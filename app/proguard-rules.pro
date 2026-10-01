# Release 启用 R8 和资源压缩。三个厂商 SDK 含 JNI/反射，保留其兼容规则。
# 不用全局 -dontwarn **、-dontoptimize 或整包保留 Kotlin/AndroidX。

# ---------- 百度定位 SDK ----------
-keep class com.baidu.location.** {*;}

# ---------- 高德定位 SDK ----------
-keep class com.amap.api.location.**{*;}
-keep class com.amap.api.fence.**{*;}
-keep class com.loc.**{*;}
-keep class com.autonavi.aps.amapapi.model.**{*;}

# ---------- 腾讯定位 SDK ----------
-keepattributes *Annotation*
-keepclassmembers class ** {
    public void on*Event(...);
}
-keep public class com.tencent.location.**{
    public protected *;
}
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class c.t.**{*;}
-keep class com.tencent.map.geolocation.**{*;}
-keep class com.tencent.tencentmap.lbssdk.service.*{*;}
-keep class com.tencent.tencentmap.lbssdk.officialservice.*{*;}
-dontwarn  org.eclipse.jdt.annotation.**
-dontwarn  c.t.**
-dontwarn  android.location.Location
-dontwarn  android.net.wifi.WifiManager
-dontnote ct.**

# ---------- 反射元数据 ----------
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
# SDK 模型字段已由上面的厂商规则保留；不再把 Gson 实现整包锁定。
# Gson（若被依赖引入）使用其自带 consumer rules；Unsafe 属于运行时类，
# 项目 keep 规则无法把它打包进 APK，无需添加 -keep。

# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn com.amap.ams.gnss.GnssSoftLocator
-dontwarn net.jafama.FastMath
-dontwarn okhttp3.Call
-dontwarn okhttp3.Dns
-dontwarn okhttp3.MediaType
-dontwarn okhttp3.OkHttpClient$Builder
-dontwarn okhttp3.OkHttpClient
-dontwarn okhttp3.Request$Builder
-dontwarn okhttp3.Request
-dontwarn okhttp3.RequestBody
-dontwarn okhttp3.Response
-dontwarn okhttp3.ResponseBody