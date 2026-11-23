plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "com.shuangpin.ime"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shuangpin.ime"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        // 第二个发布版 v0.0.2（发布包放在 发布版/v0.0.2/）
        versionName = "0.0.2"

        // 端侧重排模型用 ONNX Runtime。
        // onnxruntime-mobile 是精简版（整包 6.9 MB），全量 AAR 有 50 MB，装不下。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":engine"))

    // 端侧重排模型（chinese-ime-lm 的按键重排模型，Apache-2.0）
    //
    // 用**全量版**而不是 onnxruntime-mobile（精简版 6.9 MB）：
    // 精简版**缺 LayerNormalization 的实现**，模型的图建会话直接报
    // ORT_NOT_IMPLEMENTED（真机实测）。全量版 arm64 的 .so 是 31 MB，
    // 代价是 APK 变大——上游的做法是自己编译只含 27 个算子的精简 ORT（1.5 MB），
    // 那需要一套我们暂时没有的工具链。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
}
