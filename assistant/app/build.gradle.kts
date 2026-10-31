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

  // org.json：Android 框架自带，编译期需要。
  compileOnly(libs.org.json)
}
