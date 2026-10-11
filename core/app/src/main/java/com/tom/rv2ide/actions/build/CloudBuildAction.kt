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

package com.tom.rv2ide.actions.build

import android.content.Context
import androidx.core.content.ContextCompat
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.EditorActivityAction
import com.tom.rv2ide.actions.markInvisible
import com.tom.rv2ide.cloudbuild.CloudBuildService
import com.tom.rv2ide.fragments.CloudBuildDialogFragment
import com.tom.rv2ide.R
import com.tom.rv2ide.R.string

/**
 * 编辑器工具栏的「云打包」入口：打开 [CloudBuildDialogFragment] 底部表单。
 *
 * <p><b>为什么挂在工具栏而不是侧栏</b>：云打包与「构建」是同一类动作，工具栏里已有
 * [RunTasksAction]（Gradle 任务）、[ProjectSyncAction]（项目同步）、
 * [QuickRunWithCancellationAction]（构建并安装）——用户找「打包」时的第一落点是这里。
 * 侧栏承担的是「与编辑器并列的常驻工具面板」（文件树、Git 客户端），云打包是一次性动作，
 * 放侧栏反而与旁边的面板语义不符。
 *
 * <p><b>为什么继承 [EditorActivityAction] 而不是 [com.tom.rv2ide.actions.BaseBuildAction]</b>：
 * 后者的 [com.tom.rv2ide.actions.BaseBuildAction.prepare] 会把 enabled 绑在
 * `BuildService.isBuildInProgress` 上——本地 Gradle 构建进行中就禁用。但云打包与本地构建
 * **互不冲突**（它在 GitHub 的机器上跑），跟着禁用会凭空挡住一条可用路径。
 *
 * <p><b>可见性</b>：只在项目已打开时显示。云打包的前提是有一个项目目录可识别仓库，
 * 无项目时显示这个入口只会导向「请先打开项目」的报错。
 */
class CloudBuildAction(context: Context, override val order: Int) : EditorActivityAction() {

  override val id: String = "ide.editor.cloudBuild"

  /** 打开对话框本身很快；仓库识别在对话框内异步做。 */
  override var requiresUIThread: Boolean = true

  init {
    label = context.getString(string.cloud_build_action_label)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_github)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)
    data.getActivity()
        ?: run {
          markInvisible()
          return
        }

    // 刻意**不**按「是否有项目」判断可见性。
    //
    // 原因：`fillMenu` 对不可见的 action 直接跳过（不加入菜单），而后续
    // `prepareOptionsMenu` 只能通过 `menu.findItem(itemId)` 找到已加入的项——
    // 一旦某次菜单构建发生在项目初始化完成之前（`projectDirOrNull == null`），
    // 这个入口就永久缺失，直到下一次 `invalidateOptionsMenu` 重建菜单才可能恢复，
    // 而那次重建未必会到来。实测表现是「工具栏与溢出菜单里都找不到云打包」。
    //
    // 无项目时点开对话框会显示「当前没有打开的项目」的明确提示（见
    // CloudBuildDialogFragment.startDetection），比隐藏入口更诚实。
    visible = true
    enabled = true
  }

  override suspend fun execAction(data: ActionData): Any {
    return CloudBuildDialogFragment()
  }

  override fun postExec(data: ActionData, result: Any) {
    if (result !is CloudBuildDialogFragment) {
      return
    }
    val activity = data.requireActivity()
    // 已有一个实例时先关掉旧的，避免叠加两个表单（用户连点两次的情况）。
    activity.supportFragmentManager.findFragmentByTag(CloudBuildDialogFragment.TAG)?.let {
      (it as? CloudBuildDialogFragment)?.dismiss()
    }
    result.show(activity.supportFragmentManager, CloudBuildDialogFragment.TAG)
  }
}
