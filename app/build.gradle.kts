plugins {
    alias(libs.plugins.android.application)
    // Compose 编译器：AGP 9 已内置 Kotlin，仅需额外应用 Compose 插件
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.hpu.autoclicker"
    // 用整数重载声明 compileSdk：新的 compileSdk { version = release(36) } 块语法会让 compileSdk
    // 变成 AndroidApiLevel 类型，IDE 的 Compose Live Edit 插件用 %d 格式化它时会抛
    // IllegalFormatConversionException（d != AndroidApiLevel）。本项目未用到 API 36.1 特有功能。
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hpu.autoclicker"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // 开启 Compose（XML 布局已全部迁移为 Compose）
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    // Compose 入口（Activity 的 setContent）
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.constraintlayout)

    // ===== Compose UI 依赖（版本由 BOM 统一约束）=====
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    // 服务内 ComposeView 的宿主生命周期支持
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}