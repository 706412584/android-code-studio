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

package com.tom.rv2ide.actions.sidebar

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.requireContext
import com.tom.rv2ide.fragments.CloudBuildDialogFragment
import com.tom.rv2ide.R
import kotlin.reflect.KClass

/**
 * 侧栏的云编译入口。
 *
 * <p><b>为什么与工具栏的 [com.tom.rv2ide.actions.build.CloudBuildAction] 并存</b>：
 * 工具栏按钮是「正在构建时顺手点」的高频入口，而侧栏是常驻的导航栏——用户找
 * 「构建相关」功能时的第一落点就是那里（BuildVariants 旁）。两处指向同一个对话框，
 * 不是两份实现：本类与工具栏版本都只负责「显示 CloudBuildDialogFragment」，
 * 真正的流程全在对话框与服务层里。
 *
 * <p><b>为什么 fragmentClass 为 null</b>：侧栏对 `fragmentClass == null` 的项
 * 执行 [execAction] 而不是切页（见 `EditorSidebarActions` 的 onItemClick）——
 * 云编译是「弹一次表单」的动作而非一个常驻页面，与「关闭项目」同类。
 */
class CloudBuildSidebarAction(context: Context, override val order: Int) :
    AbstractSidebarAction() {

  override val id: String = "ide.editor.sidebar.cloudBuild"

  override val fragmentClass: KClass<out Fragment>? = null

  init {
    label = context.getString(R.string.cloud_build_action_label)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_github)
    iconRes = R.drawable.ic_github
  }

  override suspend fun execAction(data: ActionData): Any {
    // requiresUIThread = true（基类默认），此处可直接操作 FragmentManager。
    // 侧栏的 ActionData 携带的是 Activity 上下文（编辑器界面），requireContext 拿到它。
    val activity = data.requireContext() as? FragmentActivity ?: return false

    // 已有一个实例时先关掉旧的，避免叠加两个表单（与工具栏入口同一处理：
    // 两个入口都可能被连点）。
    activity.supportFragmentManager.findFragmentByTag(CloudBuildDialogFragment.TAG)?.let {
      (it as? CloudBuildDialogFragment)?.dismiss()
    }
    CloudBuildDialogFragment().show(activity.supportFragmentManager, CloudBuildDialogFragment.TAG)
    return true
  }
}
