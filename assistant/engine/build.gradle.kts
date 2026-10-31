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
 * 通用助手 —— 引擎层。
 *
 * 职责：把 `core/ai-*` 内核装配成「能真的发一条消息、跑一轮工具」的运行时。
 * 包含模型配置存储、服务商预设、HTTP 端口、工具集装配、会话运行器。
 *
 * **本模块不依赖任何 ACS 模块，也不依赖 assistant:ui**——引擎与渲染是两条独立的轴：
 * 渲染层可以换成别的前端，引擎可以在没有界面的地方（测试、后台）跑。
 */

import com.tom.rv2ide.build.config.BuildConfig

plugins {
  id("com.android.library")
  id("kotlin-android")
}

android {
  namespace = "com.assistant.engine"

  compileSdk = BuildConfig.compileSdk

  defaultConfig {
    minSdk = BuildConfig.minSdk
  }

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
  // 唯一的内核依赖：四个 ai-* 模块由它传递。
  implementation(projects.core.aiAgent)

  // org.json：Android 框架自带（工具层的参数解析用），编译期需要。
  compileOnly(libs.org.json)

  implementation(libs.common.kotlin)
}
