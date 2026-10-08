/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

@file:Suppress("UnstableApiUsage")

import com.tom.rv2ide.build.config.BuildConfig
import com.tom.rv2ide.desugaring.utils.JavaIOReplacements.applyJavaIOReplacements
import com.tom.rv2ide.plugins.AndroidIDEAssetsPlugin
import java.util.Properties

plugins {
  id("com.tom.rv2ide.core-app")
  id("com.android.application")
  id("kotlin-android")
  id("kotlin-kapt")
  id("kotlinx-serialization")
  id("kotlin-parcelize")
  id("androidx.navigation.safeargs.kotlin")
  id("com.tom.rv2ide.desugaring")
  // Compose 编译器插件。Kotlin 2.x 起它由官方插件提供，版本必须与 Kotlin 一致
  // （由根项目的 kotlin 版本目录统一约束），不再需要 composeOptions.kotlinCompilerExtensionVersion。
  id("org.jetbrains.kotlin.plugin.compose")
}

apply { plugin(AndroidIDEAssetsPlugin::class.java) }

buildscript {
  dependencies {
    classpath(libs.logging.logback.core)
    classpath(libs.composite.desugaringCore)
  }
}

// AGP 用 *FileDependencies 任务把 `files(...)` 形式的 jar 依赖 dex 化
// （本项目有 9 个：jdk-compiler / java-compiler / jaxp / jdt / javapoet 等）。
// 这些任务曾被上面那条「名字含 desugar 就禁用」的规则连带禁掉，后果是：
// jar 虽然在运行时 classpath 上，却从未进入 dex 合并输入，导致
// `openjdk.tools.javac.**`（约 1900 个类）在 APK 中整体缺失，
// 打开项目做 Gradle 同步时抛 NoClassDefFoundError: openjdk/tools/javac/file/CacheFSInfo。
//
// 这里只禁用 l8DexDesugarLib*（core library desugaring 的库 dex 化，
// 由 com.tom.rv2ide.desugaring 插件接管），不再按名字模糊匹配。
tasks.configureEach {
    if (name.startsWith("l8DexDesugarLib")) {
        enabled = false
    }
}

configurations.all {
  resolutionStrategy {
    force("com.google.guava:guava:32.1.3-android")
    eachDependency {
      if (requested.group == "com.google.guava" && requested.name == "guava") {
        if (requested.version?.contains("jre") == true) {
          useVersion("32.1.3-android")
          because("Force Android version to avoid synthetic lambda conflicts")
        }
      }
    }
  }
}

