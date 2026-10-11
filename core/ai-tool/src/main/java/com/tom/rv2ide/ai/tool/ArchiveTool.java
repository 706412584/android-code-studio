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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool;

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;

/**
 * 压缩 / 解压（zip）。
 *
 * <p><b>为什么这个工具在手机上尤其必要</b>：手机上常见的操作都要用到它——解压下载的
 * SDK / 依赖压缩包、把项目或产物打包成 zip、备份归档。此前这些只能靠 {@code shell_execute}
 * 手敲 {@code unzip}/{@code zip}（设备上未必有），AI 也没有结构化的失败信息。
 *
 * <p>纯 Java（{@link java.util.zip}），零 Android 依赖，可在 JVM 单测。
 *
 * <p><b>安全</b>（解压是典型的高危操作）：
 * <ul>
 *   <li>所有路径经 {@link FileToolPathPolicy} 限制在工作区（可写根）内
 *   <li><b>Zip Slip 防护</b>：逐条校验 entry 解析后的真实路径仍在目标目录内——否则一个
 *       精心构造的 {@code ../../x} 条目就能写出工作区
 *   <li><b>Zip Bomb 防护</b>：限制条目数与解压后总体积
 * </ul>
 */
public final class ArchiveTool extends BaseTool {

  /** 条目数上限，防 zip bomb。 */
  static final int MAX_ENTRIES = 20_000;

  /** 解压后总体积上限（1GB），防 zip bomb。 */
  static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;

  /** ZIP Slip 防护：目标路径必须仍在解压根目录内。 */
  static final String ERR_ZIP_SLIP = "归档内路径越出目标目录（已拒绝，可能是 Zip Slip）";

  private static final String ACTION_COMPRESS = "compress";
  private static final String ACTION_EXTRACT = "extract";

  @Override
  public String getName() {
    return ToolNames.ARCHIVE;
  }

  @Override
  public String getDescription() {
    return "压缩或解压 zip 归档（手机上处理 SDK/依赖包、打包产物、备份归档）。"
        + "action=compress：把 path（文件或目录）压成 output 指定的 .zip；"
        + "action=extract：把 path（.zip）解压到 output 目录（默认同名目录）。"
        + "路径均相对工作区。解压做了 Zip Slip 与体积/条目数防护。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.WRITE;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.GENERIC;
  }

