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
import static org.junit.Assert.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * 折叠态工具组摘要的计数、去重与排序。
 *
 * <p><b>为什么值得单独测</b>：这一行是折叠后用户唯一读到的文字，它错了却很难被发现——
 * <ul>
 *   <li><b>计数错</b>：把 3 次读算成 1 次（误加了 READ 去重），或把 3 次改算成 3 个文件，
 *       都只是数字不同，看不出是 bug；
 *   <li><b>顺序错</b>：按分类枚举序排而不是按发生顺序，文案读起来通顺，
 *       只是与下方卡片列表的实际顺序不符。
 * </ul>
 * 因此下面把这几条钉死，并显式区分「READ 不去重 / WRITE 去重」这对相反取舍。
 */
public final class AssistantToolGroupSummaryTest {

  /**
   * 构造一条工具调用元信息。
   *
   * <p>分类用 {@link ToolDisplayCategory#name()} 传入（而不是硬编码字符串），
   * 是为了让测试跟着枚举走：若枚举改名，这里会立刻编译失败，而不是悄悄退化成兜底文案。
   */
  private static AssistantToolGroupSummary.Entry entry(
      String toolName, ToolDisplayCategory category, String target, boolean failed) {
    return new AssistantToolGroupSummary.Entry(
        toolName, category.name(), target, failed, 0L);
  }

  private static AssistantToolGroupSummary.Entry read(String path) {
    return entry("file_read", ToolDisplayCategory.READ, path, false);
  }

  private static AssistantToolGroupSummary.Entry write(String path) {
    return entry("file_edit", ToolDisplayCategory.WRITE, path, false);
  }

  private static String summarize(AssistantToolGroupSummary.Entry... entries) {
    return AssistantToolGroupSummary.INSTANCE.summarize(Arrays.asList(entries));
  }

  /** 带思考块数量的摘要。思考块不在 Entry 里（它是推理不是工具调用），故单独传。 */
  private static String summarizeWithThinking(
      int thinkingCount, AssistantToolGroupSummary.Entry... entries) {
    return AssistantToolGroupSummary.INSTANCE.summarize(Arrays.asList(entries), thinkingCount);
  }

  // ---- 1. 单次调用 ----

  @Test
  public void singleReadProducesOneSegment() {
    assertEquals("读取 1 个文件", summarize(read("a.kt")));
  }

  @Test
  public void singleShellCommandUsesTheCommandMeasureWord() {
    // 量词按分类不同：命令论「条」，文件论「个」。统一成「次」会让文案像机翻。
    assertEquals(
        "执行 1 条命令",
        summarize(entry("shell_execute", ToolDisplayCategory.SHELL, null, false)));
  }

  // ---- 2. 多次同类计数 ----

  @Test
  public void repeatedReadsAreCounted() {
    assertEquals(
        "读取 3 个文件", summarize(read("a.kt"), read("b.kt"), read("c.kt")));
  }

  @Test
  public void repeatedShellCommandsAreCounted() {
    assertEquals(
        "执行 2 条命令",
        summarize(
            entry("shell_execute", ToolDisplayCategory.SHELL, null, false),
            entry("shell_execute", ToolDisplayCategory.SHELL, null, false)));
  }

  // ---- 3. 顺序 = 首次出现顺序 ----

  @Test
  public void segmentsFollowFirstAppearanceOrderNotEnumOrder() {
    // 先写后读。枚举里 READ 排在 WRITE 之前，若按枚举序排会得到「读取… · 修改…」，
    // 与下方卡片列表的实际顺序（先改文件、后读文件）不符。
    String summary =
        summarize(
            write("a.kt"),
            write("b.kt"),
            read("c.kt"),
            entry("shell_execute", ToolDisplayCategory.SHELL, null, false));

    assertEquals("修改 2 个文件 · 读取 1 个文件 · 执行 1 条命令", summary);
    assertTrue("写应排在前: " + summary, summary.indexOf("修改") < summary.indexOf("读取"));
  }

