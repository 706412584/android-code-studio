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
 * 通用助手 —— 消息渲染层（Compose）。
 *
 * 来源：core/app 的 `com.tom.rv2ide.artificial.agent.compose`（75 文件 / 约 24k 行）
 * 逐文件拷贝，包名统一改为 `com.assistant.ui`。
 *
 * **本模块不得依赖任何 ACS 模块**——这是「剥离」的判定标准。若某次改动引入了
 * `projects.core.*` / `editor` / `java` / `xml` / `tooling` / `termux` / `utilities`，
 * 剥离即失败。依赖只允许：AI 内核（core:ai-*）+ AndroidX/Compose + 本模块自带资源。
 */

import com.tom.rv2ide.build.config.BuildConfig

plugins {
  id("com.android.library")
  id("kotlin-android")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "com.assistant.ui"

  compileSdk = BuildConfig.compileSdk

  defaultConfig {
    minSdk = BuildConfig.minSdk
  }

  buildFeatures {
    compose = true
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
  // AI 内核：ToolNames / AgentUIMessage 关联的事件类型等都从它来。
  implementation(projects.core.aiAgent)

  // ---- Compose（坐标与版本与 core/app 完全一致，见那边的长注释）----
  implementation("androidx.compose.runtime:runtime:1.7.6")
  implementation("androidx.compose.foundation:foundation:1.7.6")
  implementation("androidx.compose.ui:ui:1.7.6")
  implementation("androidx.compose.material3:material3:1.3.1")
  // 必须显式引 extended：material3 传递依赖 material-icons-core:1.6.0，本机缓存只有 1.7.8。
  implementation("androidx.compose.material:material-icons-extended:1.7.8")
  implementation("br.com.devsrsouza.compose.icons:feather:1.1.1")
  implementation("androidx.activity:activity-compose:1.9.3")

  // Markdown：**必须停 0.33.0**。0.34+ 调用 Compose foundation 1.8 的 TextAutoSize，
  // 项目钉 1.7.6 —— 编译期不报错，运行时渲染 Markdown 段落才抛 NoSuchMethodError。
  implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.33.0")
  implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.33.0")
  implementation("dev.snipme:highlights-jvm:1.1.0")
  implementation("ru.noties:jlatexmath-android:0.2.0")
  implementation("io.coil-kt:coil-compose:2.7.0")

  // kotlinx-serialization：工具卡/待办卡/搜索卡要解析工具返回的 JSON。
  // 与 core/app 同版本（1.6.0）；插件版本由根项目统一声明（1.9.10）。
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

  // jsoup：Markdown 预处理要清洗 HTML 片段。版本取自版本目录（与 core/app 同一份）。
  implementation(libs.common.jsoup)

  // androidx.core：ColorUtils / ViewCompat / WindowInsetsCompat / PathParser / FileProvider。
  // 这些是 Compose 之外的基础设施，不是 ACS 模块。
  implementation(libs.androidx.core.ktx)

  // 图像查看器用 Coil 加载已解码的字节（base64 字符串会被当路径解析而失败）。
  // 与上面 coil-compose 同一坐标，这里只是显式声明以便注释就近说明用途。
  implementation("io.coil-kt:coil:2.7.0")

  implementation(libs.common.kotlin)
  compileOnly(libs.org.json)
}
