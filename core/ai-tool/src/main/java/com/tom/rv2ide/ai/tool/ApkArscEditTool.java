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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;

/**
 * 编辑 resources.arsc 里的**字符串资源值**：按资源 id 找到全局字符串池中的那个值，
 * 等长原地替换，再重写 ZIP（可选重签）。
 *
 * <p><b>为什么是「等长」</b>：arsc 的字符串池用偏移索引，改变某个字符串长度会让后续所有
 * 偏移失效——要么整体重建池（复杂、易错），要么原地替换等长内容。改名/翻译（同语言、
 * 长度相近，如「测试」→「你好」）通常能满足；长度不同则明确报错，提示改用其它方式。
 *
 * <p>使用前可先用 {@code apk_arsc}（lookup）查到目标资源 id。
 */
public final class ApkArscEditTool extends BaseTool {

  private final ShellBackendRegistry registry;

  public ApkArscEditTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.APK_ARSC_EDIT;
  }

  @Override
  public String getDescription() {
    return "编辑 resources.arsc 的字符串资源值（等长原地替换，不解压整包）。参数：apk_path、"
        + "id（资源 id，如 0x7f120000，可先用 apk_arsc lookup 查）、value（新字符串）、"
        + "keystore/ks_pass（可选，传了才重签名）、output（默认 <apk>.edited.apk）。"
        + "注意：新值编码后字节长度必须与旧值相等，否则会拒绝（避免破坏字符串池偏移）。";
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
                .put("id", new JSONObject().put("type", "string").put("description", "资源 id，如 0x7f120000"))
                .put("value", new JSONObject().put("type", "string").put("description", "新的字符串值"))
                .put("keystore", new JSONObject().put("type", "string").put("description", "密钥库 .jks（可选）"))
                .put("ks_pass", new JSONObject().put("type", "string").put("description", "密钥库密码，默认 android"))
                .put("key_alias", new JSONObject().put("type", "string").put("description", "密钥别名（可选）"))
                .put("key_pass", new JSONObject().put("type", "string").put("description", "密钥密码（可选）"))
                .put("output", new JSONObject().put("type", "string").put("description", "输出路径，默认 <apk>.edited.apk")))
        .put("required", new org.json.JSONArray().put("apk_path").put("id").put("value"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String apkPath = input.optString("apk_path", "").trim();
    String idText = input.optString("id", "").trim();
    String newValue = input.optString("value", "");
    if (apkPath.isEmpty() || idText.isEmpty()) {
      return error("apk_path 与 id 不能为空");
    }
    Long id = parseId(idText);
    if (id == null) {
      return error("id 格式不对，应为 0x7f120000 这类十六进制资源 id");
    }
    String home = context == null ? "" : context.getHomePath();
    File file = resolve(apkPath, home);
    if (file == null || !file.isFile()) {
      return error("APK 文件不存在：" + apkPath);
    }

    // 1) 读 arsc 并定位目标字符串。
    byte[] arscBytes;
    ArscParser parser;
    try (ZipFile zip = new ZipFile(file)) {
      ZipEntry entry = zip.getEntry("resources.arsc");
      if (entry == null) {
        return error("APK 内没有 resources.arsc。");
      }
      try (InputStream in = zip.getInputStream(entry)) {
        arscBytes = ParseUtil.readAll(in);
      }
    } catch (Exception e) {
      return error("读取 resources.arsc 失败：" + e.getMessage());
    }
    parser = ArscParser.open(arscBytes);
    if (parser == null) {
      return error("resources.arsc 解析失败。");
    }
    ArscParser.Entry target = null;
    for (ArscParser.Entry e : parser.entries()) {
      if (e.id == id) {
        target = e;
        break;
      }
    }
    if (target == null) {
      return error("未找到资源 id：" + idText + "（先用 apk_arsc lookup 确认 id）");
    }
    if (target.valueType != 0x03) {
      return error("该资源不是字符串类型（dataType=0x" + Integer.toHexString(target.valueType)
          + "），暂只支持改字符串值。");
    }
    int[] slot = parser.globalSlotRange(target.valueData);
    if (slot == null) {
      return error("定位字符串池失败（index=" + target.valueData + "）。");
    }
    byte[] newSlot = buildSlot(newValue, parser.globalIsUtf8());
    if (newSlot == null) {
      return error("构造新字符串 slot 失败。");
    }
    int oldLen = slot[1] - slot[0];
    if (newSlot.length != oldLen) {
      return error("新值编码后 " + newSlot.length + " 字节，旧值 " + oldLen
          + " 字节，长度不相等无法原地替换。请换成长度相近的值（同语言翻译通常可满足）。");
    }
    byte[] newArsc = arscBytes.clone();
    System.arraycopy(newSlot, 0, newArsc, slot[0], newSlot.length);

    // 2) 重写 ZIP，替换 resources.arsc。
    String output = input.optString("output", "").trim();
    if (output.isEmpty()) {
      output = apkPath + ".edited.apk";
    }
    File outFile = resolve(output, home);
    File temp = new File(outFile.getAbsolutePath() + ".tmp");
    try {
      rewriteZip(file, temp, newArsc);
    } catch (Exception e) {
      temp.delete();
      return error("重写 APK 失败：" + e.getMessage());
    }

    // 3) 可选重签名。
    String keystore = input.optString("keystore", "").trim();
    if (keystore.isEmpty()) {
      if (outFile.exists() && !outFile.delete()) {
        temp.delete();
        return error("无法覆盖输出文件：" + outFile.getAbsolutePath());
      }
      if (!temp.renameTo(outFile)) {
        temp.delete();
        return error("无法写入输出文件：" + outFile.getAbsolutePath());
      }
      return ok("已将资源 " + target.idHex() + "（" + target.typeName + "/" + target.keyName
          + "）的值改为：" + newValue + "\n\n已输出未签名副本：\n" + outFile.getAbsolutePath()
          + "\n（如需可安装，请传 keystore 重签名。）");
    }
    return sign(temp, outFile, output, keystore, input.optString("ks_pass", "android"),
        input.optString("key_alias", "").trim(), input.optString("key_pass", "").trim(), home, context);
  }

  /** 按池编码构造新 slot（含长度前缀与结尾 0）。 */
  private static byte[] buildSlot(String value, boolean utf8) {
    if (utf8) {
      byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      writeLen8(out, value.length()); // UTF-16 单元数
      writeLen8(out, bytes.length);
      out.write(bytes, 0, bytes.length);
      out.write(0);
      return out.toByteArray();
    }
    int len = value.length();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (len > 0x7fff) {
      int hi = ((len >> 16) & 0x7fff) | 0x8000;
      out.write(hi & 0xff);
      out.write((hi >> 8) & 0xff);
      out.write(len & 0xff);
      out.write((len >> 8) & 0xff);
    } else {
      out.write(len & 0xff);
      out.write((len >> 8) & 0xff);
    }
    for (int i = 0; i < len; i++) {
      char c = value.charAt(i);
      out.write(c & 0xff);
      out.write((c >> 8) & 0xff);
    }
    out.write(0);
    out.write(0);
    return out.toByteArray();
  }

  private static void writeLen8(ByteArrayOutputStream out, int v) {
    if (v > 0x7f) {
      out.write(((v >> 8) & 0x7f) | 0x80);
      out.write(v & 0xff);
    } else {
      out.write(v);
    }
  }

  /** 流式重写 zip，替换 resources.arsc，其余条目原样透传。 */
  private static void rewriteZip(File src, File temp, byte[] newArsc) throws Exception {
    try (ZipFile zip = new ZipFile(src);
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(temp))) {
      Enumeration<? extends ZipEntry> en = zip.entries();
      boolean replaced = false;
      while (en.hasMoreElements()) {
        ZipEntry e = en.nextElement();
        out.putNextEntry(new ZipEntry(e.getName()));
        if (e.getName().equals("resources.arsc")) {
          out.write(newArsc);
          replaced = true;
        } else if (!e.isDirectory()) {
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
      if (!replaced) {
        throw new IllegalStateException("zip 中没有 resources.arsc");
      }
    }
  }

  private ToolResult sign(
      File unsigned,
      File outFile,
      String outputArg,
      String keystore,
      String ksPass,
      String keyAlias,
      String keyPass,
      String home,
      ToolContext context) {
    ShellBackendRegistry.Resolution resolution = registry.resolveActive();
    if (!resolution.isUsable()) {
      unsigned.delete();
      return error("没有可用的 shell 后端，无法重签名。可在设置里启用 Termux 或 Shizuku，或不传 keystore 输出未签名副本。");
    }
    ShellBackend backend = resolution.getBackend();
    String signer = resolveApkSigner(backend, home);
    if (signer == null) {
      unsigned.delete();
      return error(ReverseToolSupport.notInstalled("apksigner", "见 apk_resign 的安装说明。"));
    }
    StringBuilder cmd = new StringBuilder(signer).append(" sign");
    cmd.append(" --ks ").append(ReverseToolSupport.quote(keystore));
    cmd.append(" --ks-pass ").append(ReverseToolSupport.quote("pass:" + ksPass));
    if (!keyAlias.isEmpty()) {
      cmd.append(" --ks-key-alias ").append(ReverseToolSupport.quote(keyAlias));
    }
    if (!keyPass.isEmpty()) {
      cmd.append(" --key-pass ").append(ReverseToolSupport.quote("pass:" + keyPass));
    }
    cmd.append(" --out ").append(ReverseToolSupport.quote(outputArg));
    cmd.append(" ").append(ReverseToolSupport.quote(unsigned.getAbsolutePath()));
    ToolContext.CancellationSignal runCancel = context == null ? null : context.getCancellation();
    ShellRequest.CancellationSignal cancel = runCancel == null ? null : runCancel::isCancelled;
    ShellRequest.ShellResult r =
        ReverseToolSupport.run(backend, cmd.toString(), home, 120_000L, cancel);
    unsigned.delete();
    if (r.getExitCode() != 0) {
      return error("apksigner 签名失败（退出码 " + r.getExitCode() + "）：\n"
          + ReverseToolSupport.truncate(
              (r.getStderr() == null ? "" : r.getStderr())
                  + "\n" + (r.getStdout() == null ? "" : r.getStdout()), 2000));
    }
    return ok("已修改资源并重签名：\n" + outputArg);
  }

  private static Long parseId(String text) {
    String s = text;
    if (s.startsWith("0x") || s.startsWith("0X")) {
      s = s.substring(2);
    }
    if (s.isEmpty() || s.length() > 8) {
      return null;
    }
    for (int i = 0; i < s.length(); i++) {
      if (Character.digit(s.charAt(i), 16) < 0) {
        return null;
      }
    }
    try {
      return Long.parseLong(s, 16);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static File resolve(String path, String home) {
    File file = new File(path);
    if (!file.isAbsolute() && home != null && !home.isEmpty()) {
      file = new File(home, path);
    }
    return file;
  }

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
