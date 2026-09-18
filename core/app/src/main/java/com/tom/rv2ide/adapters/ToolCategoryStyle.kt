/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.adapters

import androidx.annotation.DrawableRes
import com.tom.rv2ide.R
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory

/**
 * 工具卡片的分类视觉：图标与强调色。
 *
 * <p><b>为什么需要分类着色</b>：一个长任务会产生几十张工具卡片。全是同一个灰盒子时，
 * 用户无法快速扫出「哪几步改了文件、哪几步只是读、哪几步在跑命令」——只能逐个展开看，
 * 这在几十步的任务里等于不可用。分类色提供一条视觉索引。
 *
 * @author AndroidCodeStudio
 */
internal object ToolCategoryStyle {

  /**
   * 分类 → 强调色。
   *
   * <p><b>为什么不用主题的主色系</b>：与代码高亮遇到的是同一个坑——ACS 的主题主色是
   * 低饱和暖灰/米色系（实测 `colorPrimaryContainer` 在深色主题下解析成 `#866743` 棕色），
   * 拿它做分类色根本分不出「读」和「写」。分类色的目的是让用户扫一眼就区分操作类型，
   * 这要求色相彼此远离，而不是与主题协调。
   *
   * <p>这里用的色板与 `AssistantCodeHighlighter` 的深色系一致，保证整个助手界面
   * 的语义色是同一套（蓝=读、绿=命令、橙=写、红=删除、紫=派生）。
   *
   * <p>语义分组：
   * - 读/搜索类用冷色（蓝）——无副作用，视觉上最弱
   * - 写/改类用暖色（橙）——有副作用，需要被注意到
   * - 删除用红——破坏性且不可逆
   * - 命令用绿——可控但影响面广
   * - 派生类（agent/todo）用紫——不是直接操作
   */
  fun accentColor(category: ToolDisplayCategory): Int =
      when (category) {
        ToolDisplayCategory.READ -> COLOR_READ
        ToolDisplayCategory.WRITE -> COLOR_WRITE
        ToolDisplayCategory.DELETE -> COLOR_DELETE
        ToolDisplayCategory.SHELL -> COLOR_SHELL
        ToolDisplayCategory.TODO -> COLOR_AGENT
        ToolDisplayCategory.AGENT,
        ToolDisplayCategory.AGENT_PIPELINE -> COLOR_AGENT
        ToolDisplayCategory.IMAGE_GENERATION -> COLOR_WRITE
        ToolDisplayCategory.PHONE_CONTROL -> COLOR_SHELL
        ToolDisplayCategory.GENERIC -> COLOR_GENERIC
      }

  /** 分类 → 图标。 */
  @DrawableRes
  fun iconRes(category: ToolDisplayCategory): Int =
      when (category) {
        ToolDisplayCategory.READ -> R.drawable.ic_tool_read
        ToolDisplayCategory.WRITE -> R.drawable.ic_tool_write
        ToolDisplayCategory.DELETE -> R.drawable.ic_tool_delete
        ToolDisplayCategory.SHELL -> R.drawable.ic_tool_shell
        ToolDisplayCategory.TODO -> R.drawable.ic_tool_todo
        ToolDisplayCategory.AGENT,
        ToolDisplayCategory.AGENT_PIPELINE -> R.drawable.ic_tool_agent
        ToolDisplayCategory.IMAGE_GENERATION -> R.drawable.ic_tool_write
        ToolDisplayCategory.PHONE_CONTROL -> R.drawable.ic_tool_shell
        ToolDisplayCategory.GENERIC -> R.drawable.ic_tool_generic
      }

  // 分类色板。与 AssistantCodeHighlighter 的深色系同源，保证语义色全局一致。
  private const val COLOR_READ = 0xFF7AA2F7.toInt() // 蓝：读
  private const val COLOR_WRITE = 0xFFE0AF68.toInt() // 橙：写
  private const val COLOR_DELETE = 0xFFF7768E.toInt() // 红：删
  private const val COLOR_SHELL = 0xFF9ECE6A.toInt() // 绿：命令
  private const val COLOR_AGENT = 0xFFBB9AF7.toInt() // 紫：派生
  private const val COLOR_GENERIC = 0xFF9AA5CE.toInt() // 灰蓝：未知
}