  @Test
  public void sameCategoryNeverSplitsIntoTwoSegments() {
    // 读、写、读交错出现时，两次「读」必须并成一段（而不是产生两段「读取」），
    // 但该段仍落在它**首次**出现的位置。
    String summary = summarize(read("a.kt"), write("b.kt"), read("c.kt"));

    assertEquals("读取 2 个文件 · 修改 1 个文件", summary);
  }

  // ---- 4. WRITE 按 target 去重 ----

  @Test
  public void sameFileEditedThreeTimesCountsAsOne() {
    // 照抄参考项目 cc-haha 的取舍：同一个文件改三次是一次逻辑改动。
    assertEquals(
        "修改 1 个文件", summarize(write("a.kt"), write("a.kt"), write("a.kt")));
  }

  @Test
  public void differentFilesAreNotDeduplicated() {
    assertEquals("修改 2 个文件", summarize(write("a.kt"), write("b.kt")));
  }

  @Test
  public void writeWithoutTargetIsNotDeduplicated() {
    // 目标缺失时无法证明是同一个文件。此时各算一次是**保守**方向：
    // 塌成「修改 1 个文件」会少报真实改动，比多报危险得多。
    assertEquals(
        "修改 3 个文件",
        summarize(
            entry("file_write", ToolDisplayCategory.WRITE, null, false),
            entry("file_write", ToolDisplayCategory.WRITE, null, false),
            entry("file_write", ToolDisplayCategory.WRITE, null, false)));
  }

  @Test
  public void blankTargetIsTreatedAsMissing() {
    // 模型偶尔传空串。空串去重会把所有无目标写入错误地合并成一次。
    assertEquals(
        "修改 2 个文件",
        summarize(
            entry("file_write", ToolDisplayCategory.WRITE, "  ", false),
            entry("file_write", ToolDisplayCategory.WRITE, "", false)));
  }

  @Test
  public void deduplicationIgnoresSurroundingWhitespace() {
    // 同一路径带不带首尾空白是同一次改动，不能因为空白差一个字符就多算一次。
    assertEquals("修改 1 个文件", summarize(write("a.kt"), write(" a.kt ")));
  }

  // ---- 5. READ 不去重 ----

  @Test
  public void sameFileReadThreeTimesCountsAsThree() {
    // 与 WRITE 相反：重复读说明模型真的读了三次，这既是真实工作量，
    // 也常常是「它没找到想要的东西」的信号，值得如实呈现。
    assertEquals(
        "读取 3 个文件", summarize(read("a.kt"), read("a.kt"), read("a.kt")));
  }

  // ---- 6. 失败计数 ----

  @Test
  public void failuresAppendAsTheirOwnSegment() {
    String summary =
        summarize(
            read("a.kt"),
            entry("shell_execute", ToolDisplayCategory.SHELL, null, true),
            entry("shell_execute", ToolDisplayCategory.SHELL, null, true));

    assertEquals("读取 1 个文件 · 执行 2 条命令 · 2 项失败", summary);
    assertTrue("失败应在末尾: " + summary, summary.endsWith("2 项失败"));
  }

  @Test
  public void noFailuresMeansNoFailureSegment() {
    assertFalse(summarize(read("a.kt")).contains("失败"));
  }

  @Test
  public void failedWritesStillCountTowardsTheirCategory() {
    // 失败独立成段，但**不从分类计数里扣除**：用户要知道「改了 2 个文件、
    // 其中 1 次失败」，而不是以为只改过 1 个文件。
    String summary =
        summarize(write("a.kt"), entry("file_edit", ToolDisplayCategory.WRITE, "b.kt", true));

    assertEquals("修改 2 个文件 · 1 项失败", summary);
  }

  // ---- 7. 空输入 ----

  @Test
  public void emptyInputYieldsEmptyString() {
    // 返回空串而不是 null 或异常：调用方据此隐藏这一行，无需再做判空。
    assertEquals("", AssistantToolGroupSummary.INSTANCE.summarize(Collections.emptyList()));
  }

  @Test
  public void nullSafeEmptyList() {
    assertEquals("", summarize(new AssistantToolGroupSummary.Entry[0]));
  }

  // ---- 8. 未知分类兜底 ----

