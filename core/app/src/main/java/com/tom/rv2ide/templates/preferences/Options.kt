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
    OPT_MIN_SDK = 21
  }
}
