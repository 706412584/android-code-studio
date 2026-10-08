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

package com.tom.rv2ide.adapters

import com.tom.rv2ide.ai.tool.api.ToolNames

/**
 * 工具分组的决策逻辑。
 *
 * <p><b>为什么把它从适配器里抽出来</b>：适配器的 `notify*` 是 `final` 方法、且依赖
 * `RecyclerView` 的观察者机制，在纯 JVM 单测里无法驱动（`android.database.Observable`
 * 的 `mObservers` 字段在测试用的 android.jar 里恒为 null，而字段是 final，
 * 反射改修饰符在 JDK 21 上也被拒）。把决策抽成不依赖任何 Android 类型的纯函数后，
 * 「什么该成组、什么该打断、什么该独立」这些**真正容易错**的规则就能被测试覆盖。
 *
 * <p>适配器只负责「按决策结果改列表 + 发通知」，那部分是机械的。
 */
object ToolGrouping {

  /**
   * 追加一个工具调用时的处置方式。
   *
   * <p>用枚举而不是 sealed interface + object：后者在 Java 侧要写成
   * `Decision.Append.INSTANCE`，可读性差且容易写错；枚举是 `Decision.APPEND`，
   * 对 Kotlin 与 Java 都自然。
   */
  enum class Decision {
    /** 直接追加到列表末尾（自成一张卡片）。 */
    APPEND,

    /** 并入末组。 */
    MERGE_INTO_LAST_GROUP,

    /** 把「末项单卡片 + 新调用」合并成一个新组。 */
    MERGE_LAST_TWO,
  }

  /**
   * 追加一个**推理块**时的处置方式。
   *
   * <p>推理块本身不产生摘要，它只是「这一轮在干什么」的一部分。因此只有两种去向：
   * 并入末组（若末项是组），或独立成块。
   *
   * @param lastIsGroup 末项是否为工具组
   * @return true 表示应并入末组
   */
  @JvmStatic
  fun shouldThinkingJoinGroup(lastIsGroup: Boolean): Boolean = lastIsGroup

  /**
   * 该工具是否必须独立成卡片，不参与分组。
   *
   * <p>子 agent 类工具带 `steps`（运行期间每步在做什么）且可能长跑。
   * 一旦被折进组里，那串步骤就没有展示位了——而它是用户回看子 agent 干了什么的唯一凭据；
   * 组头只显示计数，表达不了「这个子 agent 分几步、每步读哪个文件」。
   */
  @JvmStatic
  fun isStandaloneTool(toolName: String): Boolean =
      toolName == ToolNames.AGENT ||
          toolName == ToolNames.AGENT_PIPELINE ||
          toolName == ToolNames.AGENT_OUTPUT

  /**
   * 决定新工具调用如何入列。
   *
   * @param lastIsGroup 末项是否为工具组
   * @param lastToolName 末项为**单张工具卡片**时的工具名；其它情况传 null
   * @param incomingToolName 本次要追加的工具名
   */
  @JvmStatic
  fun decide(
      lastIsGroup: Boolean,
      lastToolName: String?,
      incomingToolName: String,
  ): Decision {
    // 不吸收的类型永远独立——先判它，避免被下面的分支吸进组里。
    if (isStandaloneTool(incomingToolName)) {
      return Decision.APPEND
    }
    if (lastIsGroup) {
      return Decision.MERGE_INTO_LAST_GROUP
    }
    // 末项是普通工具卡片、且它自己也不是独立类型时，两张合并成组。
    if (lastToolName != null && !isStandaloneTool(lastToolName)) {
      return Decision.MERGE_LAST_TWO
    }
    return Decision.APPEND
  }
}
