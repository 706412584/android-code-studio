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
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;

/**
 * 读取 APK 的 {@code resources.arsc} 资源表：按资源 id / 名字查类型与值。
 *
 * <p><b>为什么需要它</b>：{@code apk_browse} 只能读 ZIP 原始字节，看不到 arsc 内部；
 * {@code apk_resources}（apktool）会把资源全量解出来但慢。想快速回答
 * 「{@code 0x7f0f0001} 是什么」「哪个资源的值是这段文字」时，直接解析 arsc 最快。
 *
 * <p>纯 Java、无需 shell 后端。复杂条目（style/array 等 bag）标 {@code <complex>}，
 * 不展开其 map 明细；不同限定符（density/locale）下的同名条目会各占一行。
 */
public final class ApkArscTool extends BaseTool {

  private static final int DEFAULT_LIMIT = 100;

  @Override
  public String getName() {
    return ToolNames.APK_ARSC;
  }

  @Override
  public String getDescription() {
    return "读取 APK 的 resources.arsc 资源表（纯 Java，不解压到磁盘）。action："
        + "list（列出资源）/ lookup（按 id 或名查值）。参数：apk_path、action、"
        + "query（id 如 0x7f0f0001，或名字子串）、type（类型名过滤，如 string/drawable）、"
        + "limit（默认 100）。每条格式：资源id 类型/名 = 值。";
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
                .put(
                    "action",
                    new JSONObject().put("type", "string").put("description", "list（默认）/ lookup"))
                .put(
                    "query",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "资源 id（0x7f0f0001）或名字/类型子串"))
                .put(
                    "type",
                    new JSONObject().put("type", "string").put("description", "类型名过滤（如 string）"))
                .put(
                    "limit",
                    new JSONObject().put("type", "integer").put("description", "最多返回多少条，默认 100")))
        .put("required", new org.json.JSONArray().put("apk_path"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String apkPath = input.optString("apk_path", "").trim();
    if (apkPath.isEmpty()) {
      return error("apk_path 不能为空");
    }
    String action = input.optString("action", "list").trim().toLowerCase(Locale.ROOT);
    String query = input.optString("query", "").trim();
    String typeFilter = input.optString("type", "").trim();
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
    if (action.equals("lookup") && query.isEmpty() && typeFilter.isEmpty()) {
      return error("action=lookup 需要 query（资源 id 或名字）或 type 过滤");
    }

    ArscParser parser;
    try (ZipFile zip = new ZipFile(file)) {
      ZipEntry entry = zip.getEntry("resources.arsc");
      if (entry == null) {
        return error("APK 内没有 resources.arsc。");
      }
      byte[] bytes;
      try (InputStream in = zip.getInputStream(entry)) {
        bytes = ParseUtil.readAll(in);
      }
      parser = ArscParser.open(bytes);
    } catch (Exception e) {
      return error("读取 resources.arsc 失败：" + e.getMessage());
    }
    if (parser == null) {
      return error("resources.arsc 解析失败（格式无法识别）。");
    }

    Long idQuery = parseId(query);
    String q = query.toLowerCase(Locale.ROOT);
    String typeLower = typeFilter.toLowerCase(Locale.ROOT);

    List<ArscParser.Entry> entries = parser.entries();
    int matched = 0;
    StringBuilder sb = new StringBuilder();
    sb.append("resources.arsc：共 ").append(entries.size()).append(" 条");
    if (!query.isEmpty()) {
      sb.append("，过滤 \"").append(query).append("\"");
    }
    if (!typeFilter.isEmpty()) {
      sb.append("，type=").append(typeFilter);
    }
    sb.append("：\n");

    for (ArscParser.Entry e : entries) {
      if (!matches(e, idQuery, q, typeLower)) {
        continue;
      }
      matched++;
      if (matched <= limit) {
        sb.append(e.idHex()).append("  ").append(e.typeName).append('/').append(e.keyName)
            .append(" = ").append(e.value).append('\n');
      }
    }
    if (matched == 0) {
      sb.append("（无匹配资源）");
    } else if (matched > limit) {
      sb.append("… 共 ").append(matched).append(" 条命中，已显示前 ").append(limit).append(" 条。\n");
    }
    return ok(sb.toString());
  }

  /** 把 {@code 0x7f0f0001} 或 {@code 7f0f0001} 形式的查询解析成资源 id；否则返回 null。 */
  private static Long parseId(String query) {
    if (query.isEmpty()) {
      return null;
    }
    String s = query;
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

  private static boolean matches(ArscParser.Entry e, Long idQuery, String q, String typeLower) {
    if (!typeLower.isEmpty() && !e.typeName.toLowerCase(Locale.ROOT).equals(typeLower)) {
      return false;
    }
    if (idQuery != null) {
      return e.id == idQuery;
    }
    if (q.isEmpty()) {
      return true;
    }
    return e.keyName.toLowerCase(Locale.ROOT).contains(q)
        || e.typeName.toLowerCase(Locale.ROOT).contains(q)
        || e.value.toLowerCase(Locale.ROOT).contains(q);
  }
}
