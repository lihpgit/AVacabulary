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
        aidl =true
    }
}

// 移除临时源码下载任务
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
    
    // 强制下载源码，解决跳转到class文件的问题
    implementation("androidx.compose.ui:ui:1.7.0") {
        isTransitive = false
    }
    implementation("androidx.compose.material3:material3:1.3.0") {
        isTransitive = false
    }
    
    // Startup Profile Installer (Compose 启动优化)
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    compileOnly("de.robv.android.xposed:api:82")
//    compileOnly("de.robv.android.xposed:api:82:sources")
}

// 兼容 Java 工具链：部分 IDE/扩展会调用 testClasses，Android 项目默认无此任务
tasks.register("testClasses") {
    dependsOn("compileDebugUnitTestKotlin")
}