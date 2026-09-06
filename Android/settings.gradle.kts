// Hanser Android 工程（与 Windows 版功能对齐的安卓客户端）
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Hanser-Android"
include(":app")
