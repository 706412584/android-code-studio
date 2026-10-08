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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool;

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.json.JSONObject;

/**
 * 按正则搜索文件内容。
 *
 * <p><b>为什么需要它（设备上没有可用的 grep）</b>：Android 自带的是 BSD grep 2.5.1，
 * 实测既不支持 {@code |} 交替，{@code -r} 的行为也不可靠；ripgrep 则根本没有。
 * 在此之前模型想搜内容只能用 {@code shell_execute} 敲那条残缺的 grep，写出的正则
 * 常常静默失配——它拿不到「搜到了但没匹配」与「这个 grep 不支持该语法」的区别。
 * 本工具用 {@link Pattern} 在进程内搜索，语义与模型先验一致。
 *
 * <p><b>语义对齐参考项目 cc-haha 的 GrepTool</b>（它基于 ripgrep）：三种输出模式、
 * 上下文行、行号、大小写、head_limit/offset 分页、自动排除版本控制目录。差异只在
 * 实现层——本工具不依赖外部二进制，因此在任何后端（Termux / Shizuku）下都可用。
 *
 * <p><b>为什么不直接用 CodeGraph 代替</b>：CodeGraph 是符号级索引，回答「这个类在哪、
 * 谁调用它」；grep 回答「这个字符串出现在哪一行」。字符串资源、注释、配置、日志文案
 * 都不在符号索引里，而那恰恰是 grep 的用武之地。两者互补，提示词里也如此分工。
 */
public final class GrepTool extends BaseTool {

  /** 单次返回给模型的行数上限（content 模式下是行，其它模式是条目）。 */
  private static final int DEFAULT_HEAD_LIMIT = 250;

  /** 遍历文件数的上限：防止在超大目录里长时间扫描。 */
  private static final int MAX_FILES_SCANNED = 20_000;

  /** 单个文件读取上限：跳过超大文件（二进制/日志），避免拖慢与撑爆内存。 */
  private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

  /** 匹配行输出宽度上限：超长行（压缩过的 js、base64）截断，避免一行占满上下文。 */
  private static final int MAX_LINE_CHARS = 500;

  /**
   * 自动排除的目录。
   *
   * <p>与 cc-haha 的 {@code VCS_DIRECTORIES_TO_EXCLUDE} 一致，另加构建产物目录：
   * 在手机上 {@code build/} 动辄上千个 class/dex，不排除会把结果淹掉。
   */
  private static final List<String> EXCLUDED_DIRS =
      Arrays.asList(
          ".git", ".svn", ".hg", ".bzr", ".jj", ".sl",
          "build", ".gradle", "node_modules", ".idea", ".cxx", ".acside");

  private static final Map<String, String> OUTPUT_MODES = new LinkedHashMap<>();

  static {
    OUTPUT_MODES.put("files_with_matches", "只列含匹配的文件路径（默认）。适合「哪些文件提到了 X」");
    OUTPUT_MODES.put("content", "列出匹配行（含行号）。适合「X 具体写在哪一行」");
    OUTPUT_MODES.put("count", "只给每个文件的匹配计数。适合「有多少处」");
  }

  @Override
  public String getName() {
    return ToolNames.GREP;
  }

