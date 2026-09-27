pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 百度地图 SDK 官方 Maven 仓库（定位 SDK 依赖）
        maven("https://maven.lbsyun.baidu.com/repository/maven-public/")
    }
}

rootProject.name = "LocationVerify"
include(":app")