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

package com.assistant.ui

import android.content.Context

/**
 * 助手偏好的**统一读写入口**（公开给宿主用）。
 *
 * <p><b>为什么集中一处</b>：这些键散在 `AssistantUiStyleStore`（字号/卡片缩放/正文颜色）、
 * `AssistantThinkingCollapsePref`、`AssistantToolStatusBarPref`、`AssistantThemePrefs`
 * 四处，且后三个是 `internal`（宿主访问不到）。设置页要能读写它们，因此在这里开一个
 * 公开门面——**只转发，不另存**：键名与存储位置仍由各自的类持有，
 * 这样「改名时漏改一处」的风险不会因为多一层而变大。
 *
 * <p>存储统一在 `ai_agent_tools` 偏好文件里（与 ACS 一致）。
 */
object AssistantPrefs {

  private const val PREFS_NAME = "ai_agent_tools"

  // ---- 渲染行为 ----

  /** 思考块是否「流式期间展开、结束后自动折叠」。默认开。 */
  fun thinkingAutoCollapse(context: Context): Boolean =
      AssistantThinkingCollapsePref.isEnabled(context)

  fun setThinkingAutoCollapse(context: Context, enabled: Boolean) =
      AssistantThinkingCollapsePref.setEnabled(context, enabled)

  /** 工具运行状态条是否显示。默认关。 */
  fun toolStatusBar(context: Context): Boolean = AssistantToolStatusBarPref.isEnabled(context)

  fun setToolStatusBar(context: Context, enabled: Boolean) =
      AssistantToolStatusBarPref.setEnabled(context, enabled)

  // ---- 外观 ----

  /** 卡片缩放系数（1.0 = 默认）。 */
  fun cardScale(context: Context): Float = AssistantUiStyleStore(context).cardScale

  fun setCardScale(context: Context, scale: Float) {
    AssistantUiStyleStore(context).cardScale = scale
  }

  /** 正文颜色（ARGB）；0 表示跟随主题。 */
  fun textColor(context: Context): Int = AssistantUiStyleStore(context).textColor

  fun setTextColor(context: Context, argb: Int) {
    AssistantUiStyleStore(context).textColor = argb
  }

  /** 该颜色是否为用户自定义（而非「跟随主题」）。 */
  fun isTextColorCustomized(context: Context): Boolean =
      AssistantUiStyleStore(context).isTextColorCustomized

  // ---- 对话模式 ----

  /**
   * 对话模式 id（chat / plan / agent / control）。
   *
   * <p>与 ACS 的 `PrefsChatModeStore` 同一存储（`ai_agent_tools` 的 `chat_mode` 键），
   * 因此两边切换会互相看见——本 App 只有一个宿主，实际不存在冲突，
   * 但保持键名一致能让用户迁移偏好时不必重配。
   */
  fun chatMode(context: Context): String =
      context
          .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
          .getString(KEY_CHAT_MODE, DEFAULT_CHAT_MODE) ?: DEFAULT_CHAT_MODE

  fun setChatMode(context: Context, modeId: String) {
    context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_CHAT_MODE, modeId)
        .apply()
  }

  private const val KEY_CHAT_MODE = "chat_mode"

  /** 默认「执行」：与内核 `ChatMode.DEFAULT` 一致。 */
  private const val DEFAULT_CHAT_MODE = "agent"

  // ---- 提示词模板 ----

  /** 读取用户自定义的模板正文；空串表示未自定义（回退内核默认）。 */
  fun promptTemplate(context: Context, templateId: String): String =
      context
          .getSharedPreferences(PREFS_NAME_TEMPLATES, Context.MODE_PRIVATE)
          .getString(KEY_PREFIX + templateId, "")
          ?: ""

  fun setPromptTemplate(context: Context, templateId: String, text: String) {
    context
        .getSharedPreferences(PREFS_NAME_TEMPLATES, Context.MODE_PRIVATE)
        .edit()
        .apply {
          if (text.isBlank()) remove(KEY_PREFIX + templateId) else putString(KEY_PREFIX + templateId, text)
        }
        .apply()
  }

  /** 某个模板是否已被自定义。 */
  fun isPromptTemplateCustomized(context: Context, templateId: String): Boolean =
      promptTemplate(context, templateId).isNotBlank()

  private const val PREFS_NAME_TEMPLATES = "ai_prompt_templates"
  private const val KEY_PREFIX = "tpl_"
}
