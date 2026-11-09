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
import com.tom.rv2ide.R
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.requireContext
import com.tom.rv2ide.activities.editor.AssistantFullscreenActivity
import com.tom.rv2ide.fragments.sidebar.AssistantSidebarFragment
import kotlin.reflect.KClass

/**
 * 侧栏的 AI 助手入口：**点一下直接进全屏**。
 *
 * <p><b>为什么不再开侧栏页</b>：原先点击是切到侧栏里的助手 Fragment，用户还得再点一次
 * 面板上的全屏键才能全屏——两步操作，而侧栏窄、对话区更窄，实际用起来几乎总会立刻全屏。
 * 现在入口直达 [AssistantFullscreenActivity]，少一步。
 *
 * <p>实现沿用 [CloudBuildSidebarAction] 的既有模式：`fragmentClass = null` 时侧栏不切页、
 * 而是执行 [execAction]（见 `EditorSidebarActions` 的 onItemClick）。
 * **图标仍在侧栏**，只是点击行为从「切页」变成「开窗口」。
 *
 * <p>[AssistantSidebarFragment] 保留在代码里未删——侧栏形态（编辑器旁竖着聊）在宽屏下
 * 仍有价值，将来若要恢复只需把 `fragmentClass` 改回去。
 */
class AssistantSidebarAction(context: Context, override val order: Int) :
    AbstractSidebarAction() {

  companion object {
    const val ID = "ide.editor.sidebar.assistant"
  }

  override val id: String = ID

  /**
   * null = 侧栏不切页，改为执行 [execAction]（与「云编译」「关闭项目」同类）。
   *
   * 类型仍需 `KClass<out Fragment>?`：接口如此声明。
   */
  override val fragmentClass: KClass<out Fragment>? = null

  init {
    label = context.getString(R.string.sidebar_assistant_title)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_ai_agent)
    iconRes = R.drawable.ic_ai_agent
  }

  override suspend fun execAction(data: ActionData): Any {
    // requiresUIThread = true（基类默认），此处可直接 startActivity。
    // 侧栏的 ActionData 携带编辑器 Activity 上下文。
    val activity = data.requireContext() as? FragmentActivity ?: return false
    // FLAG_ACTIVITY_NEW_TASK 不必要（从 Activity 启动），但无害；
    // 不加它反而在某些 ROM 上因 task 归属问题被拒。
    activity.startActivity(android.content.Intent(activity, AssistantFullscreenActivity::class.java))
    return true
  }
}
