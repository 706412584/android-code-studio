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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link RootShellBackend} 的探测、缓存与执行语义。
 *
 * <p>测试不真的调用 {@code su}（CI 与开发机都没有），而是把「起进程」这一步
 * 替换成本机 shell。这样探测逻辑、输出收集、超时与取消都是真实路径，只有
 * 可执行文件本身是假的。
 */
final class RootShellBackendTest {

  /** 跨平台的 {@code sh -c} 包装。 */
  private static Process shell(String command) throws IOException {
    boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    return windows
        ? new ProcessBuilder("cmd", "/c", command).start()
        : new ProcessBuilder("/bin/sh", "-c", command).start();
  }

  /**
   * 把 {@code su -c <cmd>} 映射到本机 shell。
   *
   * <p>记录调用次数，供缓存测试断言「没有重复探测」。
   */
  private static class ShellStarter implements RootShellBackend.ProcessStarter {
    final AtomicInteger calls = new AtomicInteger();
    private final String probeOutput;
    private final int probeExit;

    ShellStarter(String probeOutput, int probeExit) {
      this.probeOutput = probeOutput;
      this.probeExit = probeExit;
    }

    @Override
    public Process start(List<String> argv, String cwd, String stdin) throws IOException {
      calls.incrementAndGet();
      String command = argv.get(argv.size() - 1);
      if ("id -u".equals(command)) {
        // 用 echo 模拟 su 的输出与退出码
        String script = "echo " + probeOutput;
        if (probeExit != 0) {
          script = "exit " + probeExit;
        }
        return shell(script);
      }
      return shell(command);
    }
  }

  private static RootShellBackend backend(
      List<String> candidates, RootShellBackend.ProcessStarter starter) {
    return new RootShellBackend(candidates, starter, 5_000L, 60_000L, 10_000L);
  }

  @Test
  void parseUidAcceptsPlainAndLeadingNoise() {
    assertEquals(0, RootShellBackend.parseUid("0\n"));
    assertEquals(0, RootShellBackend.parseUid(" 0 "));
    assertEquals(1000, RootShellBackend.parseUid("1000"));
    assertEquals(0, RootShellBackend.parseUid("some warning\n0\n"));
    assertEquals(-1, RootShellBackend.parseUid(""));
    assertEquals(-1, RootShellBackend.parseUid(null));
    assertEquals(-1, RootShellBackend.parseUid("not-a-number"));
  }

  @Test
  void argvWrapsCommandInSuDashC() {
    assertEquals(Arrays.asList("su", "-c", "id -u"), RootShellBackend.argv("su", "id -u"));
  }

  @Test
  void probeSucceedsWhenSuPrintsZero() {
    ShellStarter starter = new ShellStarter("0", 0);
    RootShellBackend backend = backend(Arrays.asList("su"), starter);

    assertTrue(backend.isAvailable());
    assertTrue(backend.hasAdbPrivileges(), "root 是 adb 权限的超集");
    assertTrue(backend.hasRootPrivileges());
    assertFalse(backend.hasCliToolchain());
    assertEquals("", backend.unavailableReason());
    assertEquals("su", backend.resolvedSuPath());
  }

  @Test
  void probeFailsWhenSuExitsNonZero() {
    ShellStarter starter = new ShellStarter("0", 1);
    RootShellBackend backend = backend(Arrays.asList("su"), starter);

    assertFalse(backend.isAvailable());
    assertTrue(backend.unavailableReason().contains("未获得 root 权限"));
    assertEquals("", backend.resolvedSuPath());
  }

  @Test
  void probeFailsWhenSuPrintsNonZeroUid() {
    // su 可用但返回 uid 2000（例如某些「伪 su」）：不能当作 root 后端。
    RootShellBackend backend = backend(Arrays.asList("su"), new ShellStarter("2000", 0));

    assertFalse(backend.isAvailable());
  }

  @Test
  void probeTriesCandidatesInOrderUntilOneWorks() {
    ShellStarter starter =
        new ShellStarter("0", 0) {
          @Override
          public Process start(List<String> argv, String cwd, String stdin) throws IOException {
            // 第一个候选路径不可执行，模拟 /sbin/su 在 app 域被拒绝
            if ("/sbin/su".equals(argv.get(0))) {
              calls.incrementAndGet();
              throw new IOException("Permission denied");
            }
            return super.start(argv, cwd, stdin);
          }
        };
    RootShellBackend backend =
        backend(Arrays.asList("/sbin/su", "/system/bin/su"), starter);

    assertTrue(backend.isAvailable());
    assertEquals("/system/bin/su", backend.resolvedSuPath());
  }

