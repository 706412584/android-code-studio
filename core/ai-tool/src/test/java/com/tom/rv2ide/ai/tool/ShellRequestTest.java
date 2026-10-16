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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link ShellRequest} 的不可变派生与 {@link ShellRequest.ShellResult} 的展示文本。
 *
 * <p>派生（withCommand/withCwd）是 proot 装饰器改写命令的关键路径：取消信号与
 * stdin 必须原样带过去，丢了会让后台任务的 stop 失效（命令永远跑不完）。
 * toDisplayText 是模型实际读到的文本，退出码/截断/空输出三类边角都要钉住。
 */
final class ShellRequestTest {

  // ---- 构造与默认值 ----

  @Test
  void nullCommandAndCwdBecomeEmptyStrings() {
    ShellRequest request = new ShellRequest(null, null, 5_000, null);
    assertEquals("", request.getCommand());
    assertEquals("", request.getCwd());
    assertEquals(5_000, request.getTimeoutMs());
    assertNull(request.getStdin());
  }

  @Test
  void nonPositiveTimeoutFallsBackToDefault() {
    // 0 与负数都必须回落到默认，否则后端会拿到一个立即超时的请求。
    assertEquals(ShellRequest.DEFAULT_TIMEOUT_MS,
        new ShellRequest("x", "", 0, null).getTimeoutMs());
    assertEquals(ShellRequest.DEFAULT_TIMEOUT_MS,
        new ShellRequest("x", "", -1, null).getTimeoutMs());
  }

  @Test
  void ofUsesDefaults() {
    ShellRequest request = ShellRequest.of("ls");
    assertEquals("ls", request.getCommand());
    assertEquals("", request.getCwd());
    assertEquals(ShellRequest.DEFAULT_TIMEOUT_MS, request.getTimeoutMs());
    assertNull(request.getStdin());
    assertNull(request.getCancellation());
  }

  // ---- 取消信号 ----

  @Test
  void isCancellationRequestedIsFalseWithoutSignal() {
    assertFalse(ShellRequest.of("x").isCancellationRequested());
  }

  @Test
  void isCancellationRequestedReflectsSignalState() {
    AtomicInteger state = new AtomicInteger(0);
    ShellRequest request = new ShellRequest(
        "x", "", 1_000, null, () -> state.get() == 1);
    assertFalse(request.isCancellationRequested());
    state.set(1);
    assertTrue(request.isCancellationRequested());
  }

  // ---- 派生 ----

  @Test
  void withCommandKeepsEverythingElseIncludingCancellationAndStdin() {
    ShellRequest.CancellationSignal signal = () -> true;
    ShellRequest original = new ShellRequest("orig", "/w", 9_000, "input", signal);

    ShellRequest derived = original.withCommand("proot orig");

    assertEquals("proot orig", derived.getCommand());
    assertEquals("/w", derived.getCwd());
    assertEquals(9_000, derived.getTimeoutMs());
    assertEquals("input", derived.getStdin());
    assertSame(signal, derived.getCancellation(), "派生必须保留取消信号");
    // 原对象不变。
    assertEquals("orig", original.getCommand());
  }

  @Test
  void withCwdKeepsEverythingElse() {
    ShellRequest.CancellationSignal signal = () -> false;
    ShellRequest original = new ShellRequest("cmd", "/old", 3_000, "in", signal);

    ShellRequest derived = original.withCwd("/new");

    assertEquals("cmd", derived.getCommand());
    assertEquals("/new", derived.getCwd());
    assertEquals(3_000, derived.getTimeoutMs());
    assertEquals("in", derived.getStdin());
    assertSame(signal, derived.getCancellation());
    assertEquals("/old", original.getCwd());
  }

  // ---- ShellResult ----

  @Test
  void resultFactoriesSetMutuallyExclusiveState() {
    ShellRequest.ShellResult success = ShellRequest.ShellResult.success("ok");
    assertEquals(0, success.getExitCode());
    assertEquals("ok", success.getStdout());
    assertTrue(success.isSuccess());
    assertFalse(success.isTimedOut());

    ShellRequest.ShellResult error = ShellRequest.ShellResult.error("boom");
    assertEquals(-1, error.getExitCode());
    assertEquals("boom", error.getStderr());
    assertFalse(error.isSuccess());

    ShellRequest.ShellResult timeout = ShellRequest.ShellResult.timeout();
    assertTrue(timeout.isTimedOut());
    assertFalse(timeout.isSuccess(), "超时不算成功");
  }

  @Test
  void nullStreamsNormalizeToEmpty() {
    ShellRequest.ShellResult result = new ShellRequest.ShellResult(1, null, null, false, false);
    assertEquals("", result.getStdout());
    assertEquals("", result.getStderr());
  }

  @Test
  void displayTextShowsExitCodeAndStreams() {
    String text = new ShellRequest.ShellResult(3, "out\n", "err\n", false, false).toDisplayText();
    assertTrue(text.contains("exit_code=3"), text);
    assertTrue(text.contains("--- stdout ---\nout\n"), text);
    assertTrue(text.contains("--- stderr ---\nerr\n"), text);
  }

  @Test
  void displayTextAddsMissingTrailingNewline() {
    // 输出不以换行结尾时必须补一个，否则下一个段落标题会粘在输出尾巴上。
    String text = new ShellRequest.ShellResult(0, "no-newline", "err-no-newline", false, false)
        .toDisplayText();
    assertTrue(text.contains("no-newline\n--- stderr ---"), text);
    assertTrue(text.endsWith("err-no-newline\n"), text);
  }

  @Test
  void displayTextMarksTimeoutAndTruncation() {
    String text = new ShellRequest.ShellResult(-1, "x", "", true, true).toDisplayText();
    assertTrue(text.contains("(命令超时被终止)"), text);
    assertTrue(text.contains("(输出过长，已截断)"), text);
  }

  @Test
  void displayTextSaysNoOutputWhenBothStreamsEmpty() {
    String text = new ShellRequest.ShellResult(0, "", "", false, false).toDisplayText();
    assertTrue(text.contains("(无输出)"), text);
    assertFalse(text.contains("--- stdout ---"), text);
  }

  // ---- idsOf ----

  private static ShellBackend backend(String id) {
    return new ShellBackend() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public String displayName() {
        return id;
      }

      @Override
      public boolean isAvailable() {
        return true;
      }

      @Override
      public String unavailableReason() {
        return "";
      }

      @Override
      public ShellRequest.ShellResult execute(
          ShellRequest request, ShellRequest.ShellOutputSink sink) {
        return ShellRequest.ShellResult.success("");
      }
    };
  }

  @Test
  void idsOfListsBackendIdsInOrderAndIsUnmodifiable() {
    List<String> ids = ShellRequest.idsOf(
        Arrays.asList(backend("a"), backend("b"), backend("c")));
    assertEquals(Arrays.asList("a", "b", "c"), ids);
    assertThrows(UnsupportedOperationException.class, () -> ids.add("d"));
  }

  @Test
  void idsOfToleratesNullAndEmpty() {
    assertEquals(Collections.emptyList(), ShellRequest.idsOf(null));
    assertEquals(Collections.emptyList(), ShellRequest.idsOf(Collections.emptyList()));
  }

  // ---- NOOP sink ----

  @Test
  void noopSinkSwallowsOutput() {
    // 只验证调用不抛——契约就是「什么都不做」。
    ShellRequest.ShellOutputSink.NOOP.onOutput("anything");
  }
}
