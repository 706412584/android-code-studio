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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * MT 管理器式「不解压编辑」APK：直接重写 ZIP 内目标条目，**不把整包解压到目录**。
 *
 * <p>与 apktool「全量解包 → 改 → 回编译」不同，本工具用 {@link ZipFile} 读 + {@link ZipOutputStream}
 * 流式重写：其余条目原样透传，只对目标条目做替换/打补丁/删除/新增，最后按需重签名。
 *
 * <p>支持的动作：
 * <ul>
 *   <li>{@code replace_text}：替换文本条目内容（改 strings.xml 等）</li>
 *   <li>{@code replace_file}：用本地文件的字节替换目标条目（可改二进制，如 dex/so/图片）</li>
 *   <li>{@code patch_bytes}：在条目内部按偏移打字节补丁（MT 的 patch_bytes，可校验原字节）</li>
 *   <li>{@code delete}：删除一个条目</li>
 *   <li>{@code add}：把本地文件作为一个新条目加入</li>
 * </ul>
 * 重签名用 apksigner；不传 keystore 时输出未签名副本并提示。
 */
public final class ApkEditTool extends BaseTool {

  /** 单条目可整体读入内存打补丁的上限。 */
  private static final long MAX_PATCH_ENTRY_BYTES = 64L * 1024 * 1024;

  private final ShellBackendRegistry registry;