  @Override
  public boolean needsConfirmation() {
    // 写工作区内容（可能大量文件），按写类工具处理。
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
                    "action",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "compress（压缩）或 extract（解压）")
                        .put(
                            "enum",
                            new org.json.JSONArray().put(ACTION_COMPRESS).put(ACTION_EXTRACT)))
                .put(
                    "path",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "compress：要压缩的文件/目录；extract：要解压的 .zip"))
                .put(
                    "output",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "compress：输出 .zip 路径；extract：解压目标目录（省略则用 zip 同名的目录）")))
        .put("required", new org.json.JSONArray().put("action").put("path"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String action = input.optString("action", "").trim().toLowerCase(Locale.US);
    String rawPath = input.optString("path", "").trim();
    if (rawPath.isEmpty()) {
      return error("path 不能为空。");
    }
    File source;
    try {
      source = FileToolPathPolicy.resolve(context, rawPath);
    } catch (IOException e) {
      return error("路径无效：" + e.getMessage());
    }
    if (!source.exists()) {
      return error("路径不存在：" + display(context, source));
    }

    if (ACTION_COMPRESS.equals(action)) {
      return compress(source, input.optString("output", "").trim(), context);
    }
    if (ACTION_EXTRACT.equals(action)) {
      return extract(source, input.optString("output", "").trim(), context);
    }
    return error("action 只能是 compress 或 extract，收到：" + action);
  }

  // ---------------------------------------------------------------------------
  // 压缩
  // ---------------------------------------------------------------------------

  private ToolResult compress(File source, String rawOutput, ToolContext context) {
    File output;
    try {
      String target = rawOutput.isEmpty() ? defaultArchiveName(source) : rawOutput;
      output = FileToolPathPolicy.resolve(context, target);
    } catch (IOException e) {
      return error("输出路径无效：" + e.getMessage());
    }
    if (!output.getName().toLowerCase(Locale.US).endsWith(".zip")) {
      return error("输出文件必须以 .zip 结尾：" + display(context, output));
    }
    // 不允许把归档压进它自己（会边写边读，产出损坏文件）。
    if (isInside(source, output)) {
      return error("输出 zip 不能位于被压缩的目录内部（会边写边读导致损坏）。");
    }

    List<String> entries = new ArrayList<>();
    try {
      if (output.getParentFile() != null) {
        output.getParentFile().mkdirs();
      }
      try (ZipOutputStream zip =
          new ZipOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(output)))) {
        if (source.isFile()) {
          addFile(zip, source, source.getName(), entries);
        } else {
          File[] children = source.listFiles();
          if (children == null || children.length == 0) {
            return error("目录为空，没有可压缩的内容：" + display(context, source));
          }
          // 以目录自身名作前缀（等价 `zip -r out.zip proj`）：解压后能重建同名目录，
          // 否则解压会把内容平铺到目标目录、丢掉来源目录这一层。
          addEntryRecursively(zip, source, source.getName(), entries);
        }
      }
    } catch (IOException e) {
      return error("压缩失败：" + e.getMessage());
    }
    return ok(
        "压缩完成。\n"
            + "来源: " + display(context, source) + "\n"
            + "归档: " + display(context, output) + "\n"
            + "条目数: " + entries.size() + "\n"
            + "大小: " + (output.length() / 1024) + " KB");
  }

  private static void addEntryRecursively(
      ZipOutputStream zip, File file, String entryName, List<String> entries) throws IOException {
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children == null || children.length == 0) {
        // 空目录也要留一个条目，否则解压后目录结构丢失。
        zip.putNextEntry(new ZipEntry(entryName + "/"));
        zip.closeEntry();
        entries.add(entryName + "/");
        return;
      }
      for (File child : sortedFiles(children)) {
        addEntryRecursively(zip, child, entryName + "/" + child.getName(), entries);
      }
      return;
    }
    addFile(zip, file, entryName, entries);
  }

  private static void addFile(
      ZipOutputStream zip, File file, String entryName, List<String> entries) throws IOException {
    zip.putNextEntry(new ZipEntry(entryName));
    try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) >= 0) {
        zip.write(buffer, 0, read);
      }
    }
    zip.closeEntry();
    entries.add(entryName);
  }

  private static File[] sortedFiles(File[] files) {
    List<File> list = new ArrayList<>();
    Collections.addAll(list, files);
    // 稳定顺序：同名同结构的目录打出的 zip 尽量一致，便于比对。
    Collections.sort(list, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
    return list.toArray(new File[0]);
  }

  private static String defaultArchiveName(File source) {
    String base = source.getName();
    int dot = base.lastIndexOf('.');
    if (source.isFile() && dot > 0) {
      base = base.substring(0, dot);
    }
    File parent = source.getParentFile();
    return (parent == null ? "" : parent.getAbsolutePath() + File.separator) + base + ".zip";
  }

  // ---------------------------------------------------------------------------
  // 解压
  // ---------------------------------------------------------------------------

  private ToolResult extract(File archive, String rawOutput, ToolContext context) {
    if (!archive.isFile()) {
      return error("要解压的必须是文件：" + display(context, archive));
    }
    File targetDir;
    try {
      String target = rawOutput.isEmpty() ? defaultExtractDir(archive) : rawOutput;
      targetDir = FileToolPathPolicy.resolve(context, target);
    } catch (IOException e) {
      return error("目标目录无效：" + e.getMessage());
    }
    if (targetDir.exists() && !targetDir.isDirectory()) {
      return error("目标路径已存在且不是目录：" + display(context, targetDir));
    }
    if (!targetDir.exists() && !targetDir.mkdirs() && !targetDir.isDirectory()) {
      return error("无法创建目标目录：" + display(context, targetDir));
    }

    String targetRoot = targetDir.getAbsolutePath();
    int entries = 0;
    long totalBytes = 0L;
    try (ZipInputStream zip =
        new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
      ZipEntry entry;
      byte[] buffer = new byte[8192];
      while ((entry = zip.getNextEntry()) != null) {
        if (++entries > MAX_ENTRIES) {
          return error("归档条目数超过上限 " + MAX_ENTRIES + "（可能是 zip bomb），已中止。");
        }
        File out = safeResolve(targetRoot, entry.getName());
        if (out == null) {
          return error(ERR_ZIP_SLIP + "：" + entry.getName());
        }
        if (entry.isDirectory()) {
          if (!out.isDirectory() && !out.mkdirs() && !out.isDirectory()) {
            return error("无法创建目录：" + out.getAbsolutePath());
          }
          continue;
        }
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
          return error("无法创建目录：" + parent.getAbsolutePath());
        }
        try (FileOutputStream fos = new FileOutputStream(out)) {
          int read;
          while ((read = zip.read(buffer)) >= 0) {
            totalBytes += read;
            if (totalBytes > MAX_TOTAL_BYTES) {
              return error("解压后总体积超过上限 " + (MAX_TOTAL_BYTES / 1024 / 1024) + "MB（可能是 zip bomb），已中止。");
            }
            fos.write(buffer, 0, read);
          }
        }
      }
    } catch (IOException e) {
      return error("解压失败：" + e.getMessage());
    }
    return ok(
        "解压完成。\n"
            + "归档: " + display(context, archive) + "\n"
            + "目标目录: " + display(context, targetDir) + "\n"
            + "条目数: " + entries + "\n"
            + "解压后大小: " + (totalBytes / 1024) + " KB");
  }

  private static String defaultExtractDir(File archive) {
    String name = archive.getName();
    if (name.toLowerCase(Locale.US).endsWith(".zip")) {
      name = name.substring(0, name.length() - 4);
    }
    File parent = archive.getParentFile();
    return (parent == null ? "" : parent.getAbsolutePath() + File.separator) + name;
  }

  /**
   * 把归档条目名解析到目标目录内，越界返回 {@code null}。
   *
   * <p>这是 Zip Slip 的防线：{@code ZipEntry} 的名字完全由归档作者控制，可能是
   * {@code ../../etc/x} 或绝对路径。必须用**规范化后的真实路径**判断是否仍在
   * {@code targetRoot} 之下，而不是相信原字符串。
   */
  static File safeResolve(String targetRoot, String entryName) {
    if (entryName == null || entryName.isEmpty()) {
      return null;
    }
    try {
      File candidate = new File(targetRoot, entryName).getCanonicalFile();
      String root = new File(targetRoot).getCanonicalFile().getAbsolutePath();
      String path = candidate.getAbsolutePath();
      if (path.equals(root) || path.startsWith(root + File.separator)) {
        return candidate;
      }
      return null;
    } catch (IOException e) {
      return null;
    }
  }

  private static boolean isInside(File dir, File candidate) {
    if (!dir.isDirectory()) {
      return false;
    }
    try {
      String root = dir.getCanonicalFile().getAbsolutePath();
      String path = candidate.getCanonicalFile().getAbsolutePath();
      return path.startsWith(root + File.separator);
    } catch (IOException e) {
      return false;
    }
  }

  private static String display(ToolContext context, File file) {
    try {
      return FileToolPathPolicy.displayPath(context.getHomePath(), file);
    } catch (IOException e) {
      return file.getAbsolutePath();
    }
  }
}
