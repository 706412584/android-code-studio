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
 * 把 apktool 解包目录**回编译并重签名为可安装 APK**（对应 MT 的 build）。
 *
 * <p>工作流：`apktool b <解包目录> -o <未签名.apk>` 回编译，再用 apksigner 重签名。
 * 与 {@link ApkResignTool}（只重签一个现成 APK）不同，本工具多了「解包目录 → 回编译」这一步，
 * 因此可以支撑「解包 → 改 smali/资源 → 回编译重签」的完整改包闭环。
 */
public final class ApkBuildTool extends BaseTool {

  private static final long TIMEOUT_MS = 300_000L;

  private final ShellBackendRegistry registry;

  public ApkBuildTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.APK_BUILD;
  }

  @Override
  public String getDescription() {
    return "把 apktool 解包目录回编译并重签名为可安装 APK（对应 MT 的 build）。"
        + "参数：input_dir（apktool 解包目录）、keystore（密钥库 .jks）、ks_pass（密码，默认 android）、"
        + "key_alias/key_pass（可选）、output（输出路径，默认 <dir>.signed.apk）。"
        + "支撑「解包 → 改 smali/资源 → 回编译重签」的改包闭环。";
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
                .put("input_dir", new JSONObject().put("type", "string").put("description", "apktool 解包目录"))
                .put("keystore", new JSONObject().put("type", "string").put("description", "密钥库 .jks 路径"))
                .put("ks_pass", new JSONObject().put("type", "string").put("description", "密钥库密码，默认 android"))
                .put("key_alias", new JSONObject().put("type", "string").put("description", "密钥别名（可选）"))
                .put("key_pass", new JSONObject().put("type", "string").put("description", "密钥密码（可选）"))
                .put("output", new JSONObject().put("type", "string").put("description", "输出路径，默认 <dir>.signed.apk")))
        .put("required", new org.json.JSONArray().put("input_dir").put("keystore"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String inputDir = input.optString("input_dir", "").trim();
    String keystore = input.optString("keystore", "").trim();
    if (inputDir.isEmpty() || keystore.isEmpty()) {
      return error("input_dir 与 keystore 不能为空");
    }
    String ksPass = input.optString("ks_pass", "android");
    String keyAlias = input.optString("key_alias", "").trim();
    String keyPass = input.optString("key_pass", "").trim();
    String output = input.optString("output", "").trim();
    if (output.isEmpty()) {
      output = inputDir + ".signed.apk";
    }
    String unsigned = inputDir + ".unsigned.apk";

    String cwd = context == null ? "" : context.getHomePath();
    ShellBackendRegistry.Resolution resolution = registry.resolveActive();
    if (!resolution.isUsable()) {
      return error("没有可用的 shell 后端。请在设置里启用 Termux 或 Shizuku 后端。");
    }
    ShellBackend backend = resolution.getBackend();
    ToolContext.CancellationSignal runCancel = context == null ? null : context.getCancellation();
    ShellRequest.CancellationSignal cancel = runCancel == null ? null : runCancel::isCancelled;

    // 1) apktool b 回编译。
    String apktool = ReverseToolSupport.probeExecutable(backend, "apktool", cwd);
    String jarPrefix = null;
    if (apktool == null) {
      jarPrefix = ReverseToolSupport.probeJarTool(backend, "apktool.jar", null, cwd);
    }
    if (apktool == null && jarPrefix == null) {
      return error(ReverseToolSupport.notInstalled("apktool", "见 apk_resources 的安装说明。"));
    }
    String execPrefix = apktool != null ? ReverseToolSupport.quote(apktool) : jarPrefix;
    String buildCmd =
        execPrefix + " b " + ReverseToolSupport.quote(inputDir)
            + " -o " + ReverseToolSupport.quote(unsigned);
    ShellRequest.ShellResult build = ReverseToolSupport.run(backend, buildCmd, cwd, TIMEOUT_MS, cancel);
    if (build.getExitCode() != 0) {
      return error("apktool 回编译失败（退出码 " + build.getExitCode() + "）：\n"
          + ReverseToolSupport.truncate(
              (build.getStderr() == null ? "" : build.getStderr())
                  + "\n" + (build.getStdout() == null ? "" : build.getStdout()), 2000));
    }

    // 2) apksigner 重签名。
    String signer = resolveApkSigner(backend, cwd);
    if (signer == null) {
      return error(ReverseToolSupport.notInstalled("apksigner", "见 apk_resign 的安装说明。"));
    }
    StringBuilder signCmd = new StringBuilder(signer).append(" sign");
    signCmd.append(" --ks ").append(ReverseToolSupport.quote(keystore));
    signCmd.append(" --ks-pass ").append(ReverseToolSupport.quote("pass:" + ksPass));
    if (!keyAlias.isEmpty()) {
      signCmd.append(" --ks-key-alias ").append(ReverseToolSupport.quote(keyAlias));
    }
    if (!keyPass.isEmpty()) {
      signCmd.append(" --key-pass ").append(ReverseToolSupport.quote("pass:" + keyPass));
    }
    signCmd.append(" --out ").append(ReverseToolSupport.quote(output));
    signCmd.append(" ").append(ReverseToolSupport.quote(unsigned));
    ShellRequest.ShellResult sign = ReverseToolSupport.run(backend, signCmd.toString(), cwd, 120_000L, cancel);
    if (sign.getExitCode() != 0) {
      return error("apksigner 签名失败（退出码 " + sign.getExitCode() + "）：\n"
          + ReverseToolSupport.truncate(
              (sign.getStderr() == null ? "" : sign.getStderr())
                  + "\n" + (sign.getStdout() == null ? "" : sign.getStdout()), 2000));
    }
    return ok("回编译并签名完成：\n" + output);
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
