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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * editor_state / file_open / save_all 三工具的行为：降级、参数校验、1 基↔0 基换算、
 * 失败文案、只读/并发判定。
 *
 * <p>重点是**不可用与「空状态」必须可区分**：会话消失时报失败，而不是返回空快照
 * （后者会被模型读成「编辑器开着但没有文件」）。以及 1 基→0 基换算——它错了只会
 * 静默错位，必须用假端口收到的参数钉死。
 */
class EditorStateToolTest {

  /** 记录调用、可配置返回值的假端口。 */
  private static final class FakePort implements EditorSessionPort {
    boolean available = true;
    String reason = "";
    Snapshot snapshot;
    OpenResult openResult = OpenResult.opened(true);
    SaveReport saveReport = SaveReport.of(Collections.emptyList(), Collections.emptyList());

    int snapshotCalls;
    int openCalls;
    int saveCalls;

    // openAt 收到的参数（验证 1 基 → 0 基换算）
    String lastPath;
    int lastStartLine = Integer.MIN_VALUE;
    int lastStartColumn = Integer.MIN_VALUE;
    int lastEndLine = Integer.MIN_VALUE;
    int lastEndColumn = Integer.MIN_VALUE;

    @Override
    public boolean isAvailable() {
      return available;
    }

    @Override
    public String unavailableReason() {
      return reason;
    }

    @Override
    public Snapshot snapshot() {
      snapshotCalls++;
      return snapshot;
    }

    @Override
    public OpenResult openAt(
        String absolutePath, int startLine, int startColumn, int endLine, int endColumn) {
      openCalls++;
      lastPath = absolutePath;
      lastStartLine = startLine;
      lastStartColumn = startColumn;
      lastEndLine = endLine;
      lastEndColumn = endColumn;
      return openResult;
    }

    @Override
    public SaveReport saveAll(long timeoutMs) {
      saveCalls++;
      return saveReport;
    }
  }

  private static ToolContext contextWith(EditorSessionPort port, String home) {
    return ToolContext.builder().homePath(home).editorSession(port).build();
  }

  /** 展示路径用平台分隔符；断言前统一成 '/'，避免 Windows 下误报。 */
  private static String norm(String text) {
    return text.replace('\\', '/');
  }

  private static EditorSessionPort.Snapshot snapshotOf(
      String currentPath, int line, int col, int selStartLine, int selStartCol,
      int selEndLine, int selEndCol, boolean modified) {
    return EditorSessionPort.Snapshot.of(
        currentPath, line, col, selStartLine, selStartCol, selEndLine, selEndCol,
        Collections.emptyList(), modified);
  }

  // ---------------------------------------------------------------- 端口缺失

  @Test
  void allToolsReportExplicitErrorWhenPortMissing() throws Exception {
    ToolContext context = ToolContext.builder().homePath(".").build();

    ToolResult state = new EditorStateTool().execute(new JSONObject(), context);
    ToolResult open =
        new FileOpenTool()
            .execute(new JSONObject().put("file_path", "A.java").put("line", 1), context);
    ToolResult save = new SaveAllTool().execute(new JSONObject(), context);

    assertTrue(state.isError());
    assertTrue(state.getContent().contains("未接入"), state.getContent());
    assertTrue(open.isError());
    assertTrue(open.getContent().contains("未接入"), open.getContent());
    assertTrue(save.isError());
    assertTrue(save.getContent().contains("未接入"), save.getContent());
  }

  // ---------------------------------------------------------------- 端口不可用

  @Test
  void unavailablePortReportsReasonAndDoesNotTouchSession(@TempDir File dir) throws Exception {
    File file = new File(dir, "A.java");
    Files.write(file.toPath(), "class A {}".getBytes(StandardCharsets.UTF_8));

    FakePort port = new FakePort();
    port.available = false;
    port.reason = "当前没有在编辑器中打开项目";
    ToolContext context = contextWith(port, dir.getAbsolutePath());

    ToolResult state = new EditorStateTool().execute(new JSONObject(), context);
    ToolResult open =
        new FileOpenTool()
            .execute(new JSONObject().put("file_path", "A.java").put("line", 1), context);
    ToolResult save = new SaveAllTool().execute(new JSONObject(), context);

    for (ToolResult result : Arrays.asList(state, open, save)) {
      assertTrue(result.isError());
      assertTrue(result.getContent().contains("没有在编辑器中打开项目"), result.getContent());
    }
    assertEquals(0, port.snapshotCalls, "不可用时不应读快照");
    assertEquals(0, port.openCalls, "不可用时不应打开文件");
    assertEquals(0, port.saveCalls, "不可用时不应触发保存");
  }