  @Test
  void probeFailureReasonKeepsEveryCandidate() {
    // 回归（真机实测）：只保留最后一个候选的错误时，真正的原因（PATH 里的 su 被
    // Magisk 拒绝 / 被 Shamiko 隐藏）会被最后那个 /data/adb/magisk/su 的
    // Permission denied 盖掉，用户照提示排查会走错方向。
    RootShellBackend.ProcessStarter starter =
        new RootShellBackend.ProcessStarter() {
          @Override
          public Process start(List<String> argv, String cwd, String stdin) throws IOException {
            if ("/sbin/su".equals(argv.get(0))) {
              throw new IOException("Permission denied");
            }
            throw new IOException("No such file or directory");
          }
        };
    RootShellBackend backend =
        new RootShellBackend(
            Arrays.asList("/sbin/su", "/data/adb/magisk/su"), starter, 5_000L, 60_000L, 10_000L);

    assertFalse(backend.isAvailable());
    String reason = backend.unavailableReason();
    assertTrue(reason.contains("/sbin/su"), reason);
    assertTrue(reason.contains("/data/adb/magisk/su"), reason);
    assertTrue(reason.contains("Shamiko"), "应提示隐藏 root 的模块，这是最常见的真机原因");
  }

  @Test
  void successfulProbeIsCached() {
    ShellStarter starter = new ShellStarter("0", 0);
    RootShellBackend backend = backend(Arrays.asList("su"), starter);

    assertTrue(backend.isAvailable());
    int afterFirst = starter.calls.get();
    assertTrue(backend.isAvailable());
    assertTrue(backend.isAvailable());
    assertEquals(afterFirst, starter.calls.get(), "成功结果应命中缓存，不重复起 su");
  }

  @Test
  void invalidateCacheForcesReprobe() {
    ShellStarter starter = new ShellStarter("0", 0);
    RootShellBackend backend = backend(Arrays.asList("su"), starter);

    assertTrue(backend.isAvailable());
    int afterFirst = starter.calls.get();
    backend.invalidateCache();
    assertTrue(backend.isAvailable());
    assertEquals(afterFirst + 1, starter.calls.get());
  }

  @Test
  void invalidateCacheLeavesConsistentState() {
    // 回归：invalidateCache 曾在锁外只置 probeAtMs/resolvedSu，与进行中的 probe 交错时
    // 可能出现 probeResult=true 但 resolvedSu=null，随后 execute 把 null 交给
    // ProcessBuilder（NPE，且不在 execute 的 catch 里）。
    ShellStarter starter = new ShellStarter("0", 0);
    RootShellBackend backend = backend(Arrays.asList("su"), starter);
    assertTrue(backend.isAvailable());

    backend.invalidateCache();

    // 失效后应重新探测，并恢复到一致的可用状态
    assertTrue(backend.isAvailable());
    assertEquals("su", backend.resolvedSuPath());
  }

  @Test
  void isPresentDoesNotForkForMissingAbsoluteCandidates() {
    // 廉价判断：只查文件存在，不启动 su（否则每次生成提示词都会弹授权框）。
    ShellStarter starter = new ShellStarter("0", 0);
    RootShellBackend backend =
        backend(Arrays.asList("/definitely/not/here/su", "/also/missing/su"), starter);

    assertFalse(backend.isPresent());
    assertEquals(0, starter.calls.get(), "isPresent 不应起进程");
  }

  @Test
  void isPresentIsTrueForExistingAbsoluteCandidate() throws IOException {
    java.io.File fake = java.io.File.createTempFile("fake-su", ".bin");
    fake.deleteOnExit();
    ShellStarter starter = new ShellStarter("0", 0);
    RootShellBackend backend = backend(Arrays.asList(fake.getAbsolutePath()), starter);

    assertTrue(backend.isPresent());
    assertEquals(0, starter.calls.get(), "isPresent 不应起进程");
  }

  @Test
  void executeTimeoutBudgetIncludesProbeCost() {
    // 回归：探测可能耗时（首次会弹 Magisk 授权框），而 deadline 原先在探测**之后**
    // 才起算，于是模型传入的 timeoutMs 完全覆盖不到探测，单次调用实际耗时远超预期。
    ShellStarter starter = new ShellStarter("0", 0);
    RootShellBackend backend = backend(Arrays.asList("su"), starter);
    assertTrue(backend.isAvailable()); // 先探测一次，后续 execute 命中缓存

    long started = System.currentTimeMillis();
    ShellRequest.ShellResult result =
        backend.execute(
            new ShellRequest("echo quick", "", 5_000L, null),
            ShellRequest.ShellOutputSink.NOOP);
    long elapsed = System.currentTimeMillis() - started;

    assertEquals(0, result.getExitCode());
    assertTrue(elapsed < 5_000L, "执行不应超过给定预算，实际 " + elapsed + "ms");
  }

