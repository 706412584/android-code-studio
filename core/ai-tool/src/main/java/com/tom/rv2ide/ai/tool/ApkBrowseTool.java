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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;

/**
 * MT 管理器式「不解压」浏览 APK：把 APK 当 ZIP 随机访问，直接列内容、读包信息、读文件。
 *
 * <p><b>与 {@link ApkResourcesTool}（apktool 全量解包）的区别</b>：apktool 要把整个 APK
 * 解到目录（大文件慢、曾超时），而本工具用 {@link ZipFile} 随机读取条目，
 * **不解压、秒级**，与 MT 管理器同一做法。包信息用自写的 {@link AxmlParser}
 * 直接解析 manifest 字节（不依赖 aapt/PackageManager）。
 *
 * <p>纯 Java、无需 shell 后端（ZipFile 在 app 进程内直接读文件路径）。
 */
public final class ApkBrowseTool extends BaseTool {

  /** 列内容时最多列多少条，避免刷屏。 */
  private static final int MAX_LIST = 60;

  @Override
  public String getName() {
    return ToolNames.APK_BROWSE;
  }

  @Override
  public String getDescription() {
    return "不解压浏览 APK（MT 管理器式，把 APK 当 ZIP 随机访问，秒级）。"
        + "action=list 列出包内文件；action=manifest 读包信息（包名/版本/权限/组件，直接解析"
        + "AndroidManifest 字节，不解压）；action=read 读指定文件（entry 参数为包内路径，"
        + "如 AndroidManifest.xml 或 res/values/strings.xml）。比 apktool 全量解包快得多。";
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
                        .put("description", "APK 文件路径（相对工作区根目录或绝对路径）"))
                .put(
                    "action",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "list（列内容，默认）/ manifest（读包信息）/ read（读指定文件）"))
                .put(
                    "entry",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "action=read 时的包内文件路径（如 AndroidManifest.xml）")))
        .put("required", new org.json.JSONArray().put("apk_path"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String apkPath = input.optString("apk_path", "").trim();
    if (apkPath.isEmpty()) {
      return error("apk_path 不能为空");
    }
    String action = input.optString("action", "list").trim().toLowerCase();
    String home = context == null ? "" : context.getHomePath();
    File file = new File(apkPath);
    if (!file.isAbsolute() && !home.isEmpty()) {
      file = new File(home, apkPath);
    }
    if (!file.isFile()) {
      return error("APK 文件不存在或不可读：" + file.getAbsolutePath());
    }

    try (ZipFile zip = new ZipFile(file)) {
      switch (action) {
        case "manifest":
          return readManifest(zip);
        case "read":
          return readEntry(zip, input.optString("entry", "").trim());
        case "list":
        default:
          return listEntries(zip, file);
      }
    } catch (Exception e) {
      return error("读取 APK 失败：" + e.getMessage());
    }
  }

  /** 列出包内文件（名称 + 大小）。 */
  private ToolResult listEntries(ZipFile zip, File file) {
    StringBuilder sb = new StringBuilder();
    sb.append("APK：").append(file.getName())
        .append("（").append(file.length() / 1024).append(" KB）\n");
    List<String> lines = new ArrayList<>();
    Enumeration<? extends ZipEntry> en = zip.entries();
    while (en.hasMoreElements()) {
      ZipEntry e = en.nextElement();
      lines.add(e.getName() + "  (" + e.getSize() + " B)");
    }
    sb.append("共 ").append(lines.size()).append(" 个条目");
    if (lines.size() > MAX_LIST) {
      sb.append("，前 ").append(MAX_LIST).append(" 个：\n");
    } else {
      sb.append("：\n");
    }
    for (int i = 0; i < Math.min(lines.size(), MAX_LIST); i++) {
      sb.append(lines.get(i)).append('\n');
    }
    sb.append("\n用 action=manifest 读包信息、action=read&entry=<路径> 读具体文件。");
    return ok(sb.toString());
  }

  /** 读 AndroidManifest.xml 并用 AxmlParser 解析出包信息（不解压）。 */
  private ToolResult readManifest(ZipFile zip) throws Exception {
    ZipEntry entry = zip.getEntry("AndroidManifest.xml");
    if (entry == null) {
      return error("APK 里没有 AndroidManifest.xml。");
    }
    byte[] bytes;
    try (InputStream in = zip.getInputStream(entry)) {
      bytes = readAll(in);
    }
    AxmlParser.Manifest m = AxmlParser.parse(bytes);
    if (m == null) {
      return error("AndroidManifest.xml 解析失败（AXML 格式无法识别）。");
    }
    StringBuilder sb = new StringBuilder();
    sb.append("package: ").append(m.packageName).append('\n');
    sb.append("versionName: ").append(m.versionName).append('\n');
    sb.append("versionCode: ").append(m.versionCode).append('\n');
    sb.append("minSdk: ").append(m.minSdk).append('\n');
    sb.append("targetSdk: ").append(m.targetSdk).append('\n');
    appendList(sb, "permissions", m.permissions);
    appendList(sb, "activities", m.activities);
    appendList(sb, "services", m.services);
    appendList(sb, "receivers", m.receivers);
    appendList(sb, "providers", m.providers);
    return ok(sb.toString());
  }

  /** 读指定条目内容（文本直接读，二进制给大小与提示）。 */
  private ToolResult readEntry(ZipFile zip, String entryName) throws Exception {
    if (entryName.isEmpty()) {
      return error("action=read 需要 entry 参数（包内文件路径）。");
    }
    ZipEntry entry = zip.getEntry(entryName);
    if (entry == null) {
      return error("包内没有该文件：" + entryName);
    }
    // 二进制（dex/so/图片等）不读全文，只给大小；文本才读。
    String lower = entryName.toLowerCase();
    if (lower.endsWith(".dex") || lower.endsWith(".so") || lower.endsWith(".png")
        || lower.endsWith(".jpg") || lower.endsWith(".webp") || lower.endsWith(".arsc")) {
      return ok(entryName + " 是二进制文件（" + entry.getSize() + " B），"
          + "用 binary_disasm（.so）或 apk_decompile（dex）进一步分析。");
    }
    byte[] bytes;
    try (InputStream in = zip.getInputStream(entry)) {
      bytes = readAll(in);
    }
    String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    return ok(ReverseToolSupport.truncate(text, 8000));
  }

  private static void appendList(StringBuilder sb, String title, List<String> items) {
    if (items.isEmpty()) {
      return;
    }
    sb.append("\n").append(title).append(" (").append(items.size()).append("):\n");
    for (String item : items) {
      sb.append("  ").append(item).append('\n');
    }
  }

  private static byte[] readAll(InputStream in) throws Exception {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) != -1) {
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }
}