  public ApkEditTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.APK_EDIT;
  }

  @Override
  public String getDescription() {
    return "不解压编辑 APK（MT 管理器式，直接重写 ZIP 内目标条目，不把整包解到目录）。"
        + "参数：apk_path、action、entry、以及各自需要的参数。动作："
        + "replace_text（content=新文本）/ replace_file（source=本地文件的字节）/ "
        + "patch_bytes（patches=[{offset,expected_hex,replacement_hex}]，在条目内部按偏移打补丁）/ "
        + "patch_native_string（old=旧字符串、new=等长新字符串，在 .so 等二进制条目里替换 ASCII 串）/ "
        + "delete（删条目）/ add（source=本地文件，作为新条目加入）。"
        + "keystore/ks_pass 可选：传了就用 apksigner 重签名，不传则输出未签名副本。"
        + "output 默认 <apk>.edited.apk。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.WRITE;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.SHELL;
  }

  @Override
  public boolean isSensitiveCall(JSONObject args) {
    return true;
  }

  @Override
  public boolean needsConfirmation() {
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
                .put(
                    "action",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "replace_text（默认）/ replace_file / patch_bytes / delete / add"))
                .put("entry", new JSONObject().put("type", "string").put("description", "包内目标条目路径"))
                .put("content", new JSONObject().put("type", "string").put("description", "action=replace_text 的新文本"))
                .put("source", new JSONObject().put("type", "string").put("description", "action=replace_file/add 的本地文件路径"))
                .put("old", new JSONObject().put("type", "string").put("description", "action=patch_native_string 的旧字符串"))
                .put("new", new JSONObject().put("type", "string").put("description", "action=patch_native_string 的等长新字符串"))
                .put(
                    "patches",
                    new JSONObject()
                        .put("type", "array")
                        .put("description", "action=patch_bytes 的补丁数组，每项 {offset, expected_hex, replacement_hex}"))
                .put("keystore", new JSONObject().put("type", "string").put("description", "密钥库 .jks（可选，传了才重签名）"))
                .put("ks_pass", new JSONObject().put("type", "string").put("description", "密钥库密码，默认 android"))
                .put("key_alias", new JSONObject().put("type", "string").put("description", "密钥别名（可选）"))
                .put("key_pass", new JSONObject().put("type", "string").put("description", "密钥密码（可选）"))
                .put("output", new JSONObject().put("type", "string").put("description", "输出路径，默认 <apk>.edited.apk")))
        .put("required", new org.json.JSONArray().put("apk_path"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String apkPath = input.optString("apk_path", "").trim();
    String action = input.optString("action", "replace_text").trim().toLowerCase(java.util.Locale.ROOT);
    String entry = input.optString("entry", "").trim();
    if (apkPath.isEmpty()) {
      return error("apk_path 不能为空");
    }
    String home = context == null ? "" : context.getHomePath();
    File srcFile = resolve(apkPath, home);
    if (srcFile == null || !srcFile.isFile()) {
      return error("APK 文件不存在：" + apkPath);
    }
    if (!action.equals("add") && entry.isEmpty()) {
      return error("action=" + action + " 需要 entry（包内目标条目路径）");
    }
    if (action.equals("patch_native_string")) {
      String oldS = input.optString("old", "");
      String newS = input.optString("new", "");
      if (oldS.isEmpty() || newS.isEmpty()) {
        return error("patch_native_string 需要 old 与 new");
      }
      if (oldS.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
          != newS.getBytes(java.nio.charset.StandardCharsets.UTF_8).length) {
        return error("old 与 new 的 UTF-8 字节长度必须相等（二进制原地替换不改条目长度）");
      }
    }

    File sourceFile = null;
    if (action.equals("replace_file") || action.equals("add")) {
      String source = input.optString("source", "").trim();
      if (source.isEmpty()) {
        return error("action=" + action + " 需要 source（本地文件路径）");
      }
      sourceFile = resolve(source, home);
      if (sourceFile == null || !sourceFile.isFile()) {
        return error("source 文件不存在：" + source);
      }
    }

    String output = input.optString("output", "").trim();
    if (output.isEmpty()) {
      output = apkPath + ".edited.apk";
    }
    File outFile = resolve(output, home);
    File temp = new File(outFile.getAbsolutePath() + ".tmp");

    // 1) 流式重写 ZIP（不解压到目录），只改目标条目。
    boolean changed = false;
    String detail;
    try {
      detail = rewrite(srcFile, temp, action, entry, input, sourceFile);
      if (detail == null) {
        temp.delete();
        return error(action.equals("add")
            ? "包内已存在同条目，未覆盖：" + entry
            : "APK 内没有该条目：" + entry);
      }
      changed = true;
    } catch (Exception e) {
      temp.delete();
      return error("编辑 APK 失败：" + e.getMessage());
    }
    if (!changed) {
      temp.delete();
      return error("未做任何改动。");
    }

    // 2) 可选重签名。
    String keystore = input.optString("keystore", "").trim();
    if (keystore.isEmpty()) {
      // 未提供密钥库：直接产出未签名副本。
      if (outFile.exists() && !outFile.delete()) {
        temp.delete();
        return error("无法覆盖已存在的输出文件：" + outFile.getAbsolutePath());
      }
      if (!temp.renameTo(outFile)) {
        temp.delete();
        return error("无法写入输出文件：" + outFile.getAbsolutePath());
      }
      return ok(detail + "\n\n已输出未签名副本：\n" + outFile.getAbsolutePath()
          + "\n（如需可安装，请用 keystore 参数重签名，或改完用 apk_build/apk_resign。）");
    }

    String ksPass = input.optString("ks_pass", "android");
    String keyAlias = input.optString("key_alias", "").trim();
    String keyPass = input.optString("key_pass", "").trim();
    String cwd = home;
    ShellBackendRegistry.Resolution resolution = registry.resolveActive();
    if (!resolution.isUsable()) {
      temp.delete();
      return error("没有可用的 shell 后端，无法重签名。请在设置里启用 Termux 或 Shizuku 后端。");
    }
    ShellBackend backend = resolution.getBackend();
    String signer = resolveApkSigner(backend, cwd);
    if (signer == null) {
      temp.delete();
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
    signCmd.append(" ").append(ReverseToolSupport.quote(temp.getAbsolutePath()));
    ToolContext.CancellationSignal runCancel = context == null ? null : context.getCancellation();
    ShellRequest.CancellationSignal cancel = runCancel == null ? null : runCancel::isCancelled;
    ShellRequest.ShellResult sign =
        ReverseToolSupport.run(backend, signCmd.toString(), cwd, 120_000L, cancel);
    temp.delete();
    if (sign.getExitCode() != 0) {
      return error("apksigner 签名失败（退出码 " + sign.getExitCode() + "）：\n"
          + ReverseToolSupport.truncate(
              (sign.getStderr() == null ? "" : sign.getStderr())
                  + "\n" + (sign.getStdout() == null ? "" : sign.getStdout()), 2000));
    }
    return ok(detail + "\n\n编辑并重签完成：\n" + output);
  }

  /**
   * 把 src 流式重写到 temp，应用动作。
   *
   * @return 成功时返回动作描述；目标条目不存在（且非 add）或 add 已存在时返回 null
   */
  private String rewrite(
      File src, File temp, String action, String entry, JSONObject input, File sourceFile)
      throws Exception {
    boolean found = false;
    try (ZipFile zip = new ZipFile(src);
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(temp))) {
      Enumeration<? extends ZipEntry> en = zip.entries();
      while (en.hasMoreElements()) {
        ZipEntry e = en.nextElement();
        String name = e.getName();
        if (name.equals(entry) && action.equals("delete")) {
          found = true;
          continue; // 跳过即删除
        }
        if (name.equals(entry) && !action.equals("add")) {
          found = true;
          byte[] data;
          if (action.equals("replace_text")) {
            data = input.optString("content", "").getBytes(java.nio.charset.StandardCharsets.UTF_8);
          } else if (action.equals("replace_file")) {
            data = readFile(sourceFile);
          } else if (action.equals("patch_bytes")) {
            data = applyPatches(readZipEntry(zip, e), input.optJSONArray("patches"));
            if (data == null) {
              throw new IllegalArgumentException("补丁与原字节不匹配或越界");
            }
          } else if (action.equals("patch_native_string")) {
            data = patchNativeString(
                readZipEntry(zip, e), input.optString("old", ""), input.optString("new", ""));
            if (data == null) {
              throw new IllegalArgumentException("条目中未找到旧字符串：" + input.optString("old", ""));
            }
          } else {
            throw new IllegalArgumentException("未知 action：" + action);
          }
          out.putNextEntry(new ZipEntry(name));
          out.write(data);
          out.closeEntry();
          continue;
        }
        // 原样透传。
        out.putNextEntry(new ZipEntry(name));
        if (!e.isDirectory()) {
          try (InputStream in = zip.getInputStream(e)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
              out.write(buf, 0, n);
            }
          }
        }
        out.closeEntry();
      }

      if (action.equals("add")) {
        // 已存在则不覆盖（found 含义相反：add 需不存在）。
        boolean exists = zip.getEntry(entry) != null;
        if (exists) {
          return null;
        }
        out.putNextEntry(new ZipEntry(entry));
        byte[] data = readFile(sourceFile);
        out.write(data);
        out.closeEntry();
        return "已新增条目 " + entry + "（" + data.length + " B）";
      }

      if (!found) {
        return null;
      }
    }
    return "已对条目 " + entry + " 执行 " + action;
  }

  private static byte[] readZipEntry(ZipFile zip, ZipEntry e) throws Exception {
    if (e.getSize() > MAX_PATCH_ENTRY_BYTES) {
      throw new IllegalArgumentException(
          "条目过大（" + e.getSize() + " B），超过 patch_bytes 上限 " + MAX_PATCH_ENTRY_BYTES + " B");
    }
    try (InputStream in = zip.getInputStream(e)) {
      return ParseUtil.readAll(in);
    }
  }

  /** 应用字节补丁；任一补丁的原字节不匹配或越界则返回 null。 */
  private static byte[] applyPatches(byte[] data, JSONArray patches) {
    if (patches == null || patches.length() == 0) {
      return null;
    }
    byte[] result = data.clone();
    for (int i = 0; i < patches.length(); i++) {
      JSONObject patch = patches.optJSONObject(i);
      if (patch == null) {
        return null;
      }
      int offset = patch.optInt("offset", -1);
      byte[] replacement = hexToBytes(patch.optString("replacement_hex", ""));
      if (offset < 0 || replacement == null || offset + replacement.length > result.length) {
        return null;
      }
      String expectedHex = patch.optString("expected_hex", "");
      if (!expectedHex.isEmpty()) {
        byte[] expected = hexToBytes(expectedHex);
        if (expected == null || expected.length != replacement.length) {
          return null;
        }
        for (int k = 0; k < expected.length; k++) {
          if (result[offset + k] != expected[k]) {
            return null;
          }
        }
      }
      System.arraycopy(replacement, 0, result, offset, replacement.length);
    }
    return result;
  }

  /** 在二进制里等长替换一段 ASCII 字节串（用于 .so 提示文案/特征串）；未命中返回 null。 */
  private static byte[] patchNativeString(byte[] data, String oldS, String newS) {
    byte[] oldB = oldS.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] newB = newS.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (oldB.length == 0 || oldB.length != newB.length) {
      return null;
    }
    byte[] result = data.clone();
    boolean any = false;
    outer:
    for (int i = 0; i + oldB.length <= result.length; i++) {
      for (int k = 0; k < oldB.length; k++) {
        if (result[i + k] != oldB[k]) {
          continue outer;
        }
      }
      System.arraycopy(newB, 0, result, i, newB.length);
      any = true;
      i += oldB.length - 1;
    }
    return any ? result : null;
  }

  private static byte[] hexToBytes(String hex) {
    if (hex == null) {
      return null;
    }
    String s = hex.replaceAll("\\s+", "");
    if (s.isEmpty() || (s.length() & 1) != 0) {
      return null;
    }
    byte[] out = new byte[s.length() / 2];
    for (int i = 0; i < out.length; i++) {
      int hi = Character.digit(s.charAt(i * 2), 16);
      int lo = Character.digit(s.charAt(i * 2 + 1), 16);
      if (hi < 0 || lo < 0) {
        return null;
      }
      out[i] = (byte) ((hi << 4) | lo);
    }
    return out;
  }

  private static byte[] readFile(File file) throws Exception {
    try (InputStream in = new FileInputStream(file)) {
      return ParseUtil.readAll(in);
    }
  }

  private static File resolve(String path, String home) {
    File file = new File(path);
    if (!file.isAbsolute() && home != null && !home.isEmpty()) {
      file = new File(home, path);
    }
    return file;
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