  @Test
  void failureProbeIsCachedAfterCompletion() {
    // 回归：probeAtMs 曾在探测**开始**时写入。单候选超时 10s、候选最多 6 个，
    // 一次慢失败刚结束缓存就已过期，下一次调用立刻重新 fork。改为结束时写。
    ShellStarter starter = new ShellStarter("0", 1);
    RootShellBackend backend = backend(Arrays.asList("su"), starter);

    assertFalse(backend.isAvailable());
    int afterFirst = starter.calls.get();
    assertFalse(backend.isAvailable());
    assertFalse(backend.isAvailable());
    assertEquals(afterFirst, starter.calls.get(), "失败结果也应在 TTL 内命中缓存");
  }

  @Test
  void executeReturnsStdoutAndExitCode() {
    RootShellBackend backend = backend(Arrays.asList("su"), new ShellStarter("0", 0));

    ShellRequest.ShellResult result =
        backend.execute(
            new ShellRequest("echo hello-root", "", 5_000L, null),
            ShellRequest.ShellOutputSink.NOOP);

    assertEquals(0, result.getExitCode());
    assertTrue(result.getStdout().contains("hello-root"), result.getStdout());
    assertFalse(result.isTimedOut());
  }

  @Test
  void executeCapturesStderrSeparately() {
    boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    String command = windows ? "echo boom 1>&2" : "echo boom 1>&2";
    RootShellBackend backend = backend(Arrays.asList("su"), new ShellStarter("0", 0));

    ShellRequest.ShellResult result =
        backend.execute(new ShellRequest(command, "", 5_000L, null),
            ShellRequest.ShellOutputSink.NOOP);

    assertTrue(result.getStderr().contains("boom"), result.getStderr());
    assertTrue(result.getStdout().trim().isEmpty(), result.getStdout());
  }

  @Test
  void executeReportsTimeout() {
    boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    String slow = windows ? "ping -n 20 127.0.0.1 > nul" : "sleep 5";
    RootShellBackend backend = backend(Arrays.asList("su"), new ShellStarter("0", 0));

    long started = System.currentTimeMillis();
    ShellRequest.ShellResult result =
        backend.execute(new ShellRequest(slow, "", 300L, null),
            ShellRequest.ShellOutputSink.NOOP);
    long elapsed = System.currentTimeMillis() - started;

    assertTrue(result.isTimedOut());
    assertEquals(-1, result.getExitCode());
    assertTrue(elapsed < 4_000L, "超时后应立即返回，实际耗时 " + elapsed + "ms");
  }

  @Test
  void executeHonoursCancellation() {
    RootShellBackend backend = backend(Arrays.asList("su"), new ShellStarter("0", 0));
    ShellRequest request =
        new ShellRequest("echo never", "", 30_000L, null, () -> true);

    long started = System.currentTimeMillis();
    ShellRequest.ShellResult result = backend.execute(request, ShellRequest.ShellOutputSink.NOOP);
    long elapsed = System.currentTimeMillis() - started;

    assertTrue(result.isTimedOut(), "取消按超时语义返回（调用方据此区分已停止）");
    assertTrue(elapsed < 2_000L, "取消应快速生效，实际耗时 " + elapsed + "ms");
  }

  @Test
  void executeRefusesWhenUnavailable() {
    RootShellBackend backend = backend(Arrays.asList("su"), new ShellStarter("0", 1));

    ShellRequest.ShellResult result =
        backend.execute(ShellRequest.of("id"), ShellRequest.ShellOutputSink.NOOP);

    assertEquals(-1, result.getExitCode());
    assertTrue(result.getStderr().contains("未获得 root 权限"), result.getStderr());
  }

  @Test
  void executeRejectsEmptyCommand() {
    RootShellBackend backend = backend(Arrays.asList("su"), new ShellStarter("0", 0));

    ShellRequest.ShellResult result =
        backend.execute(new ShellRequest("   ", "", 1_000L, null),
            ShellRequest.ShellOutputSink.NOOP);

    assertEquals(-1, result.getExitCode());
    assertNotNull(result.getStderr());
  }

  @Test
  void streamingSinkReceivesStdout() {
    RootShellBackend backend = backend(Arrays.asList("su"), new ShellStarter("0", 0));
    StringBuilder seen = new StringBuilder();

    backend.execute(
        new ShellRequest("echo streamed", "", 5_000L, null),
        seen::append);

    assertTrue(seen.toString().contains("streamed"), seen.toString());
  }
}
