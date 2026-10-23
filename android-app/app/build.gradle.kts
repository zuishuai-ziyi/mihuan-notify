import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// release 签名配置来自项目根 keystore.properties（gitignore，不提交）。
// 缺失时 release 回退 debug 签名，保证无密钥环境也可出包。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.mihuan.notifyrelay"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.mihuan.notifyrelay"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "2.6"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // Jetpack Compose（现代声明式 UI 工具包）
    buildFeatures {
        compose = true
    }
    composeOptions {
        // Compose Compiler 与 Kotlin 1.9.24 的对应版本
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreProps.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

dependencies {
    // 内嵌 HTTP 服务器（接收 PC 转发的 /notify）
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Material XML 组件库：仅用于 Activity XML 主题 Theme.Relay（状态栏颜色等），UI 本体已迁移 Compose
    implementation("com.google.android.material:material:1.12.0")

    // ===== Jetpack Compose（BOM 统一管理版本，material3 1.3.0 / ui 1.7.x）=====
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
