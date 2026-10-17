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

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link FileDiagnostics} 搭车诊断的取舍：只有「有诊断」才附注，
 * 不可用/失败/超时/无问题一律返回空串（不往编辑结果里塞噪音）。
 */
class FileDiagnosticsTest {

  /** 可编排的诊断端口。 */
  private static final class FakePort implements DiagnosticsPort {
    boolean available = true;
    boolean supported = true;
    Report report = Report.of(new ArrayList<>());
    String lastPath;
    long lastTimeout = -1;
    Throwable thrown;

    @Override
    public Report analyze(String absolutePath, long timeoutMs) {
      lastPath = absolutePath;
      lastTimeout = timeoutMs;
      if (thrown instanceof RuntimeException) {
        throw (RuntimeException) thrown;
      }
      if (thrown instanceof Error) {
        throw (Error) thrown;
      }
      return report;
    }

    @Override
    public boolean isSupported(String filePath) {
      return supported;
    }

    @Override
    public boolean isAvailable() {
      return available;
    }

    @Override
    public String unavailableReason() {
      return "";
    }
  }

  private static DiagnosticsPort.Item item(int line, int severity, String message) {
    return new DiagnosticsPort.Item(line, 0, severity, message, "code");
  }

  private static ToolContext context(String home, DiagnosticsPort port) {
    return ToolContext.builder().homePath(home).diagnostics(port).build();
  }

  @Test
  void nullContextYieldsNoNote(@TempDir File dir) {
    assertEquals("", FileDiagnostics.describe(null, new File(dir, "A.java")));
  }

  @Test
  void unavailablePortYieldsNoNote(@TempDir File dir) {
    FakePort port = new FakePort();
    port.available = false;
    assertEquals(
        "", FileDiagnostics.describe(context(dir.getAbsolutePath(), port), new File(dir, "A.java")));
  }

  @Test
  void nullPortYieldsNoNote(@TempDir File dir) {
    assertEquals(
        "",
        FileDiagnostics.describe(
            ToolContext.builder().homePath(dir.getAbsolutePath()).build(), new File(dir, "A.java")));
  }

  @Test
  void unsupportedFileIsNotAnalyzed(@TempDir File dir) {
    FakePort port = new FakePort();
    port.supported = false;
    assertEquals(
        "", FileDiagnostics.describe(context(dir.getAbsolutePath(), port), new File(dir, "a.xml")));
    assertEquals(null, port.lastPath, "不支持的文件不应触发 analyze");
  }

  @Test
  void reportWithWarningsIsRendered(@TempDir File dir) throws Exception {
    File file = new File(dir, "A.java");
    Files.write(file.toPath(), new byte[] {1});
    FakePort port = new FakePort();
    port.report = DiagnosticsPort.Report.of(Arrays.asList(item(3, 2, "unused variable")));

    String note = FileDiagnostics.describe(context(dir.getAbsolutePath(), port), file);

    assertFalse(note.isEmpty(), "有诊断时必须附注");
    assertTrue(note.contains("A.java"), note);
    assertTrue(note.contains("unused variable"), note);
    assertTrue(note.contains("WARNING"), note);
    assertEquals(file.getAbsolutePath(), port.lastPath);
    assertEquals(FileDiagnostics.TIMEOUT_MS, port.lastTimeout, "必须使用搭车的短超时");
  }

  @Test
  void infoAndHintItemsAreFilteredOut(@TempDir File dir) {
    // 搭车诊断只报 warning 及以上：INFO/HINT 对「改坏没有」没有意义。
    FakePort port = new FakePort();
    port.report =
        DiagnosticsPort.Report.of(
            Arrays.asList(item(1, 3, "info only"), item(2, 4, "hint only")));
    assertEquals(
        "", FileDiagnostics.describe(context(dir.getAbsolutePath(), port), new File(dir, "A.java")));
  }

  @Test
  void emptyReportYieldsNoNote(@TempDir File dir) {
    FakePort port = new FakePort();
    port.report = DiagnosticsPort.Report.of(new ArrayList<>());
    assertEquals(
        "", FileDiagnostics.describe(context(dir.getAbsolutePath(), port), new File(dir, "A.java")));
  }

  @Test
  void unavailableAndFailedReportsYieldNoNote(@TempDir File dir) {
    FakePort port = new FakePort();
    port.report = DiagnosticsPort.Report.unavailable("no language server");
    assertEquals(
        "", FileDiagnostics.describe(context(dir.getAbsolutePath(), port), new File(dir, "A.java")));

    port.report = DiagnosticsPort.Report.failed("analysis crashed");
    assertEquals(
        "", FileDiagnostics.describe(context(dir.getAbsolutePath(), port), new File(dir, "A.java")));

    port.report = DiagnosticsPort.Report.timedOut("timeout");
    assertEquals(
        "", FileDiagnostics.describe(context(dir.getAbsolutePath(), port), new File(dir, "A.java")));

    port.report = null;
    assertEquals(
        "", FileDiagnostics.describe(context(dir.getAbsolutePath(), port), new File(dir, "A.java")));
  }

  @Test
  void anyThrowableIsSwallowedSoTheEditResultIsNotFlipped(@TempDir File dir) {
    // 搭车诊断绝不能让「已经写盘成功」的编辑变成错误结果。
    FakePort port = new FakePort();
    port.thrown = new IllegalStateException("boom");
    assertEquals(
        "", FileDiagnostics.describe(context(dir.getAbsolutePath(), port), new File(dir, "A.java")));

    // 连 Error 也必须吞掉（NoClassDefFoundError 之类）。
    FakePort erroring = new FakePort();
    erroring.thrown = new NoClassDefFoundError("missing");
    assertEquals(
        "",
        FileDiagnostics.describe(
            context(dir.getAbsolutePath(), erroring), new File(dir, "A.java")));
  }

  @Test
  void fileOutsideHomeFallsBackToItsName(@TempDir File dir) throws Exception {
    // displayPath 在路径越界时抛 IOException：此时至少要用文件名，不能整条附注丢失。
    File home = new File(dir, "home");
    assertTrue(home.mkdirs());
    File outside = new File(dir, "outside/A.java");
    outside.getParentFile().mkdirs();
    Files.write(outside.toPath(), new byte[] {1});
    FakePort port = new FakePort();
    port.report = DiagnosticsPort.Report.of(Arrays.asList(item(1, 1, "error here")));

    String note = FileDiagnostics.describe(context(home.getAbsolutePath(), port), outside);

    assertTrue(note.contains("A.java"), note);
    assertTrue(note.contains("error here"), note);
  }

  @Test
  void itemCountIsCappedAtMaxItems(@TempDir File dir) {
    List<DiagnosticsPort.Item> many = new ArrayList<>();
    for (int i = 0; i < FileDiagnostics.MAX_ITEMS + 5; i++) {
      many.add(item(i, 1, "error " + i));
    }
    FakePort port = new FakePort();
    port.report = DiagnosticsPort.Report.of(many);

    String note = FileDiagnostics.describe(context(dir.getAbsolutePath(), port), new File(dir, "A.java"));

    assertTrue(note.contains("error 0"), note);
    // 超出上限的条目不应逐条列出（block 只报数量）。
    assertFalse(note.contains("error " + (FileDiagnostics.MAX_ITEMS + 4)), note);
  }
}
