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
 * 「思考完毕后自动折叠」偏好。
 *
 * <p><b>行为</b>：开启时（默认）思考块在流式输出期间**保持展开**——用户能看到模型
 * 正在想什么，这是等待期间最有价值的信息；思考结束（收到工具调用或正文开始）时
 * **自动折叠**，把垂直空间让给正式回答。关闭时思考块始终展开，直到用户手动收起。
 *
 * <p><b>为什么默认开启</b>：一段推理动辄几百字，全部展开会把回答挤出屏幕；
 * 而「正在思考」这个瞬间是用户最需要看到内容的时刻。展开→折叠正好两全。
 *
 * <p>键与开关两侧都从这里取，不各自持有字符串——散落两处会在改名时漏掉一处，
 * 而漏掉的那侧不报错，表现只是「开关点了没反应」（与 [AssistantComposeRender] 同一纪律）。
 */
internal object AssistantThinkingCollapsePref {

  private const val PREFS_NAME = "ai_agent_tools"

  /** 偏好键。 */
  const val KEY_ENABLED = "assistant_thinking_auto_collapse"

  /** 默认 **true**：见类文档的「为什么默认开启」。 */
  fun isEnabled(context: Context): Boolean =
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

  fun setEnabled(context: Context, enabled: Boolean) {
    context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_ENABLED, enabled)
        .apply()
  }
}
