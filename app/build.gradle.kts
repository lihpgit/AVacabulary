plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.testapplication"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.testapplication"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // 不再使用 noCompress "zpk"：5本词书 zpk 总量约 4.4GB，
        // 不压缩会超过 ZIP32 的 4GB 上限导致 APK 打包失败。
        // 压缩后约 3.7GB，assets.open() 读取时 Android 会自动解压，功能不受影响。
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.atomicfu)

    // Compose 启动优化
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    // MediaSession：蓝牙/耳机媒体按键控制
    implementation("androidx.media:media:1.7.0")

    // MMKV：高性能 KV 存储（斩/取消斩、已读标记、播放设置等）
    implementation("com.tencent:mmkv:2.0.2")

    // ZXing：同步进度页的二维码生成 + 扫描
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// 兼容 Java 工具链：部分 IDE/扩展会调用 testClasses，Android 项目默认无此任务
tasks.register("testClasses") {
    dependsOn("compileDebugUnitTestKotlin")
}
