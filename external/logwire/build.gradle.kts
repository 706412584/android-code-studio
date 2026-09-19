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

    kotlinOptions { jvmTarget = "11" }
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