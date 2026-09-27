plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tiger.locationverify"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tiger.locationverify"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // AndroidManifest.xml 中以占位符方式预留 Key，可留空：
        // 应用内「配置密钥」填写的 Key 通过代码设置（优先级更高）：
        //   百度 LocationClient.setKey(...) / 高德 AMapLocationClient.setApiKey(...)
        manifestPlaceholders["BAIDU_AK"] = ""
        manifestPlaceholders["AMAP_API_KEY"] = ""
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // 百度定位 SDK（全量定位组件，包含基础组件；如官方发布新版本可自行升级）
    implementation("com.baidu.lbsyun:BaiduMapSDK_Location_All:9.7.0")

    // 高德定位 SDK（6.5.x 起支持模拟定位检测错误码 15）
    implementation("com.amap.api:location:11.3.000")
}