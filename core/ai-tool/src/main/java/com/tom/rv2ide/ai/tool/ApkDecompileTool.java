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

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import org.json.JSONObject;

/**
 * jadx 反编译：把 APK 反编译成可读的 Java 源码（含资源）。
 *
 * <p>用途：分析一个 APK 的实现——看它的类结构、逻辑、字符串、调用了哪些库。
 * 输出是**反编译产物目录**，随后可用 file_read / grep 读具体类。
 *
 * <p>只读分析（不改项目源码），产物写到工作区下的输出目录，故只读模式放行。
 * 与 {@link CodeGraphTool} 同一套「探测绝对路径 → quote 防注入 → 执行 → 解析」骨架。
 */
public final class ApkDecompileTool extends BaseTool {

  /** 反编译可能很慢（大 APK），上限 5 分钟。 */
  private static final long TIMEOUT_MS = 300_000L;

  /** 结构清单最多列多少行，避免刷屏。 */
  private static final int MAX_STRUCTURE_LINES = 40;

  private final ShellBackendRegistry registry;

  public ApkDecompileTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.APK_DECOMPILE;
  }

  @Override
  public String getDescription() {
    return "用 jadx 把 APK 反编译成 Java 源码，用于分析应用的实现（类结构、逻辑、字符串、依赖库）。"
        + "参数 apk_path 为 APK 文件路径；output_dir 可选，默认 <apk>.jadx。"
        + "成功后返回反编译产物目录与源码结构，之后用 file_read/grep 读具体类。"
        + "jadx 未安装时会给出安装提示。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.READ;
  }

  /** 只读分析（产物是分析结果，不改项目源码），只读模式放行。 */
  @Override
  public boolean isAllowedInReadonlyMode() {
    return true;
  }

  @Override
  public boolean isConcurrencySafe() {
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "apk_path",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "APK 文件的路径（相对工作区根目录或绝对路径）"))
                .put(
                    "output_dir",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "反编译产物输出目录；可选，默认 <apk_path>.jadx")))
        .put("required", new org.json.JSONArray().put("apk_path"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String apkPath = input.optString("apk_path", "").trim();
    if (apkPath.isEmpty()) {
      return error("apk_path 不能为空");
    }
    String cwd = context == null ? "" : context.getHomePath();
    ShellBackendRegistry.Resolution resolution = registry.resolveActive();
    if (!resolution.isUsable()) {
      return error("没有可用的 shell 后端，无法运行 jadx。请在设置里启用 Termux 或 Shizuku 后端。");
    }
    ShellBackend backend = resolution.getBackend();

    String jadx = ReverseToolSupport.probeExecutable(backend, "jadx", cwd);
    String cliPrefix = null;
    if (jadx == null) {
      // jadx 以 jar 分发（无 jadx 命令时，回退到 java -cp lib/* jadx.cli.JadxCLI）。
      cliPrefix = ReverseToolSupport.probeJarTool(backend, "jadx.jar", "jadx.cli.JadxCLI", cwd);
    }
    if (jadx == null && cliPrefix == null) {
      return error(
          ReverseToolSupport.notInstalled(
              "jadx",
              "安装方式：\n"
                  + "- 从 https://github.com/skylot/jadx/releases 下载 jadx-<ver>.zip，解压到 /sdcard，"
                  + "本工具会自动用 `java -cp lib/* jadx.cli.JadxCLI` 调用；\n"
                  + "- 或把 jadx 命令放进 PATH（Termux 的 $PREFIX/bin）。"));
    }
    String execPrefix = jadx != null ? ReverseToolSupport.quote(jadx) : cliPrefix;

    String outputDir = input.optString("output_dir", "").trim();
    if (outputDir.isEmpty()) {
      outputDir = apkPath + ".jadx";
    }
    // 防注入：路径全部 quote。
    String command =
        execPrefix
            + " -d "
            + ReverseToolSupport.quote(outputDir)
            + " "
            + ReverseToolSupport.quote(apkPath);
    ToolContext.CancellationSignal runCancel = context == null ? null : context.getCancellation();
    ShellRequest.CancellationSignal cancel = runCancel == null ? null : runCancel::isCancelled;
    ShellRequest.ShellResult result =
        ReverseToolSupport.run(backend, command, cwd, TIMEOUT_MS, cancel);

    if (result.isTimedOut()) {
      return error("jadx 反编译超时（" + (TIMEOUT_MS / 1000) + "s）。大 APK 可重试或拆分分析。");
    }
    if (result.getExitCode() != 0) {
      return error(
          "jadx 反编译失败（退出码 " + result.getExitCode() + "）：\n"
              + ReverseToolSupport.truncate(
                  (result.getStderr() == null ? "" : result.getStderr())
                      + "\n"
                      + (result.getStdout() == null ? "" : result.getStdout()),
                  2000));
    }

    // 成功后补一次结构清单：产物目录 + 顶层包结构 + Java 文件数，引导模型接着读源码。
    String structureCmd =
        "find "
            + ReverseToolSupport.quote(outputDir)
            + " -maxdepth 4 -type d 2>/dev/null | head -"
            + MAX_STRUCTURE_LINES
            + " ; echo \"JAVA_FILES=$(find "
            + ReverseToolSupport.quote(outputDir)
            + " -name '*.java' 2>/dev/null | wc -l)\"";
    ShellRequest.ShellResult structure =
        ReverseToolSupport.run(backend, structureCmd, cwd, 30_000L, null);
    String listing = structure.getStdout() == null ? "" : structure.getStdout().trim();

    StringBuilder sb = new StringBuilder();
    sb.append("反编译完成：").append(apkPath).append("\n");
    sb.append("产物目录：").append(outputDir).append("\n");
    sb.append("Java 源码在 ").append(outputDir).append("/sources，资源在 ")
        .append(outputDir).append("/resources。\n");
    if (!listing.isEmpty()) {
      sb.append("\n目录结构与统计：\n").append(listing).append("\n");
    }
    sb.append("\n接下来用 file_read 读具体类、grep 搜关键字，或 list_dir 浏览 sources 目录。");
    return ok(sb.toString());
  }
}
