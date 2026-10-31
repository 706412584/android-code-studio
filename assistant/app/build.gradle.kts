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
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

/*
 * 通用助手 App —— **剥离验证模块（第 1 步）**。
 *
 * 本模块存在的唯一目的：证明 `core/ai-*` 这四个纯 JVM 内核模块可以被一个
 * **不依赖任何 ACS 模块**的 Android App 消费。它刻意不引 ACS 的任何模块——
 * 一旦引了，这个验证就失去意义。
 *
 * 见 docs/assistant-standalone/PLAN.md 的「第 1 步」。
 */

import com.tom.rv2ide.build.config.BuildConfig

plugins {
  id("com.android.application")
  id("kotlin-android")
  // Compose 编译器插件：版本必须与仓库 Kotlin（2.1.0）一致，否则编译器报版本不匹配。
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "com.assistant.app"

  compileSdk = BuildConfig.compileSdk

  defaultConfig {
    applicationId = "com.assistant.app"
    minSdk = 26
    targetSdk = BuildConfig.targetSdk
    versionCode = 1
    versionName = "0.1.0-standalone-probe"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }

  buildFeatures {
    compose = true
  }

  // 与仓库约定一致（BuildConfig.javaVersion = 11）：Java 与 Kotlin 的 jvmTarget
  // 必须对齐，否则 Kotlin 插件报 "Inconsistent JVM-target compatibility"。
  compileOptions {
    sourceCompatibility = BuildConfig.javaVersion
    targetCompatibility = BuildConfig.javaVersion
  }

  buildToolsVersion = BuildConfig.buildToolsVersion
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
  }
}

dependencies {
  // 唯一的依赖：AI 内核。**没有** projects.core.* / editor / java / xml / tooling /
  // termux / utilities —— 这正是本模块要验证的事。
  implementation(projects.core.aiAgent)

  // 只留 Kotlin 标准库：探针用的是框架自带的 android.app.Activity / TextView，
  // 不需要 appcompat / material。少引一个依赖，验证的结论就更干净一分——
  // 「内核能被独立 App 消费」不该被无关的 UI 库依赖干扰。
  implementation(libs.common.kotlin)

  // ---- 第 2 步前置验证：Compose 依赖能否离线解析 ----
  //
  // 刻意用与 core/app 完全相同的坐标与版本（见 core/app/build.gradle.kts:269-305）。
  // 剥离后渲染层要原样复用这套约束，尤其是 markdown 渲染器必须停在 0.33.0——
  // 它的 0.34+ 会调用 Compose foundation 1.8 才有的 TextAutoSize 参数，编译期不报错，
  // 运行时渲染 Markdown 段落才抛 NoSuchMethodError（详见那边的长注释）。
  //
  // 探针阶段只验证「能解析 + 能编译」，尚未搬运渲染代码。
  implementation("androidx.compose.runtime:runtime:1.7.6")
  implementation("androidx.compose.foundation:foundation:1.7.6")
  implementation("androidx.compose.ui:ui:1.7.6")
  implementation("androidx.compose.material3:material3:1.3.1")
  // 必须有：material3 1.3.1 传递依赖 material-icons-core:1.6.0，而本机缓存里只有
  // 1.7.8。显式引 extended 后由版本冲突解析取高版本，1.6.0 的解析被顶掉——
  // core/app 能离线构建靠的就是这一行（见 core/app/build.gradle.kts:282）。
  implementation("androidx.compose.material:material-icons-extended:1.7.8")
  // 图标：Aharou 的组件引用 compose.icons.FeatherIcons，直接引入来源库，
  // 使被移植组件的图标引用逐字可用（无需兼容层）。
  implementation("br.com.devsrsouza.compose.icons:feather:1.1.1")
  // ComposeView 嵌入宿主所需；同时把 activity-ktx / activity-compose 的传递版本
  // 顶到已缓存的那一档（1.9.3），否则会去解析未缓存的 1.7.0 / 1.8.2。
  implementation("androidx.activity:activity-compose:1.9.3")

  // org.json：Android 框架自带，编译期需要。
  compileOnly(libs.org.json)
}
