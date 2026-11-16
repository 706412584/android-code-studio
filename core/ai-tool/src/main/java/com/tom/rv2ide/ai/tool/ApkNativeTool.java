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
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;

/**
 * 原生符号 xref（库级）：跨 APK 内 lib/ 下所有 .so，判断某符号被哪些库
 * **导出（定义）**、被哪些库**导入（引用）**。
 *
 * <p>与 {@link NativeSymbolsTool}（readelf 列单个 .so 符号）的分工：那个看「一个库里有什么」，
 * 本工具回答「某个符号在整个包里谁定义、谁使用」——纯 Java 解析 ELF 符号表，不依赖 readelf。
 *
 * <p>局限：这是**库级别**的引用关系；函数级「谁调用了谁」需要反汇编（binary_disasm/radare2）。
 */
public final class ApkNativeTool extends BaseTool {

  private static final int DEFAULT_LIMIT = 60;

  @Override
  public String getName() {
    return ToolNames.APK_NATIVE;
  }

  @Override
  public String getDescription() {
    return "原生符号 xref（纯 Java 解析 ELF）。action=xref：给定 symbol，跨 APK 内所有 lib/**/*.so "
        + "列出哪些库导出它、哪些库导入它；action=symbols：列出指定 lib（包内路径）的导出/导入符号。"
        + "参数：apk_path、action、symbol（xref 用）、lib（symbols 用）、limit（默认 60）。"
        + "这是库级引用；函数级调用点用 binary_disasm。";
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
                .put("action", new JSONObject().put("type", "string").put("description", "xref（默认）/ symbols"))
                .put("symbol", new JSONObject().put("type", "string").put("description", "action=xref 的符号名"))
                .put("lib", new JSONObject().put("type", "string").put("description", "action=symbols 的包内 .so 路径"))
                .put("limit", new JSONObject().put("type", "integer").put("description", "最多输出多少条，默认 60")))
        .put("required", new org.json.JSONArray().put("apk_path"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String apkPath = input.optString("apk_path", "").trim();
    if (apkPath.isEmpty()) {
      return error("apk_path 不能为空");
    }
    String action = input.optString("action", "xref").trim().toLowerCase(Locale.ROOT);
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
      return error("APK 文件不存在：" + apkPath);
    }

    if (action.equals("symbols")) {
      String lib = input.optString("lib", "").trim();
      if (lib.isEmpty()) {
        return error("action=symbols 需要 lib（包内路径，如 lib/arm64-v8a/libx.so）");
      }
      return symbols(file, lib, limit);
    }

    String symbol = input.optString("symbol", "").trim();
    if (symbol.isEmpty()) {
      return error("action=xref 需要 symbol");
    }
    return xref(file, symbol, limit);
  }

  private ToolResult xref(File file, String symbol, int limit) {
    List<String> defines = new ArrayList<>();
    List<String> imports = new ArrayList<>();
    try (ZipFile zip = new ZipFile(file)) {
      Enumeration<? extends ZipEntry> en = zip.entries();
      while (en.hasMoreElements()) {
        ZipEntry e = en.nextElement();
        if (!isSo(e.getName())) {
          continue;
        }
        ElfSymbols.Result r = readSymbols(zip, e);
        if (r == null) {
          continue;
        }
        if (r.defined.contains(symbol)) {
          defines.add(e.getName());
        }
        if (r.undefined.contains(symbol)) {
          imports.add(e.getName());
        }
      }
    } catch (Exception e) {
      return error("解析 APK 失败：" + e.getMessage());
    }
    StringBuilder sb = new StringBuilder();
    sb.append("符号 \"").append(symbol).append("\" 的库级 xref：\n");
    sb.append("导出（定义）它的库 (").append(defines.size()).append("):\n");
    appendCapped(sb, defines, limit);
    sb.append("导入（引用）它的库 (").append(imports.size()).append("):\n");
    appendCapped(sb, imports, limit);
    if (defines.isEmpty() && imports.isEmpty()) {
      sb.append("（未在任何 .so 的动态/静态符号表中找到该符号；注意 C++ 符号已 mangle，"
          + "可先用 apk_native action=xref 试原名或 _Z 前缀版本。）");
    }
    return ok(sb.toString());
  }

  private ToolResult symbols(File file, String lib, int limit) {
    try (ZipFile zip = new ZipFile(file)) {
      ZipEntry e = zip.getEntry(lib);
      if (e == null) {
        return error("包内没有该文件：" + lib);
      }
      ElfSymbols.Result r = readSymbols(zip, e);
      if (r == null) {
        return error(lib + " 不是可解析的 ELF（或非小端）。");
      }
      StringBuilder sb = new StringBuilder();
      sb.append(lib).append("\n导出/定义 (").append(r.defined.size()).append("):\n");
      appendCapped(sb, new ArrayList<>(r.defined), limit);
      sb.append("导入/未定义 (").append(r.undefined.size()).append("):\n");
      appendCapped(sb, new ArrayList<>(r.undefined), limit);
      return ok(sb.toString());
    } catch (Exception ex) {
      return error("读取失败：" + ex.getMessage());
    }
  }

  private static ElfSymbols.Result readSymbols(ZipFile zip, ZipEntry e) {
    try {
      byte[] bytes;
      try (InputStream in = zip.getInputStream(e)) {
        bytes = ParseUtil.readAll(in);
      }
      return ElfSymbols.read(bytes);
    } catch (Exception ex) {
      return null;
    }
  }

  private static boolean isSo(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    return lower.startsWith("lib/") && lower.endsWith(".so");
  }

  private static void appendCapped(StringBuilder sb, List<String> items, int limit) {
    if (items.isEmpty()) {
      sb.append("  （无）\n");
      return;
    }
    int shown = 0;
    for (String item : items) {
      if (shown >= limit) {
        sb.append("  … 还有 ").append(items.size() - shown).append(" 个\n");
        break;
      }
      sb.append("  ").append(item).append('\n');
      shown++;
    }
  }

  /** 便于将来扩展的最小占位：是否有任何符号集合包含给定名字。 */
  @SuppressWarnings("unused")
  private static boolean contains(Set<String> set, String name) {
    return set != null && set.contains(name);
  }
}
