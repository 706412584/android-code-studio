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
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONObject;

/**
 * 编译诊断工具：经 IDE 语言服务器对源码做静态分析，返回结构化错误/警告。
 *
 * <p><b>为什么这是 ACS 的独有能力</b>：通用编码 agent 改完代码只能跑一遍构建
 * （分钟级，且只给第一条错误）才知道对不对；IDE 内置语言服务器已持有符号索引，
 * 单文件分析是秒级，一次给出全部诊断（含行列号、严重级别、诊断码）。
 *
 * <p><b>典型用法是「改完一批、查一次」</b>：模型编辑若干文件后调用一次本工具，
 * 即可确认没写坏语法/类型，而不是每改一个文件付一次分析成本。
 *
 * <p><b>为什么不做成编辑工具搭车返回</b>：{@code file_edit}/{@code file_write} 是
 * 高频调用，搭车意味着每次编辑都触发一次 LSP 分析（Java 首次分析可达数秒），
 * 而绝大多数编辑中间态本来就不完整。需要搭车时需要带超时降级，因此拆成显式工具，
 * 语义与成本都由模型自己决定。
 */
public final class DiagnosticsTool extends BaseTool {

  /** 单个文件的分析超时（ms）。首次分析需建符号索引，给足 20s。 */
  static final long PER_FILE_TIMEOUT_MS = 20_000L;

  /** 目录模式下最多分析的文件数，避免一次扫全仓把结果撑爆。 */
  static final int DEFAULT_MAX_FILES = 20;
  static final int MAX_MAX_FILES = 100;

  /** 返回的诊断条数上限（全部文件合计）。 */
  static final int DEFAULT_MAX_ITEMS = 100;
  static final int MAX_MAX_ITEMS = 500;

