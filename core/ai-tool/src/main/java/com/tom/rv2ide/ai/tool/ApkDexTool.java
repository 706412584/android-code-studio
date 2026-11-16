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
 * 不解压随机访问 APK 里的 DEX：列类、读单个类的字段与方法摘要。
 *
 * <p>与 {@code apk_decompile}（jadx 全量 → Java 源码）的分工：jadx 要把整个 APK 反编译成
 * 一堆文件（慢、占空间），而本工具只想回答「有哪些类 / 这个类长什么样」——直接读 dex 的
 * class_def 表，秒级，适合在决定要不要上 jadx 之前先做侦察。
 *
 * <p>纯 Java、无需 shell 后端。全部 classes*.dex 都会被读入并合并。
 */
public final class ApkDexTool extends BaseTool {

  private static final int DEFAULT_LIMIT = 80;
  private static final int MEMBER_OUTPUT_LIMIT = 300;

  @Override
  public String getName() {
    return ToolNames.APK_DEX;
  }

  @Override
  public String getDescription() {
    return "不解压随机访问 APK 的 DEX（不跑 jadx 全量反编译）。action："
        + "list_classes（列出类，可加 query 过滤，limit 默认 80）/ "
        + "class_info（读单个类：父类、接口、字段、方法签名；参数 class 传 Java 名或描述符）/ "
        + "xref（找出谁引用了某方法/字段：query=方法或字段名片段，kind=method/field/both，"
        + "返回每个引用处的调用者签名）。适合快速侦察与定位调用点。深入看实现再用 apk_decompile。";
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
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "list_classes（默认）/ class_info"))
                .put(
                    "query",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "action=list_classes 时的类名过滤（不区分大小写子串）"))
                .put(
                    "class",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "action=class_info 时的类名（com.foo.Bar 或 Lcom/foo/Bar;）"))
                .put(
                    "kind",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "action=xref 时匹配 method / field / both（默认 both）"))
                .put(
                    "case_sensitive",
                    new JSONObject()
                        .put("type", "boolean")
                        .put("description", "action=xref 时是否区分大小写，默认 false"))
                .put(
                    "limit",
                    new JSONObject()
                        .put("type", "integer")
                        .put("description", "list_classes / xref 最多返回多少条，默认 80")))
        .put("required", new org.json.JSONArray().put("apk_path"));
  }

  /** 一个 dex 条目及其解析器。 */
  private static final class DexEntry {
    final String name;
    final DexParser parser;

    DexEntry(String name, DexParser parser) {
      this.name = name;
      this.parser = parser;
    }
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String apkPath = input.optString("apk_path", "").trim();
    if (apkPath.isEmpty()) {
      return error("apk_path 不能为空");
    }
    String action = input.optString("action", "list_classes").trim().toLowerCase(Locale.ROOT);
    String home = context == null ? "" : context.getHomePath();
    File file = new File(apkPath);
    if (!file.isAbsolute() && !home.isEmpty()) {
      file = new File(home, apkPath);
    }
    if (!file.isFile()) {
      return error("APK 文件不存在或不可读：" + file.getAbsolutePath());
    }

    List<DexEntry> dexes = loadDexes(file);
    if (dexes.isEmpty()) {
      return error("APK 内没有可解析的 dex（classes*.dex）。");
    }

    if (action.equals("class_info")) {
      String cls = input.optString("class", "").trim();
      if (cls.isEmpty()) {
        return error("action=class_info 需要 class 参数");
      }
      return classInfo(dexes, cls);
    }
    int limit = input.optInt("limit", DEFAULT_LIMIT);
    if (limit <= 0) {
      limit = DEFAULT_LIMIT;
    }
    if (action.equals("xref")) {
      String query = input.optString("query", "").trim();
      if (query.isEmpty()) {
        return error("action=xref 需要 query（方法/字段名片段）");
      }
      String kind = input.optString("kind", "both").trim().toLowerCase(Locale.ROOT);
      boolean wantMethods = !kind.equals("field");
      boolean wantFields = !kind.equals("method");
      return xref(dexes, query, wantMethods, wantFields,
          input.optBoolean("case_sensitive", false), limit);
    }
    return listClasses(dexes, input.optString("query", "").trim(), limit);
  }

  private static List<DexEntry> loadDexes(File file) {
    List<DexEntry> out = new ArrayList<>();
    try (ZipFile zip = new ZipFile(file)) {
      Enumeration<? extends ZipEntry> en = zip.entries();
      while (en.hasMoreElements()) {
        ZipEntry entry = en.nextElement();
        String lower = entry.getName().toLowerCase(Locale.ROOT);
        if (!lower.matches("classes[0-9]*\\.dex")) {
          continue;
        }
        try (InputStream in = zip.getInputStream(entry)) {
          byte[] bytes = ParseUtil.readAll(in);
          DexParser parser = DexParser.open(bytes);
          if (parser != null) {
            out.add(new DexEntry(entry.getName(), parser));
          }
        } catch (Exception ignored) {
          // 单个 dex 读失败就跳过。
        }
      }
    } catch (Exception ignored) {
      // 交给上层判空。
    }
    return out;
  }

  private ToolResult listClasses(List<DexEntry> dexes, String query, int limit) {
    String needle = query.toLowerCase(Locale.ROOT);
    int total = 0;
    List<String> lines = new ArrayList<>();
    for (DexEntry dex : dexes) {
      total += dex.parser.classCount();
      for (String descriptor : dex.parser.classDescriptors()) {
        String javaName = DexParser.javaName(descriptor);
        if (!needle.isEmpty() && !javaName.toLowerCase(Locale.ROOT).contains(needle)) {
          continue;
        }
        if (lines.size() < limit) {
          lines.add(javaName + "  [" + dex.name + "]");
        }
      }
    }
    StringBuilder sb = new StringBuilder();
    sb.append("共 ").append(dexes.size()).append(" 个 dex、").append(total).append(" 个类");
    if (!query.isEmpty()) {
      sb.append("，过滤 \"").append(query).append("\"");
    }
    sb.append("：\n");
    if (lines.isEmpty()) {
      sb.append("（无匹配类）");
    } else {
      for (String line : lines) {
        sb.append(line).append('\n');
      }
      if (lines.size() >= limit) {
        sb.append("… 已达上限 ").append(limit).append("，可加 query 缩小范围或提高 limit。\n");
      }
    }
    return ok(sb.toString());
  }

  private ToolResult classInfo(List<DexEntry> dexes, String name) {
    for (DexEntry dex : dexes) {
      DexParser.ClassInfo info = dex.parser.classInfo(name);
      if (info != null) {
        return ok(formatClassInfo(info, dex.name));
      }
    }
    return error("未找到类：" + name + "（可用 action=list_classes 先看有哪些类）");
  }

  private ToolResult xref(
      List<DexEntry> dexes,
      String query,
      boolean wantMethods,
      boolean wantFields,
      boolean caseSensitive,
      int limit) {
    List<String> hits = new ArrayList<>();
    for (DexEntry dex : dexes) {
      dex.parser.xref(query, caseSensitive, wantMethods, wantFields, hits, limit);
      if (hits.size() >= limit) {
        break;
      }
    }
    StringBuilder sb = new StringBuilder();
    sb.append("查找引用 \"").append(query).append("\"（")
        .append(wantMethods ? "method" : "").append(wantMethods && wantFields ? "+" : "")
        .append(wantFields ? "field" : "").append("）：\n");
    if (hits.isEmpty()) {
      sb.append("（未找到引用；可放宽 query 或改用 apk_decompile 看具体调用）");
    } else {
      for (String hit : hits) {
        sb.append(hit).append('\n');
      }
      if (hits.size() >= limit) {
        sb.append("… 已达上限 ").append(limit).append("，可缩小 query 或提高 limit。\n");
      }
    }
    return ok(sb.toString());
  }

  private static String formatClassInfo(DexParser.ClassInfo info, String dexName) {
    StringBuilder sb = new StringBuilder();
    sb.append("class: ").append(info.javaName).append("  [").append(dexName).append("]\n");
    sb.append("access: ").append(accessFlags(info.accessFlags, info.isInterface)).append('\n');
    if (!info.superName.isEmpty()) {
      sb.append("super: ").append(info.superName).append('\n');
    }
    if (!info.sourceFile.isEmpty()) {
      sb.append("source: ").append(info.sourceFile).append('\n');
    }
    if (!info.interfaces.isEmpty()) {
      sb.append("interfaces: ").append(join(info.interfaces)).append('\n');
    }
    sb.append('\n').append("fields (").append(info.fields.size()).append("):\n");
    appendMembers(sb, info.fields, false);
    sb.append('\n').append("methods (").append(info.methods.size()).append("):\n");
    appendMembers(sb, info.methods, true);
    return sb.toString();
  }

  private static void appendMembers(StringBuilder sb, List<DexParser.Member> members, boolean isMethod) {
    int shown = 0;
    for (DexParser.Member m : members) {
      if (shown >= MEMBER_OUTPUT_LIMIT) {
        sb.append("  … 还有 ").append(members.size() - shown).append(" 个未显示\n");
        break;
      }
      sb.append("  ").append(modifiers(m.accessFlags)).append(' ');
      if (isMethod) {
        sb.append(m.type).append(' ').append(m.name);
      } else {
        sb.append(m.type).append(' ').append(m.name);
      }
      sb.append('\n');
      shown++;
    }
  }

  private static String modifiers(int flags) {
    StringBuilder sb = new StringBuilder();
    if ((flags & 0x0001) != 0) {
      sb.append("public ");
    } else if ((flags & 0x0002) != 0) {
      sb.append("private ");
    } else if ((flags & 0x0004) != 0) {
      sb.append("protected ");
    }
    if ((flags & 0x0008) != 0) {
      sb.append("static ");
    }
    if ((flags & 0x0010) != 0) {
      sb.append("final ");
    }
    if ((flags & 0x0400) != 0) {
      sb.append("abstract ");
    }
    if ((flags & 0x0100) != 0) {
      sb.append("native ");
    }
    return sb.toString().trim();
  }

  private static String accessFlags(int flags, boolean isInterface) {
    StringBuilder sb = new StringBuilder();
    if ((flags & 0x0001) != 0) {
      sb.append("public ");
    }
    if ((flags & 0x0010) != 0) {
      sb.append("final ");
    }
    if (isInterface || (flags & 0x0200) != 0) {
      sb.append("interface ");
    }
    if ((flags & 0x0400) != 0) {
      sb.append("abstract ");
    }
    sb.append(String.format("(0x%04x)", flags));
    return sb.toString();
  }

  private static String join(List<String> items) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(items.get(i));
    }
    return sb.toString();
  }
}
