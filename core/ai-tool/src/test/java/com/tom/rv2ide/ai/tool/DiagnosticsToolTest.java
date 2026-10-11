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

import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link DiagnosticsTool} 的行为：降级、路径校验、过滤、目录递归、截断。
 *
 * <p>重点是**不可用与空诊断必须可区分**——语言服务器拉不到结果时报失败，
 * 而不是返回空列表（后者会被模型读成「代码没问题」）。
 */
class DiagnosticsToolTest {

  /** 按路径返回预置结果的假端口；记录调用过的路径。 */
  private static final class FakePort implements DiagnosticsPort {
    final Map<String, Report> byPath = new LinkedHashMap<>();
    final List<String> calls = new ArrayList<>();
    boolean available = true;
    String reason = "";

    @Override
    public Report analyze(String absolutePath, long timeoutMs) {
      calls.add(absolutePath);
      Report report = byPath.get(new File(absolutePath).getName());
      return report == null ? Report.of(Collections.emptyList()) : report;
    }

    @Override
    public boolean isSupported(String filePath) {
      String name = new File(filePath).getName();
      int dot = name.lastIndexOf('.');
      String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.US);
      return "java".equals(ext) || "kt".equals(ext) || "kts".equals(ext);
    }

    @Override
    public boolean isAvailable() {
      return available;
    }

