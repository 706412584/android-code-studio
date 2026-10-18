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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 通过 {@code su} 以 root（uid 0）身份执行命令。
 *
 * <p><b>为什么需要它</b>：{@link ShellBackend} 现有两个后端各有硬边界——
 * Termux 在 app 自身 uid 下执行（没有特权），Shizuku 依赖用户安装并授权
 * （而且它继承的是 Shizuku 服务端的身份，不保证是 root）。root 设备上
 * 很多调试动作（读其它应用私有目录、{@code pm} 的隐藏子命令、直接改系统属性）
 * 只有 uid 0 能做，前两个后端都给不了。
 *
 * <p><b>与 Shizuku-root 的区别</b>：Shizuku 以 root 启动时 {@code newProcess}
 * 确实继承 uid 0，但那是用户环境的巧合，不能作为能力承诺；本后端是显式的
 * 「我有 su」通道，可用性由 {@link #isAvailable()} 如实回答。
 *
 * <p><b>可用性探测有成本</b>：探测要起一个 {@code su} 进程，首次还会弹 Magisk
 * 授权框。因此结果带缓存（成功 60s / 失败 10s），避免每次 {@code shell_execute}
 * 都 fork 一次。
 *
 * <p>纯 Java、不依赖 Android，因此可在 JVM 上单测；进程创建与探测都可注入。
 */
public final class RootShellBackend implements ShellBackend {

  public static final String ID = "root";

  /** 单个流最多保留的字节数，防止 {@code cat /dev/urandom} 之类打爆内存。 */
  private static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;

  /** 等待循环的分片时长：每片之后检查一次取消信号。 */
  private static final long CANCEL_POLL_MS = 250L;

  /** 探测 su 的超时：Magisk 授权弹窗由用户操作，给足时间但仍要有上限。 */
  static final long DEFAULT_PROBE_TIMEOUT_MS = 10_000L;

  /**
   * 整轮探测的总预算。
   *
   * <p>单候选超时 10s、候选最多 6 个，无总预算时最坏 60s——足以把一次工具调用
   * 拖到用户以为卡死。总预算到期后直接放弃剩余候选并给出失败原因。
   */
  static final long DEFAULT_PROBE_BUDGET_MS = 15_000L;

  /** 探测成功后的缓存时长：root 状态极少变化。 */
  static final long DEFAULT_SUCCESS_TTL_MS = 60_000L;

  /**
   * 探测失败后的缓存时长。
   *
   * <p>用户可能刚在 Magisk 里点了允许，因此不宜过长；但也不能太短：一次失败探测
   * 本身就要尝试多个候选（每个最多 10s），10s 的缓存等于「每次调用都重新探测」，
   * 会让授权被拒的设备反复弹框。30s 是两者的折中。
   */
  static final long DEFAULT_FAILURE_TTL_MS = 30_000L;

  /** 常见 su 落点。Magisk 新版用 /debug_ramdisk，旧版在 /sbin。 */
  private static final List<String> DEFAULT_SU_CANDIDATES =
      Collections.unmodifiableList(
          Arrays.asList(
              "su",
              "/sbin/su",
              "/system/bin/su",
              "/system/xbin/su",
              "/debug_ramdisk/su",
              "/data/adb/magisk/su"));

  /** 起进程的抽象；测试用真实 {@code cmd /c} 或假进程替换。 */
  interface ProcessStarter {
    Process start(List<String> argv, String cwd, String stdin) throws IOException;
  }

  private static final ProcessStarter DEFAULT_STARTER =
      new ProcessStarter() {
        @Override
        public Process start(List<String> argv, String cwd, String stdin) throws IOException {
          ProcessBuilder builder = new ProcessBuilder(argv);
          if (cwd != null && !cwd.isEmpty()) {
            builder.directory(new File(cwd));
          }
          Process process = builder.start();
          if (stdin != null && !stdin.isEmpty()) {
            try (OutputStream out = process.getOutputStream()) {
              out.write(stdin.getBytes(StandardCharsets.UTF_8));
              out.flush();
            }
          } else {
            try {
              process.getOutputStream().close();
            } catch (IOException ignored) {
              // 子进程可能不读 stdin，提前退出
            }
          }
          return process;
        }
      };

  private final List<String> suCandidates;
  private final ProcessStarter starter;
  private final long probeTimeoutMs;
  private final long successTtlMs;
  private final long failureTtlMs;

  /** 已确认可用的 su 路径；null 表示尚未确认。 */
  private volatile String resolvedSu;

  private volatile boolean probeResult;
  private volatile long probeAtMs;
  private volatile String probeReason = "尚未探测";

  public RootShellBackend() {
    this(
        DEFAULT_SU_CANDIDATES,
        DEFAULT_STARTER,
        DEFAULT_PROBE_TIMEOUT_MS,
        DEFAULT_SUCCESS_TTL_MS,
        DEFAULT_FAILURE_TTL_MS);
  }

  /**
   * 进程级共享实例。
   *
   * <p><b>为什么需要它</b>：探测缓存是实例级的，而 app 层有三处各自构造注册表
   * （`AgentOrchestrator.buildShellBackends()`、`BackgroundShellRunner.newRegistry()`、
   * `PhoneShellRunner.defaultRegistry()`）。各建一个的话，root 用户每启动一个后台任务
   * 都会在新实例上重新 fork 一次 su——已确认的授权状态完全不复用。
   *
   * <p><b>为什么可以共享</b>：本类除探测缓存外没有可变状态；{@link #execute} 只用
   * 局部变量，探测本身在 `synchronized` 内。设置页的「测试 root」刻意 `new` 一个
   * 新实例——那里要绕过缓存做一次真实探测。
   */
  public static RootShellBackend shared() {
    return SharedHolder.INSTANCE;
  }

  private static final class SharedHolder {
    static final RootShellBackend INSTANCE = new RootShellBackend();
  }

  /** 测试入口：替换候选路径、进程创建与缓存时长。 */
  RootShellBackend(
      List<String> suCandidates,
      ProcessStarter starter,
      long probeTimeoutMs,
      long successTtlMs,
      long failureTtlMs) {
    this.suCandidates = new ArrayList<>(suCandidates);
    this.starter = starter;
    this.probeTimeoutMs = probeTimeoutMs;
    this.successTtlMs = successTtlMs;
    this.failureTtlMs = failureTtlMs;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String displayName() {
    return "Root (su)";
  }

  @Override
  public boolean hasAdbPrivileges() {
    // root 是 adb(2000) 的超集：pm / am / logcat / screencap 全都能做。
    return true;
  }

  @Override
  public boolean hasRootPrivileges() {
    return true;
  }

  @Override
  public boolean hasCliToolchain() {
    // 进程以 uid 0 运行，但 PATH 仍只有 Android 系统目录；Termux 的 prefix 在
    // app 私有目录下，root 虽然能读，其二进制仍依赖 Termux 的 lib 布局。
    return false;
  }

  @Override
  public String capabilitySummary() {
    return "root 权限（su）：pm/am/logcat/screencap 及任意文件读写都能做；"
        + "没有 git/node/python 等工具链。";
  }

  @Override
  public boolean isAvailable() {
    long now = System.currentTimeMillis();
    long ttl = probeResult ? successTtlMs : failureTtlMs;
    if (now - probeAtMs < ttl) {
      return probeResult;
    }
    synchronized (this) {
      now = System.currentTimeMillis();
      ttl = probeResult ? successTtlMs : failureTtlMs;
      if (now - probeAtMs < ttl) {
        return probeResult;
      }
      probe();
      return probeResult;
    }
  }

  /**
   * 廉价判断：只检查候选 su 路径是否存在，不 fork 进程、不弹授权框。
   *
   * <p>代价是「文件存在但未授权」会被判为存在——对「枚举可选项」的场景这是
   * 可接受的（真正执行时会走 {@link #isAvailable()} 给出准确结论）。
   */
  @Override
  public boolean isPresent() {
    if (probeResult) {
      // 已确认可用，直接复用，不必再扫文件。
      return true;
    }
    for (String candidate : suCandidates) {
      File asFile = new File(candidate);
      if (!asFile.isAbsolute()) {
        // 裸名（"su"）靠 PATH 解析。这里只扫 PATH 目录、不 fork：
        // 找不到就说明这台设备大概率没 root，不必再去弹授权框。
        String path = System.getenv("PATH");
        if (path != null) {
          for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isEmpty() && new File(dir, candidate).exists()) {
              return true;
            }
          }
        }
        continue;
      }
      if (asFile.exists()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String unavailableReason() {
    if (isAvailable()) {
      return "";
    }
    return probeReason;
  }

  /**
   * 丢弃缓存，下一次 {@link #isAvailable()} 重新探测（用户刚授权后调用）。
   *
   * <p>与 {@link #probe()} 同锁：否则可能与进行中的探测交错，出现
   * 「probeResult=true 但 resolvedSu 被置空」——随后 {@link #execute} 会把 null
   * 当成 su 路径，ProcessBuilder 抛 NPE 而不是 IOException。
   */
  public synchronized void invalidateCache() {
    probeAtMs = 0L;
    probeResult = false;
    resolvedSu = null;
    probeReason = "尚未探测";
  }

  /** 当前探测到的 su 路径；未探测或不可用时返回空串。 */
  public String resolvedSuPath() {
    return resolvedSu == null ? "" : resolvedSu;
  }

  /**
   * 依次尝试候选路径，找到第一个能执行 {@code id -u} 且输出 0 的。
   *
   * <p>必须真的执行而不是只判断文件存在：SELinux 域、su 的授权状态都会让
   * 「文件存在」与「能用」不一致（实测 app 域访问 /sbin/su 会被拒绝）。
   */
  private void probe() {
    // 刻意**不**在开头置 probeResult=false：探测期间（可能长达 15s，含授权弹窗）
    // 并发调用走快路径时应沿用上一次的结论，而不是把可用的 root 短暂报成不可用。
    // 结论只在探测真正结束时写入（成功/失败两个分支各写一次）。
    long deadline = System.currentTimeMillis() + DEFAULT_PROBE_BUDGET_MS;
    StringBuilder failures = new StringBuilder();
    for (String candidate : suCandidates) {
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        appendFailure(failures, "探测总预算已用尽，剩余候选未尝试");
        break;
      }
      Process process = null;
      try {
        process = starter.start(argv(candidate, "id -u"), "", null);
        // 并发读走两个流：串行「先读 stdout 再读 stderr」在子进程写满管道缓冲时
        // 会互相等待（探测输出很小，但 su 可能派生仍持有写端的子进程）。
        StreamDrain outDrain = new StreamDrain(process.getInputStream());
        StreamDrain errDrain = new StreamDrain(process.getErrorStream());
        outDrain.start();
        errDrain.start();

        long waitMs = Math.min(probeTimeoutMs, remaining);
        if (!process.waitFor(waitMs, TimeUnit.MILLISECONDS)) {
          destroyQuietly(process);
          appendFailure(failures, candidate + " 探测超时（可能在等待授权）");
          continue;
        }
        // 进程已退出，流很快 EOF；给 drain 一点时间收尾，但不无限等。
        outDrain.await(1_000L);
        errDrain.await(1_000L);
        String output = outDrain.text();
        String err = errDrain.text();
        if (process.exitValue() == 0 && parseUid(output) == 0) {
          resolvedSu = candidate;
          probeResult = true;
          probeReason = "";
          // 时间戳在**探测结束时**写：单候选超时 10s、候选最多 6 个，若在开始时写，
          // 一次慢失败刚结束缓存就已经过期，下一次调用会立刻再 fork 一轮 su。
          probeAtMs = System.currentTimeMillis();
          return;
        }
        String detail = err.trim();
        if (detail.isEmpty()) {
          detail = "退出码 " + process.exitValue();
        } else if (process.exitValue() != 0) {
          detail += "（退出码 " + process.exitValue() + "）";
        }
        appendFailure(failures, candidate + "：" + detail);
      } catch (IOException e) {
        // 该路径不存在或不可执行，试下一个。**不要**只保留最后一个错误：
        // 候选里最后一个是 /data/adb/magisk/su，在 app 域下必然是 Permission denied，
        // 而真正的原因往往是第一个候选（PATH 里的 su）被 Magisk 拒绝或 Shamiko 隐藏。
        appendFailure(failures, candidate + " 无法执行：" + e.getMessage());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        appendFailure(failures, "探测被中断");
        break;
      } finally {
        destroyQuietly(process);
      }
    }
    resolvedSu = null;
    probeResult = false;
    probeAtMs = System.currentTimeMillis();
    String detail = failures.length() == 0 ? "未找到可用的 su" : failures.toString();
    probeReason =
        "未获得 root 权限（"
            + detail
            + "）。请确认设备已 root、已授予本应用 su 权限；"
            + "若安装了 Shamiko 等隐藏 root 的模块，需把本应用加入其白名单。";
  }

  private static void appendFailure(StringBuilder out, String message) {
    if (out.length() > 0) {
      out.append("；");
    }
    out.append(message);
  }

  @Override
  public ShellRequest.ShellResult execute(
      ShellRequest request, ShellRequest.ShellOutputSink sink) {
    if (request == null || request.getCommand().trim().isEmpty()) {
      return ShellRequest.ShellResult.error("命令为空");
    }
    // 探测可能耗时（首次会弹 Magisk 授权框）。它不属于「命令执行」，但会实实在在地
    // 拖长一次工具调用，因此从总预算里扣：命令本身的等待时间 = timeout - 探测耗时。
    long startedAt = System.currentTimeMillis();
    if (!isAvailable()) {
      return ShellRequest.ShellResult.error(unavailableReason());
    }
    long probeCost = System.currentTimeMillis() - startedAt;
    long commandTimeout = request.getTimeoutMs() - probeCost;
    if (commandTimeout <= 0) {
      return ShellRequest.ShellResult.error(
          "root 权限探测耗时 " + probeCost + "ms，已用完本次超时预算，命令未执行");
    }
    // isAvailable() 为 true 时 resolvedSu 必然非空（probe 只在成功分支设置它）。
    // 这里仍做一次显式检查：接口约定 execute 不抛异常，而 null 传进 ProcessBuilder
    // 抛的是 NPE（不在下面的 catch 里）。
    String su = resolvedSu;
    if (su == null || su.isEmpty()) {
      return ShellRequest.ShellResult.error("root 后端状态异常（未解析到 su 路径），请重试");
    }

    Process process = null;
    try {
      process =
          starter.start(
              argv(su, request.getCommand()),
              request.getCwd(),
              request.getStdin());

      // stdout / stderr 必须并发读走：管道缓冲区（约 64KB）写满后子进程会阻塞，
      // 而本线程此时正阻塞在 waitFor 上——串行读取会死锁。Shizuku 后端能串行
      // 是因为它在进程退出后才读 ParcelFileDescriptor，这里不适用。
      StreamDrain outDrain = new StreamDrain(process.getInputStream());
      StreamDrain errDrain = new StreamDrain(process.getErrorStream());
      outDrain.start();
      errDrain.start();

      boolean timedOut = false;
      boolean exited = false;
      long deadline = System.currentTimeMillis() + commandTimeout;
      while (true) {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
          break;
        }
        if (request.isCancellationRequested()) {
          timedOut = true;
          break;
        }
        long slice = Math.min(CANCEL_POLL_MS, remaining);
        if (process.waitFor(slice, TimeUnit.MILLISECONDS)) {
          exited = true;
          break;
        }
      }

      if (!exited) {
        timedOut = true;
        destroyQuietly(process);
      } else {
        // 正常退出：流很快 EOF，等 drain 把剩余输出读完（不读完会丢尾部）。
        outDrain.await(1_000L);
        errDrain.await(1_000L);
      }

      // 超时时**不等** drain：su 可能已经退出，但它 fork 的子进程仍持有管道写端
      // （实测 Windows 上 `cmd /c ping -n 20` 会让 drain 一直阻塞到 ping 结束，
      // 把 300ms 的超时拖成 19s）。drain 线程是 daemon，取当前已收到的内容即可。

      int exitCode = exited ? process.exitValue() : -1;
      String stdout = outDrain.text();
      String stderr = errDrain.text();
      if (timedOut) {
        stderr = stderr.isEmpty() ? "命令超时被终止" : stderr + "\n(命令超时被终止)";
      }
      boolean truncated = outDrain.truncated() || errDrain.truncated();
      if (sink != null && !stdout.isEmpty()) {
        sink.onOutput(stdout);
      }
      return new ShellRequest.ShellResult(exitCode, stdout, stderr, timedOut, truncated);
    } catch (IOException e) {
      return ShellRequest.ShellResult.error("root 命令执行失败: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ShellRequest.ShellResult.error("root 命令被中断");
    } finally {
      destroyQuietly(process);
    }
  }

  /** 构造 {@code su -c <command>} 的 argv。 */
  static List<String> argv(String suPath, String command) {
    return Collections.unmodifiableList(Arrays.asList(suPath, "-c", command));
  }

  /** 从 {@code id -u} 的输出里取 uid；无法解析返回 -1。 */
  static int parseUid(String output) {
    if (output == null) {
      return -1;
    }
    String[] lines = output.trim().split("\\s+");
    for (String line : lines) {
      try {
        return Integer.parseInt(line.trim());
      } catch (NumberFormatException ignored) {
        // 有些 su 会先打印一行提示，继续看下一段
      }
    }
    return -1;
  }

  private static void destroyQuietly(Process process) {
    if (process == null) {
      return;
    }
    try {
      // destroyForcibly：su 可能在等待授权弹窗，普通 destroy（SIGTERM）未必能
      // 让它退出，会一直占着等待循环。
      process.destroyForcibly();
    } catch (Throwable ignored) {
      // 已退出
    }
  }

  /** 后台读走一个流，避免管道写满导致子进程阻塞。 */
  private static final class StreamDrain extends Thread {
    private final InputStream input;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private volatile boolean truncated;

    StreamDrain(InputStream input) {
      this.input = input;
      setDaemon(true);
    }

    @Override
    public void run() {
      byte[] chunk = new byte[8192];
      try {
        int read;
        int total = 0;
        while ((read = input.read(chunk)) > 0) {
          if (total < MAX_OUTPUT_BYTES) {
            int keep = Math.min(read, MAX_OUTPUT_BYTES - total);
            synchronized (buffer) {
              buffer.write(chunk, 0, keep);
            }
            total += keep;
            if (keep < read) {
              truncated = true;
            }
          } else {
            truncated = true;
          }
        }
      } catch (IOException ignored) {
        // 进程被终止时流会关闭
      } finally {
        try {
          input.close();
        } catch (IOException ignored) {
          // 已关闭
        }
      }
    }

    void await(long timeoutMs) {
      try {
        join(timeoutMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    String text() {
      // 超时路径下 drain 可能仍在写，必须加锁快照，否则 toByteArray 可能读到
      // 写了一半的状态（ByteArrayOutputStream 不是线程安全的）。
      synchronized (buffer) {
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
      }
    }

    boolean truncated() {
      return truncated;
    }
  }
}
