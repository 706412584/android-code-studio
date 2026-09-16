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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次 shell 执行的请求参数。
 *
 * <p>不可变；用 {@link #withCommand} 派生变体（proot 装饰器需要改写命令）。
 */
public final class ShellRequest {

  /** 默认超时：2 分钟。构建类操作由调用方显式传更大的值。 */
  public static final long DEFAULT_TIMEOUT_MS = 120_000L;

  private final String command;
  private final String cwd;
  private final long timeoutMs;
  private final String stdin;

  public ShellRequest(String command, String cwd, long timeoutMs, String stdin) {
    this.command = command == null ? "" : command;
    this.cwd = cwd == null ? "" : cwd;
    this.timeoutMs = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
    this.stdin = stdin;
  }

  public static ShellRequest of(String command) {
    return new ShellRequest(command, "", DEFAULT_TIMEOUT_MS, null);
  }

  public String getCommand() {
    return command;
  }

  /** 工作目录；空串表示由后端决定（通常是工作区根目录）。 */
  public String getCwd() {
    return cwd;
  }

  public long getTimeoutMs() {
    return timeoutMs;
  }

  /** 传给命令的标准输入，可为 null。 */
  public String getStdin() {
    return stdin;
  }

  /** 派生一个替换了命令的请求，其余字段保持不变。 */
  public ShellRequest withCommand(String newCommand) {
    return new ShellRequest(newCommand, cwd, timeoutMs, stdin);
  }

  /** 派生一个替换了工作目录的请求。 */
  public ShellRequest withCwd(String newCwd) {
    return new ShellRequest(command, newCwd, timeoutMs, stdin);
  }

  /** 一次 shell 执行的结果。 */
  public static final class ShellResult {
    private final int exitCode;
    private final String stdout;
    private final String stderr;
    private final boolean timedOut;
    private final boolean truncated;

    public ShellResult(
        int exitCode, String stdout, String stderr, boolean timedOut, boolean truncated) {
      this.exitCode = exitCode;
      this.stdout = stdout == null ? "" : stdout;
      this.stderr = stderr == null ? "" : stderr;
      this.timedOut = timedOut;
      this.truncated = truncated;
    }

    public static ShellResult success(String stdout) {
      return new ShellResult(0, stdout, "", false, false);
    }

    public static ShellResult error(String message) {
      return new ShellResult(-1, "", message, false, false);
    }

    public static ShellResult timeout() {
      return new ShellResult(-1, "", "命令执行超时", true, false);
    }

    public int getExitCode() {
      return exitCode;
    }

    public String getStdout() {
      return stdout;
    }

    public String getStderr() {
      return stderr;
    }

    /** 是否因超时被终止。 */
    public boolean isTimedOut() {
      return timedOut;
    }

    /** 输出是否因过长被截断。 */
    public boolean isTruncated() {
      return truncated;
    }

    public boolean isSuccess() {
      return exitCode == 0 && !timedOut;
    }

    /** 组装成给模型看的文本，包含退出码与非空输出流。 */
    public String toDisplayText() {
      StringBuilder sb = new StringBuilder();
      sb.append("exit_code=").append(exitCode).append('\n');
      if (timedOut) {
        sb.append("(命令超时被终止)\n");
      }
      if (truncated) {
        sb.append("(输出过长，已截断)\n");
      }
      if (!stdout.isEmpty()) {
        sb.append("--- stdout ---\n").append(stdout);
        if (!stdout.endsWith("\n")) {
          sb.append('\n');
        }
      }
      if (!stderr.isEmpty()) {
        sb.append("--- stderr ---\n").append(stderr);
        if (!stderr.endsWith("\n")) {
          sb.append('\n');
        }
      }
      if (stdout.isEmpty() && stderr.isEmpty()) {
        sb.append("(无输出)\n");
      }
      return sb.toString();
    }
  }

  /** 执行过程中的输出增量回调。实现方需容忍来自非主线程的调用。 */
  public interface ShellOutputSink {
    void onOutput(String chunk);

    /** 什么都不做的实现，供不关心流式输出的调用方使用。 */
    ShellOutputSink NOOP =
        new ShellOutputSink() {
          @Override
          public void onOutput(String chunk) {}
        };
  }

  /** 用于把多个后端列成清单时的只读视图。 */
  public static List<String> idsOf(List<ShellBackend> backends) {
    List<String> ids = new ArrayList<>();
    if (backends != null) {
      for (ShellBackend backend : backends) {
        ids.add(backend.id());
      }
    }
    return Collections.unmodifiableList(ids);
  }
}
