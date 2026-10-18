import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 构建目标 ABI 可由 -Pabi=x86_64 覆盖（CI 双架构矩阵 / 模拟器测试用），默认真机 arm64
val targetAbi = (project.findProperty("abi") as String?) ?: "arm64-v8a"

android {
    namespace = "app.dsh.mobile"
    // CI 上 compileSdk=35；本地 SDK 只有 33/36/36.1 时可临时降/升到此值，
    // compileSdk 仅决定编译期 API 可见性，不影响运行时行为（targetSdk=28 才是生效阈值）。
    compileSdk = 36

    defaultConfig {
        applicationId = "app.dsh.mobile"
        minSdk = 26
        // 关键决策：targetSdk 28 —— sideload 分发，豁免 Android 10+ 的 W^X 限制，
        // 允许从 filesDir 直接 execve bionic 二进制（Termux 同款策略）。
        targetSdk = 28
        versionCode = 238
        versionName = "1.3.0"

        ndk {
            abiFilters += listOf(targetAbi)
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c11"
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // targetSdk 28 会触发大量 lint 提示（前台服务类型、通知权限等），
        // 这些是刻意的兼容性决策，不阻断构建。
        abortOnError = false
    }
}





// ── runtime 自动匹配 ABI（v1.2.127）─────────────────────────────────────
// 模拟器（x86_64）与真机（arm64）构建此前要手动替换 assets/runtime.zip（126MB）
// 并手改 MANIFEST —— 实测踩过"忘了换 → Android 14 报 libz.so EM_AARCH64"。
// 现按 -Pabi 自动选源（_runtime_bak/rt-{x86_64-current,arm64-work}.zip），
// 用 .abi 戳记避免重复复制大文件。
val prepareRuntime by tasks.registering {
    val abi = targetAbi
    val projRoot = rootProject.projectDir
    val assetsZip = file("src/main/assets/runtime.zip")
    val manifest = file("src/main/assets/runtime/MANIFEST.json")
    val stamp = file("src/main/assets/runtime/.abi")
    outputs.upToDateWhen { false }
    doLast {
        val last = if (stamp.exists()) stamp.readText().trim() else ""
        if (last == abi) {
            logger.lifecycle("runtime: 已是 " + abi + "，跳过")
            return@doLast
        }
        val src = if (abi == "x86_64") File(projRoot, "_runtime_bak/rt-x86_64-current.zip")
                  else File(projRoot, "_runtime_bak/rt-arm64-work.zip")
        if (!src.exists()) {
            logger.warn("runtime: 源缺失 " + src.absolutePath + "，保持 assets 现状")
            return@doLast
        }
        src.copyTo(assetsZip, overwrite = true)
        val sha = MessageDigest.getInstance("SHA-256").digest(assetsZip.readBytes())
            .joinToString("") { b -> "%02x".format(b) }
        val updated = manifest.readLines().joinToString("\n") { line ->
            when {
                line.contains("\"version\"") -> "  \"version\": \"v1.2.102-" + abi + "\","
                line.contains("\"sha256\"") -> "  \"sha256\": \"" + sha + "\","
                else -> line
            }
        }
        manifest.writeText(updated)
        stamp.writeText(abi)
        logger.lifecycle("runtime: → " + abi)
    }
}
tasks.named("preBuild") { dependsOn(prepareRuntime) }

// ── Android 环境说明书插件（v1.3.1）──────────────────────────────────────
// 源码只维护一份：packages/dsh-android-env/（标准 dsh 插件包布局：index.js +
// sections/*.md + package.json）。APK 只认 assets，所以构建时把它同步进
// src/main/assets/android-plugins/android-env/（该目录已在 .gitignore 里）。
//
// 为什么不用 Copy 任务 + assets.srcDir：那会给生成目录声明 outputs，Gradle 8 的
// 隐式依赖校验会判定 mergeAssets 与它重叠并直接报错。这里照抄 prepareRuntime
// 的做法 —— doLast 里直接写文件、只挂 preBuild，风险最低（CI 已验证过这个形状）。
val syncAndroidEnvPlugin by tasks.registering {
    val srcDir = rootProject.file("packages/dsh-android-env")
    val dstDir = file("src/main/assets/android-plugins/android-env")
    outputs.upToDateWhen { false }
    doLast {
        if (!srcDir.isDirectory) {
            logger.warn("android-env: 源目录缺失 " + srcDir.absolutePath + "，保持 assets 现状")
            return@doLast
        }
        dstDir.deleteRecursively()
        srcDir.copyRecursively(dstDir, overwrite = true)
        // README 是给开发者看的，不进 APK
        File(dstDir, "README.md").delete()
        val files = dstDir.walkTopDown().filter { it.isFile }.count()
        logger.lifecycle("android-env: 已同步 " + files + " 个文件 → " + dstDir.relativeTo(projectDir))
    }
}
tasks.named("preBuild") { dependsOn(syncAndroidEnvPlugin) }


dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 扩展中心解包链路：Termux .deb 的 data.tar.xz 解码（纯 Java 实现，~110KB）
    implementation("org.tukaani:xz:1.10")
    // Shizuku 官方 API（m1.25）：bind 服务才能触发授权弹窗与真实 adb-shell 能力
    // aidl 提供 IShizukuService/IRemoteProcess（进程执行），api 提供授权与 binder 封装
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
    // 微软 Edge 神经网络语音合成（v1.2.78）：客户端直连，需 WebSocket 客户端
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    // 离线语音识别已移除（v1.2.75）：small 模型质量差、大模型 228MB 不划算。
    // 语音通道 = 系统识别 → 云端 API（免费或自建，OpenAI 兼容），见 CloudAsr。
    // 离线 OCR（v1.2.123）：ML Kit 中文文本识别 bundled 版——模型随 APK 分发，
    // 不依赖 Google Play 服务（国内 ROM 可用）。无障碍节点树缺失的场景
    // （WebView 不暴露节点、自绘/Canvas 界面）从截图提取文字作为 dump 兜底。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}