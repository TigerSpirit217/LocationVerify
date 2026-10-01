plugins {
    id("com.android.application")
}

android {
    namespace = "com.tiger.locationverify"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.tiger.locationverify"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        // AndroidManifest.xml 中以占位符方式预留 Key，可留空：
        // 应用内「配置密钥」填写的 Key 通过代码设置（优先级更高）：
        //   百度 LocationClient.setKey(...) / 高德 AMapLocationClient.setApiKey(...)
        manifestPlaceholders["BAIDU_AK"] = ""
        manifestPlaceholders["AMAP_API_KEY"] = ""
        manifestPlaceholders["TENCENT_KEY"] = ""
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.4.20"))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-ktx:1.13.0")

    // 百度定位 SDK（全量定位组件，包含基础组件；如官方发布新版本可自行升级）
    implementation("com.baidu.lbsyun:BaiduMapSDK_Location_All:9.7.0")

    // 高德定位 SDK（6.5.x 起支持模拟定位检测错误码 15）
    implementation("com.amap.api:location:11.3.000")

    // 腾讯定位 SDK（7.5.4.8+ 提供反作弊字段 getFakeReason/getFakeProbability）
    // 7.6.1.9 是 2026-07 发布版本；8.7.5.1 是 2020 年旧版本，不能按编号升级。
    implementation("com.tencent.map.geolocation:TencentLocationSdk-openplatform:7.6.1.9")
}
