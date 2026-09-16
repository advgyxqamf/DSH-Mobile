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

    // =========================================================================
    //  签名配置：**从环境变量/密钥文件注入，缺失时退化为 debug 并显式警告**
    // =========================================================================
    //  为什么必须有这一段（这是一个真实的、用户可见的缺陷）：
    //
    //  此前本项目**没有任何 signingConfig** —— 每次 CI 出包都用 AGP 自动生成的
    //  debug keystore（`~/.android/debug.keystore`，由 runner 现场生成，
    //  指纹每次都不同）。后果是：
    //
    //    · `adb install -r` 新包 → INSTALL_FAILED_UPDATE_INCOMPATIBLE
    //      （签名不一致，Android 拒绝覆盖安装）
    //    · 「设备自我升级 APK」这条路彻底走不通 —— 而它是 A'（重打包路线）
    //      与 app.install 静默升级能力的**共同绝对前提**
    //    · 增量升级、灰度推送等一切依赖"应用身份稳定"的机制都不成立
    //
    //  所以这里让它可注入。设计上的三个取舍：
    //
    //  ① **可选而非必需**。没配密钥时**不报错**，退化为 debug 签名继续出包 ——
    //     本项目大部分场景（本地开发、功能验证）只需要一个能装的包，
    //     强制要求密钥会让这些场景全部卡住。
    //
    //  ② **但必须显式警告**。缺失时打 WARNING 并说明后果 ——
    //     静默退化会让"发布包签名不稳定"这件事永远浮不出来，
    //     而那正是上面三个后果的根源。
    //
    //  ③ **读文件而非只读环境变量**。密钥库是二进制，环境变量传它会
    //     有很大的转义/长度风险。所以约定：CI 从 secret 解出文件放到
    //     `keys/release.keystore`，这里按文件存在性判断
    //     （`keys/` 整体 gitignored，与 ota-private.pem 一致）。
    // =========================================================================
    val releaseKeystore = rootProject.file("keys/release.keystore")
    val hasReleaseKeystore = releaseKeystore.exists()

    // 密码先取到局部不可变 val，再赋给 signingConfig。
    //
    // ⚠️ 不能写成 `storePassword = System.getenv(...) ?: ""` 后紧跟
    //    `require(storePassword.isNotEmpty())` —— 会直接编译失败：
    //      Smart cast to 'String' is impossible, because 'storePassword'
    //      is a mutable property that could have been changed by this time
    //    ApkSigningConfig 的这两个属性是 `var`，Kotlin 拒绝对可变属性做
    //    智能转换。用局部 val 绕开，同时也让"读环境变量"只发生一次。
    val keystorePw = System.getenv("DSH_KEYSTORE_PASSWORD") ?: ""
    val keyPw = System.getenv("DSH_KEY_PASSWORD") ?: keystorePw
    val keyAliasName = System.getenv("DSH_KEY_ALIAS") ?: "dsh"

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = keystorePw
                keyAlias = keyAliasName
                keyPassword = keyPw
                // 校验：密码空着会让 apksigner 在**最后一步**才失败，
                // 那时整个构建已经等了很久（release 构建含 native 交叉编译）。
                // 这里前置报错，失败得越早越好。
                require(keystorePw.isNotEmpty()) {
                    "检测到 keys/release.keystore，但 DSH_KEYSTORE_PASSWORD 为空。" +
                        "请设置该环境变量（CI: 由 secret 注入）。"
                }
                // V1/V2 都开：minSdk=24 的设备对 V2 支持良好，但 V1 保留可兼容
                // 更老的安装器与某些加固/分发渠道。
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // debug 构建也允许用稳定签名 —— 否则"开发期装两次要卸载重装"。
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    // 构建期把签名状态打出来。放在这里（配置阶段）而不是某个 task 里，
    // 是为了让它在**任何**任务执行时都出现在日志开头 —— 排查"装不上"时
    // 第一眼就要看到"这个包到底是用什么签的"。
    if (!hasReleaseKeystore) {
        logger.warn(
            "[dsh-signing] ⚠ 未找到 ${releaseKeystore.path} —— 本次产物将使用 AGP 自动生成的 " +
                "debug 签名。后果：签名指纹每次都不同，新包无法覆盖安装到旧包上" +
                "（INSTALL_FAILED_UPDATE_INCOMPATIBLE）。若这是发布构建，请配置密钥。" +
                "本地可用 ./scripts/keygen-android-keystore.sh 生成。"
        )
    } else {
        logger.lifecycle("[dsh-signing] 使用稳定签名: ${releaseKeystore.path}")
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

            // -----------------------------------------------------------------
            // keepDebugSymbols = 旧 API 的 doNotStrip。
            //
            // 命名容易误解：它不只是「保留调试信息」，实际语义就是
            // 「这些 .so 不要交给 strip 处理」。AGP 官方文档里 doNotStrip 的
            // 替代写法明确指向它："Use jniLibs.keepDebugSymbols.add() instead."
            //
            // 为什么这两个文件必须豁免 strip：
            //   AGP 默认会把 jniLibs 里的 .so 交给 NDK 的 llvm-strip 处理以减小体积。
            //   但这两个文件【不是普通的共享库】：
            //     · libnode.so      —— 其实是一个可执行文件，只是被改名为 lib*.so，
            //                          借 jniLibs 这条通道落到可执行目录（见上文 W^X 说明）。
            //                          strip 会破坏它被 exec 所需的信息。
            //     · libc++_shared.so —— node 的运行期动态依赖，符号由它提供。
            //                          真机报的 cannot locate symbol
            //                          "_ZTVNSt6__ndk119basic_ostringstream..." 正指向它；
            //                          strip 掉符号表只会让动态链接更无解。
            //
            //   这两个文件由 CI 用与 node 相同的 NDK 亲自挑选/产出，不需要 AGP 再加工。
            //   显式豁免，避免「体积看着小了、真机反而加载失败」这类极难排查的副作用。
            // -----------------------------------------------------------------
            // ↓ 直接读清单，不再硬编码文件名。
            //
            // 清单来源：.github/native-assets.txt —— 它是
            // app/src/main/java/com/example/nodecontainer/native/NativeAssetRegistry.kt
            // 的**投影**（注册表是唯一事实来源）。
            //
            // 这样做的意义：加一个新的可执行资产时，只要改注册表 + 这份清单，
            // gradle 配置**自动跟上**。此前这里写死两个名字，新资产会被 AGP
            // 悄悄 strip 掉 —— 而 strip 的破坏只在真机运行期才暴露，极难排查。
            //
            // 清单缺失时**不静默降级**：直接 fail，避免打出一个看似正常、
            // 实则缺符号的 APK。
            keepDebugSymbols += nativeAssetNames().map { "**/$it" }
        }
    }
    // 注意：node 二进制现位于 jniLibs/arm64-v8a/libnode.so。
    // 它的文件名必须以 lib 开头、.so 结尾，否则 AGP 不会把它当 native lib 处理，
    // 也就不会被解压到可执行的 lib dir 里去。
}

