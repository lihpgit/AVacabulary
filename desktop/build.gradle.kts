import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.json:json:${libs.versions.orgJson.get()}")
    implementation("org.xerial:sqlite-jdbc:${libs.versions.sqliteJdbc.get()}")
    // 音频用 macOS 自带 afplay（支持 MP3/AAC），不再需要 JLayer
}

compose.desktop {
    application {
        mainClass = "com.example.testapplication.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "VocabGuess"
            packageVersion = "1.0.0"
        }
    }
}

// 开发期运行：把读取目录指向当前仓库 assets，写入目录指向 mac_data/
tasks.withType<JavaExec>().configureEach {
    systemProperty("vocab.assets", rootProject.projectDir.resolve("app/src/main/assets/baicizhan").absolutePath)
    systemProperty("vocab.data", rootProject.projectDir.resolve("mac_data").absolutePath)
}
