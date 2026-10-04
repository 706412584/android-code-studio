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

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 工具白名单的回归测试。
 *
 * <p>白名单是子 agent 的能力边界，判错的后果是「只读角色拿到了写工具」——
 * 一个静默的安全退化，必须在单元层面锁死。
 */
final class ToolNameFilterTest {

  @Test
  void unrestrictedAllowsEverything() {
    ToolNameFilter filter = ToolNameFilter.unrestricted();
    assertFalse(filter.restricts());
    assertTrue(filter.allows("anything"));
    assertTrue(filter.allows(null));
    assertTrue(filter.names().isEmpty());
  }

  @Test
  void emptyOrNullSetMeansUnrestrictedNotZeroTools() {
    // 关键语义：空集合 = 不限制，不是「一个都不给」。混同会让通用 agent 无法工作。
    assertFalse(ToolNameFilter.of(null).restricts());
    assertFalse(ToolNameFilter.of(new HashSet<String>()).restricts());
    assertTrue(ToolNameFilter.of(new HashSet<String>()).allows("file_read"));
  }

  @Test
  void restrictsToGivenNames() {
    ToolNameFilter filter = ToolNameFilter.of(new HashSet<>(Arrays.asList("file_read", "glob")));
    assertTrue(filter.restricts());
    assertTrue(filter.allows("file_read"));
    assertTrue(filter.allows("glob"));
    assertFalse(filter.allows("file_write"));
    assertFalse(filter.allows("shell_execute"));
    assertFalse(filter.allows(null));
  }

  @Test
  void preservesOrderAndDeduplicates() {
    Set<String> input = new LinkedHashSet<>(Arrays.asList("glob", "file_read", "glob"));
    ToolNameFilter filter = ToolNameFilter.of(input);
    assertEquals(Arrays.asList("glob", "file_read"), new java.util.ArrayList<>(filter.names()));
  }

  @Test
  void blankNamesAreIgnored() {
    ToolNameFilter filter = ToolNameFilter.of(new HashSet<>(Arrays.asList("file_read", "  ", "")));
    assertEquals(1, filter.names().size());
    assertTrue(filter.allows("file_read"));
  }

  @Test
  void namesAreTrimmed() {
    ToolNameFilter filter = ToolNameFilter.of(new HashSet<>(Arrays.asList(" file_read ")));
    assertTrue(filter.allows("file_read"));
  }
}
