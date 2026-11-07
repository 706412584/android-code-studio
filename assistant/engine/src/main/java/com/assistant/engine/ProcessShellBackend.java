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

package com.assistant.engine;

import com.tom.rv2ide.ai.tool.ShellBackend;
import com.tom.rv2ide.ai.tool.ShellRequest;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 用 {@code Runtime.exec} 在 app 自身 uid 下执行命令的 shell 后端。
 *
 * <p><b>这是剥离版的临时后端，不是终局</b>。ACS 用 Termux（自带完整 rootfs 与
 * 命令行工具链），剥离后按决策 A2 要搬 {@code termux/{emulator,view}} 并自建精简
 * service——那一步还没做。在那之前，本后端让「模型能跑命令」这条链路可验证：
 * 它能执行 {@code ls} / {@code cat} / {@code echo}，但没有 git / node / python，
 * 也没有 PTY（无交互、无颜色、无行编辑）。
 *
 * <p><b>必须如实告知能力边界</b>：{@link #hasCliToolchain()} 返回 false、
 * {@link #capabilitySummary()} 说明缺什么——模型据此不会尝试 {@code git log} 然后
 * 困惑于失败。这一点是 {@link ShellBackend} 的契约要求，不是可选优化。
 *
 * <p><b>输出上限</b>：单个流最多保留 4MB。{@code cat /dev/urandom} 之类会让内存
 * 直接打爆，而模型也读不了那么长的输出。
 */
public final class ProcessShellBackend implements ShellBackend {

  public static final String ID = "process";

  /** 单个流最多保留的字节数。 */
  private static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;

  /** 等待循环的分片时长：每片之后检查一次取消信号与超时。 */
  private static final long POLL_MS = 100L;

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String displayName() {
    return "内置 shell（无 Termux）";
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public String unavailableReason() {
    return "";
  }

  /**
   * 能力说明，会被放进工具提示词与失败提示。
   *
   * <p>必须写清「没有工具链」：模型看到「shell 可用」会直接尝试 {@code git}，
   * 而失败原因（{@code git: not found}）不足以让它意识到是环境缺工具而非命令写错。
   */
  @Override
  public String capabilitySummary() {
    return "在 app 自身 uid 下执行普通命令（ls / cat / echo / mkdir 等系统自带工具）。"
        + "**没有** git / node / python / gcc 等命令行工具链，也没有 PTY 交互。";
  }

  @Override
  public ShellRequest.ShellResult execute(
      ShellRequest request, ShellRequest.ShellOutputSink sink) {
    if (request == null || request.getCommand() == null || request.getCommand().trim().isEmpty()) {
      return ShellRequest.ShellResult.error("命令为空");
    }
    String cwd = request.getCwd();
    String[] argv;
    if (cwd != null && !cwd.isEmpty()) {
      // 用 sh -c 而不是拆分参数：模型给的是完整命令行（含管道、重定向），
      // 拆开就失去了 shell 语义。
      argv = new String[] {"sh", "-c", request.getCommand()};
    } else {
      argv = new String[] {"sh", "-c", request.getCommand()};
    }

    Process process = null;
    try {
      ProcessBuilder builder = new ProcessBuilder(argv);
      builder.redirectErrorStream(false);
      if (cwd != null && !cwd.isEmpty()) {
        File dir = new File(cwd);
        if (dir.isDirectory()) {
          builder.directory(dir);
        }
      }
      process = builder.start();

      // stdin：工具可能要给命令喂输入（例如 cat > file）。写完后立刻关，否则命令等输入会挂住。
      if (request.getStdin() != null && !request.getStdin().isEmpty()) {
        try (java.io.OutputStream stdin = process.getOutputStream()) {
          stdin.write(request.getStdin().getBytes(StandardCharsets.UTF_8));
          stdin.flush();
        }
      } else {
        process.getOutputStream().close();
      }

      ByteArrayOutputStream stdout = new ByteArrayOutputStream();
      ByteArrayOutputStream stderr = new ByteArrayOutputStream();
      Thread outReader = drain(process.getInputStream(), stdout, sink);
      Thread errReader = drain(process.getErrorStream(), stderr, null);

      long deadline = System.currentTimeMillis() + request.getTimeoutMs();
      boolean timedOut = false;
      while (true) {
        if (process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
          break;
        }
        if (request.getCancellation() != null && request.getCancellation().isCancelled()) {
          process.destroy();
          outReader.interrupt();
          errReader.interrupt();
          return ShellRequest.ShellResult.error("命令已取消");
        }
        if (System.currentTimeMillis() > deadline) {
          timedOut = true;
          process.destroy();
          break;
        }
      }
      // 进程结束后等读线程把剩余输出读完（否则大输出的尾部会丢）。
      outReader.join(2000);
      errReader.join(2000);

      boolean truncated =
          stdout.size() >= MAX_OUTPUT_BYTES || stderr.size() >= MAX_OUTPUT_BYTES;
      int exit = timedOut ? -1 : process.exitValue();
      return new ShellRequest.ShellResult(
          exit,
          stdout.toString(StandardCharsets.UTF_8.name()),
          stderr.toString(StandardCharsets.UTF_8.name()),
          timedOut,
          truncated);
    } catch (IOException e) {
      return ShellRequest.ShellResult.error("无法启动命令：" + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ShellRequest.ShellResult.error("命令被中断");
    } finally {
      if (process != null) {
        process.destroy();
      }
    }
  }

  /**
   * 后台读一个流，边读边累积（超限后只丢弃不再增长），可选地转发给 sink。
   *
   * <p>必须用独立线程：进程的 stdout 管道有缓冲区（通常 64KB），
   * 不并发读的话，命令写满缓冲区就会阻塞，而主线程在 waitFor——直接死锁。
   */
  private static Thread drain(
      InputStream input, ByteArrayOutputStream sinkBuffer, ShellRequest.ShellOutputSink sink) {
    Thread thread =
        new Thread(
            () -> {
              byte[] chunk = new byte[8192];
              int read;
              try {
                while ((read = input.read(chunk)) != -1) {
                  if (sinkBuffer.size() < MAX_OUTPUT_BYTES) {
                    int allowed = Math.min(read, MAX_OUTPUT_BYTES - sinkBuffer.size());
                    sinkBuffer.write(chunk, 0, allowed);
                  }
                  if (sink != null && read > 0) {
                    sink.onOutput(new String(chunk, 0, read, StandardCharsets.UTF_8));
                  }
                }
              } catch (IOException ignored) {
                // 进程被 destroy 时读会抛，属正常路径。
              }
            },
            "shell-drain");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }
}
