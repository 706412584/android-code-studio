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
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

/**
 * 按名称搜索整个工作区的 Java 符号声明位置。
 *
 * <p>与 {@code lsp_navigate} 的分工：后者给定 file:line 做导航（跳定义/找引用），
 * 本工具在**不知道位置**时按名字全局找——回答「MainActivity 定义在哪个文件」。
 * 与 {@code grep} 的区别：只返回**声明处**，不会把引用位置混进来。
 *
 * <p>只搜索 Java 源码（.kt 暂不支持；XML/Clang 无符号索引），需要项目已在编辑器中打开。
 */
public final class WorkspaceSymbolTool extends BaseTool {

  /** 单次搜索的时间预算（ms）。首次可能触发源码索引，与 diagnostics 同级给 20s。 */
  static final long TIMEOUT_MS = 20_000L;

  static final int DEFAULT_LIMIT = 50;
  static final int MAX_LIMIT = 200;

  /** 最短查询长度：单字符会命中几乎整个工作区，直接拒绝而不是让它跑到超时。 */
  static final int MIN_QUERY_LENGTH = 2;

  @Override
  public String getName() {
    return ToolNames.WORKSPACE_SYMBOL;
  }

  @Override
  public String getDescription() {
    return "按名称搜索整个工作区的 Java 符号（类/接口/枚举/注解/方法/字段），返回每个声明的 文件:行:列 列表。"
        + "适用于「MainActivity 定义在哪个文件」「谁定义了 parseConfig」这类问题——"
        + "它只返回**声明处**，不会像 grep 那样把引用位置混进来。"
        + "参数：query（符号名，至少 2 个字符，区分大小写；也可给全限定名如 com.foo.MainActivity）、"
        + "kind（可选过滤：class/interface/enum/annotation/method/field，默认全部）、"
        + "limit（结果上限，默认 50，最大 200）。"
        + "只搜索当前已打开项目的 Java 源码（.kt 暂不支持；XML/Clang 无符号索引）。"
        + "项目需已在编辑器中打开（索引随项目会话建立）；查询过宽（如单个常见单词）时结果会被截断，"
        + "此时请换更精确的名字。";
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
    // 纯查询，不改任何文件，只读模式下必须放行。
    return true;
  }

  @Override
  public boolean isConcurrencySafe() {
    // 搜索核心用语言服务器的编译缓存与 Parser 的静态单条 parse 缓存（均无同步），
    // 并发只会互相破坏，必须串行。
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
                    "query",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "符号名，至少 "
                                + MIN_QUERY_LENGTH
                                + " 个字符，区分大小写；也可给全限定名（com.foo.MainActivity）"))
                .put(
                    "kind",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "可选：只搜某一类符号；缺省搜索全部种类")
                        .put(
                            "enum",
                            new org.json.JSONArray()
                                .put("class")
                                .put("interface")
                                .put("enum")
                                .put("annotation")
                                .put("method")
                                .put("field")))
                .put(
                    "limit",
                    new JSONObject()
                        .put("type", "integer")
                        .put(
                            "description",
                            "结果条数上限，默认 "
                                + DEFAULT_LIMIT
                                + "，最大 "
                                + MAX_LIMIT
                                + "（超出自动截到上限）")))
        .put("required", new org.json.JSONArray().put("query"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    WorkspaceSymbolPort port = context == null ? null : context.getWorkspaceSymbol();
    if (port == null) {
      return error(
          "当前环境未接入 IDE 符号索引，无法做工作区符号搜索。"
              + "请改用 grep（文本搜索）或 file_read 代替。");
    }
    String reason = port.unavailableReason();
    if (!reason.isEmpty()) {
      return error("符号索引当前不可用：" + reason + "。符号搜索需要项目已在编辑器中打开。");
    }

    String query = input.optString("query", "").trim();
    if (query.length() < MIN_QUERY_LENGTH) {
      return error(
          "query 至少需要 " + MIN_QUERY_LENGTH + " 个字符（收到 \""
              + query
              + "\"）。单字符会命中几乎整个工作区，请给出更具体的符号名。");
    }

    Set<WorkspaceSymbolPort.Kind> kinds = null;
    String kindRaw = input.optString("kind", "").trim();
    if (!kindRaw.isEmpty()) {
      WorkspaceSymbolPort.Kind kind = WorkspaceSymbolMessages.parseKind(kindRaw);
      if (kind == null) {
        return error(
            "kind 只能是 class / interface / enum / annotation / method / field，收到："
                + kindRaw);
      }
      kinds = Collections.unmodifiableSet(EnumSet.of(kind));
    }

    // 非正数回落默认（模型给了无意义的值时按「没给」处理），超过上限则钳到上限。
    int rawLimit = input.optInt("limit", DEFAULT_LIMIT);
    int limit = rawLimit <= 0 ? DEFAULT_LIMIT : Math.min(rawLimit, MAX_LIMIT);

    WorkspaceSymbolPort.Result result = port.search(query, kinds, limit, TIMEOUT_MS);
    if (result.isUnavailable()) {
      return error("符号索引不可用：" + result.getUnavailableReason());
    }
    if (result.isFailed()) {
      String hint = result.isTimedOut() ? "（可稍后重试，首次搜索较慢）" : "";
      return error("符号搜索失败：" + result.getFailureReason() + hint);
    }

    List<WorkspaceSymbolPort.Symbol> symbols = result.getSymbols();
    if (symbols.isEmpty()) {
      // 「查到了但没有结果」与「查不了」必须分开说——否则模型会以为工具坏了而反复重试。
      return ok(
          "没有找到名为 \""
              + query
              + "\" 的符号。注意：只搜索 Java 源码、区分大小写；"
              + "请确认项目已完成 Gradle 同步（索引随项目会话建立）。");
    }

    StringBuilder sb = new StringBuilder();
    sb.append("找到 ").append(symbols.size()).append(" 个符号：\n");
    for (WorkspaceSymbolPort.Symbol symbol : symbols) {
      sb.append("  ").append(WorkspaceSymbolMessages.formatSymbol(context, symbol)).append('\n');
    }
    if (result.isTruncated()) {
      sb.append("（").append(result.getTruncatedReason())
          .append("；请用更精确的查询或缩小 kind）\n");
    }
    return ok(ToolResult.truncateContent(sb.toString()));
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
