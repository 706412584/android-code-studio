/*
 * This file is part of AndroidCodeStudio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.tom.rv2ide.activities.PreferencesActivity
import com.tom.rv2ide.preferences.AIAgentPreferencesScreen

/**
 * 打开 AI 助手设置屏。
 *
 * <p>助手面板与模型选择器都要跳到同一处，把跳转逻辑放这里而不是各写一份——
 * 两个入口的落点必须一致，否则用户会看到两套不同的设置。
 */
object AssistantSettings {

  fun open(context: Context) {
    // 传「AI 设置屏的 children」而不是屏幕本身。
    //
    // IDEPreferencesFragment 在顶层只接受两种类型：IPreferenceScreen（渲染成可点击入口）
    // 与 IPreferenceGroup（渲染成 PreferenceCategory）。直接传 AIAgentPreferencesScreen
    // 会在展开其 children 时抛 ClassCastException——那层 children 是普通 Preference，
    // 而 addChildren 对非 Screen/Group 的分支仍按 Group 处理。
    // 把 children 展开到顶层则每一层都符合上述两种类型。
    //
    // 直达 AI 设置屏而非设置首页：AI 设置挂在「配置 → AI 助手」下，逐层展开要三次点击，
    // 而用户点「设置」的意图就是改 AI 配置。
    val screen = AIAgentPreferencesScreen()
    val intent = Intent(context, PreferencesActivity::class.java)
    intent.putParcelableArrayListExtra(
        PreferencesActivity.EXTRA_DIRECT_CHILDREN,
        ArrayList(screen.children),
    )
    intent.putExtra(PreferencesActivity.EXTRA_DIRECT_TITLE, context.getString(screen.title))
    // 非 Activity 上下文（应用外悬浮的 Service 宿主）启动 Activity 必须带 NEW_TASK，
    // 否则抛 "Calling startActivity() from outside of an Activity context requires
    // FLAG_ACTIVITY_NEW_TASK"。Activity 上下文不能带该 flag（会另起任务栈），故按类型分支。
    if (context !is Activity) {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
  }
}
