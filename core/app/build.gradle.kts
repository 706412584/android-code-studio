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
          
          storeFile = keyStoreFile
          storePassword = signing_storePassword
          keyAlias = "androidcs"
          keyPassword = signing_keyPassword
      }
  }

  androidResources { generateLocaleConfig = true }

  buildFeatures {
    aidl = true
    dataBinding = true
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
  
}
