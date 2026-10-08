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
import com.tom.rv2ide.R
import com.tom.rv2ide.fragments.sidebar.AssistantSidebarFragment
import kotlin.reflect.KClass

/**
 * 侧栏的 AI 助手入口。
 *
 * <p><b>为什么回到侧栏</b>：助手此前是编辑界面上的悬浮窗（可拖拽 FAB + 贴边面板）。
 * 悬浮形态的问题是它与编辑器争空间、且入口是一个飘在代码上的图标；而 ACS 的侧栏本就承担
 * 「与编辑器并列的工具面板」（文件树、Git 客户端、构建变体…），助手属于同一类。
 * 移进侧栏后它与 Git 客户端形态一致，且不再遮挡代码。
 *
 * <p><b>与历史上那一版侧栏助手的区别</b>：早先侧栏里曾有一份助手页（旧路径：单次生成 +
 * `FILE_TO_MODIFY` 标记），后来因为「与悬浮助手界面重复」被移除（见
 * [com.tom.rv2ide.utils.EditorSidebarActions.registerActions] 的历史注释）。
 * 本次不同：悬浮形态**已被移除**，侧栏成为唯一入口，因此不存在两份界面并存的问题；
 * 且侧栏内跑的是新的 Compose 渲染路径，不是旧路径。
 */
class AssistantSidebarAction(context: Context, override val order: Int) :
    AbstractSidebarAction() {

  companion object {
    const val ID = "ide.editor.sidebar.assistant"
  }

  override val id: String = ID
  override val fragmentClass: KClass<out Fragment> = AssistantSidebarFragment::class

  init {
    label = context.getString(R.string.sidebar_assistant_title)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_ai_agent)
    iconRes = R.drawable.ic_ai_agent
  }
}
