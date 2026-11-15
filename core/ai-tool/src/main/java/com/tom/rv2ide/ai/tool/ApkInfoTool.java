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
 * aapt 读取 APK 包信息：包名、versionCode/versionName、权限、组件、SDK 版本。
 *
 * <p>纯只读（`aapt dump badging` 不写文件），是分析一个 APK 最快的入口——先看它
 * 声明了什么，再决定要不要用 {@link ApkDecompileTool} / {@link ApkResourcesTool} 深入。
 */
public final class ApkInfoTool extends BaseTool {

  private static final long TIMEOUT_MS = 30_000L;

  /** badging 输出上限，保留首部（package/application/权限在前面）。 */
  private static final int MAX_OUTPUT_CHARS = 4000;

  /**
   * app 层注入的 APK 包信息解析器。
   *
   * <p><b>为什么需要它</b>：本工具最初用 `aapt dump badging`，但 aapt 是**桌面工具**
   * （x86_64），在 Android 设备上根本跑不起来（实测报「aapt not executable: 64-bit ELF」）。
   * 正解是用 Android 原生的 `PackageManager.getPackageArchiveInfo()`——设备自带、
   * 无需安装任何外部工具。app 层以此接口注入实现（core/ai-tool 是纯 Java，不依赖 Android）。
   */
  public interface Provider {
    /** 解析 APK，返回包信息文本（包名/版本/权限/组件）；失败返回 null 以回退到 aapt。 */
    String readApkInfo(String apkPath);
  }

  private static volatile Provider provider;

  /** app 启动时注入一次（PackageManager.getPackageArchiveInfo 实现）。 */
  public static void setProvider(Provider p) {
    provider = p;
  }

  private final ShellBackendRegistry registry;

  public ApkInfoTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.APK_INFO;
  }

  @Override
  public String getDescription() {
    return "用 aapt 读取 APK 的包信息：包名、versionCode/versionName、声明的权限、"
        + "四大组件、minSdk/targetSdk。是分析 APK 最快的入口（只读、秒级）。"
        + "参数 apk_path 为 APK 文件路径。aapt 未安装时给出提示。";
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
                        .put("description", "APK 文件的路径（相对工作区根目录或绝对路径）")))
        .put("required", new org.json.JSONArray().put("apk_path"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String apkPath = input.optString("apk_path", "").trim();
    if (apkPath.isEmpty()) {
      return error("apk_path 不能为空");
    }
    // 优先 app 层 PackageManager（设备原生，无需外部 aapt）。
    Provider p = provider;
    if (p != null) {
      String info = p.readApkInfo(apkPath);
      if (info != null && !info.trim().isEmpty()) {
        return ok(info);
      }
      // provider 解析失败则回退到 aapt（见下）。
    }
    String cwd = context == null ? "" : context.getHomePath();
    ShellBackendRegistry.Resolution resolution = registry.resolveActive();
    if (!resolution.isUsable()) {
      return error("没有可用的 shell 后端，无法运行 aapt。请在设置里启用 Termux 或 Shizuku 后端。");
    }
    ShellBackend backend = resolution.getBackend();

    String aapt = resolveAapt(backend, cwd);
    if (aapt == null) {
      return error(
          ReverseToolSupport.notInstalled(
              "aapt",
              "aapt 随 Android SDK 的 build-tools 提供。请确认已安装 Android SDK"
                  + "（设置 → SDK 管理器），aapt 位于 build-tools/<版本>/aapt。"));
    }

    String command =
        ReverseToolSupport.quote(aapt) + " dump badging " + ReverseToolSupport.quote(apkPath);
    ToolContext.CancellationSignal runCancel = context == null ? null : context.getCancellation();
    ShellRequest.CancellationSignal cancel = runCancel == null ? null : runCancel::isCancelled;
    ShellRequest.ShellResult result =
        ReverseToolSupport.run(backend, command, cwd, TIMEOUT_MS, cancel);

    if (result.isTimedOut()) {
      return error("aapt 读取超时。");
    }
    String out = result.getStdout() == null ? "" : result.getStdout();
    if (result.getExitCode() != 0) {
      return error(
          "aapt 读取失败（退出码 " + result.getExitCode() + "）：\n"
              + ReverseToolSupport.truncate(
                  (result.getStderr() == null ? "" : result.getStderr()) + "\n" + out, 2000));
    }
    if (out.trim().isEmpty()) {
      return error("aapt 没有输出，可能不是有效的 APK 文件：" + apkPath);
    }
    return ok(ReverseToolSupport.truncate(out, MAX_OUTPUT_CHARS));
  }

  /** 解析 aapt 绝对路径：先 PATH/常见位置，再回退到 Android SDK 的 build-tools。 */
  private String resolveAapt(ShellBackend backend, String cwd) {
    String path = ReverseToolSupport.probeExecutable(backend, "aapt", cwd);
    if (path != null) {
      return path;
    }
    // Android SDK 的 build-tools（取最新版本）。两个常见环境变量都试。
    String cmd =
        "ls $ANDROID_HOME/build-tools/*/aapt $ANDROID_SDK_ROOT/build-tools/*/aapt"
            + " 2>/dev/null | sort -V | tail -1";
    ShellRequest.ShellResult r = ReverseToolSupport.run(backend, cmd, cwd, 15_000L, null);
    String p = r.getStdout() == null ? "" : r.getStdout().trim();
    return p.startsWith("/") ? p : null;
  }
}
