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

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link TodoItem} 的状态归一：同义词映射、非法值回落、不可变派生。
 *
 * <p>模型经常写 {@code done}/{@code finished}/「完成」这类同义词。严格拒绝会让它
 * 反复重试同一次调用，因此归一必须宽进；无法识别时退回 pending（而不是报错），
 * 因为待办是辅助手段，不值得为它中断任务。这些分支此前只经 TodoUpdateTool 间接
 * 走到两个状态。
 */
final class TodoItemTest {

  @Test
  void normalizeStatusPassesThroughCanonicalValues() {
    assertEquals(TodoItem.STATUS_PENDING, TodoItem.normalizeStatus("pending"));
    assertEquals(TodoItem.STATUS_IN_PROGRESS, TodoItem.normalizeStatus("in_progress"));
    assertEquals(TodoItem.STATUS_COMPLETED, TodoItem.normalizeStatus("completed"));
  }

  @Test
  void normalizeStatusMapsCompletionSynonyms() {
    for (String value : new String[] {"done", "complete", "finished", "completed", "完成", "已完成"}) {
      assertEquals(TodoItem.STATUS_COMPLETED, TodoItem.normalizeStatus(value), value);
    }
  }

  @Test
  void normalizeStatusMapsInProgressSynonyms() {
    for (String value :
        new String[] {"doing", "active", "current", "in-progress", "inprogress", "进行中"}) {
      assertEquals(TodoItem.STATUS_IN_PROGRESS, TodoItem.normalizeStatus(value), value);
    }
  }

  @Test
  void normalizeStatusMapsPendingSynonyms() {
    for (String value : new String[] {"todo", "open", "not_started", "未开始", "待办"}) {
      assertEquals(TodoItem.STATUS_PENDING, TodoItem.normalizeStatus(value), value);
    }
  }

  @Test
  void normalizeStatusIsCaseInsensitiveAndTrimmed() {
    assertEquals(TodoItem.STATUS_COMPLETED, TodoItem.normalizeStatus("  DONE  "));
    assertEquals(TodoItem.STATUS_IN_PROGRESS, TodoItem.normalizeStatus("In-Progress"));
  }

  @Test
  void unknownStatusFallsBackToPendingWithoutThrowing() {
    // 宽进：无法识别不报错，退回 pending。报错会让模型反复重试。
    assertEquals(TodoItem.STATUS_PENDING, TodoItem.normalizeStatus("whatever"));
    assertEquals(TodoItem.STATUS_PENDING, TodoItem.normalizeStatus(null));
    assertEquals(TodoItem.STATUS_PENDING, TodoItem.normalizeStatus(""));
    assertEquals(TodoItem.STATUS_PENDING, TodoItem.normalizeStatus("   "));
  }

  @Test
  void constructorNormalizesStatusAndNullContent() {
    TodoItem item = new TodoItem(null, "done");
    assertEquals("", item.getContent());
    assertEquals(TodoItem.STATUS_COMPLETED, item.getStatus());
  }

  @Test
  void withStatusDerivesNewInstanceKeepingContent() {
    TodoItem original = new TodoItem("读取配置", TodoItem.STATUS_PENDING);
    TodoItem next = original.withStatus("in_progress");

    assertEquals("读取配置", next.getContent());
    assertEquals(TodoItem.STATUS_IN_PROGRESS, next.getStatus());
    // 原实例不变（不可变契约）。
    assertEquals(TodoItem.STATUS_PENDING, original.getStatus());
  }

  @Test
  void isValidStatusAcceptsOnlyCanonicalValues() {
    assertTrue(TodoItem.isValidStatus("pending"));
    assertTrue(TodoItem.isValidStatus("in_progress"));
    assertTrue(TodoItem.isValidStatus("completed"));
    // 同义词与 null 不是「合法值」——它们由 normalize 转换。
    assertFalse(TodoItem.isValidStatus("done"));
    assertFalse(TodoItem.isValidStatus(null));
  }

  @Test
  void toLineUsesStatusMarkers() {
    assertEquals("[x] 已完成的事",
        new TodoItem("已完成的事", TodoItem.STATUS_COMPLETED).toLine());
    assertEquals("[>] 正在做的事",
        new TodoItem("正在做的事", TodoItem.STATUS_IN_PROGRESS).toLine());
    assertEquals("[ ] 还没做的事",
        new TodoItem("还没做的事", TodoItem.STATUS_PENDING).toLine());
  }
}