android {
  namespace = BuildConfig.packageName

  defaultConfig {
    applicationId = BuildConfig.packageName
    vectorDrawables.useSupportLibrary = true
  }
  
  experimentalProperties["android.experimental.enableGlobalSynthetics"] = true
  

  signingConfigs {
      create("custom") {
          val keyStorePath = "${rootProject.projectDir}/signing/signing-key.jks"
          val keyStoreFile = file(keyStorePath)
          
          val signing_storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: ""
          val signing_keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: ""
          // storeFile 与 keyAlias 支持环境变量覆盖：仓库内 signing-key.jks 的密码只在
          // CI Secrets 里，本机无法还原；本机发布/调试用
          // D:/android/keys/acs-debug-signing.jks（密码 android，别名 androidcs，
          // 证书 SHA-1 83AB...C1DA，即已发布 r4/r5/r6 用的那把）。
          // 未设置这两个变量时行为与之前完全一致（CI 仍走仓库内密钥）。
          val signing_storeFileEnv = System.getenv("SIGNING_STORE_FILE")
          val signing_keyAliasEnv = System.getenv("SIGNING_KEY_ALIAS")
          
          storeFile =
              if (signing_storeFileEnv.isNullOrBlank()) keyStoreFile else file(signing_storeFileEnv)
          storePassword = signing_storePassword
          keyAlias = if (signing_keyAliasEnv.isNullOrBlank()) "androidcs" else signing_keyAliasEnv
          keyPassword = signing_keyPassword
      }
  }

  androidResources { generateLocaleConfig = true }

  buildFeatures {
    aidl = true
    dataBinding = true
    // Compose 只用于 AI 助手的消息渲染层（ComposeView 嵌进现有 View 树），
    // 不替换现有 XML/View 体系——两者并存，避免一次性重写整个界面。
    compose = true
  }

  buildTypes {
    debug {
      signingConfig = signingConfigs.getByName("custom")
    }

    release {
      isShrinkResources = false
      signingConfig = signingConfigs.getByName("custom")
    }
  }
  
  lint {
    abortOnError = false
    disable.addAll(arrayOf("VectorPath", "NestedWeights", "ContentDescription", "SmallSp"))
  }

  packaging {
    resources {
      pickFirsts += "kotlin/**.kotlin_builtins"
      pickFirsts += "THIRD-PARTY"
      pickFirsts += "LICENSE"
    }
  }

  applicationVariants.all {
    val variant = this
    variant.outputs.all {
      val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl

      val versionName = variant.versionName ?: "unknown"
      val versionCode = variant.versionCode
      val buildType = variant.buildType.name
      val filters = output.filters
      val abiFilter = filters.find { it.filterType == "ABI" }
      val archSuffix =
          abiFilter?.identifier
              ?: run {
                val variantName = variant.name.lowercase()
                when {
                  variantName.contains("arm64") -> "arm64-v8a"
                  variantName.contains("armeabi") || variantName.contains("arm7") -> "armeabi-v7a"
                  else -> {
                    // This should not happen with our configuration
                    throw IllegalStateException(
                        "Could not determine ABI for variant: $variantName. Expected arm64-v8a or armeabi-v7a."
                    )
                  }
                }
              }

      if (archSuffix !in listOf("arm64-v8a", "armeabi-v7a")) {
        throw IllegalStateException(
            "Unsupported architecture: $archSuffix. Only arm64-v8a and armeabi-v7a are supported."
        )
      }

      val appName = "android-code-studio"
      val fileName =
          if (buildType == "release") {
            "${appName}-${archSuffix}-${versionName}.apk"
          } else {
            "${appName}-${archSuffix}-${buildType}-${versionName}.apk"
          }

      output.outputFileName = fileName

      println(
          "Generated APK: $fileName for variant: ${variant.name}, arch: $archSuffix, versionCode: $versionCode"
      )
    }
  }
}

kapt { arguments { arg("eventBusIndex", "${BuildConfig.packageName}.events.AppEventsIndex") } }

desugaring {
  replacements {
    includePackage(
        "org.eclipse.jgit",
    )

    applyJavaIOReplacements()
  }
}


