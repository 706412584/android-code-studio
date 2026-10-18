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
      "javac"
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

    val appMainVersion = System.getenv("MAIN_VERSION") ?: "1.0.0"
    val revision = System.getenv("REVISION_NUM")
        ?: readUpdaterField("baseVersionName")?.substringAfterLast("r", "06")
        ?: "06"
    val baseVersion = "$appMainVersion+gh.r$revision"
    println("Android code studio version: $baseVersion")
    project.setProperty("version", baseVersion)
}

rootProject.name = "AndroidCodeStudio"

include(
  ":annotation:annotations",
  ":annotation:processors",
  ":annotation:processors-ksp",

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