/**
 * 读 `.github/native-assets.txt`（NativeAssetRegistry 的投影）。
 *
 * ⚠️ 必须是 **普通函数**，不能用 `val ... by lazy` 的委托属性：
 * 在 Gradle Kotlin DSL 里，脚本体的 `val x by lazy {}` 其委托对象是在
 * 脚本**求值过程中**才赋值的，而 `packaging { }` 这个 lambda 会在同一次
 * 求值里先于该赋值执行 —— 于是拿到的是 null，报
 *   `Cannot invoke "kotlin.Lazy.getValue()" because "<local1>" is null`
 * （CI 真实踩过）。函数没有这个求值顺序问题。
 *
 * 文件缺失/为空时直接抛异常，**刻意不静默降级**：
 * 否则会产出「编译成功但真机跑不起来」的 APK，排查成本远高于一次构建失败。
 */
fun nativeAssetNames(): List<String> {
    val f = rootProject.file(".github/native-assets.txt")
    if (!f.exists()) {
        throw GradleException(
            "缺少 .github/native-assets.txt —— 它是 NativeAssetRegistry 的投影，构建必需。\n" +
                "该文件同时被 CI（fast-apk.yml / build-apk.yml）读取，用于下载校验与 APK 审计。"
        )
    }
    val names = f.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
    if (names.isEmpty()) {
        throw GradleException(".github/native-assets.txt 里没有任何资产名 —— 是不是被清空了？")
    }
    return names
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