dependencies {
  // debugImplementation(libs.common.leakcanary)
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
  implementation("org.tukaani:xz:1.9")
  implementation("org.apache.commons:commons-compress:1.21")

  // ---- Jetpack Compose（仅 AI 助手消息渲染层用，见 buildFeatures.compose 的说明）----
  // 不引 BOM：BOM 只做版本对齐，而这里依赖面窄（6 个 artifact），
  // 显式写版本更可控，也避免多一个需要联网解析的平台约束。
  implementation("androidx.compose.runtime:runtime:1.7.6")
  implementation("androidx.compose.foundation:foundation:1.7.6")
  implementation("androidx.compose.ui:ui:1.7.6")
  implementation("androidx.compose.material3:material3:1.3.1")
  // ComposeView 嵌入现有 View 树所需（ComposeView 本身在 ui 里，但 Activity 集成在此）
  implementation("androidx.activity:activity-compose:1.9.3")
  // 图标：Aharou 的组件引用 compose.icons.FeatherIcons，直接引入它的来源库，
  // 使被移植组件的图标引用逐字可用（不需要任何兼容层）。
  implementation("br.com.devsrsouza.compose.icons:feather:1.1.1")
  // 图标：Aharou 自身也声明了 material-icons-extended，其组件里另有直接引用 Material 图标之处。
  implementation("androidx.compose.material:material-icons-extended:1.7.8")
  // Markdown 正文渲染。Aharou 的消息气泡与代码卡都走它，属逐字移植的前提。
  //
  // 版本必须停在 0.33.0。这是**三条**独立天花板取交集的结果，不是随意挑的：
  //
  // 1) Kotlin 元数据：0.36 起改用 Kotlin 2.2+ 编译，其 .class 元数据版本 2.2/2.3，
  //    本项目 Kotlin 2.1.0 的编译器读不了（实测 0.41.0 报
  //    "Module was compiled with an incompatible version of Kotlin ... 2.3.0, expected 2.1.0"）。
  // 2) compileSdk：0.39 起 AAR metadata 声明 minCompileSdk=36，而本项目统一 compileSdk=34。
  // 3) **Compose ABI**（最容易漏的一条，且只有运行时才暴露）：0.34 起该库改为针对更新的
  //    Compose 编译，会调用 `BasicText(…, TextAutoSize, …)`，而 TextAutoSize 是
  //    Compose foundation **1.8** 才加入的参数。本项目钉 foundation 1.7.6，编译期完全不报错
  //    （Kotlin 只校验我们自己的代码对库 AAR 的引用，不校验库内部调用的目标是否存在），
  //    到**运行时渲染 Markdown 段落**才抛
  //    `NoSuchMethodError: No static method BasicText-CL7eQgs(…, TextAutoSize, …)`，
  //    并被应用自带崩溃处理器捕获。实测 0.34/0.35 各引用 TextAutoSize 2 处，0.33 及更早为 0 处。
  //    已在 foundation 1.7.6 上逐个核验：0.33.0 需要的 BasicText-RWo7tUw 与 BasicText-VhcvRP8
  //    两个重载均存在；0.35.0 需要的 BasicText-CL7eQgs 不存在。
  //
  // 三条同时满足的只有 ≤0.33.0。取最新的 0.33.0（kotlin-stdlib 2.1.20、minCompileSdk=1）。
  implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.33.0")
  implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.33.0")
  // 代码块语法高亮（markdown-renderer-code 的高亮后端）
  implementation("dev.snipme:highlights-jvm:1.1.0")
  // 数学公式（MarkdownMathSupport）
  implementation("ru.noties:jlatexmath-android:0.2.0")
  // 图片加载（ChatImageLoader / 消息内图片）
  implementation("io.coil-kt:coil-compose:2.7.0")

  // external deps here
  implementation("com.github.Dimezis:BlurView:version-3.2.0")
  implementation("androidx.security:security-crypto:1.1.0-alpha06")
  implementation(projects.external.acsprovider)
  implementation(projects.external.atc) 
  implementation(libs.external.customizable.cardview)
  implementation(projects.external.logwire)
	implementation(libs.external.seasonal.effects)
  
  // Annotation processors
  kapt(libs.common.glide.ap)
  kapt(libs.google.auto.service)
  kapt(projects.annotation.processors)

  implementation(libs.common.editor)
  implementation(libs.common.utilcode)
  implementation(libs.common.glide)
  implementation(libs.common.jsoup)
  implementation(libs.common.kotlin.coroutines.android)
  implementation(libs.common.retrofit)
  implementation(libs.common.retrofit.gson)
  implementation(libs.common.charts)
  implementation(libs.common.hiddenApiBypass)
  implementation(libs.aapt2.common)

  implementation(libs.google.auto.service.annotations)
  implementation(libs.google.gson)
  implementation(libs.google.guava)

  implementation("com.google.ai.client.generativeai:generativeai:0.9.0") {
    exclude(group = "org.slf4j", module = "slf4j-api")
    exclude(group = "org.slf4j", module = "slf4j-simple")
    exclude(group = "org.slf4j", module = "slf4j-nop")
  }
  
  // TODO: remove this
  implementation("com.github.MiyazKaori:SilentInstaller:1.0.0-alpha")

  // Shizuku：agent 的 shell 后端之一，提供 adb 级权限（pm install / am start / logcat）。
  // aidl 由 api 传递引入，含 IShizukuService / IRemoteProcess。
  implementation("dev.rikka.shizuku:api:13.1.5")
  implementation("dev.rikka.shizuku:provider:13.1.5")

  // Git
  implementation(libs.git.jgit)

  // AndroidX
  implementation(libs.androidx.splashscreen)
  implementation(libs.androidx.annotation)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.cardview)
  implementation(libs.androidx.constraintlayout)
  implementation(libs.androidx.coordinatorlayout)
  implementation(libs.androidx.drawer)
  implementation(libs.androidx.grid)
  implementation(libs.androidx.nav.fragment)
  implementation(libs.androidx.nav.ui)
  implementation(libs.androidx.preference)
  implementation(libs.androidx.recyclerview)
  implementation(libs.androidx.transition)
  implementation(libs.androidx.vectors)
  implementation(libs.androidx.animated.vectors)
  implementation(libs.androidx.work)
  implementation(libs.androidx.work.ktx)
  implementation(libs.google.material)
  implementation(libs.google.flexbox)

  // Markdown rendering for assistant replies. markwon 4.6.2 was already declared in
  // libs.versions.toml and used by the termux modules, so this adds no new dependency
  // to the build — only a new consumer.
  implementation(libs.common.markwon.core)
  implementation(libs.common.markwon.extStrikethrough)
  implementation(libs.common.markwon.linkify)

  // 代码块语法高亮。见 libs.versions.toml 中 prism4j 的说明——用 Nekogram 分支
  // （32 种语言内嵌、无 kapt），代价是不能用 markwon 官方的 syntax-highlight 扩展，
  // 高亮由 AssistantCodeHighlighter 自己实现。
  implementation(libs.common.prism4j)

  // Kotlin
  implementation(libs.androidx.core.ktx)
  implementation(libs.common.kotlin)

  // Dependencies in composite build
  implementation(libs.composite.appintro)
  implementation(libs.composite.desugaringCore)
  // implementation(libs.composite.javapoet)
  implementation(files(rootProject.file("composite-builds/build-deps/libs/javapoet.jar")))

  // Local projects here
  // AI agent stack: tool API contracts, model protocols, tool implementations,
  // and the multi-turn agent loop. All four are pure java-library modules.
  implementation(projects.core.aiToolApi)
  implementation(projects.core.aiProtocol)
  implementation(projects.core.aiTool)
  implementation(projects.core.aiAgent)
  implementation(projects.core.projectdata)
  implementation(projects.ideconfigurations)
  implementation(projects.core.actions)
  implementation(projects.core.common)
  implementation(projects.core.indexingApi)
  implementation(projects.core.indexingCore)
  implementation(projects.core.lspApi)
  implementation(projects.core.projects)
  implementation(projects.core.resources)
  implementation(projects.editor.impl)
  implementation(projects.editor.lexers)
  implementation(projects.event.eventbus)
  implementation(projects.event.eventbusAndroid)
  implementation(projects.event.eventbusEvents)
  implementation(projects.java.javacServices)
  implementation(projects.java.lspSetup)
  implementation(projects.java.lsp)
  implementation(projects.logging.idestats)
  implementation(projects.logging.logsender)
  implementation(projects.termux.application)
  implementation(projects.termux.view)
  implementation(projects.termux.emulator)
  implementation(projects.termux.shared)
  implementation(projects.tooling.api)
  implementation(projects.tooling.pluginConfig)
  implementation(projects.utilities.buildInfo)
  implementation(projects.utilities.lookup)
  implementation(projects.utilities.preferences)
  implementation(projects.utilities.templatesApi)
  implementation(projects.utilities.templatesImpl)
  implementation(projects.utilities.treeview)
  implementation(projects.utilities.uidesigner)
  implementation(projects.utilities.xmlInflater)
  implementation(projects.xml.aaptcompiler)
  implementation(projects.xml.lsp)
  implementation(projects.xml.utils)

  // This is to build the tooling-api-impl project before the app is built
  // So we always copy the latest JAR file to assets
  compileOnly(projects.tooling.impl)

  // JVM unit tests for the pure-logic pieces of the AI assistant UI (streaming throttle,
  // code fence parser, provider config).
  // Deliberately no Robolectric: the tested classes have no Android dependencies, and
  // pulling in Robolectric would slow every test run for no gain.
  //
  // org.json 在单测里要显式依赖：运行时它由 Android 框架提供，而 JVM 单测跑在桩实现上
  // （每个方法抛 "not mocked"）。ProviderConfig 的序列化要用真实实现才测得了。
  testImplementation(libs.tests.junit)
  testImplementation(libs.org.json)
}
