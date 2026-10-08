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

package com.tom.rv2ide.fragments.sidebar

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agent.FloatingAssistantView
import com.tom.rv2ide.artificial.agent.host.ActivityHost
import com.tom.rv2ide.projects.IProjectManager
import java.io.File

/**
 * 侧栏的 AI 助手页。
 *
 * <p><b>形态</b>：[FloatingAssistantView.Mode.EMBEDDED] —— 占满侧栏标签页，
 * 不挂 FAB，保留进入真全屏的入口。
 *
 * <p><b>为什么不另写一套消息列表</b>：面板的消息流、工具卡片、会话抽屉、diff 回滚、
 * 图片灯箱是同一套逻辑（[FloatingAssistantView]），复制一份必然在后续改动里分叉。
 * 本类只负责「挂到哪、什么形态、工作区取哪」三件事。
 *
 * <p><b>orchestrator 归属</b>：与其他宿主（真全屏、应用外悬浮）共用
 * [com.tom.rv2ide.artificial.agent.AssistantOrchestratorProvider] 的进程级单例。
 * 各建一个会各持一个活动会话 id，可能同时跑两个 agent 循环、并发写两份会话文件。
 * 经 [FloatingAssistantView] 的 `sharedOrchestrator` 参数注入。
 *
 * <p><b>生命周期</b>：作用域用 `viewLifecycleOwner.lifecycleScope`，不能用
 * `BaseFragment.viewLifecycleScope`（那是 Dispatchers.Default 的普通作用域，
 * 不随视图销毁取消，会在视图 detach 后继续写控件）。
 */
class AssistantSidebarFragment : Fragment() {

  private var assistant: FloatingAssistantView? = null

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?,
  ): View = inflater.inflate(R.layout.fragment_assistant_page, container, false)

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    val host = ActivityHost(requireContext(), viewLifecycleOwner.lifecycleScope, view as ViewGroup)
    val assistantView =
        FloatingAssistantView(
            host,
            FloatingAssistantView.Mode.EMBEDDED,
            com.tom.rv2ide.artificial.agent.AssistantOrchestratorProvider.get(),
        )
    assistantView.attach()
    assistantView.setWorkspace(currentWorkspace())
    // 侧栏页没有 FAB，视图创建后直接展开。
    // open() 还会回放本工作区上次的会话、刷新模型/权限标签与上下文圆环。
    assistantView.open()
    assistant = assistantView
  }

  override fun onDestroyView() {
    // 先取消进行中的运行，避免视图销毁后回调仍写控件。
    assistant?.dispose()
    assistant = null
    super.onDestroyView()
  }

  /**
   * 当前打开的项目目录；没有项目时返回 null。
   *
   * <p>取 [IProjectManager] 而不是「最近打开的项目」：侧栏只在项目已打开的编辑界面里存在，
   * 此时当前项目是**确定**的，不需要去猜。这也与 [FileTreeFragment] 同源，
   * 保证侧栏里各标签页看到的是同一个项目。
   *
   * <p>无项目时传 null 而不是静默失败：助手会提示「请先打开项目」。
   */
  private fun currentWorkspace(): File? {
    val path = runCatching { IProjectManager.getInstance().projectDirPath }.getOrNull()
    if (path.isNullOrEmpty()) {
      return null
    }
    val dir = File(path)
    return if (dir.exists() && dir.isDirectory) dir else null
  }
}
