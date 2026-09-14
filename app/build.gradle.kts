plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.nodecontainer"
    // compileSdk 必须 >= 35：依赖里的 androidx.core 1.15.0 / core-ktx 1.15.0 带有
    // AAR metadata 声明，要求使用方 compileSdk >= 35。原先是 34，导致 gradle 在
    // :app:checkDebugAarMetadata 阶段失败：
    //   Dependency 'androidx.core:core:1.15.0' requires libraries and applications
    //   that depend on it to compile against version 35 or later of the Android APIs.
    //
    // 关键区分：compileSdk 只决定"用哪个版本的 API 头来编译"，
    // 与 minSdk（APK 能装到哪些设备上）完全独立。所以这次提升
    // **不影响** APK 的最低系统要求 —— minSdk 仍然是 24（Android 7.0）。
    //
    // AGP 8.7.3 同时要求 compileSdk >= 34（其 Java 9+ 字节码要求），35 满足。
    // CI 侧相应安装了 platforms;android-35 + build-tools;35.0.0。
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.example.nodecontainer"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // 当前仅支持 arm64-v8a（bionic 链接的 Node 二进制只编这个 ABI）。
        // 需要 32 位设备时，扩展 build-node-android.sh 增加 armeabi-v7a 产物即可。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        jniLibs {
            // Node 二进制作为运行时资源（assets）解压，不进 jniLibs，因此这里保持默认即可。
            useLegacyPackaging = false
        }
    }
    // 注意：assets/node-bin/** 下的 node 可执行文件必须原样打进 APK，
    // 切勿做 resources.excludes（否则运行时会找不到 node 二进制）。
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
