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

package com.tom.rv2ide.plugins.conf

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.FilterConfiguration
import com.android.build.api.variant.impl.getFilter
import com.tom.rv2ide.build.config.BuildConfig
import com.tom.rv2ide.build.config.projectVersionCode
import com.tom.rv2ide.plugins.NoDesugarPlugin
import com.tom.rv2ide.plugins.util.SdkUtils.getAndroidJar
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.provider.Provider

/**
 * ABIs for which the product flavors will be created. The keys in this map are the names of the
 * product flavors whereas, the value for each flavor is a number that will be incremented to the
 * base version code of the IDE and set as the version code of that flavor.
 *
 * For example, if the base version code of the IDE is 270 (for v2.7.0), then for arm64-v8a flavor,
 * the version code will be `100 * 270 + 1` i.e. `27001`
 */
internal val flavorsAbis = mapOf("arm64-v8a" to 0, "armeabi-v7a" to 0)

fun Project.configureAndroidModule(coreLibDesugDep: Provider<MinimalExternalModuleDependency>) {
  val isAppModule = plugins.hasPlugin("com.android.application")
  assert(isAppModule || plugins.hasPlugin("com.android.library")) {
    "${javaClass.simpleName} can only be applied to Android projects"
  }

  val androidJar =
      extensions
          .getByType(AndroidComponentsExtension::class.java)
          .getAndroidJar(assertExists = true)
  val frameworkStubsJar =
      findProject(":utilities:framework-stubs")!!.file("libs/android.jar").also {
        it.parentFile.mkdirs()
      }

  // 该 jar 被 gitignore（每次在干净检出上从真实 SDK 拷贝），供 java:lsp 当框架面资源用。
  // 注意：**只在文件不存在时拷贝**，所以升级 compileSdk 后本机旧快照不会自动刷新——
  // 想同步（例如从 34 换到 37）需先删掉 utilities/framework-stubs/libs/android.jar。
  // 它只影响 LSP 的框架 stub，不参与 APK 编译，故不刷新也不会构建出错。
  if (!(frameworkStubsJar.exists() && frameworkStubsJar.isFile)) {
    androidJar.copyTo(frameworkStubsJar)
  }

  // 取 CommonExtension（AGP 9 里 BaseExtension 不再是注册的扩展类型，
  // 注册的是 LibraryExtensionImpl / ApplicationExtensionImpl，两者都实现 CommonExtension）。
  //
  // **AGP 9 的破坏性变更**：lambda 形式 DSL（defaultConfig{}、compileOptions{},
  // buildTypes{}、splits{}、testOptions{}、lint{}、packaging{}…）从 CommonExtension
  // 移到了具体的 ApplicationExtension / LibraryExtension 上——AGP 8 时 CommonExtension
  // 有 26 个 Function1 方法，AGP 9 只剩 1 个（compileSdk）。两者没有共同的 lambda 父接口。
  // 因此这里改用 **getter + Kotlin 属性/apply**：这些 getter（getDefaultConfig、
  // getCompileOptions、getBuildTypes、getTestOptions、getBuildFeatures、getLint、
  // getPackaging、getSplits、getDefaultProguardFile）在 AGP 8 与 9 的 CommonExtension
  // 上都存在，且返回的具体接口（BaseFlavor / CompileOptions / BuildType …）两版一致。
  //
  // 少数成员是**模块类型专属**的（targetSdk / versionCode / versionName / multiDexEnabled
  // 只在 ApplicationBaseFlavor / ApplicationVariantDimension 上，library 没有），
  // 因此这里按 isAppModule 分支取具体扩展类型处理 defaultConfig。
  val android = extensions.getByType(CommonExtension::class.java)
  android.compileSdk = BuildConfig.compileSdk
  android.compileSdkMinor = BuildConfig.compileSdkMinor
  android.buildToolsVersion = BuildConfig.buildToolsVersion

  // AGP 9 删除了 CommonExtension 上的 lambda 形式 DSL（AGP 8 有 26 个 Function1 方法，
  // AGP 9 只剩 compileSdk 一个）。lint/packaging 改用 **Kotlin 属性语法**。
  //
  // 注意必须用 `android.lint` 而非 `android.getLint()`：AGP 9 给 getLint() 等一批 getter
  // 加了 `@org.gradle.declarative.dsl.model.annotations.HiddenInDefinition`，显式方法调用
  // 在 Kotlin 里报 Unresolved reference，而属性语法能正常解析。
  // 内层 resources{} 是 ResourcesPackaging 上的方法，AGP 9 仍保留（含 excludes/pickFirsts）。
  android.lint.checkDependencies = true

  android.packaging.apply {
    resources {
      excludes.addAll(
          arrayOf(
              "META-INF/CHANGES",
              "META-INF/README.md",
          )
      )
      pickFirsts.addAll(
          arrayOf(
              "META-INF/eclipse.inf",
              "META-INF/LICENSE.md",
              "META-INF/AL2.0",
              "META-INF/LGPL2.1",
              "META-INF/INDEX.LIST",
              "about_files/LICENSE-2.0.txt",
              "plugin.xml",
              "plugin.properties",
              "about.mappings",
              "about.properties",
              "about.ini",
              "modeling32.png",
          )
      )
    }
  }

  // 公共部分（BaseFlavor 上，app/library 都有）。
  android.defaultConfig.apply {
    minSdk = BuildConfig.minSdk
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // multiDexEnabled 与 targetSdk/versionCode/versionName 一样是模块类型专属
  // （在 ApplicationVariantDimension / LibraryVariantDimension 上，BaseFlavor 没有），
  // 必须按类型分别设。AGP 9 起 core library desugaring 强制要求 multidex，缺了会报
  // "In order to use core library desugaring, please enable multidex"。
  if (isAppModule) {
    val app = extensions.getByType(ApplicationExtension::class.java)
    app.defaultConfig.apply {
      targetSdk = BuildConfig.targetSdk
      versionCode = projectVersionCode
      versionName = rootProject.version.toString()
      multiDexEnabled = true
    }
  } else {
    val lib = extensions.getByType(LibraryExtension::class.java)
    lib.defaultConfig.multiDexEnabled = true
  }

  android.compileOptions.apply {
    sourceCompatibility = BuildConfig.javaVersion
    targetCompatibility = BuildConfig.javaVersion
  }

  configureCoreLibDesugaring(android, coreLibDesugDep)

  if (project.plugins.hasPlugin("com.tom.rv2ide.core-app")) {
    // packagingOptions / splits 的 getter 在 CommonExtension 上也有（实测 9.2.0）。
    android.packagingOptions.jniLibs.useLegacyPackaging = true

    android.splits.abi.apply {
      reset()
      isEnable = true
      isUniversalApk = false
      include("arm64-v8a", "armeabi-v7a")
    }

    extensions.getByType(ApplicationAndroidComponentsExtension::class.java).apply {
      onVariants { variant ->
        variant.outputs.forEach { output ->
          val abiFilter = output.getFilter(FilterConfiguration.FilterType.ABI)
          val abiIdentifier = abiFilter?.identifier

          project.logger.info("Processing variant: ${variant.name}, ABI: $abiIdentifier")

          if (abiIdentifier == null) {
            project.logger.error("No ABI filter found for variant ${variant.name}")
            throw IllegalStateException(
                "ABI filter is required but missing for variant ${variant.name}"
            )
          }

          val verCodeIncr = flavorsAbis[abiIdentifier]
          if (verCodeIncr == null) {
            throw UnsupportedOperationException(
                "Unsupported ABI: $abiIdentifier. Only ARM architectures are supported: ${flavorsAbis.keys.joinToString()}"
            )
          }

          output.versionCode.set(projectVersionCode + verCodeIncr)
        }
      }
    }
  } else {
    android.defaultConfig.ndk.abiFilters.apply {
      clear()
      addAll(flavorsAbis.keys)
    }
  }

  // BuildType 容器：用 getByName 拿到实例后逐项赋值（原先的 `getByName("debug"){}`
  // 是 BuildType 上的 lambda 重载，AGP 9 已移除；容器本身的 getByName 仍在）。
  android.buildTypes.getByName("debug").isMinifyEnabled = false
  android.buildTypes.getByName("release").apply {

    // from AGP 8.4.0 onwards, there are some behavioral changes in R8
    // enabling R8 on library projects results in missing class errors
    // see https://issuetracker.google.com/issues/338411137#comment11
    isMinifyEnabled = false
    // getDefaultProguardFile 是扩展级方法，apply 内 this 是 BuildType，必须显式限定。
    proguardFiles(
        android.getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
    )
  }

  // development build type
  // similar to 'release', but disables proguard/r8
  // this build type can be used to gain release-like performance at runtime
  // the build are faster for this build type as compared to 'release'
  android.buildTypes.register("dev") {
    initWith(android.buildTypes.getByName("release"))
    isMinifyEnabled = false
  }

  android.testOptions.unitTests.isIncludeAndroidResources = true

  android.buildFeatures.viewBinding = true
  android.buildFeatures.buildConfig = true
}

private fun Project.configureCoreLibDesugaring(
    commonExtension: CommonExtension,
    coreLibDesugDep: Provider<MinimalExternalModuleDependency>,
) {
  val coreLibDesugaringEnabled = !project.plugins.hasPlugin(NoDesugarPlugin::class.java)

  commonExtension.compileOptions.isCoreLibraryDesugaringEnabled = coreLibDesugaringEnabled

  if (coreLibDesugaringEnabled) {
    project.dependencies.add("coreLibraryDesugaring", coreLibDesugDep)
  }

}
