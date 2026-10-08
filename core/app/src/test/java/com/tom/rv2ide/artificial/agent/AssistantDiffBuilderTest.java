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

package com.tom.rv2ide.artificial.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * 行级 diff 的回归测试（Kotlin 类，从 Java 调用）。
 *
 * <p><b>为什么需要它</b>：这个类最容易出错的地方，恰好都是「看起来能跑但结果荒谬」的：
 * CRLF 文件整篇刷红、BOM 让首行永远显示为改动、超大文件把进程打崩。这些在真机上要么
 * 不易察觉（用户以为 AI 真重写了整个文件），要么直接崩溃。用纯 JVM 测试钉住成本极低。
 *
 * <p>降级路径尤其重要：守卫一旦写错（例如用 int 相乘导致溢出），守卫本身就成了
 * 新的崩溃点——正是本类要规避的那个坑。
 */
public final class AssistantDiffBuilderTest {

  // ---- 单行改名 ----

  @Test
  public void singleLineRenameHighlightsOnlyTheChangedToken() {
    // 核心诉求：`userId` → `accountId` 只该高亮这两个词，而不是整行。
    DiffResult result = AssistantDiffBuilder.build("val userId = 1", "val accountId = 1");

    assertEquals(2, result.getLines().size());
    DiffLine deleted = result.getLines().get(0);
    DiffLine inserted = result.getLines().get(1);
    assertEquals(DiffLineType.DELETE, deleted.getType());
    assertEquals(DiffLineType.INSERT, inserted.getType());

    assertEquals("userId", changedText(deleted));
    assertEquals("accountId", changedText(inserted));
  }

  @Test
  public void singleLineRenameLeavesTheRestOfTheLineUntouched() {
    DiffResult result = AssistantDiffBuilder.build("val userId = 1", "val accountId = 1");

    DiffLine deleted = result.getLines().get(0);
    DiffLine inserted = result.getLines().get(1);

    // 未变化的部分必须保留，否则界面会把整行标红。
    assertTrue(unchangedText(deleted).contains("val"));
    assertTrue(unchangedText(deleted).contains("= 1"));
    assertTrue(unchangedText(inserted).contains("val"));
    assertTrue(unchangedText(inserted).contains("= 1"));
  }

  @Test
  public void punctuationChangeIsHighlightedOnBothSides() {
    // `==` → `!=`：两个 token 各自标出，不能退化成「整行都变了」。
    DiffResult result = AssistantDiffBuilder.build("if (a == b)", "if (a != b)");

    DiffLine deleted = result.getLines().get(0);
    DiffLine inserted = result.getLines().get(1);
    assertEquals("=", changedText(deleted));
    assertEquals("!", changedText(inserted));
  }

  // ---- 多行插入 / 删除 ----

  @Test
  public void insertedLinesCarryCorrectNewLineNumbers() {
    DiffResult result =
        AssistantDiffBuilder.build("a\nb\nc", "a\nx\ny\nb\nc");

    assertEquals(5, result.getLines().size());
    assertEquals(2, result.getAdded());
    assertEquals(0, result.getRemoved());

    assertLine(result.getLines().get(0), DiffLineType.EQUAL, 1, 1);
    assertLine(result.getLines().get(1), DiffLineType.INSERT, null, 2);
    assertLine(result.getLines().get(2), DiffLineType.INSERT, null, 3);
    assertLine(result.getLines().get(3), DiffLineType.EQUAL, 2, 4);
    assertLine(result.getLines().get(4), DiffLineType.EQUAL, 3, 5);

    // 纯插入没有对侧行可比较，不应产生词级分段。
    assertTrue(result.getLines().get(1).getSegments().isEmpty());
  }

  @Test
  public void deletedLinesCarryCorrectOldLineNumbers() {
    DiffResult result = AssistantDiffBuilder.build("a\nx\ny\nb\nc", "a\nb\nc");

    assertEquals(5, result.getLines().size());
    assertEquals(0, result.getAdded());
    assertEquals(2, result.getRemoved());

    assertLine(result.getLines().get(0), DiffLineType.EQUAL, 1, 1);
    assertLine(result.getLines().get(1), DiffLineType.DELETE, 2, null);
    assertLine(result.getLines().get(2), DiffLineType.DELETE, 3, null);
    assertLine(result.getLines().get(3), DiffLineType.EQUAL, 4, 2);
    assertLine(result.getLines().get(4), DiffLineType.EQUAL, 5, 3);
  }

