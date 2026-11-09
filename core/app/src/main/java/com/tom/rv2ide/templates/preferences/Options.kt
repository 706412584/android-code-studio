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

package com.tom.rv2ide.templates.preferences

import com.tom.rv2ide.preferences.internal.BuildPreferences
import com.tom.rv2ide.templates.PROJECTS_COMPILE_SDK_VERSION

/**
 * Global template configuration options.
 *
 * This singleton manages template generation settings that persist across the wizard workflow and
 * can be accessed by all template handlers.
 *
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
object Options {

  /** Use Gradle Kotlin DSL (.gradle.kts) instead of Groovy (.gradle). Default: true */
  var OPT_USE_GRADLE_KTS: Boolean = false

  /** Minimum SDK API level for the project. Default: 21 */
  var OPT_MIN_SDK: Int = 21
  
  /** Use CMake build system instead of ndk build system. Default: false */
  var OPT_BUILD_SYSTEM_USE_CMAKE: Boolean = false

  /** Create a project without any Activity (Empty Project with no UI). Default: false */
  var OPT_IS_NO_ACTIVITY: Boolean = false

  /** Is the project a native cpp? Default: false */
  var OPT_IS_NATIVE_CPP: Boolean = false

  /** Is the project a native game activity? Default: false */
  var OPT_IS_NATIVE_GAME_ACTIVITY: Boolean = false

  /** Native language type for C++ projects. Values: "cpp" or "c" Default: "cpp" */
  var OPT_NATIVE_LANGUAGE: String = "cpp"

  /** Selected NDK version for native projects. Default: null (will auto-select highest) */
  var OPT_SELECTED_NDK_VERSION: String? = null

  /**
   * 用户在向导里选的 build-tools 版本（如 `"36.0.0"`）。
   *
   * <p>null 表示**不写进 build.gradle**，由 AGP 自己挑「它要求的最低版本」——
   * 这是模板长期以来的行为，多数项目也不需要显式指定。
   *
   * <p><b>什么时候需要它</b>：设备上装了多个 build-tools（如同时有 36.0.0 与 37.0.0），
   * 而 AGP 挑的那个恰好有问题时，用户可以钉一个已知能用的；反过来，AGP 9 要求
   * ≥36.0.0，设备若只装了 35.x，构建直接失败，不给出路就等于无解。
   *
   * <p>取值来自设备上**已安装**的 build-tools（见 `SdkManagerInstaller.installedBuildTools`）——
   * 不列未安装的，选一个没装的只会让构建报「找不到 build-tools」。
   */
  var OPT_BUILD_TOOLS_VERSION: String? = null

  /**
   * 用户在向导里选的 **compileSdk**（API level，如 36）。
   *
   * <p>默认 [PROJECTS_COMPILE_SDK_VERSION]（36）——模板长期行为。
   *
   * <p><b>与 [OPT_MIN_SDK] 的区别</b>：minSdk 是「最低支持的系统版本」（写进
   * `defaultConfig`），compileSdk 是「用哪个平台的 android.jar 编译」（写进
   * `android {}`）。前者影响运行范围，后者影响能用哪些 API 与编译结果。
   *
   * <p><b>与 build-tools 的区别</b>：compileSdk 是**项目级**（每个项目可不同），
   * build-tools 是**设备级工具链**，因此后者不放这里、而在 SDK 管理器里设默认。
   */
  var OPT_COMPILE_SDK: Int = PROJECTS_COMPILE_SDK_VERSION

  /**
   * [OPT_COMPILE_SDK] 的**次要版本**（如 2 → 平台目录 `android-37.2`）。
   *
   * <p>null = 该 API 没有次要版本（如 36 → `android-36`）。
   * 非 null 时必须落盘，否则 AGP 去找不存在的 `android-<主版本>`
   * （本机 core/app 对此有实测记录）。
   *
   * <p>取值由向导按所选的平台目录名推导：`android-37.2` → 37 + minor 2。
   */
  var OPT_COMPILE_SDK_MINOR: Int? = null

  /**
   * 用户在向导里显式选择的 CMake **版本号**（如 "3.31.6"）。
   *
   * <p>null 表示「自动挑一个当前设备能跑的」，见
   * [com.tom.rv2ide.templates.android.etc.NativeCpp.Check.getHighestRunnableCMakeVersion]。
   *
   * <p><b>为什么存版本号而不是可执行文件路径</b>：生成的 build.gradle 里
   * `cmake { version '…' }` 要的是版本号，AGP 自己按版本号去 `$SDK/cmake/<version>/`
   * 找二进制。存路径既写不进 build.gradle，也跨不过「项目在别处构建」这一步——
   * 原先那个 `OPT_CMAKE_PATH` 正是因此从未被任何地方读取，用户选完等于没选。
   */
  var OPT_CMAKE_VERSION: String? = null

  /** Reset all options to their default values. */
  fun resetToDefaults() {
    OPT_USE_GRADLE_KTS = false
    OPT_IS_NO_ACTIVITY = false
    OPT_IS_NATIVE_CPP = false
    OPT_IS_NATIVE_GAME_ACTIVITY = false
    OPT_BUILD_SYSTEM_USE_CMAKE = false
    OPT_NATIVE_LANGUAGE = "cpp"
    OPT_SELECTED_NDK_VERSION = null
    OPT_CMAKE_VERSION = null
    // build-tools 是**设备级**设置，默认取 SDK 管理器里的全局偏好
    // （用户没设过就是 null = 不写进 build.gradle，由 AGP 自挑）。
    // 不写死 null：否则用户在 SDK 管理器设的默认值一到模板里就被抹掉了。
    OPT_BUILD_TOOLS_VERSION = normalizeBuildToolsVersion(BuildPreferences.defaultBuildToolsVersion)
    OPT_COMPILE_SDK = PROJECTS_COMPILE_SDK_VERSION
    OPT_COMPILE_SDK_MINOR = null
    OPT_MIN_SDK = 21
  }

  /**
   * 把偏好里的 build-tools 值归一化成「写进 build.gradle 的值」。
   *
   * <p><b>为什么需要它</b>：偏好里「不指定」存的是**空串**（见 `BuildPreferences`
   * 的 `defaultBuildToolsVersion`，默认值与 SDK 管理器的「自动」项都是 `""`），
   * 而生成模板要的是 **null**（`buildToolsVersion(null)` = 不写这一行）。
   * 空串直落会生成 `buildToolsVersion ''`，AGP 报 `Invalid revision:` 且
   * **整个项目初始化失败**——不是构建失败，是连项目模型都读不出来
   * （2026-11-10 PJA110 实机复现，daemon 日志 `FAILURE ... Invalid revision:`）。
   *
   * <p>抽成纯函数（不碰偏好、不碰 Android）以便 JVM 单测钉死：这里错一次就是
   * 「新建项目点开即坏」这种级别的故障。
   */
  @JvmStatic
  fun normalizeBuildToolsVersion(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
}
