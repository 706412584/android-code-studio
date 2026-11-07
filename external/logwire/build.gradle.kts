import com.tom.rv2ide.plugins.NoDesugarPlugin
import com.tom.rv2ide.build.config.BuildConfig
import java.io.File
import org.gradle.api.GradleException

apply { plugin(NoDesugarPlugin::class.java) }

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.mohammedbaqernull.logger.logwire"
    // **必须固定 36，不能跟随主项目的 BuildConfig.compileSdk（37）。**
    //
    // 原因：本模块产出的 `logger-runtime.aar` 由 AndroidIDEAssetsPlugin 打包进 APK，
    // 运行期再由 Gradle 构建服务解出，通过 init script 以 `implementation files(...)`
    // 注入**每一个用户项目**。因此这里的 compileSdk 会成为所有用户工程 compileSdk 的下限
    // ——而新建模板工程默认 `compileSdk 36` + AGP 8.13.0（其 HIGHEST_SUPPORTED_API 也是 36）。
    //
    // 实测把这里改成 37 后，新建模板工程同步报：
    //   checkDebugAarMetadata: Dependency 'logger-runtime.aar' requires ... compile
    //   against version 37 or later. :app is currently compiled against android-36.
    // 即整个新建项目流程失效。
    //
    // 本 AAR 只依赖 androidx.annotation:1.7.0，36 完全够用，没有任何 API 需要 37。
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig { minSdk = 21 }

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

    buildFeatures { aidl = true }

}

dependencies {
    implementation("androidx.annotation:annotation:1.7.0")
}

/**
 * Renames the AAR produced by `bundleReleaseAar` to `logger-runtime.aar`.
 *
 * 只在 `build/outputs/aar/` 内改名，**不再**往 `core/app/src/main/assets/` 里拷：
 * 那条路径是源码树，而 `.gitignore` 有全局 `*.aar` 规则——拷进去的文件会被
 * 静默忽略，既进不了版本控制，也依赖「构建顺序恰好正确」才会被打进 APK。
 * 现在由 `AndroidIDEAssetsPlugin` 以生成 assets 目录的方式接管（见该插件中
 * `copy<variant>LoggerRuntimeAar`），AGP 会在合并 assets 时取用此处的产物。
 */
tasks.register("fixAarName") {
    doLast {
        val aarDir = layout.buildDirectory.dir("outputs/aar").get().asFile
        val finalName = "logger-runtime.aar"
        val files = aarDir.listFiles { f -> f.extension == "aar" } ?: return@doLast
        // 排除已是目标名的文件，否则重复执行时会选到自己
        val aar =
            files
                .filter { it.name != finalName }
                .maxByOrNull { it.lastModified() } ?: return@doLast
        val renamed = File(aar.parentFile, finalName)
        renamed.delete()
        if (!aar.renameTo(renamed)) {
            // 改名失败必须让构建失败：静默失败会让下游的 assets 拷贝拿不到输入
            throw GradleException("Could not rename ${aar.name} to $finalName")
        }
    }
}

plugins.withId("com.android.library") {
    afterEvaluate {
        tasks.named("bundleReleaseAar").configure {
            finalizedBy("fixAarName")
        }
    }
}

// 同上：Kotlin 2.3 移除了 android 块内的 kotlinOptions。
//
// **languageVersion 必须锁 2.1，不能跟随构建本项目的 Kotlin 2.3.21。**
//
// 本模块产出的 `logger-runtime.aar` 里带 Kotlin 元数据（`logwire_release.kotlin_module`），
// 而该 AAR 由 init script 以 `implementation files(...)` 注入**每一个用户项目**
// （见 GradleBuildService 的 getLoggerRuntimeAar / init script）。默认 languageVersion
// 会让元数据版本随编译器走：实测 Kotlin 2.3.21 编出 `mv = 2.3.0`，而新建模板工程用
// `templates-api/constants.kt` 的 KOTLIN_VERSION = 2.1.0，编译时报
//   Module was compiled with an incompatible version of Kotlin.
//   The binary version of its metadata is 2.3.0, expected version is 2.1.0.
// 即所有新建项目的 Kotlin 编译直接失败。
//
// 锁到 2.1 后元数据为 2.1.0，2.1.0 与 2.3.x 两端都能读。本模块 5 个源文件只用
// 基础 Kotlin（无 2.2/2.3 新语法），降级不损失任何东西。
// 全仓暂无此先例可循，故此处以注释固化理由。
kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
  }
}
