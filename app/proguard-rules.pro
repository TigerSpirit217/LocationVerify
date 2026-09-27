# ---------- 百度定位 SDK ----------
-keep class com.baidu.** {*;}
-keep class vi.com.** {*;}
-dontwarn com.baidu.**

# ---------- 高德定位 SDK ----------
-keep class com.amap.api.location.** {*;}
-keep class com.amap.api.fence.** {*;}
-keep class com.loc.** {*;}
-dontwarn com.amap.**

# ---------- 通用（SDK 可能依赖 Gson） ----------
-keepattributes Signature
-keep class com.google.gson.** {*;}
-keep class sun.misc.Unsafe { *; }