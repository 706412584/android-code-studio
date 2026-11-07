package com.tom.rv2ide.build.config/*
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

import org.gradle.api.JavaVersion

/**
 * Build configuration for the IDE.
 *
 * @author Akash Yadav
 */
object BuildConfig {

  /** AndroidIDE's package name. */
  // const val packageName = "com.tom.rv2ide"
  const val packageName = "com.tom.rv2ide"

  /**
   * The compile SDK version.
   *
   * <p>37 而非 36：AI 助手的消息渲染层要用 Material 3 Expressive 的 Compose 组件
   * （`LoadingIndicator` 等），其可用的 material3 版本（1.4.0-alpha10）声明
   * `minCompileSdk=35`；取 37 是为了与已安装平台对齐（本机与 CI 都装 android-37.2）。
   *
   * <p><b>AGP 8.13.0 官方只支持到 36</b>（其 sdklib 的 `HIGHEST_SUPPORTED_API=36`），
   * 因此编译时会打一条「tested up to compile SDK version 36.1」的警告——这是软警告
   * 不是错误，用 `android.suppressUnsupportedCompileSdk` 抑制（见 gradle.properties）。
   * 实测 compileSdk=37 + compileSdkMinor=2 可正常 assemble。
   *
   * <p><b>必须同时给 compileSdkMinor</b>：SDK 平台目录名是 `android-37.2`，
   * 只写 37 会让 AGP 去找不存在的 `android-37` 并报
   * `Failed to find target with hash string 'android-37'`。
   */
  const val compileSdk = 37

  /** 与 [compileSdk] 配套的次要版本（见上）。 */
  const val compileSdkMinor = 2

  /**
   * SDK 平台目录名（`platforms/<这个>`）。
   *
   * <p>不带 minor 时 AGP 期望 `android-37`，而 SDK 管理器实际装的是 `android-37.2`。
   * [com.tom.rv2ide.plugins.util.SdkUtils] 按名字直接拼路径取 `android.jar`，
   * 故必须用它而非裸的 [compileSdk]。
   */
  const val compileSdkPlatform = "android-$compileSdk.$compileSdkMinor"

  /** The build tools version. */
  const val buildToolsVersion = "35.0.0"

  /** The minimum SDK version. */
  const val minSdk = 26

  /** The target SDK version. */
  const val targetSdk = 28

  const val ndkVersion = "26.1.10909125"

  /** The source and target Java compatibility. */
  val javaVersion = JavaVersion.VERSION_11
}
