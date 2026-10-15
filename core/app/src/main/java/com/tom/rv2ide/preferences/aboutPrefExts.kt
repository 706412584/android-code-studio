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

package com.tom.rv2ide.preferences

import android.content.Intent
import com.tom.rv2ide.activities.AboutActivity
import com.tom.rv2ide.activities.TomIDEUpdater
import com.tom.rv2ide.app.IDEApplication
import com.tom.rv2ide.resources.R

private const val KEY_CHANGELOG = "idepref_changelog"
private const val KEY_ABOUT = "idepref_about"
private const val KEY_CHECK_UPDATE = "idepref_check_update"

val changelog =
    SimpleClickablePreference(
        key = KEY_CHANGELOG,
        title = R.string.pref_changelog,
        summary = R.string.idepref_changelog_summary,
    ) {
      IDEApplication.instance.showChangelog()
      true
    }

/**
 * 手动「检查更新」。
 *
 * <p>此前检查更新只在启动时静默执行（`MainActivity.onCreate`），设置里没有入口——
 * 用户想主动确认是否有新版时无从下手（用户报的「没有检查更新选项」）。
 */
val checkUpdate =
    SimpleClickablePreference(
        key = KEY_CHECK_UPDATE,
        title = R.string.pref_check_update,
        summary = R.string.idepref_check_update_summary,
    ) { preference ->
      TomIDEUpdater(preference.context).checkForUpdatesManually()
      true
    }
val about =
    SimpleClickablePreference(
        key = KEY_ABOUT,
        title = R.string.idepref_about_title,
        summary = R.string.idepref_about_summary,
    ) {
      it.context.startActivity(Intent(it.context, AboutActivity::class.java))
      true
    }