  @Test
  public void unknownCategoryFallsBackInsteadOfCrashing() {
    // 新增一个本类还不认识的分类时不能崩——枚举加值不该阻断整个 app 的构建。
    String summary =
        summarize(
            new AssistantToolGroupSummary.Entry("future_tool", "BROWSER", null, false, 0L),
            new AssistantToolGroupSummary.Entry("future_tool", "BROWSER", null, false, 0L));

    assertEquals("调用 2 次工具", summary);
  }

  @Test
  public void allUnknownCategoriesCollapseIntoOneSegment() {
    // 两个不同的未知分类若各出一段「调用 1 次工具」，看起来像 bug（重复文案）。
    String summary =
        summarize(
            new AssistantToolGroupSummary.Entry("t1", "BROWSER", null, false, 0L),
            new AssistantToolGroupSummary.Entry("t2", "SOMETHING_NEW", null, false, 0L));

    assertEquals("调用 2 次工具", summary);
  }

  @Test
  public void nullCategoryIsTreatedAsUnknown() {
    String summary = summarize(new AssistantToolGroupSummary.Entry("t", null, null, false, 0L));

    assertEquals("调用 1 次工具", summary);
  }

  @Test
  public void categoryNameIsCaseAndWhitespaceInsensitive() {
    // 调用方可能传枚举的 name() 也可能传 toString()，两种大小写写法都应识别。
    String summary =
        summarize(
            new AssistantToolGroupSummary.Entry("file_read", " read ", null, false, 0L),
            new AssistantToolGroupSummary.Entry("file_read", "Read", null, false, 0L));

    assertEquals("读取 2 个文件", summary);
  }

  // ---- 覆盖全部已知分类 ----

  @Test
  public void everyKnownCategoryHasItsOwnWording() {
    // 每个分类都必须有自己的中文文案。若某个分类漏了分支而落进兜底，
    // 用户会在折叠态看到「调用 1 次工具」而不是「更新 1 次待办」这种具体说法。
    List<ToolDisplayCategory> categories =
        new ArrayList<>(Arrays.asList(ToolDisplayCategory.values()));
    for (ToolDisplayCategory category : categories) {
      // GENERIC 本身就是兜底分类，它的文案「调用 N 次工具」就是兜底文案，跳过。
      if (category == ToolDisplayCategory.GENERIC) {
        continue;
      }
      String summary =
          summarize(new AssistantToolGroupSummary.Entry("t", category.name(), null, false, 0L));
      assertFalse("分类 " + category + " 落进了兜底文案: " + summary, summary.equals("调用 1 次工具"));
      assertTrue("分类 " + category + " 的文案不应为空", summary.length() > 0);
    }
  }

  @Test
  public void segmentsAreJoinedWithTheSharedSeparator() {
    // 视图层要在末尾追加耗时，必须用同一个分隔符，否则会拼出两套标点混用的文案。
    String summary = summarize(read("a.kt"), write("b.kt"));

    assertEquals("读取 1 个文件" + AssistantToolGroupSummary.SEPARATOR + "修改 1 个文件", summary);
  }

  // ---- 9. 思考次数 ----

  @Test
  public void thinkingSegmentLeadsTheSummary() {
    // 每轮都是先推理再调工具，按时间顺序思考段就该在最前。
    String summary = summarizeWithThinking(3, read("a.kt"), read("b.kt"));

    assertEquals("思考 3 次" + AssistantToolGroupSummary.SEPARATOR + "读取 2 个文件", summary);
  }

  @Test
  public void zeroThinkingProducesNoSegment() {
    // 非推理模型或旧日志里没有 reasoningContent：不该出现「思考 0 次」这种噪声段。
    assertEquals("读取 1 个文件", summarizeWithThinking(0, read("a.kt")));
  }

  @Test
  public void thinkingAloneIsEnoughForASummary() {
    // 组内可能只有推理块、还没有工具调用（运行刚开始）。此时摘要不能是空串——
    // 空串会让视图退回 fallback 文案，把「思考了 2 次」这条真实信息抹掉。
    assertEquals("思考 2 次", summarizeWithThinking(2));
  }
}
