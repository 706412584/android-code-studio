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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.compose

import android.content.Context

/**
 * 「工具运行状态条」的偏好存取（[com.tom.rv2ide.artificial.agent.compose.components.independent.FloatingToolStatusBar]）。
 *
 * <p><b>为什么默认关闭</b>：它是常驻在输入区上方的一条浮层（缩略图 + 状态条），
 * 给的是「此刻工具在跑什么」的额外可视化。但 ACS 已有底部状态条（assistantWorking）
 * 承担一句话播报，缩略图对多数用户是重复信息且持续占据垂直空间。默认关、需要时开。
 *
 * <p>键与开关两侧都从这里取，不各自持有字符串——散落两处会在改名时漏掉一处，
 * 而漏掉的那侧不报错，表现只是「开关点了没反应」（与 [AssistantComposeRender] 同一纪律）。
 */
internal object AssistantToolStatusBarPref {

  private const val PREFS_NAME = "ai_agent_tools"

  /**
   * 偏好键。
   *
   * <p>[com.tom.rv2ide.artificial.agent.FloatingAssistantView] 监听偏好变更以即时显隐，
   * 需要按键名过滤——同一份偏好文件里还有十来个别的键，不筛会误触发。
   */
  const val KEY_ENABLED = "assistant_tool_status_bar"

  /** 默认 **false**（关闭）：见类文档的「为什么默认关闭」。 */
  fun isEnabled(context: Context): Boolean =
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

  fun setEnabled(context: Context, enabled: Boolean) {
    context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_ENABLED, enabled)
        .apply()
  }
}
