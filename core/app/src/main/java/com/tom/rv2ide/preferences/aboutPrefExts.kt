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
import android.widget.Button
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.orange.update.HotUpdateHelper
import com.tom.rv2ide.activities.AboutActivity
import com.tom.rv2ide.activities.TomIDEUpdater
import com.tom.rv2ide.resources.R
import kotlinx.parcelize.Parcelize

private const val KEY_CHANGELOG = "idepref_changelog"
private const val KEY_ABOUT = "idepref_about"
private const val KEY_CHECK_UPDATE = "idepref_check_update"
private const val KEY_PATCH_STATUS = "idepref_patch_status"

val changelog =
    SimpleClickablePreference(
        key = KEY_CHANGELOG,
        title = R.string.pref_changelog,
        summary = R.string.idepref_changelog_summary,
    ) {
      // 应用内弹窗展示本版更新内容（不跳转浏览器——国内设备常挂代理，一跳就白屏）。
      TomIDEUpdater(it.context).showChangelogDialog()
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
 * 热更新补丁：状态 + 回退，合并为一行。
 *
 * <p>原先拆成两行（只读的补丁状态 + 单独的回退入口），占位多且回退那行没有独立信息。
 * 现在状态作为本行 summary（「已应用补丁 rNN」/「未应用补丁」），回退收成右侧一个按钮
 * （[pref_patch_widget]），未装补丁时按钮禁用。
 *
 * <p>保留回退能力的理由不变：补丁是 DEX 注入 + 资源替换，出问题时用户此前**没有任何
 * 自救手段**——只能卸载重装（丢掉设备上的项目数据）。
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
    // 用自定义 Preference 子类：只有它能在 onBindViewHolder 里拿到 widget 视图，
    // 把右侧「回退」按钮的状态与点击挂上（IPreference 接口没有绑定钩子）。
    return object : Preference(context) {
      override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val button = holder.itemView.findViewById<Button>(R.id.patch_revert_button) ?: return
        val ctx = holder.itemView.context
        val applied = hotUpdateHelper(ctx)?.hasAppliedPatch() == true
        button.isEnabled = applied
        // 未装补丁时禁用并压暗：点它没有意义，但仍要占位（避免整行左右跳动）。
        button.alpha = if (applied) 1f else 0.4f
        button.setOnClickListener { confirmRevert(ctx) }
      }
    }.apply {
      widgetLayoutResource = R.layout.pref_patch_widget
      summary = describe(context)
    }
  }

  /**
   * 整行点击只刷新状态（回退动作只由右侧按钮触发）。
   *
   * <p>注意必须覆写这里而不是在 [onCreatePreference] 里 setOnPreferenceClickListener：
   * [BasePreference.onCreateView] 在本方法返回后会**再设一次**点击监听来调用本方法，
   * 在里面设的监听会被覆盖。
   */
  override fun onPreferenceClick(preference: Preference): Boolean {
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

  private fun hotUpdateHelper(context: Context): HotUpdateHelper? {
    return try {
      HotUpdateHelper.getInstance(context)
    } catch (e: Throwable) {
      null
    }
  }

  private fun confirmRevert(context: Context) {
    val helper = hotUpdateHelper(context)
    if (helper == null || !helper.hasAppliedPatch()) {
      MaterialAlertDialogBuilder(context)
          .setTitle(R.string.idepref_patch_revert_title)
          .setMessage(R.string.idepref_patch_none)
          .setPositiveButton(android.R.string.ok, null)
          .show()
      return
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