  // ---------------------------------------------------------------- editor_state

  @Test
  void editorStateFormatsSnapshotWithOneBasedPositions(@TempDir File dir) throws Exception {
    File src = new File(dir, "src/main/java");
    assertTrue(src.mkdirs());
    File current = new File(src, "Foo.java");
    File other = new File(dir, "build.gradle.kts");
    Files.write(current.toPath(), "class Foo {}".getBytes(StandardCharsets.UTF_8));
    Files.write(other.toPath(), "// gradle".getBytes(StandardCharsets.UTF_8));

    FakePort port = new FakePort();
    // 端口内 0 基：cursor 42 → 展示 43；无选区 → 四个 selection* 传 -1
    port.snapshot =
        EditorSessionPort.Snapshot.of(
            current.getAbsolutePath(), 42, 9, -1, -1, -1, -1,
            Arrays.asList(
                new EditorSessionPort.OpenFile(current.getAbsolutePath(), true, true),
                new EditorSessionPort.OpenFile(other.getAbsolutePath(), false, false)),
            true);

    ToolResult result =
        new EditorStateTool().execute(new JSONObject(), contextWith(port, dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    String content = norm(result.getContent());
    assertTrue(content.contains("src/main/java/Foo.java"), content);
    assertTrue(content.contains("第 43 行"), content);
    assertTrue(content.contains("第 10 列"), content);
    assertTrue(content.contains("未保存修改：是"), content);
    assertTrue(content.contains("已打开文件（2 个）"), content);
    assertTrue(content.contains("build.gradle.kts"), content);
    assertTrue(content.contains("（当前文件，未保存修改）"), content);
    assertFalse(content.contains("选中："), "无选区时不应有选中行：" + content);
  }

  @Test
  void editorStateReportsSelectionWithOneBasedPositions(@TempDir File dir) throws Exception {
    File file = new File(dir, "Foo.java");
    Files.write(file.toPath(), "class Foo {}".getBytes(StandardCharsets.UTF_8));

    FakePort port = new FakePort();
    port.snapshot = snapshotOf(file.getAbsolutePath(), 0, 0, 42, 9, 44, 2, false);

    ToolResult result =
        new EditorStateTool().execute(new JSONObject(), contextWith(port, dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("选中：第 43 行第 10 列 → 第 45 行第 3 列"),
        result.getContent());
  }

  @Test
  void editorStateWithoutCurrentFileSaysSoExplicitly(@TempDir File dir) throws Exception {
    FakePort port = new FakePort();
    // 编辑器在，但没有打开任何文件：currentFilePath 空串、光标 -1
    port.snapshot = snapshotOf("", -1, -1, -1, -1, -1, -1, false);

    ToolResult result =
        new EditorStateTool().execute(new JSONObject(), contextWith(port, dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("没有文件"), result.getContent());
    assertFalse(result.getContent().contains("光标："), result.getContent());
  }

  @Test
  void editorStateRaceAfterCheckIsReportedAsErrorNotEmptySnapshot(@TempDir File dir) throws Exception {
    FakePort port = new FakePort();
    port.snapshot = EditorSessionPort.Snapshot.unavailable("编辑器已关闭");

    ToolResult result =
        new EditorStateTool().execute(new JSONObject(), contextWith(port, dir.getAbsolutePath()));

    assertTrue(result.isError(), "竞态下必须报错而不是返回空快照：" + result.getContent());
    assertTrue(result.getContent().contains("编辑器已关闭"), result.getContent());
  }

  // ---------------------------------------------------------------- file_open

  @Test
  void fileOpenRejectsMissingOrZeroLine(@TempDir File dir) throws Exception {
    File file = new File(dir, "A.java");
    Files.write(file.toPath(), "class A {}".getBytes(StandardCharsets.UTF_8));
    FakePort port = new FakePort();
    ToolContext context = contextWith(port, dir.getAbsolutePath());

    ToolResult missing =
        new FileOpenTool().execute(new JSONObject().put("file_path", "A.java"), context);
    ToolResult zero =
        new FileOpenTool()
            .execute(new JSONObject().put("file_path", "A.java").put("line", 0), context);

    assertTrue(missing.isError());
    assertTrue(missing.getContent().contains("line"), missing.getContent());
    assertTrue(zero.isError());
    assertTrue(zero.getContent().contains("line"), zero.getContent());
    assertEquals(0, port.openCalls);
  }

  @Test
  void fileOpenConvertsOneBasedInputToZeroBasedPortArguments(@TempDir File dir) throws Exception {
    File file = new File(dir, "A.java");
    Files.write(file.toPath(), "class A {}".getBytes(StandardCharsets.UTF_8));

    FakePort port = new FakePort();
    ToolResult result =
        new FileOpenTool()
            .execute(
                new JSONObject().put("file_path", "A.java").put("line", 43).put("column", 10),
                contextWith(port, dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    assertEquals(1, port.openCalls);
    assertEquals(file.getAbsolutePath(), port.lastPath);
    // 核心契约：1 基 → 0 基
    assertEquals(42, port.lastStartLine);
    assertEquals(9, port.lastStartColumn);
    // 点定位：end == start
    assertEquals(42, port.lastEndLine);
    assertEquals(9, port.lastEndColumn);
    assertTrue(result.getContent().contains("第 43 行第 10 列"), result.getContent());
  }

  @Test
  void fileOpenSortsReversedRange(@TempDir File dir) throws Exception {
    File file = new File(dir, "A.java");
    Files.write(file.toPath(), "class A {}".getBytes(StandardCharsets.UTF_8));

    FakePort port = new FakePort();
    ToolResult result =
        new FileOpenTool()
            .execute(
                new JSONObject()
                    .put("file_path", "A.java")
                    .put("line", 45)
                    .put("column", 3)
                    .put("end_line", 43)
                    .put("end_column", 10),
                contextWith(port, dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    assertEquals(42, port.lastStartLine, "反序输入应被排序：start 取较小的行");
    assertEquals(9, port.lastStartColumn);
    assertEquals(44, port.lastEndLine);
    assertEquals(2, port.lastEndColumn);
    assertTrue(result.getContent().contains("选中"), result.getContent());
  }

  @Test
  void fileOpenRejectsMissingAndNonFileTargets(@TempDir File dir) throws Exception {
    FakePort port = new FakePort();
    ToolContext context = contextWith(port, dir.getAbsolutePath());

    ToolResult missing =
        new FileOpenTool()
            .execute(new JSONObject().put("file_path", "Nope.java").put("line", 1), context);
    assertTrue(missing.isError());
    assertTrue(missing.getContent().contains("文件不存在"), missing.getContent());

    assertTrue(new File(dir, "sub").mkdirs());
    ToolResult notFile =
        new FileOpenTool()
            .execute(new JSONObject().put("file_path", "sub").put("line", 1), context);
    assertTrue(notFile.isError());
    assertTrue(notFile.getContent().contains("不是文件"), notFile.getContent());
    assertEquals(0, port.openCalls, "文件校验失败不应调用端口");
  }

  @Test
  void fileOpenRejectsPathOutsideWorkspace(@TempDir File dir) throws Exception {
    FakePort port = new FakePort();
    ToolResult result =
        new FileOpenTool()
            .execute(
                new JSONObject().put("file_path", "../../etc/passwd").put("line", 1),
                contextWith(port, new File(dir, "project").getAbsolutePath()));

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("工作区"), result.getContent());
    assertEquals(0, port.openCalls);
  }

  @Test
  void fileOpenMentionsBackgroundEditorWhenNotVisible(@TempDir File dir) throws Exception {
    File file = new File(dir, "A.java");
    Files.write(file.toPath(), "class A {}".getBytes(StandardCharsets.UTF_8));

    FakePort port = new FakePort();
    port.openResult = EditorSessionPort.OpenResult.opened(false);

    ToolResult result =
        new FileOpenTool()
            .execute(
                new JSONObject().put("file_path", "A.java").put("line", 3),
                contextWith(port, dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("不在前台"), result.getContent());
    assertTrue(result.getContent().contains("A.java"), result.getContent());
  }

  @Test
  void fileOpenPropagatesPortFailure(@TempDir File dir) throws Exception {
    File file = new File(dir, "A.java");
    Files.write(file.toPath(), "class A {}".getBytes(StandardCharsets.UTF_8));

    FakePort port = new FakePort();
    port.openResult = EditorSessionPort.OpenResult.failed("打开文件超时（主线程繁忙），请稍后重试。");

    ToolResult result =
        new FileOpenTool()
            .execute(
                new JSONObject().put("file_path", "A.java").put("line", 1),
                contextWith(port, dir.getAbsolutePath()));

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("超时"), result.getContent());
  }

  // ---------------------------------------------------------------- save_all

  @Test
  void saveAllFormatsSavedAndSkippedGroups(@TempDir File dir) throws Exception {
    FakePort port = new FakePort();
    port.saveReport =
        EditorSessionPort.SaveReport.of(
            Arrays.asList(
                new File(dir, "app/src/main/java/Foo.java").getAbsolutePath(),
                new File(dir, "build.gradle.kts").getAbsolutePath()),
            Collections.singletonList(new File(dir, "Bar.java").getAbsolutePath()));

    ToolResult result =
        new SaveAllTool().execute(new JSONObject(), contextWith(port, dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    String content = norm(result.getContent());
    assertTrue(content.contains("已保存 2 个文件"), content);
    assertTrue(content.contains("app/src/main/java/Foo.java"), content);
    assertTrue(content.contains("build.gradle.kts"), content);
    assertTrue(content.contains("被跳过"), content);
    assertTrue(content.contains("Bar.java"), content);
  }

  @Test
  void saveAllWithNothingToSaveSaysSo(@TempDir File dir) throws Exception {
    FakePort port = new FakePort();
    port.saveReport =
        EditorSessionPort.SaveReport.of(Collections.emptyList(), Collections.emptyList());

    ToolResult result =
        new SaveAllTool().execute(new JSONObject(), contextWith(port, dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("没有需要保存的文件"), result.getContent());
    assertEquals(1, port.saveCalls);
  }

  @Test
  void saveAllPropagatesTimeoutFailure(@TempDir File dir) throws Exception {
    FakePort port = new FakePort();
    port.saveReport = EditorSessionPort.SaveReport.failed("保存超时（超过 30 秒），可能仍有文件未保存");

    ToolResult result =
        new SaveAllTool().execute(new JSONObject(), contextWith(port, dir.getAbsolutePath()));

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("超时"), result.getContent());
  }

  // ---------------------------------------------------------------- 元数据判定

  @Test
  void namesMatchToolNamesConstants() {
    assertEquals(ToolNames.EDITOR_STATE, new EditorStateTool().getName());
    assertEquals(ToolNames.FILE_OPEN, new FileOpenTool().getName());
    assertEquals(ToolNames.SAVE_ALL, new SaveAllTool().getName());
    assertNotEquals(ToolNames.EDITOR_STATE, ToolNames.FILE_OPEN);
  }

  @Test
  void categoryAndReadonlyAndConcurrencyFlagsMatchDesign() {
    EditorStateTool state = new EditorStateTool();
    assertEquals(ToolCategory.READ, state.getCategory());
    assertTrue(state.isAllowedInReadonlyMode());
    assertTrue(state.isConcurrencySafe());
    assertFalse(state.needsConfirmation());
    assertFalse(state.shouldRecordDiff());

    FileOpenTool open = new FileOpenTool();
    assertEquals(ToolCategory.READ, open.getCategory());
    assertTrue(open.isAllowedInReadonlyMode(), "只改视图状态，只读模式应放行");
    assertFalse(open.isConcurrencySafe(), "改共享视图状态，必须串行");

    SaveAllTool save = new SaveAllTool();
    assertEquals(ToolCategory.SYSTEM, save.getCategory());
    assertFalse(save.isAllowedInReadonlyMode(), "写磁盘，只读模式必须拦截");
    assertFalse(save.isConcurrencySafe());
    assertFalse(save.needsConfirmation());
    assertFalse(save.shouldRecordDiff(), "内容是用户改的，记 diff 会污染回滚语义");
  }

  // ---------------------------------------------------------------- 格式化纯函数

  @Test
  void messagesFormatSaveReportWithoutContextDependency(@TempDir File dir) {
    ToolContext context = ToolContext.builder().homePath(dir.getAbsolutePath()).build();

    String none =
        EditorStateMessages.formatSaveReport(
            context,
            EditorSessionPort.SaveReport.of(Collections.emptyList(), Collections.emptyList()));
    assertTrue(none.contains("没有需要保存的文件"), none);

    String mixed =
        EditorStateMessages.formatSaveReport(
            context,
            EditorSessionPort.SaveReport.of(
                Collections.singletonList(new File(dir, "A.java").getAbsolutePath()),
                Collections.singletonList(new File(dir, "B.java").getAbsolutePath())));
    assertTrue(mixed.contains("已保存 1 个文件"), mixed);
    assertTrue(mixed.contains("- A.java"), mixed);
    assertTrue(mixed.contains("- B.java"), mixed);
    assertTrue(mixed.contains("被跳过"), mixed);
  }

  @Test
  void messagesFallBackToAbsolutePathOutsideWorkspace(@TempDir File dir) {
    ToolContext context = ToolContext.builder().homePath(new File(dir, "workspace").getAbsolutePath()).build();
    String outside = new File(dir, "elsewhere/C.java").getAbsolutePath();

    String text =
        EditorStateMessages.formatSaveReport(
            context, EditorSessionPort.SaveReport.of(Collections.singletonList(outside),
                Collections.emptyList()));
    assertTrue(text.contains(outside), "工作区外的文件应展示绝对路径：" + text);
  }
}
