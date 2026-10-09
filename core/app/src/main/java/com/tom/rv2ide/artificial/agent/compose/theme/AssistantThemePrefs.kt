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

package com.tom.rv2ide.artificial.agent.compose.theme

import android.content.Context

/**
 * 消息配色方案的持久化。
 *
 * <p>与 [com.tom.rv2ide.artificial.agent.AssistantComposeRender] 同一份偏好文件
 * （"ai_agent_tools"）：AI 渲染相关的开关/字号/卡片缩放都在那里，配色方案没有理由例外。
 *
 * <p><b>为什么不学 Aharou 用 DataStore</b>：ACS 全仓库的偏好只有 SharedPreferences 一种
 * （已知陷阱里明确「会话日志刻意不引入 Room」，偏好同理不引入 DataStore）。为一条
 * 字符串键引入一套新存储是负资产。
 */
object AssistantThemePrefs {

  private const val KEY_PRESET_ID = "assistant_theme_preset_id"

  /** 当前选中的配色方案 id；未设置时为 null（由调用方回退 [AppThemePreset.DEFAULT]）。 */
  fun presetId(context: Context): String? {
    val id =
        context
            .applicationContext
            .getSharedPreferences("ai_agent_tools", Context.MODE_PRIVATE)
            .getString(KEY_PRESET_ID, null)
    // 存量校验：偏好里是被删掉的旧 id 时按未设置处理， findById 也会兜底，双保险。
    return id?.takeIf { any -> AppThemePreset.ALL_PRESETS.any { it.id == any } }
  }

  fun setPresetId(context: Context, id: String) {
    context
        .applicationContext
        .getSharedPreferences("ai_agent_tools", Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_PRESET_ID, id)
        .apply()
  }
}
