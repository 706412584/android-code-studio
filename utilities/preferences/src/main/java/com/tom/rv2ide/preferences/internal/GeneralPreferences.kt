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

import androidx.appcompat.app.AppCompatDelegate
import com.tom.rv2ide.resources.localization.LocaleProvider

/** @author Akash Yadav */
@Suppress("MemberVisibilityCanBePrivate")
object GeneralPreferences {

  const val IS_FIRST_PROJECT_BUILD = "project_isFirstBuild"
  const val UI_MODE = "idepref_general_uiMode"
  const val SELECTED_THEME = "idpref_general_theme"
  const val SELECTED_LOCALE = "idpref_general_locale"
  const val OPEN_PROJECTS = "idepref_general_autoOpenProjects"
  const val CONFIRM_PROJECT_OPEN = "idepref_general_confirmProjectOpen"
  const val SNOWFALL_OVERLAY = "idepref_general_snowfall"
  const val IDE_LOGS_ENABLED = "idepref_ide_logs_enabled"
  const val TERMINAL_USE_SYSTEM_SHELL = "idepref_general_terminalShell"
  const val LAST_OPENED_PROJECT = "ide_last_project"

  /** 最近打开的项目列表；换行分隔，避免引入 JSON 依赖。 */
  const val LAST_OPENED_PROJECTS = "ide_last_projects"

  const val NO_OPENED_PROJECT = "<NO_OPENED_PROJECT>"

  /** 最近项目列表的上限。 */
  const val MAX_RECENT_PROJECTS = 10

  var uiMode: Int
    get() = prefManager.getInt(UI_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    set(value) {
      prefManager.putInt(UI_MODE, value)
    }

  var selectedTheme: String?
    get() = prefManager.getString(SELECTED_THEME, null)
    set(value) {
      prefManager.putString(SELECTED_THEME, value)
    }

  var selectedLocale: String?
    get() =
        prefManager.getString(SELECTED_LOCALE, null).let { locale ->

          // if the locale is set to a locale key that is not supported,
          // fall back to 'System default'
          if (LocaleProvider.getLocale(locale) == null) {
            null
          } else {
            locale
          }
        }
    set(value) {
      prefManager.putString(SELECTED_LOCALE, value)
    }

  var isFirstBuild: Boolean
    get() = prefManager.getBoolean(IS_FIRST_PROJECT_BUILD, true)
    set(value) {
      prefManager.putBoolean(IS_FIRST_PROJECT_BUILD, value)
    }

  var autoOpenProjects: Boolean
    get() = prefManager.getBoolean(OPEN_PROJECTS, true)
    set(value) {
      prefManager.putBoolean(OPEN_PROJECTS, value)
    }

  var confirmProjectOpen: Boolean
    get() = prefManager.getBoolean(CONFIRM_PROJECT_OPEN, false)
    set(value) {
      prefManager.putBoolean(CONFIRM_PROJECT_OPEN, value)
    }

  var snowfallOverlay: Boolean
    get() = prefManager.getBoolean(SNOWFALL_OVERLAY, true)
    set(value) {
      prefManager.putBoolean(SNOWFALL_OVERLAY, value)
    }

  var ideLogsEnabled: Boolean
    get() = prefManager.getBoolean(IDE_LOGS_ENABLED, false)
    set(value) {
      prefManager.putBoolean(IDE_LOGS_ENABLED, value)
    }

  var useSystemShell: Boolean
    get() = prefManager.getBoolean(TERMINAL_USE_SYSTEM_SHELL, false)
    set(value) {
      prefManager.putBoolean(TERMINAL_USE_SYSTEM_SHELL, value)
    }

  var lastOpenedProject: String
    get() = prefManager.getString(LAST_OPENED_PROJECT, NO_OPENED_PROJECT)
    set(value) {
      prefManager.putString(LAST_OPENED_PROJECT, value)
    }

  /**
   * 最近打开的项目（新到旧，去重）。
   *
   * <p>旧字段 [lastOpenedProject] 是单值，多标签页后只能记住最后一个；本字段是它的
   * 列表版。两者同时维护：旧字段保持「启动时自动打开的项目」，旧版本降级/回滚仍能工作。
   */
  var lastOpenedProjects: List<String>
    get() =
        prefManager
            .getString(LAST_OPENED_PROJECTS, "")
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != NO_OPENED_PROJECT }
            .toList()
    set(value) {
      prefManager.putString(
          LAST_OPENED_PROJECTS,
          value.filter { it.isNotEmpty() && it != NO_OPENED_PROJECT }
              .distinct()
              .take(MAX_RECENT_PROJECTS)
              .joinToString("\n"),
      )
    }

  /** 记录一个刚成功打开的项目：置顶、去重、裁剪，并同步单值字段。 */
  fun recordOpenedProject(path: String) {
    if (path.isEmpty() || path == NO_OPENED_PROJECT) {
      return
    }
    val current = lastOpenedProjects
    lastOpenedProjects = listOf(path) + current.filter { it != path }
    lastOpenedProject = path
  }

  /**
   * 从最近列表移除一个项目（关闭项目时）。
   *
   * <p>不动 [lastOpenedProject]：显式关闭项目后启动不应自动打开别的项目，
   * 调用方仍按原逻辑把它重置为 [NO_OPENED_PROJECT]。
   */
  fun forgetOpenedProject(path: String) {
    val current = lastOpenedProjects
    val remaining = current.filter { it != path }
    if (remaining.size != current.size) {
      lastOpenedProjects = remaining
    }
  }
}
