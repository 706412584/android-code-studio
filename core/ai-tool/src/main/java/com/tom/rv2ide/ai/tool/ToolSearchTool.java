/*
 * This file is part of AndroidCodeStudio.
 *
 * Ported from Aharou (https://github.com/520huxiangli/Aharou),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * 按需加载工具的发现入口（照搬 Aharou {@code ToolSearchTool} 的语义）。
 *
 * <p><b>为什么需要它</b>：MCP 工具的定义每轮都完整注入时，几个 server 就能吃掉
 * 几十万 token，而单轮实际只会用到其中一两个。改成「平时只给目录、命中才展开」后，
 * 上下文里只留真正要用到的 schema。
 *
 * <p><b>与 Aharou 的差异</b>：
 * <ul>
 *   <li>检索范围：Aharou 只对 MCP 工具延迟；本移植相同——内置工具描述短、数量固定、
 *       几乎每轮都可能用到，延迟化反而增加一次检索往返。
 *   <li>目录可见性：本类的 {@link #promptSupplement} 动态返回目录摘要，
 *       让模型在提示词里就能看到有哪些按需工具（Aharou 靠工具描述本身告知）。
 *   <li>权限：纯目录查询，不改动任何外部状态，{@link #needsConfirmation()} 为 false
 *       （Aharou 的 AUTO_APPROVE 同义）。
 * </ul>
 *
 * <p><b>展开的作用域</b>：{@link ToolRegistry#activate} 记在注册表上，而注册表是
 * 每次运行装配的（见 {@code AgentOrchestrator.buildRegistry}），因此展开只影响本次
 * 运行——与 Aharou 按会话生效的语义一致。
 */
public final class ToolSearchTool extends BaseTool {

  /** 单次最多展开的工具数：一次展开太多，等于把刚省下的上下文又还回去。 */
  private static final int MAX_RESULTS = 10;

  /** 单个工具参数 schema 的截断长度，防止一个巨型 schema 独占返回。 */
  private static final int SCHEMA_MAX_CHARS = 1200;

  private final ToolRegistry registry;

  public ToolSearchTool(ToolRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.TOOL_SEARCH;
  }

  @Override
  public String getDescription() {
    return "搜索按需加载的工具目录（MCP 工具默认不可直接调用，要靠它展开）。"
        + "query 传关键词（工具名或用途），多个词用空格分隔且需全部命中；留空则列出目录里的全部工具。"
        + "命中的工具会立即展开，从下一轮起可直接调用。";
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
    // 纯目录查询，不改变设备/文件状态；只读模式下允许。
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
                    "query",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "搜索关键词（工具名或用途），多个词用空格分隔；留空列出全部。")))
        .put("required", new org.json.JSONArray());
  }

  /**
   * 提示词补充：把按需目录（名字 + 一行描述）附在工具说明后面。
   *
   * <p><b>为什么目录要进提示词</b>：模型不看到目录，就不知道「有这些工具存在」，
   * 自然不会去检索——按需加载变成「永远不加载」。目录只列名字与截断后的描述，
   * 完整 schema 留在检索展开时才给出。
   */
  @Override
  public String promptSupplement(String executionMode) {
    List<BaseTool> catalog = registry.getDeferredTools();
    if (catalog.isEmpty()) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    sb.append("按需加载工具目录（").append(catalog.size()).append(" 个，调用前需先用 ")
        .append(ToolNames.TOOL_SEARCH).append(" 展开）：");
    for (BaseTool tool : catalog) {
      sb.append("\n- ").append(tool.getName()).append(": ").append(abbreviate(tool.getDescription(), 80));
    }
    return sb.toString();
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    try {
      String query = input == null ? "" : input.optString("query", "").trim();
      List<BaseTool> catalog = registry.getDeferredTools();
      if (catalog.isEmpty()) {
        return ok("按需加载的工具目录是空的（当前没有已连接的 MCP server）。");
      }

      List<BaseTool> matched = new ArrayList<>();
      if (query.isEmpty()) {
        matched.addAll(catalog);
      } else {
        String[] terms = query.toLowerCase(Locale.ROOT).split("\\s+");
        for (BaseTool tool : catalog) {
          String haystack =
              (tool.getName() + " " + tool.getDescription()).toLowerCase(Locale.ROOT);
          boolean all = true;
          for (String term : terms) {
            if (!term.isEmpty() && !haystack.contains(term)) {
              all = false;
              break;
            }
          }
          if (all) {
            matched.add(tool);
          }
        }
      }
      if (matched.isEmpty()) {
        return ok(
            "没有匹配「"
                + query
                + "」的工具。目录里现有 "
                + catalog.size()
                + " 个工具，换个关键词重试，或留空 query 列出全部。");
      }

      List<BaseTool> picked = matched.subList(0, Math.min(MAX_RESULTS, matched.size()));
      List<String> names = new ArrayList<>();
      for (BaseTool tool : picked) {
        names.add(tool.getName());
      }
      registry.activate(names);

      StringBuilder sb = new StringBuilder();
      sb.append("已展开 ").append(picked.size()).append(" 个工具，从下一轮起可直接调用：");
      if (matched.size() > picked.size()) {
        sb.append("（共匹配 ")
            .append(matched.size())
            .append(" 个，单次最多展开 ")
            .append(MAX_RESULTS)
            .append(" 个，可用更具体的关键词缩小范围）");
      }
      for (int i = 0; i < picked.size(); i++) {
        BaseTool tool = picked.get(i);
        sb.append("\n\n").append(i + 1).append(". ").append(tool.getName());
        sb.append("\n   ").append(tool.getDescription());
        String schema;
        try {
          schema = tool.getParameters() == null ? "{}" : tool.getParameters().toString();
        } catch (Exception e) {
          schema = "{}";
        }
        sb.append("\n   参数 schema：").append(abbreviate(schema, SCHEMA_MAX_CHARS));
      }
      return ok(sb.toString());
    } catch (RuntimeException e) {
      return error("tool_search 失败: " + e.getMessage());
    }
  }

  /** 截断长文本（超长时保留头部并标注省略了多少字符）。 */
  private static String abbreviate(String value, int maxChars) {
    if (value == null) {
      return "";
    }
    String oneLine = value.replace('\n', ' ').trim();
    if (oneLine.length() <= maxChars) {
      return oneLine;
    }
    return oneLine.substring(0, maxChars) + "…（省略 " + (oneLine.length() - maxChars) + " 字）";
  }
}