  @Test
  public void multiLineReplacementKeepsLineNumbersConsistent() {
    // 公共子序列是 [a, d]，因此中间两行是「删 2 行 + 增 2 行」，不是「改 2 行」。
    DiffResult result = AssistantDiffBuilder.build("a\nb\nc\nd", "a\nB\nC\nd");

    assertEquals(6, result.getLines().size());
    assertEquals(2, result.getAdded());
    assertEquals(2, result.getRemoved());

    assertLine(result.getLines().get(0), DiffLineType.EQUAL, 1, 1);
    assertLine(result.getLines().get(1), DiffLineType.DELETE, 2, null);
    assertLine(result.getLines().get(2), DiffLineType.DELETE, 3, null);
    assertLine(result.getLines().get(3), DiffLineType.INSERT, null, 2);
    assertLine(result.getLines().get(4), DiffLineType.INSERT, null, 3);
    assertLine(result.getLines().get(5), DiffLineType.EQUAL, 4, 4);
    // 配对行（b↔B、c↔C）应带词级分段。
    assertFalse(result.getLines().get(1).getSegments().isEmpty());
    assertEquals("b", changedText(result.getLines().get(1)));
    assertEquals("B", changedText(result.getLines().get(3)));
  }

  // ---- 新建 / 清空 ----

  @Test
  public void newFileIsAllInsert() {
    // oldExists=false 时 oldContent 必须被忽略，否则「新建」会被渲染成一大堆删除。
    DiffResult result = AssistantDiffBuilder.build("", "a\nb\nc", false);

    assertEquals(3, result.getLines().size());
    assertEquals(3, result.getAdded());
    assertEquals(0, result.getRemoved());
    assertFalse(result.getTruncated());
    for (DiffLine line : result.getLines()) {
      assertEquals(DiffLineType.INSERT, line.getType());
      assertNull(line.getOldNo());
    }
  }

  @Test
  public void newFileIgnoresStaleOldContent() {
    // 即使调用方误传了 oldContent，只要 oldExists=false 就不能出现删除行。
    DiffResult result = AssistantDiffBuilder.build("stale\ncontent", "fresh", false);

    assertEquals(1, result.getLines().size());
    assertEquals(DiffLineType.INSERT, result.getLines().get(0).getType());
    assertEquals(0, result.getRemoved());
  }

  @Test
  public void clearedFileIsAllDelete() {
    DiffResult result = AssistantDiffBuilder.build("a\nb\nc", "");

    assertEquals(3, result.getLines().size());
    assertEquals(0, result.getAdded());
    assertEquals(3, result.getRemoved());
    for (DiffLine line : result.getLines()) {
      assertEquals(DiffLineType.DELETE, line.getType());
      assertNull(line.getNewNo());
    }
  }

  // ---- 换行归一 / BOM ----

  @Test
  public void identicalCrlfContentIsNotReportedAsChanged() {
    // 不做换行归一时，每行尾部都残留 \r，整个文件会显示为改动。
    DiffResult result = AssistantDiffBuilder.build("a\r\nb\r\nc", "a\r\nb\r\nc");

    assertAllEqual(result, 3);
  }

  @Test
  public void crlfVersusLfIsTreatedAsUnchanged() {
    // 同一内容、不同换行风格，不算改动。
    DiffResult result = AssistantDiffBuilder.build("a\r\nb\r\nc", "a\nb\nc");

    assertAllEqual(result, 3);
  }

  @Test
  public void loneCrIsNormalizedToo() {
    // 老 Mac 风格的行尾同样要归一，否则整文件刷红。
    DiffResult result = AssistantDiffBuilder.build("a\rb\rc", "a\nb\nc");

    assertAllEqual(result, 3);
  }

  @Test
  public void bomIsStrippedSoTheFirstLineIsUnchanged() {
    // 带 BOM 的旧内容与不带 BOM 的新内容，若不去 BOM，首行永远显示为改动。
    DiffResult result = AssistantDiffBuilder.build("\uFEFFa\nb", "a\nb");

    assertAllEqual(result, 2);
  }

  @Test
  public void bomOnBothSidesIsStripped() {
    DiffResult result = AssistantDiffBuilder.build("\uFEFFa\nb", "\uFEFFa\nb");

    assertAllEqual(result, 2);
  }

  // ---- 完全相同 ----

  @Test
  public void identicalContentIsAllEqual() {
    DiffResult result = AssistantDiffBuilder.build("a\nb\nc", "a\nb\nc");

    assertAllEqual(result, 3);
  }

  @Test
  public void bothEmptyIsEmptyResult() {
    DiffResult result = AssistantDiffBuilder.build("", "");

    assertEquals(0, result.getLines().size());
    assertEquals(0, result.getAdded());
    assertEquals(0, result.getRemoved());
    assertFalse(result.getTruncated());
  }