    @Override
    public String unavailableReason() {
      return reason;
    }
  }

  private static ToolContext contextWith(DiagnosticsPort port, String home) {
    return ToolContext.builder().homePath(home).diagnostics(port).build();
  }

  private static DiagnosticsPort.Item item(int line, int col, int severity, String message) {
    return new DiagnosticsPort.Item(line, col, severity, message, "");
  }

  @Test
  void reportsExplicitErrorWhenPortMissing() throws Exception {
    ToolResult result =
        new DiagnosticsTool()
            .execute(new JSONObject().put("path", "A.java"), ToolContext.builder().homePath(".").build());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("语言服务器"));
  }

  @Test
  void reportsUnavailableReasonWhenServerNotStarted(@TempDir File dir) throws Exception {
    FakePort port = new FakePort();
    port.available = false;
    port.reason = "当前没有打开的项目";

    ToolResult result =
        new DiagnosticsTool()
            .execute(new JSONObject().put("path", "A.java"), contextWith(port, dir.getAbsolutePath()));

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("没有打开的项目"));
    assertTrue(port.calls.isEmpty(), "不可用时不应对文件做分析");
  }

  @Test
  void noUpdateSentinelIsReportedAsFailureNotClean(@TempDir File dir) throws Exception {
    File file = new File(dir, "A.java");
    Files.write(file.toPath(), "class A {}".getBytes(StandardCharsets.UTF_8));

    FakePort port = new FakePort();
    // 语言服务器「分析不了」的信号：显式 failed，而不是空诊断列表。
    port.byPath.put("A.java", DiagnosticsPort.Report.failed("文件不属于任何模块"));

    ToolResult result =
        new DiagnosticsTool()
            .execute(new JSONObject().put("path", "A.java"), contextWith(port, dir.getAbsolutePath()));

    // 单文件失败：无诊断但失败原因必须可见，绝不能报「未发现问题」。
    assertFalse(result.isError(), "单文件失败不致命，仍返回成功结果");
    assertTrue(result.getContent().contains("不属于任何模块"), result.getContent());
    assertTrue(result.getContent().contains("未完成"), result.getContent());
  }

  @Test
  void formatsItemsWithOneBasedLineAndSeverity(@TempDir File dir) throws Exception {
    File file = new File(dir, "A.java");
    Files.write(file.toPath(), "class A {}".getBytes(StandardCharsets.UTF_8));

    FakePort port = new FakePort();
    port.byPath.put(
        "A.java",
        DiagnosticsPort.Report.of(
            Arrays.asList(
                item(11, 4, 1, "cannot find symbol"), item(2, 0, 2, "unused import"))));

    ToolResult result =
        new DiagnosticsTool()
            .execute(new JSONObject().put("path", "A.java"), contextWith(port, dir.getAbsolutePath()));

    assertFalse(result.isError());
    String content = result.getContent();
    // 行号从 1 开始展示（LSP 是 0 基）：line=11 → 12:5
    assertTrue(content.contains("12:5 [ERROR] cannot find symbol"), content);
    assertTrue(content.contains("3:1 [WARNING] unused import"), content);
    // 更严重的排前面
    assertTrue(content.indexOf("ERROR") < content.indexOf("WARNING"), content);
  }

  @Test
  void minSeverityErrorFiltersOutWarnings(@TempDir File dir) throws Exception {
    File file = new File(dir, "A.java");
    Files.write(file.toPath(), "class A {}".getBytes(StandardCharsets.UTF_8));

    FakePort port = new FakePort();
    port.byPath.put(
        "A.java",
        DiagnosticsPort.Report.of(
            Arrays.asList(item(0, 0, 1, "boom"), item(1, 0, 2, "meh"))));

    ToolResult result =
        new DiagnosticsTool()
            .execute(
                new JSONObject().put("path", "A.java").put("min_severity", "error"),
                contextWith(port, dir.getAbsolutePath()));

    assertTrue(result.getContent().contains("boom"), result.getContent());
    assertFalse(result.getContent().contains("meh"), result.getContent());
  }

  @Test
  void cleanFileReportsNoDiagnostics(@TempDir File dir) throws Exception {
    File file = new File(dir, "A.java");
    Files.write(file.toPath(), "class A {}".getBytes(StandardCharsets.UTF_8));

    FakePort port = new FakePort();
    port.byPath.put("A.java", DiagnosticsPort.Report.of(Collections.emptyList()));

    ToolResult result =
        new DiagnosticsTool()
            .execute(new JSONObject().put("path", "A.java"), contextWith(port, dir.getAbsolutePath()));

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("未发现"), result.getContent());
  }

  @Test
  void unsupportedExtensionIsRejected(@TempDir File dir) throws Exception {
    File file = new File(dir, "app.iml");
    Files.write(file.toPath(), "x".getBytes(StandardCharsets.UTF_8));

    FakePort port = new FakePort();
    ToolResult result =
        new DiagnosticsTool()
            .execute(new JSONObject().put("path", "app.iml"), contextWith(port, dir.getAbsolutePath()));

    assertTrue(result.isError());
    assertTrue(result.getContent().contains(".java"), result.getContent());
    assertTrue(port.calls.isEmpty());
  }

  @Test
  void directoryModeRecursesAndSkipsBuildDirs(@TempDir File dir) throws Exception {
    File src = new File(dir, "src");
    assertTrue(new File(src, "pkg").mkdirs());
    assertTrue(new File(dir, "build").mkdirs());
    Files.write(new File(src, "A.java").toPath(), "class A {}".getBytes(StandardCharsets.UTF_8));
    Files.write(new File(src, "pkg/B.kt").toPath(), "class B".getBytes(StandardCharsets.UTF_8));
    // 构建产物目录里的文件必须被跳过
    Files.write(new File(dir, "build/C.java").toPath(), "class C {}".getBytes(StandardCharsets.UTF_8));
    // 不受支持的扩展名也跳过
    Files.write(new File(src, "notes.txt").toPath(), "hi".getBytes(StandardCharsets.UTF_8));

    FakePort port = new FakePort();
    port.byPath.put("A.java", DiagnosticsPort.Report.of(Collections.singletonList(item(0, 0, 1, "eA"))));
    port.byPath.put("B.kt", DiagnosticsPort.Report.of(Collections.singletonList(item(0, 0, 1, "eB"))));
    port.byPath.put("C.java", DiagnosticsPort.Report.of(Collections.singletonList(item(0, 0, 1, "eC"))));

    ToolResult result =
        new DiagnosticsTool()
            .execute(new JSONObject().put("path", "."), contextWith(port, dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    assertEquals(2, port.calls.size(), "只应分析 src 下的 A.java 与 B.kt：" + port.calls);
    assertTrue(result.getContent().contains("eA"), result.getContent());
    assertTrue(result.getContent().contains("eB"), result.getContent());
    assertFalse(result.getContent().contains("eC"), "build/ 下的文件不应被分析");
  }

  @Test
  void maxItemsTruncatesAndSaysSo(@TempDir File dir) throws Exception {
    File file = new File(dir, "A.java");
    Files.write(file.toPath(), "class A {}".getBytes(StandardCharsets.UTF_8));

    List<DiagnosticsPort.Item> many = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      many.add(item(i, 0, 1, "err" + i));
    }
    FakePort port = new FakePort();
    port.byPath.put("A.java", DiagnosticsPort.Report.of(many));

    ToolResult result =
        new DiagnosticsTool()
            .execute(
                new JSONObject().put("path", "A.java").put("max_items", 3),
                contextWith(port, dir.getAbsolutePath()));

    assertTrue(result.getContent().contains("err0"), result.getContent());
    assertFalse(result.getContent().contains("err5"), result.getContent());
    assertTrue(result.getContent().contains("截断"), result.getContent());
  }

  @Test
  void pathOutsideWorkspaceIsRejected(@TempDir File dir) throws Exception {
    FakePort port = new FakePort();
    ToolResult result =
        new DiagnosticsTool()
            .execute(
                new JSONObject().put("path", "../../etc/passwd.java"),
                contextWith(port, new File(dir, "project").getAbsolutePath()));

    assertTrue(result.isError());
    assertTrue(port.calls.isEmpty());
  }

  @Test
  void parsesSeverityNamesCaseInsensitively() {
    assertEquals(1, DiagnosticsMessages.parseSeverity("Error"));
    assertEquals(1, DiagnosticsMessages.parseSeverity(" e "));
    assertEquals(2, DiagnosticsMessages.parseSeverity("warning"));
    assertEquals(3, DiagnosticsMessages.parseSeverity("INFO"));
    assertEquals(4, DiagnosticsMessages.parseSeverity("hint"));
    assertEquals(2, DiagnosticsMessages.parseSeverity("garbage"));
  }
}
