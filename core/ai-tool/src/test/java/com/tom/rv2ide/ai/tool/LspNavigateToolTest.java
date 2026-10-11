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

import com.tom.rv2ide.ai.tool.LspNavigationPort.Location;
import com.tom.rv2ide.ai.tool.LspNavigationPort.Result;
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
 * {@link LspNavigateTool}：降级、1 基→0 基换算、空结果与失败的区分、格式化。
 *
 * <p>最要紧的是**「查到但没有结果」不能被说成「查不了」**——否则模型会以为工具坏了。
 */
class LspNavigateToolTest {

  /** 记录收到参数的假端口。 */
  private static final class FakePort implements LspNavigationPort {
    LspNavigationPort.Result result = Result.of(Collections.emptyList());
    boolean available = true;
    String reason = "";
    String lastPath;
    int lastLine;
    int lastColumn;
    Kind lastKind;
    long lastTimeout;

    @Override
    public Result navigate(String filePath, int line, int column, Kind kind, long timeoutMs) {
      lastPath = filePath;
      lastLine = line;
      lastColumn = column;
      lastKind = kind;
      lastTimeout = timeoutMs;
      return result;
    }

    @Override
    public boolean isSupported(String filePath) {
      return filePath.endsWith(".java") || filePath.endsWith(".kt");
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

  private static ToolContext ctx(FakePort port, String home) {
    return ToolContext.builder().homePath(home).lspNavigation(port).build();
  }

  private static void touch(File file) throws Exception {
    Files.write(file.toPath(), "class A {}".getBytes(StandardCharsets.UTF_8));
  }

  private static JSONObject args(String path, int line) throws Exception {
    return new JSONObject().put("file_path", path).put("line", line);
  }

  @Test
  void reportsExplicitErrorWhenPortMissing() throws Exception {
    ToolResult r =
        new LspNavigateTool()
            .execute(new JSONObject().put("file_path", "A.java").put("line", 1),
                ToolContext.builder().homePath(".").build());
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("未接入"), r.getContent());
  }

  @Test
  void reportsUnavailableReason(@TempDir File dir) throws Exception {
    touch(new File(dir, "A.java"));
    FakePort port = new FakePort();
    port.available = false;
    port.reason = "当前没有打开的项目";
    ToolResult r = new LspNavigateTool().execute(args("A.java", 1), ctx(port, dir.getAbsolutePath()));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("没有打开的项目"), r.getContent());
  }

  @Test
  void convertsOneBasedLineColumnToZeroBased(@TempDir File dir) throws Exception {
    touch(new File(dir, "A.java"));
    FakePort port = new FakePort();
    port.result = Result.of(Collections.singletonList(new Location("x/A.java", 9, 4)));

    new LspNavigateTool()
        .execute(
            new JSONObject().put("file_path", "A.java").put("line", 12).put("column", 5),
            ctx(port, dir.getAbsolutePath()));

    // 模型给 1 基（12,5）→ 端口收到 0 基（11,4）
    assertEquals(11, port.lastLine);
    assertEquals(4, port.lastColumn);
  }

  @Test
  void formatsResultsWithOneBasedPositions(@TempDir File dir) throws Exception {
    touch(new File(dir, "A.java"));
    FakePort port = new FakePort();
    port.result =
        Result.of(
            Arrays.asList(
                new Location(new File(dir, "A.java").getAbsolutePath(), 9, 4),
                new Location(new File(dir, "B.java").getAbsolutePath(), 0, 0)));

    ToolResult r = new LspNavigateTool().execute(args("A.java", 12), ctx(port, dir.getAbsolutePath()));

    assertFalse(r.isError());
    assertTrue(r.getContent().contains("找到 2 处定义"), r.getContent());
    // 展示为 1 基：A.java:10:5
    assertTrue(r.getContent().contains("A.java:10:5"), r.getContent());
    assertTrue(r.getContent().contains("B.java:1:1"), r.getContent());
  }

  @Test
  void emptyResultIsSuccessNotFailure(@TempDir File dir) throws Exception {
    touch(new File(dir, "A.java"));
    FakePort port = new FakePort();
    port.result = Result.of(Collections.emptyList());

    ToolResult r = new LspNavigateTool().execute(args("A.java", 3), ctx(port, dir.getAbsolutePath()));

    assertFalse(r.isError(), "查到但没有结果不是错误");
    assertTrue(r.getContent().contains("没有找到"), r.getContent());
  }

  @Test
  void timeoutIsReportedWithRetryHint(@TempDir File dir) throws Exception {
    touch(new File(dir, "A.java"));
    FakePort port = new FakePort();
    port.result = Result.timedOut("导航超时（超过 20 秒）");

    ToolResult r = new LspNavigateTool().execute(args("A.java", 1), ctx(port, dir.getAbsolutePath()));

    assertTrue(r.isError());
    assertTrue(r.getContent().contains("稍后重试"), r.getContent());
  }

  @Test
  void referencesKindIsPassedThrough(@TempDir File dir) throws Exception {
    touch(new File(dir, "A.java"));
    FakePort port = new FakePort();
    port.result = Result.of(Collections.singletonList(new Location("A.java", 0, 0)));

    new LspNavigateTool()
        .execute(
            new JSONObject().put("file_path", "A.java").put("line", 1).put("kind", "references"),
            ctx(port, dir.getAbsolutePath()));

    assertEquals(LspNavigationPort.Kind.REFERENCES, port.lastKind);
  }

  @Test
  void rejectsNonPositiveLine(@TempDir File dir) throws Exception {
    touch(new File(dir, "A.java"));
    ToolResult r =
        new LspNavigateTool().execute(args("A.java", 0), ctx(new FakePort(), dir.getAbsolutePath()));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("从 1 开始"), r.getContent());
  }

  @Test
  void rejectsUnsupportedExtension(@TempDir File dir) throws Exception {
    File xml = new File(dir, "layout.xml");
    Files.write(xml.toPath(), "<x/>".getBytes(StandardCharsets.UTF_8));
    ToolResult r =
        new LspNavigateTool().execute(args("layout.xml", 1), ctx(new FakePort(), dir.getAbsolutePath()));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("无法导航"), r.getContent());
  }

  @Test
  void rejectsBadKind(@TempDir File dir) throws Exception {
    touch(new File(dir, "A.java"));
    ToolResult r =
        new LspNavigateTool()
            .execute(
                new JSONObject().put("file_path", "A.java").put("line", 1).put("kind", "jump"),
                ctx(new FakePort(), dir.getAbsolutePath()));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("kind"), r.getContent());
  }
}
