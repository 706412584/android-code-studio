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

package com.tom.rv2ide.preferences.internal

/** @author Akash Yadav */
@Suppress("MemberVisibilityCanBePrivate")
object BuildPreferences {

  const val STACKTRACE = "idepref_gradleCmd_stacktrace"
  const val DEBUG = "idepref_gradleCmd_debug"
  const val SCAN = "idepref_gradleCmd_scan"
  const val INFO = "idepref_gradleCmd_info"
  const val WARNING_MODE = "idepref_gradleCmd_warningMode"
  const val BUILD_CACHE = "idepref_gradleCmd_buildCache"
  const val OFFLINE_MODE = "idepref_gradleCmd_offlineMode"

  const val GRADLE_COMMANDS = "idepref_build_gradleCommands"
  const val GRADLE_CLEAR_CACHE = "idepref_build_gradleClearCache"
  const val CUSTOM_GRADLE_INSTALLATION = "idepref_build_customGradleInstallation"
  const val GRADLE_MIRROR_ID = "ide.build.gradleMirrorId"
  const val DEFAULT_BUILD_TOOLS_VERSION = "ide.build.defaultBuildToolsVersion"
  const val LAUNCH_APP_AFTER_INSTALL = "ide.build.run.launchAppAfterInstall"
  const val INSTALL_VIA_SHIZUKU = "ide.build.run.installViaShizuku"
  const val PREF_JAVA_HOME = "ide.build.javaHome"

  const val ENABLE_BUILD_OUTPUT = "ide.build.enableBuildOutput"
  const val DEPENDENCIES_UPDATER = "ide.build.enableDependenciesUpdater"
  const val KT_INDEXING_NOTIFICATION = "ide.build.enableKotlinIndexingNotification"

  var isKtIndexingNotificationEnabled: Boolean
    get() = prefManager.getBoolean(KT_INDEXING_NOTIFICATION, true)
    set(value) = prefManager.putBoolean(KT_INDEXING_NOTIFICATION, value)

  var isDependenciesUpdaterEnabled: Boolean
    get() = prefManager.getBoolean(DEPENDENCIES_UPDATER, true)
    set(value) = prefManager.putBoolean(DEPENDENCIES_UPDATER, value)

  var isBuildOutputEnabled: Boolean
    get() = prefManager.getBoolean(ENABLE_BUILD_OUTPUT, true)
    set(value) = prefManager.putBoolean(ENABLE_BUILD_OUTPUT, value)

  /** Switch for Gradle `--debug` option. */
  var isDebugEnabled: Boolean
    get() = prefManager.getBoolean(DEBUG)
    set(enabled) {
      prefManager.putBoolean(DEBUG, enabled)
    }

  /** Switch for Gradle `--scan` option. */
  var isScanEnabled: Boolean
    get() = prefManager.getBoolean(SCAN)
    set(enabled) {
      prefManager.putBoolean(SCAN, enabled)
    }

  /** Switch for Gradle `--warning-mode all` option. */
  var isWarningModeAllEnabled: Boolean
    get() = prefManager.getBoolean(WARNING_MODE)
    set(enabled) {
      prefManager.putBoolean(WARNING_MODE, enabled)
    }

  /** Switch for Gradle `--build-cache` option. */
  var isBuildCacheEnabled: Boolean
    get() = prefManager.getBoolean(BUILD_CACHE)
    set(enabled) {
      prefManager.putBoolean(BUILD_CACHE, enabled)
    }

  /** Switch for Gradle `--offline` option. */
  var isOfflineEnabled: Boolean
    get() = prefManager.getBoolean(OFFLINE_MODE)
    set(enabled) {
      prefManager.putBoolean(OFFLINE_MODE, enabled)
    }

  /** Switch for Gradle `--stacktrace` option. */
  var isStacktraceEnabled: Boolean
    get() = prefManager.getBoolean(STACKTRACE)
    set(value) {
      prefManager.putBoolean(STACKTRACE, value)
    }

  /** Switch for Gradle `--info` option. */
  var isInfoEnabled: Boolean
    get() = prefManager.getBoolean(INFO, GeneralPreferences.isFirstBuild)
    set(enabled) {
      prefManager.putBoolean(INFO, enabled)
    }

  /** Custom Gradle installation directory path. */
  var gradleInstallationDir: String
    get() = prefManager.getString(CUSTOM_GRADLE_INSTALLATION, "")
    set(value) {
      prefManager.putString(CUSTOM_GRADLE_INSTALLATION, value)
    }

  /**
   * 新建项目时优先使用的 Gradle 下载镜像 id（取值见 `GradleMirror.ALL`）。
   *
   * <p>空串表示「未显式选择」→ 调用方按发行版取默认（CN 版国内镜像、国际版官方源）。
   * 存 id 而不是 URL：镜像地址将来可能调整，存 id 才能跟着变，
   * 且换地址不会让用户的旧偏好失效。
   */
  var gradleMirrorId: String
    get() = prefManager.getString(GRADLE_MIRROR_ID, "")
    set(value) {
      prefManager.putString(GRADLE_MIRROR_ID, value)
    }

  /**
   * 新建项目时默认使用的 **build-tools 版本**（如 `"36.0.0"`）。
   *
   * <p>空串 = **不指定**，生成的 `build.gradle` 里不出现 `buildToolsVersion`，
   * 由 AGP 挑它要求的最低版本。这是模板长期行为，也是 Google 的推荐做法。
   *
   * <p><b>为什么是设备级全局设置而不是每个项目选一次</b>：build-tools 装在
   * `$ANDROID_HOME/build-tools/`，同一设备所有项目共享同一套工具链；让每个项目
   * 各选一次既重复又容易选出互相冲突的版本。用户在 SDK 管理器设一次即可。
   *
   * <p>需要它的情况：AGP 自挑的那个恰好有问题（缺组件、架构不符）时钉一个能用的；
   * 或设备只有低版本可用（AGP 9 要求 ≥36.0.0，只装了 35.x 时构建会失败）。
   */
  var defaultBuildToolsVersion: String
    get() = prefManager.getString(DEFAULT_BUILD_TOOLS_VERSION, "")
    set(value) {
      prefManager.putString(DEFAULT_BUILD_TOOLS_VERSION, value)
    }

  /** Whether the app should be launched automatically after installation (after build). */
  var launchAppAfterInstall: Boolean
    get() = prefManager.getBoolean(LAUNCH_APP_AFTER_INSTALL, true)
    set(value) {
      prefManager.putBoolean(LAUNCH_APP_AFTER_INSTALL, value)
    }

  var installViaShizuku: Boolean
    get() = prefManager.getBoolean(INSTALL_VIA_SHIZUKU, false)
    set(value) {
      prefManager.putBoolean(INSTALL_VIA_SHIZUKU, value)
    }

  /** The selected Java installation. */
  var javaHome: String
    get() = prefManager.getString(PREF_JAVA_HOME, "")
    set(value) {
      prefManager.putString(PREF_JAVA_HOME, value)
    }
}
