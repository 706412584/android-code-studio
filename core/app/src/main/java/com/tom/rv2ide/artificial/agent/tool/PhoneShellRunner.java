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
 * along with AndroidCodeStudio.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.tool;

import com.tom.rv2ide.ai.tool.ShellBackend;
import com.tom.rv2ide.ai.tool.ShellBackendRegistry;
import com.tom.rv2ide.ai.tool.ShellRequest;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全项目唯一的设备命令执行器。
 *
 * <p>设备观察/交互/验证工具（{@code phone_*}、{@code LaunchAppTool}、
 * {@code PhoneMemInfoTool} 等）统一经由此处执行 {@code screencap} / {@code uiautomator} /
 * {@code dumpsys} / {@code am} / {@code pidof} 这类命令。
 *
 * <p><b>严格语义（无本地降级）</b>：这些命令在应用自身 uid 下<b>必然失败</b>——
 * {@code dumpsys} 需要 {@code android.permission.DUMP}（signature|privileged），
 * {@code am start} 需要从后台启动 Activity 的特权，{@code screencap} 需要
 * {@code READ_FRAME_BUFFER}。降级到本地进程只会得到「channel=local、输出为空」的
 * <b>误导性结果</b>：模型会把「没权限」误读成「没有数据」。因此无 Shizuku 时
 * 直接返回明确错误（含当前后端名、为何需要 Shizuku、如何启用），不降级。
 *
 * <p>（需要「拿不到 adb 也要尽力启动」的场景由 {@code LaunchAppTool} 自己的
 * {@code IntentUtils.launchApp} 回退负责，不依赖本类。）
 */
final class PhoneShellRunner {

  private static final Logger log = LoggerFactory.getLogger(PhoneShellRunner.class);

  /**
   * 合法 Android 包名的正则。
   *
   * <p>用于在把包名拼进 shell 命令<b>之前</b>做校验：包名来自模型，若含空格、
   * 分号、反引号、{@code $(...)} 等字符，在 Shizuku 的 uid 2000 下就是一条
   * 任意命令执行。严格只放行 {@code [A-Za-z0-9_.]}，从根上消除注入面。
   */
  private static final Pattern PACKAGE_PATTERN =
      Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");

  /** 包名长度上限（Android 组件名上限 255 字节，这里给足裕量）。 */
  private static final int MAX_PACKAGE_LENGTH = 255;

  private PhoneShellRunner() {}

  /** 包名是否合法（可用于拼接进 shell 命令）。 */
  static boolean isValidPackage(String packageName) {
    return packageName != null
        && !packageName.isEmpty()
        && packageName.length() <= MAX_PACKAGE_LENGTH
        && PACKAGE_PATTERN.matcher(packageName).matches();
  }

  /** 构造一个默认的 shell 后端注册表（Termux + Shizuku）。 */
  static ShellBackendRegistry defaultRegistry(android.content.Context context) {
    ShellBackendRegistry registry = new ShellBackendRegistry();
    try {
      registry.register(new com.tom.rv2ide.artificial.agent.TermuxShellBackend(context));
    } catch (Throwable e) {
      log.debug("注册 Termux 后端失败", e);
    }
    try {
      registry.register(new com.tom.rv2ide.artificial.agent.ShizukuShellBackend(context));
    } catch (Throwable e) {
      log.debug("注册 Shizuku 后端失败", e);
    }
    return registry;
  }

  /** 一次命令执行的结果。 */
  static final class Output {
    /** 严格成败：{@code exitCode == 0 && !timedOut}。 */
    final boolean ok;
    /** 退出码；未执行或超时为 -1。 */
    final int exitCode;
    final boolean timedOut;
    final String stdout;
    final String stderr;
    /** 实际使用的执行路径：shizuku / none。 */
    final String channel;
    /** 失败或权限受限时的可读说明；成功时为空。 */
    final String note;