  @Override
  public String getDescription() {
    return "用正则表达式搜索文件内容（在进程内执行，不依赖设备上的 grep）。"
        + "与 glob 的分工：glob 按文件名找文件，本工具按内容找。"
        + "搜索代码符号的定义/引用请优先用 codegraph（更快更准）；"
        + "本工具适合字符串资源、注释、配置值、日志文案等非代码内容。";
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
    // 只读遍历，互不依赖。
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    StringBuilder modeHint = new StringBuilder("输出模式。可用值：");
    for (Map.Entry<String, String> e : OUTPUT_MODES.entrySet()) {
      modeHint.append("\n- ").append(e.getKey()).append(": ").append(e.getValue());
    }

    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "pattern",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "正则表达式（Java 语法，支持 | 交替、\\d、\\s、分组等）。"
                                + "**不是** glob 通配符。搜索含正则元字符的字面量时记得转义。"))
                .put(
                    "path",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "搜索的文件或目录。**省略时默认当前项目根目录**。"))
                .put(
                    "glob",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "按文件名过滤要搜索的文件，如 *.kt、**/*.xml、*.{java,kt}。"
                                + "多个用逗号或空格分隔。不传则搜全部文本文件。"))
                .put("output_mode", new JSONObject().put("type", "string").put("description", modeHint.toString()))
                .put(
                    "-A",
                    new JSONObject()
                        .put("type", "number")
                        .put("description", "匹配行之后额外显示几行（仅 content 模式）"))
                .put(
                    "-B",
                    new JSONObject()
                        .put("type", "number")
                        .put("description", "匹配行之前额外显示几行（仅 content 模式）"))
                .put(
                    "-C",
                    new JSONObject()
                        .put("type", "number")
                        .put("description", "匹配行前后各显示几行（仅 content 模式）。与 -A/-B 同时给出时以 -C 为准"))
                .put(
                    "-n",
                    new JSONObject()
                        .put("type", "boolean")
                        .put("description", "是否显示行号（仅 content 模式）。默认 true"))
                .put(
                    "-i",
                    new JSONObject()
                        .put("type", "boolean")
                        .put("description", "忽略大小写。默认 false"))
                .put(
                    "head_limit",
                    new JSONObject()
                        .put("type", "number")
                        .put(
                            "description",
                            "最多返回多少行/条目（相当于 | head -N）。默认 "
                                + DEFAULT_HEAD_LIMIT
                                + "；传 0 表示不限（慎用，结果多会占用大量上下文）。"))
                .put(
                    "offset",
                    new JSONObject()
                        .put("type", "number")
                        .put("description", "跳过前 N 行/条目后再取（相当于 | tail -n +N | head -N）。默认 0")))
        .put("required", new org.json.JSONArray().put("pattern"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    try {
      String patternText = input.optString("pattern", "").trim();
      if (patternText.isEmpty()) {
        return error("pattern 不能为空");
      }

      Pattern pattern;
      try {
        int flags = input.optBoolean("-i", false) ? Pattern.CASE_INSENSITIVE : 0;
        pattern = Pattern.compile(patternText, flags);
      } catch (PatternSyntaxException e) {
        // 正则语法错误必须单独说清楚：模型把 glob 写成正则（如 *.kt）是高频失误，
        // 笼统报「搜索失败」会让它反复重试同一条错误表达式。
        return error(
            "正则表达式语法错误: "
                + e.getDescription()
                + "\n（注意：本工具用正则，不是 glob 通配符；匹配字面量 *.kt 应写作 \\*\\.kt）");
      }

      File root = FileToolPathPolicy.resolve(context, input.optString("path"));
      if (!root.exists()) {
        return error("路径不存在: " + input.optString("path", "."));
      }

      String mode = input.optString("output_mode", "files_with_matches").trim();
      if (!OUTPUT_MODES.containsKey(mode)) {
        return error(
            "不支持的 output_mode：" + mode + "。可用：" + String.join(", ", OUTPUT_MODES.keySet()));
      }

      int headLimit = input.has("head_limit") ? input.optInt("head_limit", DEFAULT_HEAD_LIMIT) : DEFAULT_HEAD_LIMIT;
      int offset = Math.max(0, input.optInt("offset", 0));

      List<String> globFilters = parseGlobFilters(input.optString("glob", ""));

      Collector collector = new Collector(pattern, mode, globFilters);
      if (root.isFile()) {
        collector.scanFile(root, root.getParentFile());
      } else {
        collector.scanDir(root);
      }

      return render(collector, root, context, headLimit, offset, mode, patternText);
    } catch (IOException e) {
      return error("搜索失败: " + e.getMessage());
    } catch (RuntimeException e) {
      ExceptionUtils.restoreInterrupt(e);
      return error("搜索异常: " + ExceptionUtils.describeException(e));
    }
  }

  // ---- 结果渲染 ----

  private ToolResult render(
      Collector collector,
      File root,
      ToolContext context,
      int headLimit,
      int offset,
      String mode,
      String patternText)
      throws IOException {

    String displayRoot =
        context == null ? root.getName() : FileToolPathPolicy.displayPath(context.getHomePath(), root);

    if (collector.filesScanned >= MAX_FILES_SCANNED) {
      // 明确报告「扫到一半就停了」，否则模型会把不完整的结果当成全量。
      collector.note =
          "（已扫描 " + MAX_FILES_SCANNED + " 个文件后停止，结果可能不完整；"
              + "请缩小 path 或加 glob 过滤后重试）";
    }

    if (mode.equals("count")) {
      List<String> lines = new ArrayList<>();
      int totalMatches = 0;
      for (Map.Entry<String, Integer> e : collector.counts.entrySet()) {
        lines.add(e.getKey() + ":" + e.getValue());
        totalMatches += e.getValue();
      }
      Page page = page(lines, headLimit, offset);
      if (page.items.isEmpty()) {
        return ok("No matches found" + collector.suffix());
      }
      StringBuilder sb = new StringBuilder();
      sb.append(String.join("\n", page.items));
      sb.append(
          "\n\nFound " + totalMatches + " occurrence(s) across " + collector.counts.size() + " file(s).");
      sb.append(page.note);
      sb.append(collector.suffix());
      return ok(sb.toString());
    }

    if (mode.equals("content")) {
      Page page = page(collector.lines, headLimit, offset);
      if (page.items.isEmpty()) {
        return ok("No matches found" + collector.suffix());
      }
      StringBuilder sb = new StringBuilder();
      sb.append(String.join("\n", page.items));
      sb.append(page.note);
      sb.append(collector.suffix());
      return ok(sb.toString());
    }

    // files_with_matches（默认）
    Page page = page(collector.files, headLimit, offset);
    if (page.items.isEmpty()) {
      return ok("No files found" + collector.suffix());
    }
    StringBuilder sb = new StringBuilder();
    sb.append("Found ").append(page.items.size()).append(" file(s) in ").append(displayRoot);
    if (page.note.isEmpty()) {
      sb.append('\n');
    } else {
      sb.append(page.note).append('\n');
    }
    for (String f : page.items) {
      sb.append(f).append('\n');
    }
    sb.append(collector.suffix());
    return ok(sb.toString().trim());
  }

  /** 分页：offset 先跳过，head_limit 再截断；0 表示不限。 */
  private static Page page(List<String> items, int headLimit, int offset) {
    Page page = new Page();
    if (items.isEmpty()) {
      return page;
    }
    int from = Math.min(offset, items.size());
    List<String> rest = items.subList(from, items.size());

    if (headLimit <= 0) {
      page.items = new ArrayList<>(rest);
      if (offset > 0) {
        page.note = "\n\n[offset: " + offset + "]";
      }
      return page;
    }

    int to = Math.min(rest.size(), headLimit);
    page.items = new ArrayList<>(rest.subList(0, to));
    if (rest.size() > to) {
      // 只在真的被截断时说明，模型才知道「还有更多，可用 offset 翻页」。
      page.note = "\n\n[showing " + to + " of " + rest.size() + " results; use offset to paginate]";
    } else if (offset > 0) {
      page.note = "\n\n[offset: " + offset + "]";
    }
    return page;
  }

  private static final class Page {
    List<String> items = new ArrayList<>();
    String note = "";
  }

  // ---- 遍历与匹配 ----

  /** 收集匹配结果。按模式只填其中一种容器。 */
  private static final class Collector {
    private final Pattern pattern;
    private final String mode;
    private final List<String> globFilters;

    final List<String> files = new ArrayList<>();
    final List<String> lines = new ArrayList<>();
    final Map<String, Integer> counts = new LinkedHashMap<>();

    int filesScanned = 0;
    String note = "";
    /** 因文件过大 / 非文本而跳过的数量，供结尾提示。 */
    int skipped = 0;

    Collector(Pattern pattern, String mode, List<String> globFilters) {
      this.pattern = pattern;
      this.mode = mode;
      this.globFilters = globFilters;
    }

    String suffix() {
      StringBuilder sb = new StringBuilder();
      if (skipped > 0) {
        sb.append("\n(已跳过 ").append(skipped).append(" 个非文本或超大文件)");
      }
      if (!note.isEmpty()) {
        sb.append('\n').append(note);
      }
      return sb.toString();
    }

    void scanDir(File dir) {
      if (filesScanned >= MAX_FILES_SCANNED) {
        return;
      }
      File[] items = dir.listFiles();
      if (items == null) {
        return;
      }
      // 名字排序：结果稳定，模型两次搜索同一目录拿到的顺序一致，便于对比。
      Arrays.sort(items, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
      for (File item : items) {
        if (filesScanned >= MAX_FILES_SCANNED) {
          return;
        }
        String name = item.getName();
        if (item.isDirectory()) {
          if (!name.startsWith(".") && !EXCLUDED_DIRS.contains(name)) {
            scanDir(item);
          }
        } else {
          scanFile(item, dir);
        }
      }
    }

    void scanFile(File file, File baseDir) {
      if (matchesGlobFilter(file.getName())) {
        return;
      }
      if (file.length() > MAX_FILE_BYTES) {
        skipped++;
        return;
      }
      filesScanned++;

      String relative = relativize(file, baseDir);
      List<String> content;
      try {
        content = readLines(file);
      } catch (IOException e) {
        skipped++;
        return;
      }
      if (content == null) {
        skipped++;
        return;
      }

      int matchCount = 0;
      for (int i = 0; i < content.size(); i++) {
        String line = content.get(i);
        if (!pattern.matcher(line).find()) {
          continue;
        }
        matchCount++;
        if (mode.equals("content")) {
          lines.add(relative + ":" + (i + 1) + ":" + abbreviate(line));
        }
      }

      if (matchCount > 0) {
        if (mode.equals("count")) {
          counts.put(relative, matchCount);
        } else if (mode.equals("files_with_matches")) {
          files.add(relative);
        }
      }
    }

    /** 返回 true 表示「因 glob 过滤而跳过」。无过滤时恒为 false。 */
    private boolean matchesGlobFilter(String fileName) {
      if (globFilters.isEmpty()) {
        return false;
      }
      for (String filter : globFilters) {
        if (matchGlob(filter, fileName)) {
          return false;
        }
      }
      return true;
    }

    private static String relativize(File file, File baseDir) {
      if (baseDir == null) {
        return file.getName();
      }
      String base = baseDir.getAbsolutePath();
      String path = file.getAbsolutePath();
      if (path.startsWith(base + File.separator)) {
        return path.substring(base.length() + 1);
      }
      return file.getName();
    }

    /** 读取文本行；二进制文件返回 null。 */
    private static List<String> readLines(File file) throws IOException {
      try (FileInputStream in = new FileInputStream(file)) {
        byte[] head = new byte[Math.min(8192, (int) file.length())];
        int read = in.read(head);
        if (read > 0 && looksBinary(head, read)) {
          return null;
        }
      }
      List<String> out = new ArrayList<>();
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          out.add(line);
        }
      }
      return out;
    }

    /** 含 NUL 字节即视为二进制——与 grep 的判定一致。 */
    private static boolean looksBinary(byte[] data, int length) {
      for (int i = 0; i < length; i++) {
        if (data[i] == 0) {
          return true;
        }
      }
      return false;
    }
  }

  /** 解析 glob 过滤串：逗号或空白分隔；含 {} 的整段不拆（与 cc-haha 一致）。 */
  private static List<String> parseGlobFilters(String raw) {
    List<String> out = new ArrayList<>();
    if (raw == null || raw.trim().isEmpty()) {
      return out;
    }
    for (String token : raw.trim().split("\\s+")) {
      if (token.contains("{") && token.contains("}")) {
        // 大括号展开：*.{java,kt} → *.java, *.kt
        int open = token.indexOf('{');
        int close = token.indexOf('}');
        if (close > open) {
          String prefix = token.substring(0, open);
          String suffix = token.substring(close + 1);
          for (String part : token.substring(open + 1, close).split(",")) {
            if (!part.trim().isEmpty()) {
              out.add(prefix + part.trim() + suffix);
            }
          }
          continue;
        }
      }
      for (String part : token.split(",")) {
        if (!part.trim().isEmpty()) {
          out.add(part.trim());
        }
      }
    }
    return out;
  }

  /**
   * 按 glob 匹配文件名或路径。
   *
   * <p>支持 {@code *}（不跨目录）、{@code **}（跨目录）、{@code ?}。
   * 与 GlobTool 的同名逻辑保持一致的语义。
   */
  static boolean matchGlob(String glob, String name) {
    String regex = globToRegex(glob);
    return Pattern.compile(regex).matcher(name).matches();
  }

  private static String globToRegex(String glob) {
    StringBuilder regex = new StringBuilder("^");
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (c == '*') {
        if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
          regex.append(".*");
          i++;
        } else {
          regex.append("[^/]*");
        }
      } else if (c == '?') {
        regex.append("[^/]");
      } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
        regex.append('\\').append(c);
      } else {
        regex.append(c);
      }
    }
    return regex.append('$').toString();
  }

  /** 超长行截断：一行压缩过的 js 或 base64 能占满整个上下文。 */
  private static String abbreviate(String line) {
    String trimmed = line.replace('\t', ' ');
    if (trimmed.length() <= MAX_LINE_CHARS) {
      return trimmed;
    }
    return trimmed.substring(0, MAX_LINE_CHARS) + "…（该行过长，已截断）";
  }
}
