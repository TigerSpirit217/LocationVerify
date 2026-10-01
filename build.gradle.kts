// 顶层构建文件
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 内置 Kotlin；显式统一编译器版本，无需 kotlin-android 插件。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
}
