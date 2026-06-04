plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // org.json：Android 平台自带，桌面端运行时由 desktop 模块提供，core 仅编译期需要
    compileOnly("org.json:json:20240303")
}
