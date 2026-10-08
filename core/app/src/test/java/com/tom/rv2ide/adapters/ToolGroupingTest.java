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

package com.tom.rv2ide.adapters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 工具分组决策的测试。
 *
 * <p><b>为什么测这个纯函数而不是测适配器</b>：适配器的 `notify*` 是 `final` 且依赖
 * `RecyclerView` 的观察者机制。在单元测试用的 android.jar 里，
 * `android.database.Observable.mObservers` 是 **final 字段且未初始化**，
 * 任何 `notifyItem*` 都会 NPE；而该字段既不能通过 `registerAdapterDataObserver` 创建
 * （同一字段为 null 时直接崩），也不能反射写入（final + JDK 21 拒绝改修饰符），
 * Robolectric 又需要联网下载 android-all（本仓库构建是 --offline）。
 *
 * <p>因此把「什么该成组」抽成 {@link ToolGrouping} 这个不依赖任何 Android 类型的纯函数，
 * 在这里覆盖。适配器剩下的部分只是「按决策改列表 + 发通知」，是机械的。
 *
 * <p>这些规则恰恰是最容易错的地方：子 agent 被误折进组会让它的步骤彻底消失，
 * 正文没打断会让两段无关的工具堆在一起。
 */
public class ToolGroupingTest {

  private static ToolGrouping.Decision decide(
      boolean lastIsGroup, String lastToolName, String incoming) {
    return ToolGrouping.decide(lastIsGroup, lastToolName, incoming);
  }

  /** 连续两张普通工具卡片 → 合并成组。 */
  @Test
  public void twoPlainToolsMerge() {
    assertEquals(
        ToolGrouping.Decision.MERGE_LAST_TWO, decide(false, "file_read", "file_read"));
  }

  /** 末项已是组 → 新调用并入。 */
  @Test
  public void mergesIntoExistingGroup() {
    assertEquals(
        ToolGrouping.Decision.MERGE_INTO_LAST_GROUP, decide(true, null, "file_read"));
  }

  /** 列表为空 → 直接追加（不可能是组，也没有前一张卡片）。 */
  @Test
  public void emptyListAppends() {
    assertEquals(ToolGrouping.Decision.APPEND, decide(false, null, "file_read"));
  }

  /**
   * 子 agent 工具不并入组。
   *
   * <p>关键回归：子 agent 卡片带 steps，被折进组里后那串步骤就没有展示位了——
   * 而它是用户回看子 agent 干了什么的唯一凭据。
   */
  @Test
  public void agentToolIsNotMergedIntoGroup() {
    assertEquals(ToolGrouping.Decision.APPEND, decide(true, null, "agent"));
    assertEquals(ToolGrouping.Decision.APPEND, decide(true, null, "agent_pipeline"));
    assertEquals(ToolGrouping.Decision.APPEND, decide(true, null, "agent_output"));
  }

  /** 末项是 agent 卡片时，新普通工具不与它合并（agent 不参与分组）。 */
  @Test
  public void plainToolDoesNotMergeWithAgentCard() {
    assertEquals(ToolGrouping.Decision.APPEND, decide(false, "agent", "file_read"));
    assertEquals(ToolGrouping.Decision.APPEND, decide(false, "agent_pipeline", "file_read"));
  }

  /** 两个 agent 也不互相合并。 */
  @Test
  public void twoAgentsDoNotMerge() {
    assertEquals(ToolGrouping.Decision.APPEND, decide(false, "agent", "agent"));
  }

  /** 不同类别的普通工具仍可合并（一个组里可以有读取+执行）。 */
  @Test
  public void differentPlainToolsMerge() {
    assertEquals(
        ToolGrouping.Decision.MERGE_LAST_TWO, decide(false, "file_read", "shell_execute"));
  }

  /** isStandaloneTool 的判定与上面一致。 */
  @Test
  public void standaloneToolDetection() {
    assertTrue(ToolGrouping.isStandaloneTool("agent"));
    assertTrue(ToolGrouping.isStandaloneTool("agent_pipeline"));
    assertTrue(ToolGrouping.isStandaloneTool("agent_output"));
    assertFalse(ToolGrouping.isStandaloneTool("file_read"));
    assertFalse(ToolGrouping.isStandaloneTool("shell_execute"));
    assertFalse(ToolGrouping.isStandaloneTool(""));
  }
}