  /**
   * 受支持的文件扩展名。
   *
   * <p><b>为什么只有 java / kt</b>：IDE 虽注册了四个语言服务器，但只有它们实现了
   * 按需分析——{@code XMLLanguageServer.analyze} 与 {@code ClangLanguageServer.analyze}
   * 恒返回 {@code NO_UPDATE}（未实现），把它们列进来只会让模型反复拿到无效结果。
   * 其中 Kotlin 也只做缺失导入检查（类型错误由 KLS 服务进程推送，不在此接口内）。
   */
  private static final Set<String> SUPPORTED_EXTENSIONS =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("java", "kt")));

  /** 扫描目录时跳过的目录名：构建产物与依赖，既非源码也极耗时间。 */
  private static final Set<String> SKIP_DIRS =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  "build", ".gradle", ".git", ".idea", "node_modules", ".cxx", "generated")));

  @Override
  public String getName() {
    return ToolNames.DIAGNOSTICS;
  }

  @Override
  public String getDescription() {
    return "用 IDE 语言服务器分析源码，返回编译错误与警告（含行列号与严重级别）。"
        + "path 传单个文件时只分析该文件；传目录时递归分析其中受支持的文件"
        + "（.java / .kt 完整分析；.xml / .c/.cpp 的语言服务器未实现按需分析，传了会明确报错）。"
        + "推荐在改完一批文件后调用一次，确认没有引入编译错误——"
        + "这比跑完整构建（gradle_build）快得多，且能一次拿到全部诊断。"
        + "注意：文件必须属于当前项目的模块（不在模块内的文件无法分析，会明确说明）。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.GENERIC;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    // 纯静态分析，不改任何文件，只读模式下必须放行。
    return true;
  }

  @Override
  public boolean isConcurrencySafe() {
    // 语言服务器内部对同一项目有编译缓存与锁，并发分析只会互相阻塞。
    return false;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "path",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "要分析的文件或目录（相对工作区或绝对路径）。默认整个工作区。"))
                .put(
                    "min_severity",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "最低严重级别：error / warning / info / hint，默认 warning"))
                .put(
                    "max_files",
                    new JSONObject()
                        .put("type", "integer")
                        .put(
                            "description",
                            "目录模式下最多分析的文件数，默认 "
                                + DEFAULT_MAX_FILES
                                + "，最大 "
                                + MAX_MAX_FILES))
                .put(
                    "max_items",
                    new JSONObject()
                        .put("type", "integer")
                        .put(
                            "description",
                            "返回的诊断条数上限，默认 "
                                + DEFAULT_MAX_ITEMS
                                + "，最大 "
                                + MAX_MAX_ITEMS)));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    DiagnosticsPort port = context == null ? null : context.getDiagnostics();
    if (port == null) {
      return error(
          "当前环境未接入 IDE 语言服务器，无法提供编译诊断。"
              + "请改用途径：在编辑器中打开项目（让语言服务器启动）后重试，"
              + "或用 gradle_build 跑构建获取错误。");
    }
    if (!port.isAvailable()) {
      String reason = port.unavailableReason();
      return error(
          "语言服务器当前不可用"
              + (reason.isEmpty() ? "。" : "：" + reason + "。")
              + "诊断需要项目已在编辑器中打开（语言服务器随项目会话启动）。");
    }

    int minSeverity = DiagnosticsMessages.parseSeverity(input.optString("min_severity", "warning"));
    int maxFiles = clamp(input.optInt("max_files", DEFAULT_MAX_FILES), 1, MAX_MAX_FILES);
    int maxItems = clamp(input.optInt("max_items", DEFAULT_MAX_ITEMS), 1, MAX_MAX_ITEMS);

    File target;
    try {
      target = FileToolPathPolicy.resolve(context, input.optString("path", ""));
    } catch (IOException e) {
      return error("路径无效：" + e.getMessage());
    }
    if (!target.exists()) {
      return error("路径不存在：" + input.optString("path"));
    }

    List<File> files;
    if (target.isDirectory()) {
      files = collectSupportedFiles(target, maxFiles);
      if (files.isEmpty()) {
        return ok(
            "目录 "
                + display(context, target)
                + " 下没有受支持的可分析文件"
                + "（.java / .kt，已跳过 build/.gradle 等产物目录）。");
      }
    } else {
      if (!isSupported(target)) {
        return error(
            "不支持的文件类型："
                + extensionOf(target)
                + "。可分析的是 .java 与 .kt（XML/Clang 语言服务器未实现按需分析）。");
      }
      files = Collections.singletonList(target);
    }

    StringBuilder sb = new StringBuilder();
    int totalItems = 0;
    int analyzed = 0;
    int fileCount = 0;
    List<String> failures = new ArrayList<>();
    boolean truncated = false;

    for (File file : files) {
      if (totalItems >= maxItems) {
        truncated = true;
        break;
      }
      fileCount++;

      DiagnosticsPort.Report report = port.analyze(file.getAbsolutePath(), PER_FILE_TIMEOUT_MS);
      if (report.isUnavailable()) {
        // 单文件不可用（例如该扩展名没有注册服务器）不致命，记为失败继续。
        failures.add(display(context, file) + "：" + report.getUnavailableReason());
        continue;
      }
      if (report.isFailed()) {
        failures.add(display(context, file) + "：" + report.getFailureReason());
        continue;
      }

      analyzed++;
      List<DiagnosticsPort.Item> matched =
          DiagnosticsMessages.sorted(
              DiagnosticsMessages.filterBySeverity(report.getItems(), minSeverity));
      if (matched.isEmpty()) {
        continue;
      }

      sb.append(display(context, file)).append('\n');
      for (DiagnosticsPort.Item item : matched) {
        if (totalItems >= maxItems) {
          truncated = true;
          break;
        }
        totalItems++;
        sb.append("  ").append(DiagnosticsMessages.formatItem(item)).append('\n');
      }
      sb.append('\n');
    }

    if (totalItems == 0 && failures.isEmpty()) {
      return ok(
          "已分析 "
              + analyzed
              + " 个文件，未发现 "
              + DiagnosticsMessages.severityName(minSeverity)
              + " 及以上级别的诊断。");
    }

    StringBuilder header = new StringBuilder();
    header
        .append("分析 ")
        .append(analyzed)
        .append('/')
        .append(fileCount)
        .append(" 个文件，发现 ")
        .append(totalItems)
        .append(" 条 ")
        .append(DiagnosticsMessages.severityName(minSeverity))
        .append(" 及以上诊断：\n\n");
    header.append(sb);
    if (truncated) {
      header
          .append("… 已达上限（最多 ")
          .append(maxItems)
          .append(" 条 / ")
          .append(maxFiles)
          .append(" 个文件），结果被截断；可缩小路径范围后重试。\n");
    }
    if (!failures.isEmpty()) {
      header.append("\n以下文件分析未完成：\n");
      for (String failure : failures) {
        header.append("  - ").append(failure).append('\n');
      }
    }
    return ok(ToolResult.truncateContent(header.toString()));
  }

  /** 广度优先收集受支持的文件，跳过构建产物目录。 */
  static List<File> collectSupportedFiles(File root, int limit) {
    List<File> found = new ArrayList<>();
    Deque<File> queue = new ArrayDeque<>();
    queue.add(root);
    while (!queue.isEmpty() && found.size() < limit) {
      File dir = queue.poll();
      File[] children = dir.listFiles();
      if (children == null) {
        continue;
      }
      Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
      for (File child : children) {
        if (found.size() >= limit) {
          break;
        }
        if (child.isDirectory()) {
          if (!SKIP_DIRS.contains(child.getName()) && !child.getName().startsWith(".")) {
            queue.add(child);
          }
        } else if (isSupported(child)) {
          found.add(child);
        }
      }
    }
    return found;
  }

  static boolean isSupported(File file) {
    return SUPPORTED_EXTENSIONS.contains(extensionOf(file));
  }

  static String extensionOf(File file) {
    String name = file.getName();
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
  }

  private static String display(ToolContext context, File file) {
    try {
      return FileToolPathPolicy.displayPath(context.getHomePath(), file);
    } catch (IOException e) {
      return file.getAbsolutePath();
    }
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
