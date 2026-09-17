/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from LineCode Pro (https://github.com/LangLang03/LineCodePro),
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
import org.json.JSONObject;

/**
 * 搜索网页。
 *
 * <p><b>为什么把 provider 做成接口</b>：LCP 有 6 个 provider（Bing / BingRss / Brave /
 * SerpApi / Tavily / Default），各自需要不同的 API key 与响应格式。全部搬过来会让本模块
 * 引入 6 份解析代码与配置读取。这里只定义 {@link Provider} 窄接口，由 app 层按用户配置
 * 选择实现——工具层只负责参数校验与结果排版。
 *
 * <p><b>为什么默认不可用而不是报错退出</b>：搜索需要 API key。未配置时应当给出一条
 * 可执行的提示（「去设置里填 key」），而不是让整个工具注册失败——注册失败会让模型
 * 完全看不到这个工具，也就无法告诉用户「我需要搜索但没配置」。
 */
public final class WebSearchTool extends BaseTool {

  /** 单次返回的结果条数上限。 */
  static final int MAX_RESULTS = 10;

  /** 默认条数。取 5：足以判断「哪个链接值得抓」，又不会把上下文占满。 */
  static final int DEFAULT_RESULTS = 5;

  /** 单条摘要的字符上限。 */
  static final int MAX_SNIPPET_CHARS = 400;

  /** 搜索后端。由 app 层按配置实现。 */
  public interface Provider {

    /** 是否已配置可用（例如 API key 已填）。 */
    boolean isConfigured();

    /** 未配置时的原因，用于告诉用户该去哪里设置。 */
    String unavailableReason();

    /**
     * 执行搜索。
     *
     * @param query 查询词
     * @param limit 期望条数
     * @return 结果列表，按相关度
     * @throws Exception 网络或解析失败
     */
    java.util.List<Result> search(String query, int limit) throws Exception;
  }

  /** 一条搜索结果。 */
  public static final class Result {
    private final String title;
    private final String url;
    private final String snippet;

    public Result(String title, String url, String snippet) {
      this.title = title == null ? "" : title;
      this.url = url == null ? "" : url;
      this.snippet = snippet == null ? "" : snippet;
    }

    public String getTitle() {
      return title;
    }

    public String getUrl() {
      return url;
    }

    public String getSnippet() {
      return snippet;
    }
  }

  private final Provider provider;

  public WebSearchTool(Provider provider) {
    this.provider = provider;
  }

  @Override
  public String getName() {
    return ToolNames.WEB_SEARCH;
  }

  @Override
  public String getDescription() {
    return "Search the web and return titles, URLs and snippets. Use this to locate the "
        + "right page when you do not know its address, then fetch it with web_fetch.";
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
                        .put("description", "Search query"))
                .put(
                    "limit",
                    new JSONObject()
                        .put("type", "integer")
                        .put("description", "Number of results, 1-" + MAX_RESULTS)))
        .put("required", new org.json.JSONArray().put("query"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String query = input.optString("query", "").trim();
    if (query.isEmpty()) {
      return error("query 不能为空。");
    }
    if (provider == null) {
      return error("未配置搜索后端。请在 AI 设置的搜索服务中填入 API key。");
    }
    if (!provider.isConfigured()) {
      String reason = provider.unavailableReason();
      return error(
          "搜索不可用："
              + (reason == null || reason.isEmpty() ? "未配置搜索服务" : reason)
              + "。请在 AI 设置中配置搜索服务后重试。");
    }

    int limit = input.optInt("limit", DEFAULT_RESULTS);
    if (limit <= 0) {
      limit = DEFAULT_RESULTS;
    }
    limit = Math.min(limit, MAX_RESULTS);

    if (context != null) {
      context.reportProgress("搜索 " + query);
    }

    try {
      java.util.List<Result> results = provider.search(query, limit);
      if (results == null || results.isEmpty()) {
        // 明确区分「没有结果」与「搜索失败」：前者模型应换关键词，后者应报告故障。
        return ok("没有找到与「" + query + "」相关的结果。可以换用更具体或更通用的关键词。");
      }

      StringBuilder sb = new StringBuilder();
      sb.append("搜索「").append(query).append("」的结果：\n\n");
      int index = 1;
      for (Result result : results) {
        sb.append(index++).append(". ").append(result.getTitle()).append('\n');
        sb.append("   ").append(result.getUrl()).append('\n');
        String snippet = result.getSnippet();
        if (!snippet.isEmpty()) {
          if (snippet.length() > MAX_SNIPPET_CHARS) {
            snippet = snippet.substring(0, MAX_SNIPPET_CHARS) + "…";
          }
          sb.append("   ").append(snippet).append('\n');
        }
        sb.append('\n');
      }
      sb.append("需要详情时用 web_fetch 抓取上面的链接。");
      return ok(sb.toString());
    } catch (Exception e) {
      ExceptionUtils.restoreInterrupt(e);
      return error("搜索失败：" + ExceptionUtils.describeException(e));
    }
  }
}
