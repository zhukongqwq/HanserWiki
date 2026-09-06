import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.zhukongqwq.hanser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zhukongqwq.hanser"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.1.8"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        checkReleaseBuilds = false // lint 基础服务与本机 JDK 不兼容，跳过 release 门禁
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true // 生成 BuildConfig.VERSION_NAME 供软件更新比对
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Compose（自绘 UI）
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // 生命周期与协程
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 结巴式词典分词：自实现（词典 dict.txt 放 assets，Android classloader 不可读 jar 资源）
    //（曾用 com.huaban:jieba-analysis，Android 上词典加载崩溃——已替换）

    // 轻量 HTTP（OpenAI 兼容 / GitHub raw）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON（协程解析）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // 单元测试（本地 JVM）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
}

tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
