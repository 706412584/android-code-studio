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

import android.content.Context
import android.content.Intent
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.orange.update.HotUpdateHelper
import com.tom.rv2ide.activities.AboutActivity
import com.tom.rv2ide.activities.TomIDEUpdater
import com.tom.rv2ide.app.IDEApplication
import com.tom.rv2ide.resources.R
import kotlinx.parcelize.Parcelize

private const val KEY_CHANGELOG = "idepref_changelog"
private const val KEY_ABOUT = "idepref_about"
private const val KEY_CHECK_UPDATE = "idepref_check_update"
private const val KEY_PATCH_STATUS = "idepref_patch_status"
private const val KEY_PATCH_REVERT = "idepref_patch_revert"

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

/**
 * 热更新补丁状态（只读展示）。
 *
 * <p>此前应用了补丁后没有任何地方能看到「当前跑的是补丁版本」——用户遇到补丁引起的
 * 问题（如资源 ID 错位导致界面文字错乱）时，既不知道原因，也没有恢复手段。
 */
@Parcelize
class PatchStatusPreference(
    override val key: String = KEY_PATCH_STATUS,
    override val title: Int = R.string.pref_patch_status,
) : SimplePreference() {

  // 动态 summary（补丁版本号），必须在 onCreatePreference 里设：
  // 静态 summary 会被 BasePreference.onCreateView 覆盖掉。
  override val summary: Int? = null

  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).apply { summary = describe(context) }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    // 刷新一次：用户可能刚打完补丁回来看状态。
    preference.summary = describe(preference.context)
    return true
  }

  private fun describe(context: Context): String {
    return try {
      val helper = HotUpdateHelper.getInstance(context)
      if (!helper.hasAppliedPatch()) {
        context.getString(R.string.idepref_patch_none)
      } else {
        val info = helper.appliedPatchInfo
        val version = info?.patchVersion?.takeIf { it.isNotBlank() } ?: "?"
        context.getString(R.string.idepref_patch_applied, version)
      }
    } catch (e: Throwable) {
      // SDK 未初始化或补丁状态损坏时不能把设置页搞崩。
      context.getString(R.string.idepref_patch_none)
    }
  }
}

/**
 * 回退已应用的热更新补丁。
 *
 * <p>补丁是 DEX 注入 + 资源替换，出问题时用户此前**没有任何自救手段**——只能卸载重装
 * （丢掉设备上的项目数据）。这个入口调 SDK 的 `clearPatch()` 清掉补丁并重启。
 */
@Parcelize
class PatchRevertPreference(
    override val key: String = KEY_PATCH_REVERT,
    override val title: Int = R.string.pref_patch_revert,
    override val summary: Int = R.string.idepref_patch_revert_summary,
) : SimplePreference() {

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val helper =
        try {
          HotUpdateHelper.getInstance(context)
        } catch (e: Throwable) {
          null
        }
    if (helper == null || !helper.hasAppliedPatch()) {
      MaterialAlertDialogBuilder(context)
          .setTitle(R.string.idepref_patch_revert_title)
          .setMessage(R.string.idepref_patch_none)
          .setPositiveButton(android.R.string.ok, null)
          .show()
      return true
    }

    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.idepref_patch_revert_title)
        .setMessage(R.string.idepref_patch_revert_message)
        .setPositiveButton(android.R.string.ok) { _, _ ->
          val cleared =
              try {
                helper.clearPatch()
              } catch (e: Throwable) {
                false
              }
          if (cleared) {
            android.widget.Toast.makeText(
                    context, R.string.idepref_patch_revert_ok, android.widget.Toast.LENGTH_SHORT)
                .show()
            restartApp(context)
          } else {
            android.widget.Toast.makeText(
                    context, R.string.idepref_patch_revert_failed, android.widget.Toast.LENGTH_LONG)
                .show()
          }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    return true
  }

  /**
   * 重启应用。
   *
   * <p>用 `killProcess` 而不是 `System.exit`：补丁是 DEX 注入，只清状态不重启的话
   * 当前进程里已注入的类仍在内存中，界面不会恢复。
   */
  private fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    if (intent != null) {
      intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    }
    android.os.Process.killProcess(android.os.Process.myPid())
  }
}
