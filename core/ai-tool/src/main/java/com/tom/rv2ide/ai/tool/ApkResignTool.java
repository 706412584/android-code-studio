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
 * 对 APK 重新签名：用 Android SDK build-tools 里的 apksigner（`ApkSignerTool`）。
 *
 * <p>改完包（回编译/改资源/改 dex）后必须重签才能安装。apksigner 的**可执行二进制**
 * 是桌面工具（设备跑不了），但它依赖的 `build-tools/lib/*.jar` 是纯 Java，
 * 设备上可 `java -cp <lib>/* com.android.apksigner.ApkSignerTool` 调用。
 *
 * <p>写操作（生成签名后的 APK），且涉及密钥库密码，逐次确认。
 */
public final class ApkResignTool extends BaseTool {

  private static final long TIMEOUT_MS = 120_000L;

  private final ShellBackendRegistry registry;

  public ApkResignTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.APK_RESIGN;
  }

  @Override
  public String getDescription() {
    return "对 APK 重新签名（改包后重签才能安装）。用 Android SDK build-tools 的 apksigner"
        + "（java 调用，非桌面二进制）。参数：apk_path（输入 APK）、keystore（密钥库 .jks 路径）、"
        + "ks_pass（密钥库密码，默认 android）、key_alias（别名，可选）、key_pass（密钥密码，可选）、"
        + "output（输出路径，默认 <apk>.signed.apk）。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.WRITE;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.SHELL;
  }

  /** 涉及密钥库密码与写文件，逐次确认。 */
  @Override
  public boolean isSensitiveCall(JSONObject args) {
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put("apk_path", new JSONObject().put("type", "string").put("description", "输入 APK 路径"))
                .put("keystore", new JSONObject().put("type", "string").put("description", "密钥库 .jks/.keystore 路径"))
                .put("ks_pass", new JSONObject().put("type", "string").put("description", "密钥库密码，默认 android"))
                .put("key_alias", new JSONObject().put("type", "string").put("description", "密钥别名（可选）"))
                .put("key_pass", new JSONObject().put("type", "string").put("description", "密钥密码（可选，默认同 ks_pass）"))
                .put("output", new JSONObject().put("type", "string").put("description", "输出路径，默认 <apk>.signed.apk")))
        .put("required", new org.json.JSONArray().put("apk_path").put("keystore"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String apkPath = input.optString("apk_path", "").trim();
    String keystore = input.optString("keystore", "").trim();
    if (apkPath.isEmpty() || keystore.isEmpty()) {
      return error("apk_path 与 keystore 不能为空");
    }
    String ksPass = input.optString("ks_pass", "android");
    String keyAlias = input.optString("key_alias", "").trim();
    String keyPass = input.optString("key_pass", "").trim();
    String output = input.optString("output", "").trim();
    if (output.isEmpty()) {
      output = apkPath + ".signed.apk";
    }

    String cwd = context == null ? "" : context.getHomePath();
    ShellBackendRegistry.Resolution resolution = registry.resolveActive();
    if (!resolution.isUsable()) {
      return error("没有可用的 shell 后端，无法运行 apksigner。请在设置里启用 Termux 或 Shizuku 后端。");
    }
    ShellBackend backend = resolution.getBackend();

    String execPrefix = resolveApkSigner(backend, cwd);
    if (execPrefix == null) {
      return error(
          ReverseToolSupport.notInstalled(
              "apksigner",
              "apksigner 位于 Android SDK 的 build-tools/<版本>/lib/apksigner.jar。"
                  + "请确认已安装 Android SDK（设置 → SDK 管理器），且环境有 java。"));
    }

    StringBuilder cmd = new StringBuilder(execPrefix).append(" sign");
    cmd.append(" --ks ").append(ReverseToolSupport.quote(keystore));
    cmd.append(" --ks-pass ").append(ReverseToolSupport.quote("pass:" + ksPass));
    if (!keyAlias.isEmpty()) {
      cmd.append(" --ks-key-alias ").append(ReverseToolSupport.quote(keyAlias));
    }
    if (!keyPass.isEmpty()) {
      cmd.append(" --key-pass ").append(ReverseToolSupport.quote("pass:" + keyPass));
    }
    cmd.append(" --out ").append(ReverseToolSupport.quote(output));
    cmd.append(" ").append(ReverseToolSupport.quote(apkPath));

    ToolContext.CancellationSignal runCancel = context == null ? null : context.getCancellation();
    ShellRequest.CancellationSignal cancel = runCancel == null ? null : runCancel::isCancelled;
    ShellRequest.ShellResult result =
        ReverseToolSupport.run(backend, cmd.toString(), cwd, TIMEOUT_MS, cancel);

    String out = result.getStdout() == null ? "" : result.getStdout();
    String err = result.getStderr() == null ? "" : result.getStderr();
    if (result.isTimedOut()) {
      return error("apksigner 签名超时。\n" + ReverseToolSupport.truncate(out + "\n" + err, 2000));
    }
    if (result.getExitCode() != 0) {
      return error(
          "apksigner 签名失败（退出码 " + result.getExitCode() + "）：\n"
              + ReverseToolSupport.truncate(out + "\n" + err, 2000));
    }
    return ok("签名完成：\n" + output + "\n" + ReverseToolSupport.truncate(out + "\n" + err, 1500));
  }

  /** 探测 apksigner.jar（SDK build-tools/lib）+ java，返回 `java -cp <lib>/* ApkSignerTool` 前缀。 */
  private String resolveApkSigner(ShellBackend backend, String cwd) {
    String java = ReverseToolSupport.probeExecutable(backend, "java", cwd);
    if (java == null) {
      return null;
    }
    String cmd =
        "ls $ANDROID_HOME/build-tools/*/lib/apksigner.jar $ANDROID_SDK_ROOT/build-tools/*/lib/apksigner.jar"
            + " 2>/dev/null | sort -V | tail -1";
    ShellRequest.ShellResult r = ReverseToolSupport.run(backend, cmd, cwd, 15_000L, null);
    String jarPath = r.getStdout() == null ? "" : r.getStdout().trim();
    if (!jarPath.startsWith("/")) {
      return null;
    }
    String dir = jarPath.substring(0, jarPath.lastIndexOf('/'));
    return ReverseToolSupport.quote(java)
        + " -cp "
        + ReverseToolSupport.quote(dir + "/*")
        + " com.android.apksigner.ApkSignerTool";
  }
}