    Output(
        boolean ok,
        int exitCode,
        boolean timedOut,
        String stdout,
        String stderr,
        String channel,
        String note) {
      this.ok = ok;
      this.exitCode = exitCode;
      this.timedOut = timedOut;
      this.stdout = stdout == null ? "" : stdout;
      this.stderr = stderr == null ? "" : stderr;
      this.channel = channel == null ? "" : channel;
      this.note = note == null ? "" : note;
    }

    static Output failed(String channel, String note) {
      return new Output(false, -1, false, "", "", channel, note);
    }

    /** stdout 与 stderr 合并，便于正则解析。 */
    String combined() {
      if (stdout.isEmpty()) {
        return stderr;
      }
      if (stderr.isEmpty()) {
        return stdout;
      }
      return stdout + "\n" + stderr;
    }

    /** 是否有任何输出（判断「空输出 = 权限不足」时用）。 */
    boolean hasOutput() {
      return !combined().trim().isEmpty();
    }
  }

  /**
   * 以 adb 级权限执行命令（严格要求 Shizuku）。
   *
   * <p>无 Shizuku / 未授权时返回明确失败，<b>不降级</b>到本地进程。
   *
   * @param registry shell 后端注册表
   * @param command 命令
   * @param timeoutMs 超时
   */
  static Output exec(ShellBackendRegistry registry, String command, long timeoutMs) {
    ShellBackend backend = resolveAdbBackend(registry);
    if (backend == null) {
      return Output.failed("none", adbRequiredNote(registry));
    }
    try {
      ShellRequest.ShellResult result =
          backend.execute(
              new ShellRequest(command, "", timeoutMs, null), ShellRequest.ShellOutputSink.NOOP);
      if (result.isTimedOut()) {
        return new Output(
            false, -1, true, result.getStdout(), result.getStderr(), "shizuku",
            "命令超时: " + command);
      }
      int exitCode = result.getExitCode();
      boolean ok = exitCode == 0;
      return new Output(
          ok, exitCode, false, result.getStdout(), result.getStderr(), "shizuku", "");
    } catch (RuntimeException e) {
      log.warn("Shizuku 执行失败: {}", command, e);
      return Output.failed("shizuku", "Shizuku 执行异常: " + e.getMessage());
    }
  }

  /**
   * 按包名查 PID。
   *
   * @return PID；无 adb 权限或查不到时返回 null
   */
  static String findPid(ShellBackendRegistry registry, String packageName) {
    if (!isValidPackage(packageName)) {
      return null;
    }
    Output output = exec(registry, "pidof " + packageName, 8_000L);
    for (String token : output.combined().trim().split("\\s+")) {
      if (token.matches("\\d+")) {
        return token;
      }
    }
    return null;
  }

  private static ShellBackend resolveAdbBackend(ShellBackendRegistry registry) {
    if (registry == null) {
      return null;
    }
    ShellBackendRegistry.Resolution resolution = registry.resolveActive();
    if (!resolution.isUsable()) {
      return null;
    }
    ShellBackend backend = resolution.getBackend();
    return backend.hasAdbPrivileges() ? backend : null;
  }

  /** 组装「需要 Shizuku」的可读说明，尽量给出具体的不可用原因。 */
  private static String adbRequiredNote(ShellBackendRegistry registry) {
    StringBuilder sb = new StringBuilder("需要 adb 级权限（Shizuku）才能执行该设备命令。");
    String reason = "";
    if (registry != null) {
      // 优先给出具备 adb 权限的后端自身的不可用原因（如「Shizuku 未授权」）
      for (ShellBackend backend : registry.all()) {
        if (backend.hasAdbPrivileges()) {
          reason = backend.unavailableReason();
          break;
        }
      }
      if (reason.isEmpty()) {
        ShellBackendRegistry.Resolution resolution = registry.resolveActive();
        if (resolution.getBackend() != null) {
          reason =
              "当前 shell 后端为 "
                  + resolution.getBackend().displayName()
                  + "，不具备 adb 权限。";
        }
      }
    }
    if (!reason.isEmpty()) {
      sb.append(' ').append(reason);
    }
    sb.append(" 请在 Shizuku 应用中安装并授权后重试。");
    return sb.toString();
  }
}
