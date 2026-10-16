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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link DiagnosticsMessages} 的过滤、排序与格式化（编辑搭车与独立工具共用）。 */
class DiagnosticsMessagesTest {

  private static DiagnosticsPort.Item item(int line, int col, int severity, String message) {
    return new DiagnosticsPort.Item(line, col, severity, message, "");
  }

  @Test
  void filterKeepsOnlyAtOrAboveMinSeverity() {
    List<DiagnosticsPort.Item> items =
        Arrays.asList(
            item(0, 0, 1, "err"), item(1, 0, 2, "warn"), item(2, 0, 3, "info"));
    List<DiagnosticsPort.Item> onlyErrors = DiagnosticsMessages.filterBySeverity(items, 1);
    assertEquals(1, onlyErrors.size());
    assertEquals("err", onlyErrors.get(0).getMessage());

    assertEquals(2, DiagnosticsMessages.filterBySeverity(items, 2).size());
    assertEquals(3, DiagnosticsMessages.filterBySeverity(items, 3).size());
  }

  @Test
  void filterDropsUnknownSeverityZero() {
    List<DiagnosticsPort.Item> items = Collections.singletonList(item(0, 0, 0, "weird"));
    assertTrue(DiagnosticsMessages.filterBySeverity(items, 4).isEmpty());
  }

  @Test
  void sortPutsSeverestFirstThenByLine() {
    List<DiagnosticsPort.Item> items =
        Arrays.asList(
            item(50, 0, 2, "warn-late"),
            item(9, 0, 1, "err-late"),
            item(2, 0, 2, "warn-early"),
            item(1, 0, 1, "err-early"));
    List<DiagnosticsPort.Item> sorted = DiagnosticsMessages.sorted(items);
    assertEquals("err-early", sorted.get(0).getMessage());
    assertEquals("err-late", sorted.get(1).getMessage());
    assertEquals("warn-early", sorted.get(2).getMessage());
    assertEquals("warn-late", sorted.get(3).getMessage());
  }

  @Test
  void formatItemUsesOneBasedLineAndColumn() {
    assertEquals("12:5 [ERROR] boom (E1)", DiagnosticsMessages.formatItem(newItem()));
  }

  @Test
  void formatItemOmitsEmptyCode() {
    assertEquals(
        "1:1 [WARNING] meh",
        DiagnosticsMessages.formatItem(item(0, 0, 2, "meh")));
  }

  @Test
  void blockListsItemsAndCapsAtMax() {
    List<DiagnosticsPort.Item> items =
        Arrays.asList(
            item(0, 0, 1, "a"), item(1, 0, 1, "b"), item(2, 0, 1, "c"), item(3, 0, 1, "d"));
    String block = DiagnosticsMessages.block("Foo.java", items, 2);
    assertTrue(block.contains("4 条编译诊断"), block);
    assertTrue(block.contains("Foo.java"), block);
    assertTrue(block.contains("a"), block);
    assertTrue(block.contains("b"), block);
    assertFalse(block.contains("[ERROR] c"), block);
    assertTrue(block.contains("另有 2 条"), block);
  }

  @Test
  void blockReturnsEmptyForNoItems() {
    assertEquals("", DiagnosticsMessages.block("Foo.java", Collections.emptyList(), 20));
    assertEquals("", DiagnosticsMessages.block("Foo.java", null, 20));
  }

  // ---- parseSeverity / severityName（此前仅经 formatItem 间接覆盖）----

  @Test
  void parseSeverityAcceptsLongAndShortForms() {
    assertEquals(1, DiagnosticsMessages.parseSeverity("error"));
    assertEquals(1, DiagnosticsMessages.parseSeverity("E"));
    assertEquals(2, DiagnosticsMessages.parseSeverity("warning"));
    assertEquals(2, DiagnosticsMessages.parseSeverity("warn"));
    assertEquals(2, DiagnosticsMessages.parseSeverity("w"));
    assertEquals(3, DiagnosticsMessages.parseSeverity("info"));
    assertEquals(3, DiagnosticsMessages.parseSeverity("i"));
    assertEquals(4, DiagnosticsMessages.parseSeverity("hint"));
    assertEquals(4, DiagnosticsMessages.parseSeverity("h"));
  }

  @Test
  void parseSeverityFallsBackToWarning() {
    // 未知/null 一律回落 warning——诊断宁可多显示不可漏显示。
    assertEquals(2, DiagnosticsMessages.parseSeverity(null));
    assertEquals(2, DiagnosticsMessages.parseSeverity("bogus"));
    assertEquals(2, DiagnosticsMessages.parseSeverity("  "));
    assertEquals(1, DiagnosticsMessages.parseSeverity("  ERROR  "), "两侧空白应被容忍");
  }

  @Test
  void severityNameMapsAllLevelsWithWarningDefault() {
    assertEquals("错误", DiagnosticsMessages.severityName(1));
    assertEquals("警告", DiagnosticsMessages.severityName(2));
    assertEquals("信息", DiagnosticsMessages.severityName(3));
    assertEquals("提示", DiagnosticsMessages.severityName(4));
    // 未知数值回落警告（default 分支）。
    assertEquals("警告", DiagnosticsMessages.severityName(0));
    assertEquals("警告", DiagnosticsMessages.severityName(99));
  }

  @Test
  void filterHandlesNullList() {
    assertTrue(DiagnosticsMessages.filterBySeverity(null, 4).isEmpty());
  }

  private static DiagnosticsPort.Item newItem() {
    return new DiagnosticsPort.Item(11, 4, 1, "boom", "E1");
  }
}
