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
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;

/**
 * 在 APK **内部**搜索，补上 {@code grep} 只能搜磁盘文件的空白：APK 是个 ZIP，
 * 条目名、纯文本条目、dex 字符串表都不在磁盘上。
 *
 * <p>三个搜索目标（可叠加）：
 * <ul>
 *   <li>{@code entries}：ZIP 条目路径名（找「包内有没有这个文件」）</li>
 *   <li>{@code text}：纯文本条目内容（assets、META-INF、kotlin 元数据等）</li>
 *   <li>{@code dex}：各 classes*.dex 的字符串表（找字符串字面量，如 URL、提示文案、
 *       类名/方法名片段）</li>
 * </ul>
 * 纯 Java、无需 shell 后端、不解压到磁盘。
 */
public final class ApkSearchTool extends BaseTool {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_TEXT_ENTRY_BYTES = 4 * 1024 * 1024;

  @Override
  public String getName() {
    return ToolNames.APK_SEARCH;
  }

  @Override
  public String getDescription() {
    return "在 APK 内部按关键词搜索（不解压、纯 Java）。target 可选："
        + "entries（搜 ZIP 条目名）/ text（搜纯文本条目内容）/ dex（搜 classes*.dex 的字符串表）/ "
        + "native（扫 lib/**/*.so 的可打印字符串）/ all（默认，四者都搜）。"
        + "参数：apk_path、query、target、case_sensitive（默认 false）、limit（默认 50）。"
        + "适合「这个包里有没有某个类/字符串/配置/SO里的符号文字」这类问题。";
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
                .put("apk_path", new JSONObject().put("type", "string").put("description", "APK 文件路径"))
                .put("query", new JSONObject().put("type", "string").put("description", "搜索关键词"))
                .put(
                    "target",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "entries / text / dex / native / all（默认 all）"))
                .put(
                    "case_sensitive",
                    new JSONObject().put("type", "boolean").put("description", "是否区分大小写，默认 false"))
                .put(
                    "limit",
                    new JSONObject()
                        .put("type", "integer")
                        .put("description", "最多返回多少条命中，默认 50")))
        .put("required", new org.json.JSONArray().put("apk_path").put("query"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String apkPath = input.optString("apk_path", "").trim();
    String query = input.optString("query", "");
    if (apkPath.isEmpty() || query.isEmpty()) {
      return error("apk_path 与 query 不能为空");
    }
    String target = input.optString("target", "all").trim().toLowerCase(Locale.ROOT);
    boolean caseSensitive = input.optBoolean("case_sensitive", false);
    int limit = input.optInt("limit", DEFAULT_LIMIT);
    if (limit <= 0) {
      limit = DEFAULT_LIMIT;
    }
    String home = context == null ? "" : context.getHomePath();
    File file = new File(apkPath);
    if (!file.isAbsolute() && !home.isEmpty()) {
      file = new File(home, apkPath);
    }
    if (!file.isFile()) {
      return error("APK 文件不存在或不可读：" + file.getAbsolutePath());
    }

    boolean doEntries = target.equals("all") || target.equals("entries");
    boolean doText = target.equals("all") || target.equals("text");
    boolean doDex = target.equals("all") || target.equals("dex");
    boolean doNative = target.equals("all") || target.equals("native");
    if (!doEntries && !doText && !doDex && !doNative) {
      return error("target 只能是 entries / text / dex / native / all");
    }

    List<String> hits = new ArrayList<>();
    int[] counts = new int[4]; // entries, text, dex, native
    try (ZipFile zip = new ZipFile(file)) {
      Enumeration<? extends ZipEntry> en = zip.entries();
      while (en.hasMoreElements()) {
        if (hits.size() >= limit) {
          break;
        }
        ZipEntry entry = en.nextElement();
        String name = entry.getName();
        if (doEntries && matches(name, query, caseSensitive)) {
          counts[0]++;
          if (hits.size() < limit) {
            hits.add("[entry] " + name);
          }
        }
        if (entry.isDirectory()) {
          continue;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (doDex && isDexEntry(lower)) {
          if (searchDex(zip, entry, query, caseSensitive, hits, limit)) {
            counts[2]++;
          }
          continue;
        }
        if (doNative && lower.endsWith(".so")) {
          if (searchNative(zip, entry, query, caseSensitive, hits, limit)) {
            counts[3]++;
          }
          continue;
        }
        if (doText && isTextEntry(lower) && entry.getSize() >= 0
            && entry.getSize() <= MAX_TEXT_ENTRY_BYTES) {
          int matched = searchText(zip, entry, query, caseSensitive, hits, limit);
          if (matched > 0) {
            counts[1]++;
          }
        }
      }
    } catch (Exception e) {
      return error("搜索 APK 失败：" + e.getMessage());
    }

    StringBuilder sb = new StringBuilder();
    sb.append("在 ").append(file.getName()).append(" 中搜索 \"").append(query).append("\"\n");
    sb.append("命中：entries ").append(counts[0])
        .append(" 个文件、text ").append(counts[1])
        .append(" 个文件、dex ").append(counts[2]).append(" 个 dex、native ")
        .append(counts[3]).append(" 个 so\n");
    if (hits.isEmpty()) {
      sb.append("（无匹配）");
    } else {
      sb.append('\n');
      for (String hit : hits) {
        sb.append(hit).append('\n');
      }
      if (hits.size() >= limit) {
        sb.append("… 已达上限 ").append(limit).append("，可缩小 target 或提高 limit。\n");
      }
    }
    return ok(sb.toString());
  }

  private static boolean isDexEntry(String lowerName) {
    return lowerName.matches("classes[0-9]*\\.dex");
  }

  /**
   * 是否按纯文本搜索。绝大多数二进制格式（dex/so/图片/音视频/字体/压缩包/arsc）
   * 直接排除；res/ 下是编译后的二进制资源（AXML/图片），也排除；而
   * AndroidManifest.xml 虽是 AXML，同样排除。其余小条目按文本读。
   */
  private static boolean isTextEntry(String lowerName) {
    if (lowerName.startsWith("res/") || lowerName.equals("androidmanifest.xml")) {
      return false;
    }
    return !(lowerName.endsWith(".dex")
        || lowerName.endsWith(".so")
        || lowerName.endsWith(".arsc")
        || lowerName.endsWith(".png")
        || lowerName.endsWith(".jpg")
        || lowerName.endsWith(".jpeg")
        || lowerName.endsWith(".webp")
        || lowerName.endsWith(".gif")
        || lowerName.endsWith(".zip")
        || lowerName.endsWith(".jar")
        || lowerName.endsWith(".aar")
        || lowerName.endsWith(".ogg")
        || lowerName.endsWith(".mp3")
        || lowerName.endsWith(".mp4")
        || lowerName.endsWith(".wav")
        || lowerName.endsWith(".ttf")
        || lowerName.endsWith(".otf")
        || lowerName.endsWith(".woff")
        || lowerName.endsWith(".woff2")
        || lowerName.endsWith(".bin")
        || lowerName.endsWith(".dat"));
  }

  private static boolean searchDex(
      ZipFile zip, ZipEntry entry, String query, boolean caseSensitive, List<String> hits, int limit) {
    try {
      byte[] bytes;
      try (InputStream in = zip.getInputStream(entry)) {
        bytes = ParseUtil.readAll(in);
      }
      DexParser dex = DexParser.open(bytes);
      if (dex == null) {
        return false;
      }
      boolean any = false;
      for (String s : dex.allStrings()) {
        if (matches(s, query, caseSensitive)) {
          any = true;
          if (hits.size() < limit) {
            hits.add("[dex:" + entry.getName() + "] " + shorten(s, 160));
          } else {
            break;
          }
        }
      }
      return any;
    } catch (Exception e) {
      return false;
    }
  }

  /** 在 .so 里扫可打印 ASCII 字符串（连续 ≥4 个可打印字符），匹配 query。 */
  private static boolean searchNative(
      ZipFile zip, ZipEntry entry, String query, boolean caseSensitive, List<String> hits, int limit) {
    try {
      byte[] bytes;
      try (InputStream in = zip.getInputStream(entry)) {
        bytes = ParseUtil.readAll(in);
      }
      boolean any = false;
      StringBuilder run = new StringBuilder();
      for (int i = 0; i <= bytes.length; i++) {
        int b = i < bytes.length ? (bytes[i] & 0xff) : 0;
        if (b >= 0x20 && b < 0x7f) {
          run.append((char) b);
        } else {
          if (run.length() >= 4 && matches(run.toString(), query, caseSensitive)) {
            any = true;
            if (hits.size() < limit) {
              hits.add("[native:" + entry.getName() + "] " + shorten(run.toString(), 160));
            } else {
              break;
            }
          }
          run.setLength(0);
        }
      }
      return any;
    } catch (Exception e) {
      return false;
    }
  }

  private static int searchText(
      ZipFile zip, ZipEntry entry, String query, boolean caseSensitive, List<String> hits, int limit) {
    try {
      byte[] bytes;
      try (InputStream in = zip.getInputStream(entry)) {
        bytes = ParseUtil.readAll(in);
      }
      String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
      int matched = 0;
      String[] lines = text.split("\n", -1);
      for (int i = 0; i < lines.length; i++) {
        if (matches(lines[i], query, caseSensitive)) {
          matched++;
          if (hits.size() < limit) {
            hits.add("[text:" + entry.getName() + ":" + (i + 1) + "] " + shorten(lines[i].trim(), 160));
          } else {
            break;
          }
        }
      }
      return matched;
    } catch (Exception e) {
      return 0;
    }
  }

  private static boolean matches(String haystack, String needle, boolean caseSensitive) {
    if (haystack == null) {
      return false;
    }
    if (caseSensitive) {
      return haystack.contains(needle);
    }
    return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
  }

  private static String shorten(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max) + "…";
  }
}
