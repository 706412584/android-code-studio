/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

@file:Suppress("UnstableApiUsage")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
  includeBuild("composite-builds/build-logic") {
    name = "build-logic"
  }

  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  val dependencySubstitutions = mapOf(
    "build-deps" to arrayOf(
      "appintro",
      "logback-core",
      "google-java-format",
      "javac",
      "sora-editor"
    ),

    "build-deps-common" to arrayOf(
      "desugaring-core"
    )
  )

  for ((build, modules) in dependencySubstitutions) {
    includeBuild("composite-builds/${build}") {
      this.name = build
      dependencySubstitution {
        for (module in modules) {
          substitute(module("com.tom.rv2ide.build:${module}"))
            .using(project(":${module}"))
        }
      }
    }
  }

  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenLocal()
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
    maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
    maven { url = uri("https://www.jetbrains.com/intellij-repository/releases") }
  }
}

gradle.rootProject {
    // 版本名与 versionCode 必须来自同一份真源，否则本地包会出现
    // 「versionName 1.0.0+gh.r06 / versionCode 1034」这类错位——
    // 应用内更新按 versionCode 比较，用户看到的版本名却对不上。
    //
    // 顺序：环境变量（CI / 发布脚本注入）→ 仓库根 updater.json → 硬编码兜底。
    val updaterJson = rootDir.resolve("updater.json")
    fun readUpdaterField(field: String): String? {
        return try {
            if (!updaterJson.isFile) null
            else Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"").find(updaterJson.readText())?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }

    // CI 构建的标志：只有 CI 与发布脚本会显式注入这两个环境变量
    // （asm_build.yml 的 Assemble 步骤、tools/publish-release.ps1）。
    // 本地 `./gradlew assembleDebug` 两者皆空，走 updater.json 回落。
    val isCiBuild = System.getenv("MAIN_VERSION") != null && System.getenv("REVISION_NUM") != null

    val appMainVersion = System.getenv("MAIN_VERSION") ?: "1.0.0"
    val revision = System.getenv("REVISION_NUM")
        ?: readUpdaterField("baseVersionName")?.substringAfterLast("r", "06")
        ?: "06"
    // 本地构建在版本名上打 `-local` 后缀。
    //
    // **为什么需要**：本地 assembleDebug/assembleRelease 与 CI 发布包的 versionCode
    // 完全相同（都从 PROJECT_CONFIG_KT_BASE_VERSION_CODE / updater.json 推导），但
    // 构建产物并不兼容——本地包资源路径不缩短、dex 切分不同、且没有注入 aapt2
    // --stable-ids。CI 的热更新补丁基于 release 包，装到本地包上会指向不存在的资源
    // → 启动即崩（实测 PJA110 无限重启 608 次）。
    //
    // 事后靠资源路径指纹能拦（见 PatchPackage.resourcePathStyle / buildsCompatible），
    // 但那要等到补丁已下载。版本名带 -local 让「这不是发布包」在**版本号层面**
    // 一眼可见——设置页、日志、崩溃报告都能立刻看出，不必再去比对 APK 内部结构。
    //
    // CI 构建**不能**带此后缀：发布资产的下载 URL、热更新补丁的 baseVersion 匹配
    // 都依赖版本名精确等于 `1.0.0+gh.rNN`。
    val baseVersion = "$appMainVersion+gh.r$revision" + if (isCiBuild) "" else "-local"
    println("Android code studio version: $baseVersion (ciBuild=$isCiBuild)")
    project.setProperty("version", baseVersion)
}

rootProject.name = "AndroidCodeStudio"

include(
  ":annotation:annotations",
  ":annotation:processors",
  ":annotation:processors-ksp",

  // 通用助手剥离验证模块（见 docs/assistant-standalone/PLAN.md）。
  ":assistant:app",
  ":assistant:engine",
  ":assistant:ui",
  ":assistant:termux",

  ":external:acsprovider",
  ":external:atc",
  ":core:projectdata",
  ":external:logwire",
  
  // ":server:server",
  // ":server:shared",
  
  ":core:actions",
  ":core:ai-agent",
  ":core:ai-protocol",
  ":core:ai-tool",
  ":core:ai-tool-api",
  // ":core:ticode",   // 结绳基本库移植：中文版(src/zh/java)已 0 错误；英文版(src/main/java)仍 657 处，
  //                     故暂不接入构建（接入前需先决定以哪版为准，见 docs/ticode-port/PROGRESS.md）；
  //                     编译用 tools/ticode-compile.sh 或 core/ticode/build.gradle.kts（见 libs/README.md）。
  ":core:app",
  ":ideconfigurations",
  ":core:common",
  ":core:indexing-api",
  ":core:indexing-core",
  ":core:lsp-api",
  ":core:lsp-models",
  ":core:projects",
  ":core:resources",
  ":editor:api",
  ":editor:impl",
  ":editor:lexers",
  ":editor:treesitter",
  ":event:eventbus",
  ":event:eventbus-android",
  ":event:eventbus-events",
  ":java:javac-services",
  ":java:lsp-setup",
  ":java:lsp",
  ":logging:idestats",
  ":logging:logger",
  ":logging:logsender",
  ":termux:application",
  ":termux:emulator",
  ":termux:shared",
  ":termux:view",
  ":tooling:api",
  ":tooling:builder-model-impl",
  ":tooling:events",
  ":tooling:impl",
  ":tooling:model",
  ":tooling:plugin",
  ":tooling:plugin-config",
  ":utilities:build-info",
  ":utilities:flashbar",
  ":utilities:framework-stubs",
  ":utilities:lookup",
  ":utilities:preferences",
  ":utilities:shared",
  ":utilities:templates-api",
  ":utilities:templates-impl",
  ":utilities:treeview",
  ":utilities:uidesigner",
  ":utilities:xml-inflater",
  ":xml:aaptcompiler",
  ":xml:dom",
  ":xml:lsp",
  ":xml:resources-api",
  ":xml:utils",
)
