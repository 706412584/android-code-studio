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
 * apktool 解包：把 APK 拆成 smali 源码、资源文件与 AndroidManifest。
 *
 * <p>与 {@link ApkDecompileTool}（jadx → Java）互补：apktool 保留 **smali 与原始资源**，
 * 适合看 AndroidManifest（包名/权限/组件）、资源、以及需要回编译改包的场合。
 */
public final class ApkResourcesTool extends BaseTool {

  private static final long TIMEOUT_MS = 180_000L;

  private final ShellBackendRegistry registry;

  public ApkResourcesTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.APK_RESOURCES;
  }

  @Override
  public String getDescription() {
    return "用 apktool 解包 APK：产出 smali 源码、res 资源与 AndroidManifest.xml。"
        + "适合看包名/权限/组件声明、资源、以及要回编译改包的场景。"
        + "参数 apk_path 为 APK 路径；output_dir 可选，默认 <apk>.apktool。"
        + "返回产物目录与 manifest 关键信息。apktool 未安装时给出安装提示。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.READ;
  }

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
                        .put("description", "解包输出目录；可选，默认 <apk_path>.apktool")))
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
      return error("没有可用的 shell 后端，无法运行 apktool。请在设置里启用 Termux 或 Shizuku 后端。");
    }
    ShellBackend backend = resolution.getBackend();

    String apktool = ReverseToolSupport.probeExecutable(backend, "apktool", cwd);
    if (apktool == null) {
      return error(
          ReverseToolSupport.notInstalled(
              "apktool",
              "安装方式：\n"
                  + "- Termux：`pkg install apktool`（若源提供）；\n"
                  + "- 或从 https://github.com/iBotPeaches/Apktool/releases 下载 apktool.jar，"
                  + "用 `java -jar <path>/apktool.jar` 调用。"));
    }

    String outputDir = input.optString("output_dir", "").trim();
    if (outputDir.isEmpty()) {
      outputDir = apkPath + ".apktool";
    }
    String command =
        ReverseToolSupport.quote(apktool)
            + " d -o "
            + ReverseToolSupport.quote(outputDir)
            + " "
            + ReverseToolSupport.quote(apkPath);
    ToolContext.CancellationSignal runCancel = context == null ? null : context.getCancellation();
    ShellRequest.CancellationSignal cancel = runCancel == null ? null : runCancel::isCancelled;
    ShellRequest.ShellResult result =
        ReverseToolSupport.run(backend, command, cwd, TIMEOUT_MS, cancel);

    if (result.isTimedOut()) {
      return error("apktool 解包超时（" + (TIMEOUT_MS / 1000) + "s）。");
    }
    if (result.getExitCode() != 0) {
      return error(
          "apktool 解包失败（退出码 " + result.getExitCode() + "）：\n"
              + ReverseToolSupport.truncate(
                  (result.getStderr() == null ? "" : result.getStderr())
                      + "\n"
                      + (result.getStdout() == null ? "" : result.getStdout()),
                  2000));
    }

    // 提取 manifest 关键行（包名/权限/组件），让模型不必再读整份 manifest。
    String manifestCmd =
        "grep -nE 'package=|uses-permission|<activity|<service|<receiver|<provider' "
            + ReverseToolSupport.quote(outputDir + "/AndroidManifest.xml")
            + " 2>/dev/null | head -50";
    ShellRequest.ShellResult manifest =
        ReverseToolSupport.run(backend, manifestCmd, cwd, 15_000L, null);
    String manifestInfo = manifest.getStdout() == null ? "" : manifest.getStdout().trim();

    StringBuilder sb = new StringBuilder();
    sb.append("解包完成：").append(apkPath).append("\n");
    sb.append("产物目录：").append(outputDir).append("\n");
    sb.append("smali 源码在 ").append(outputDir).append("/smali，资源在 ")
        .append(outputDir).append("/res，清单在 ")
        .append(outputDir).append("/AndroidManifest.xml。\n");
    if (!manifestInfo.isEmpty()) {
      sb.append("\nManifest 关键信息：\n").append(manifestInfo).append("\n");
    }
    sb.append("\n可用 file_read 读 AndroidManifest.xml / smali 文件，或 grep 搜索。");
    return ok(sb.toString());
  }
}
