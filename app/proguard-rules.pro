# Release 启用 R8 和资源压缩。三个厂商 SDK 含 JNI/反射，保留其兼容规则。
# 不用全局 -dontwarn **、-dontoptimize 或整包保留 Kotlin/AndroidX。

# ---------- 百度定位 SDK ----------
-keep class com.baidu.** {*;}
-keep class vi.com.** {*;}
-dontwarn com.baidu.**

# ---------- 高德定位 SDK ----------
-keep class com.amap.api.location.** {*;}
-keep class com.amap.api.fence.** {*;}
-keep class com.loc.** {*;}
-dontwarn com.amap.**

# ---------- 腾讯定位 SDK ----------
-keep class com.tencent.map.geolocation.** {*;}
-keep class c.t.** {*;}
-keep class com.tencent.tencentmap.lbssdk.service.* {*;}
-keep class com.tencent.tencentmap.lbssdk.officialservice.* {*;}
-dontwarn c.t.**
-dontwarn com.tencent.**
-dontnote ct.**

# ---------- 反射元数据 ----------
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
# SDK 模型字段已由上面的厂商规则保留；不再把 Gson 实现整包锁定。
# Gson（若被依赖引入）使用其自带 consumer rules；Unsafe 属于运行时类，
# 项目 keep 规则无法把它打包进 APK，无需添加 -keep。

# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn net.jafama.FastMath