  @Test
  public void trailingNewlineDoesNotCountAsAnExtraLine() {
    // 末尾换行是行终止符而非空行：`"a\nb\n"` 是 2 行。
    // 否则编辑器自动补的那个换行每次都会被算成一次改动。
    DiffResult result = AssistantDiffBuilder.build("a\nb", "a\nb\n");

    assertAllEqual(result, 2);
  }

  // ---- 降级路径 ----

  @Test
  public void hugeInputDegradesInsteadOfBlowingUp() {
    // 3000 × 3000 = 9e6 单元，超过 4e6 阈值 → 必须降级，且绝不抛异常 / OOM。
    StringBuilder oldBuilder = new StringBuilder();
    StringBuilder newBuilder = new StringBuilder();
    for (int i = 0; i < 3000; i++) {
      oldBuilder.append("old line ").append(i).append('\n');
      newBuilder.append("new line ").append(i).append('\n');
    }

    DiffResult result =
        AssistantDiffBuilder.build(oldBuilder.toString(), newBuilder.toString());

    assertTrue("超大输入必须标记为降级", result.getTruncated());
    assertEquals(3000, result.getRemoved());
    assertEquals(3000, result.getAdded());
    assertEquals(6000, result.getLines().size());
    // 降级后不再配对，因此不应有行内分段（避免渲染层去做无意义的高亮）。
    assertTrue(result.getLines().get(0).getSegments().isEmpty());
  }

  @Test
  public void tooManyLinesDegradesEvenWhenTheProductIsSmall() {
    // 单侧超过 MAX_LINES 时，即使乘积很小（20001 × 1）也要降级——
    // 行数上限是针对「拆行 / 展示」本身的成本，与 DP 表大小无关。
    StringBuilder oldBuilder = new StringBuilder();
    for (int i = 0; i <= AssistantDiffBuilder.MAX_LINES; i++) {
      oldBuilder.append("x\n");
    }

    DiffResult result = AssistantDiffBuilder.build(oldBuilder.toString(), "x");

    assertTrue(result.getTruncated());
  }

  @Test
  public void degradedResultStillReportsAccurateCounts() {
    StringBuilder oldBuilder = new StringBuilder();
    StringBuilder newBuilder = new StringBuilder();
    for (int i = 0; i < 2500; i++) {
      oldBuilder.append("o").append(i).append('\n');
    }
    for (int i = 0; i < 2000; i++) {
      newBuilder.append("n").append(i).append('\n');
    }

    DiffResult result =
        AssistantDiffBuilder.build(oldBuilder.toString(), newBuilder.toString());

    assertTrue(result.getTruncated());
    assertEquals(2500, result.getRemoved());
    assertEquals(2000, result.getAdded());
  }

  @Test
  public void thresholdIsLargeEnoughForOrdinaryFilesButBounded() {
    // 阈值本身也要钉住：太小会让正常文件频繁降级，太大则失去保护意义。
    assertTrue(AssistantDiffBuilder.MAX_CELLS >= 1_000_000L);
    assertTrue(AssistantDiffBuilder.MAX_CELLS <= 16_000_000L);
    assertTrue(AssistantDiffBuilder.MAX_LINES >= 10_000);
  }

  // ---- 辅助 ----

  private static void assertLine(
      DiffLine line, DiffLineType type, Integer oldNo, Integer newNo) {
    assertEquals("type", type, line.getType());
    assertEquals("oldNo", oldNo, line.getOldNo());
    assertEquals("newNo", newNo, line.getNewNo());
  }

  private static void assertAllEqual(DiffResult result, int expectedLines) {
    assertEquals(expectedLines, result.getLines().size());
    assertEquals(0, result.getAdded());
    assertEquals(0, result.getRemoved());
    assertFalse(result.getTruncated());
    for (DiffLine line : result.getLines()) {
      assertEquals(DiffLineType.EQUAL, line.getType());
    }
  }

  /** 该行所有「被标为变化」的分段拼接结果。 */
  private static String changedText(DiffLine line) {
    StringBuilder sb = new StringBuilder();
    for (DiffSegment segment : line.getSegments()) {
      if (segment.getChanged()) {
        sb.append(segment.getText());
      }
    }
    return sb.toString();
  }

  /** 该行所有「未变化」的分段拼接结果。 */
  private static String unchangedText(DiffLine line) {
    List<DiffSegment> segments = line.getSegments();
    List<String> parts = new ArrayList<>();
    for (DiffSegment segment : segments) {
      if (!segment.getChanged()) {
        parts.add(segment.getText());
      }
    }
    return String.join("", parts);
  }
}
