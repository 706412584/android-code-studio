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

import java.util.ArrayList;
import java.util.List;

/**
 * 逆向工具链的公共支撑：命令防注入、输出截断、二进制探测与执行。
 *
 * <p><b>为什么单独抽出来</b>：jadx / apktool / radare2 / frida 这组工具共享同一套
 * 「探测可执行文件 → 拼命令 → 执行 → 解析输出」骨架，抽在这里避免四个工具各写一遍。
 * 全部为纯 Java、只依赖 {@link ShellBackend} 接口，因此可在 JVM 上用 fake backend 单测。
 *
 * <p><b>为什么探测用 login shell</b>：shell 后端不设 PATH（见 CodeGraphTool 的实测注释），
 * 裸名会 command not found。`sh -lc` 会 source profile、设好 Termux 的 `$PREFIX/bin`，
 * 因此 `sh -lc 'command -v <name>'` 能拿到可执行文件的**绝对路径**；拿到后再以绝对路径
 * 调用，与 CodeGraphTool 的「绝对路径调用」策略一致。
 */
final class ReverseToolSupport {

  /** 探测命令的超时：很短，探测应当立即返回。 */
  private static final long PROBE_TIMEOUT_MS = 15_000L;

  private ReverseToolSupport() {}

  /** 单引号包裹并转义内部单引号，安全传给 `/system/bin/sh -c`。 */
  static String quote(String s) {
    return "'" + s.replace("'", "'\\''") + "'";
  }

  /** 输出上限截断：保留首尾，中间标注省略。 */
  static String truncate(String s, int maxChars) {
    if (s == null) {
      return "";
    }
    if (s.length() <= maxChars) {
      return s;
    }
    int head = maxChars * 2 / 3;
    int tail = maxChars - head;
    return s.substring(0, head)
        + "\n… [已截断 "
        + (s.length() - maxChars)
        + " 字符] …\n"
        + s.substring(s.length() - tail);
  }

  /** 执行一条命令并返回结果；调用方负责解析 stdout/stderr/exitCode。 */
  static ShellRequest.ShellResult run(
      ShellBackend backend,
      String command,
      String cwd,
      long timeoutMs,
      ShellRequest.CancellationSignal cancel) {
    ShellRequest request = new ShellRequest(command, cwd, timeoutMs, null, cancel);
    return backend.execute(request, ShellRequest.ShellOutputSink.NOOP);
  }

  /**
   * 探测一个可执行文件的绝对路径；找不到返回 null（调用方据此给出安装提示）。
   *
   * <p>先 `sh -lc 'command -v <name>'`（login shell 设 PATH），再逐个试候选绝对路径。
   */
  static String probeExecutable(ShellBackend backend, String name, String cwd) {
    // 1) login shell 探测：PATH 由 login shell 设好，覆盖 Termux $PREFIX/bin。
    ShellRequest.ShellResult r =
        run(backend, "sh -lc 'command -v " + name + "'", cwd, PROBE_TIMEOUT_MS, null);
    String path = r.getStdout() == null ? "" : r.getStdout().trim();
    if (r.getExitCode() == 0 && path.startsWith("/")) {
      return path;
    }
    // 2) 候选绝对路径：覆盖 Termux 官方前缀与常见安装位置。
    for (String candidate : candidatePaths(name)) {
      ShellRequest.ShellResult probe =
          run(backend, "test -x " + quote(candidate), cwd, PROBE_TIMEOUT_MS, null);
      if (probe.getExitCode() == 0) {
        return candidate;
      }
    }
    return null;
  }

  /** 候选安装路径（Termux 官方前缀 + 自定义 usr 前缀）。 */
  private static List<String> candidatePaths(String name) {
    List<String> paths = new ArrayList<>();
    paths.add("/data/data/com.termux/files/usr/bin/" + name);
    paths.add("/data/data/com.tom.rv2ide/files/usr/bin/" + name);
    paths.add("/data/data/com.tom.rv2ide/files/home/.termux/bin/" + name);
    return paths;
  }

  /** 「命令未安装」的统一提示，引导用户安装。 */
  static String notInstalled(String toolName, String installHint) {
    return "未检测到可用的 " + toolName + "。\n" + installHint;
  }

  /**
   * 探测一个 **jar 形态**的工具（jadx/apktool 主要以 jar 分发，没有 apktool/jadx 命令）。
   *
   * <p>先找到 java，再在候选路径里找 jar。fat jar（mainClass=null）用 `java -jar`；
   * 需要依赖的（mainClass 非空，如 jadx.cli.JadxCLI）用 `java -cp <dir>/* <mainClass>`。
   *
   * @return 命令前缀（含 java 与 jar/classpath），找不到返回 null
   */
  static String probeJarTool(ShellBackend backend, String jarName, String mainClass, String cwd) {
    String java = probeExecutable(backend, "java", cwd);
    if (java == null) {
      return null;
    }
    for (String jarPath : jarCandidates(jarName)) {
      if (run(backend, "test -f " + quote(jarPath), cwd, PROBE_TIMEOUT_MS, null).getExitCode() == 0) {
        if (mainClass == null) {
          return quote(java) + " -jar " + quote(jarPath);
        }
        String dir = jarPath.substring(0, jarPath.lastIndexOf('/'));
        return quote(java) + " -cp " + quote(dir + "/*") + " " + mainClass;
      }
    }
    return null;
  }

  /** jar 的候选路径（/sdcard、Termux share、工作区）。 */
  private static List<String> jarCandidates(String jarName) {
    List<String> paths = new ArrayList<>();
    paths.add("/sdcard/" + jarName);
    paths.add("/data/data/com.termux/files/usr/share/" + jarName);
    paths.add("/data/data/com.tom.rv2ide/files/usr/share/" + jarName);
    paths.add(jarName);
    return paths;
  }
}
