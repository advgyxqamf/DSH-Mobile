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
            // =====================================================================
            //  这是「node 能否在真机跑起来」的开关，别改。
            // =====================================================================
            // 背景：Android 10+ 的 SELinux 禁止 exec 应用可写目录里的文件
            //   (files/ → app_data_file，execve 返回 EACCES)
            // 唯一被允许执行的是系统在安装时解压出来的 native lib 目录：
            //   /data/app/<pkg>/lib/<abi>/  (exec_type)
            // 真机实证（Android 16/API 36）：
            //   IOException: Cannot run program ".../files/node/24.21.0/node":
            //   error=13, Permission denied
            //
            // 所以 node 以 jniLibs/arm64-v8a/libnode.so 的形式打包，
            // 运行时执行 applicationInfo.nativeLibraryDir/libnode.so。
            //
            // useLegacyPackaging 必须为 true：
            //   AGP 3.6+ 默认(false)会把 .so 以【压缩】形式放进 APK，
            //   安装时不解压到 lib dir，而是在 APK 内直接 mmap 加载。
            //   那种模式下列表 dir 根本看不到文件，File.exists() 为 false，
            //   更不可能被 exec。置 true 后系统才会把 .so 真正解压落盘到
            //   /data/app/.../lib/arm64-v8a/libnode.so，我们才能 ProcessBuilder 启动它。
            //   代价是 APK 体积变大（不压缩），这对本地运行时是必要且可接受的。
            useLegacyPackaging = true
        }
    }
    // 注意：node 二进制现位于 jniLibs/arm64-v8a/libnode.so。
    // 它的文件名必须以 lib 开头、.so 结尾，否则 AGP 不会把它当 native lib 处理，
    // 也就不会被解压到可执行的 lib dir 里去。
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
