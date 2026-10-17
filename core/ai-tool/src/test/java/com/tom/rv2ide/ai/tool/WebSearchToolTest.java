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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** {@link WebSearchTool} 与 {@link RssSearchProvider} 的参数校验、排版与 RSS/Atom 解析。 */
class WebSearchToolTest {

  /** 可编排结果的 provider。 */
  private static final class FakeProvider implements WebSearchTool.Provider {
    final List<WebSearchTool.Result> results = new ArrayList<>();
    boolean configured = true;
    String reason = "";
    Exception failure;
    int lastLimit = -1;
    String lastQuery;

    @Override
    public boolean isConfigured() {
      return configured;
    }

    @Override
    public String unavailableReason() {
      return reason;
    }

    @Override
    public List<WebSearchTool.Result> search(String query, int limit) throws Exception {
      lastQuery = query;
      lastLimit = limit;
      if (failure != null) {
        throw failure;
      }
      return results;
    }
  }

  private static WebSearchTool.Result result(String title, String url, String snippet) {
    return new WebSearchTool.Result(title, url, snippet);
  }

  @Test
  void metadata() {
    WebSearchTool tool = new WebSearchTool(new FakeProvider());
    assertEquals("web_search", tool.getName());
    assertTrue(tool.getDescription().contains("web_fetch"), tool.getDescription());
    assertEquals(com.tom.rv2ide.ai.tool.api.ToolCategory.READ, tool.getCategory());
    assertEquals(com.tom.rv2ide.ai.tool.api.ToolDisplayCategory.READ, tool.getDisplayCategory());
    assertTrue(tool.isAllowedInReadonlyMode());
    // 参数声明里必须把 query 标为必填，否则模型会发出空查询。
    JSONObject params = tool.getParameters();
    assertTrue(params.getJSONArray("required").toString().contains("query"));
    assertTrue(params.getJSONObject("properties").has("limit"));
  }

  @Test
  void blankQueryIsRejected() {
    FakeProvider provider = new FakeProvider();
    ToolResult result =
        new WebSearchTool(provider).execute(new JSONObject().put("query", "   "), null);
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("query"), result.getContent());
    assertEquals(null, provider.lastQuery, "空查询不应调用后端");
  }

  @Test
  void missingProviderExplainsHowToConfigure() {
    ToolResult result =
        new WebSearchTool(null).execute(new JSONObject().put("query", "x"), null);
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("未配置"), result.getContent());
  }

  @Test
  void unconfiguredProviderUsesItsReason() {
    FakeProvider provider = new FakeProvider();
    provider.configured = false;
    provider.reason = "API key 未填写";

    ToolResult result =
        new WebSearchTool(provider).execute(new JSONObject().put("query", "x"), null);

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("API key 未填写"), result.getContent());
  }

  @Test
  void unconfiguredProviderWithEmptyReasonHasFallback() {
    FakeProvider provider = new FakeProvider();
    provider.configured = false;
    provider.reason = "";

    ToolResult result =
        new WebSearchTool(provider).execute(new JSONObject().put("query", "x"), null);

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("未配置搜索服务"), result.getContent());
  }

  @Test
  void limitDefaultsToFiveAndIsClamped() throws Exception {
    FakeProvider provider = new FakeProvider();
    WebSearchTool tool = new WebSearchTool(provider);

    tool.execute(new JSONObject().put("query", "x"), null);
    assertEquals(WebSearchTool.DEFAULT_RESULTS, provider.lastLimit);

    tool.execute(new JSONObject().put("query", "x").put("limit", 0), null);
    assertEquals(WebSearchTool.DEFAULT_RESULTS, provider.lastLimit, "<=0 应回落到默认");

    tool.execute(new JSONObject().put("query", "x").put("limit", 999), null);
    assertEquals(WebSearchTool.MAX_RESULTS, provider.lastLimit, "应被上限截断");
  }

  @Test
  void progressIsReportedWhenContextPresent() throws Exception {
    FakeProvider provider = new FakeProvider();
    provider.results.add(result("t", "https://x", "s"));
    final List<String> progress = new ArrayList<>();
    ToolContext ctx =
        ToolContext.builder()
            .homePath("/tmp")
            .progressListener(progress::add)
            .build();

    new WebSearchTool(provider).execute(new JSONObject().put("query", "kotlin"), ctx);

    assertTrue(progress.stream().anyMatch(m -> m.contains("kotlin")), progress.toString());
  }

  @Test
  void emptyResultsSuggestDifferentKeywords() throws Exception {
    FakeProvider provider = new FakeProvider();
    ToolResult result =
        new WebSearchTool(provider).execute(new JSONObject().put("query", "zzz"), null);

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("没有找到"), result.getContent());
    assertTrue(result.getContent().contains("关键词"), result.getContent());
  }

  @Test
  void nullResultListIsTreatedAsNoResults() throws Exception {
    WebSearchTool.Provider provider =
        new WebSearchTool.Provider() {
          @Override
          public boolean isConfigured() {
            return true;
          }

          @Override
          public String unavailableReason() {
            return "";
          }

          @Override
          public List<WebSearchTool.Result> search(String query, int limit) {
            return null;
          }
        };
    ToolResult result =
        new WebSearchTool(provider).execute(new JSONObject().put("query", "x"), null);
    assertFalse(result.isError());
    assertTrue(result.getContent().contains("没有找到"), result.getContent());
  }

  @Test
  void resultsAreNumberedWithUrlAndSnippet() throws Exception {
    FakeProvider provider = new FakeProvider();
    provider.results.add(result("Kotlin 官网", "https://kotlinlang.org", "Kotlin 是一种语言"));
    provider.results.add(result("Docs", "https://kotlinlang.org/docs", ""));

    ToolResult result =
        new WebSearchTool(provider).execute(new JSONObject().put("query", "kotlin"), null);

    assertFalse(result.isError());
    String content = result.getContent();
    assertTrue(content.contains("1. Kotlin 官网"), content);
    assertTrue(content.contains("https://kotlinlang.org"), content);
    assertTrue(content.contains("Kotlin 是一种语言"), content);
    assertTrue(content.contains("2. Docs"), content);
    assertTrue(content.contains("web_fetch"), content);
    // 空摘要不应留一行空白缩进。
    assertFalse(content.contains("   \n"), content);
  }

  @Test
  void longSnippetIsTruncated() throws Exception {
    StringBuilder longSnippet = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      longSnippet.append("word-");
    }
    FakeProvider provider = new FakeProvider();
    provider.results.add(result("t", "https://x", longSnippet.toString()));

    ToolResult result =
        new WebSearchTool(provider).execute(new JSONObject().put("query", "x"), null);

    String content = result.getContent();
    assertTrue(content.contains("…"), "超长摘要应被截断");
    assertFalse(content.contains(longSnippet.toString()), "不应包含完整超长摘要");
  }

  @Test
  void searchFailureIsReportedAndInterruptIsRestored() throws Exception {
    FakeProvider provider = new FakeProvider();
    provider.failure = new java.io.IOException("dns failure");

    ToolResult result =
        new WebSearchTool(provider).execute(new JSONObject().put("query", "x"), null);

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("dns failure"), result.getContent());

    // InterruptedException：必须恢复中断标志，否则上层取消逻辑收不到信号。
    FakeProvider interrupted = new FakeProvider();
    interrupted.failure = new InterruptedException("cancelled");
    Thread.currentThread().interrupt();
    try {
      ToolResult cancelled =
          new WebSearchTool(interrupted).execute(new JSONObject().put("query", "x"), null);
      assertTrue(cancelled.isError());
      assertTrue(
          Thread.currentThread().isInterrupted(), "InterruptedException 后必须恢复中断标志");
    } finally {
      Thread.interrupted(); // 清掉标志，避免污染同线程的后续用例。
    }
  }

  // ---- RssSearchProvider ----

  private static final class FakeHttp implements HttpPort {
    String requestedUrl;
    String body = "";
    Exception failure;

    @Override
    public String getText(String url, Map<String, String> headers) throws Exception {
      requestedUrl = url;
      if (failure != null) {
        throw failure;
      }
      return body;
    }

    @Override
    public BinaryResponse getBytes(String url, Map<String, String> headers) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TextResponse postJson(String url, String jsonBody, Map<String, String> headers) {
      throw new UnsupportedOperationException();
    }

    @Override
    public StreamHandle openStream(String url, Map<String, String> headers) {
      throw new UnsupportedOperationException();
    }
  }

  private static final String RSS =
      "<rss><channel>"
          + "<item><title>First &amp; Best</title><link>https://a.example/1</link>"
          + "<description><![CDATA[<b>bold</b> text]]></description></item>"
          + "<item><title>Second</title><link>https://a.example/2</link>"
          + "<description>plain</description></item>"
          + "</channel></rss>";

  private static final String ATOM =
      "<feed>"
          + "<entry><title>Atom One</title><link href=\"https://b.example/1\"/>"
          + "<summary>atom summary</summary></entry>"
          + "</feed>";

  @Test
  void providerIsAlwaysConfiguredWithoutApiKey() {
    RssSearchProvider provider = new RssSearchProvider(new FakeHttp());
    assertTrue(provider.isConfigured());
    assertEquals("", provider.unavailableReason());
  }

  @Test
  void queryIsUrlEncodedAndFormatIsRss() throws Exception {
    FakeHttp http = new FakeHttp();
    http.body = RSS;
    new RssSearchProvider(http).search("c++ & kotlin", 3);

    assertTrue(http.requestedUrl.startsWith(RssSearchProvider.DEFAULT_ENDPOINT), http.requestedUrl);
    assertTrue(http.requestedUrl.contains("q=c%2B%2B+%26+kotlin"), http.requestedUrl);
    assertTrue(http.requestedUrl.contains("format=rss"), http.requestedUrl);
    assertTrue(http.requestedUrl.contains("count=3"), http.requestedUrl);
    assertFalse(http.requestedUrl.contains("key="), "无 key 时不应带 key 参数");
  }

  @Test
  void apiKeyAndCustomEndpointAreUsed() throws Exception {
    FakeHttp http = new FakeHttp();
    http.body = RSS;
    new RssSearchProvider(http, "https://search.example/api", "secret")
        .search("x", 1);

    assertTrue(http.requestedUrl.startsWith("https://search.example/api?"), http.requestedUrl);
    assertTrue(http.requestedUrl.contains("key=secret"), http.requestedUrl);
  }

  @Test
  void endpointWithExistingQueryStringUsesAmpersand() throws Exception {
    FakeHttp http = new FakeHttp();
    http.body = RSS;
    new RssSearchProvider(http, "https://search.example/api?engine=bing", "")
        .search("x", 1);
    assertTrue(http.requestedUrl.contains("?engine=bing&q="), http.requestedUrl);
  }

  @Test
  void blankQueryReturnsEmptyWithoutRequest() throws Exception {
    FakeHttp http = new FakeHttp();
    assertTrue(new RssSearchProvider(http).search("   ", 5).isEmpty());
    assertEquals(null, http.requestedUrl, "空查询不应发请求");
  }

  @Test
  void rssItemsAreParsedAndCappedByLimit() throws Exception {
    FakeHttp http = new FakeHttp();
    http.body = RSS;
    List<WebSearchTool.Result> results = new RssSearchProvider(http).search("x", 1);

    assertEquals(1, results.size(), "应按 limit 截断");
    // 实体解码与 CDATA 剥离都要生效。
    assertEquals("First & Best", results.get(0).getTitle());
    assertEquals("https://a.example/1", results.get(0).getUrl());
    assertEquals("bold text", results.get(0).getSnippet());
  }

  @Test
  void zeroLimitFallsBackToDefault() throws Exception {
    FakeHttp http = new FakeHttp();
    http.body = RSS;
    List<WebSearchTool.Result> results = new RssSearchProvider(http).search("x", 0);
    assertEquals(2, results.size());
  }

  @Test
  void atomEntriesAreParsedWithHrefLink() {
    List<WebSearchTool.Result> results = RssSearchProvider.parseItems(ATOM);
    assertEquals(1, results.size());
    assertEquals("Atom One", results.get(0).getTitle());
    assertEquals("https://b.example/1", results.get(0).getUrl());
    assertEquals("atom summary", results.get(0).getSnippet());
  }

  @Test
  void malformedInputYieldsEmptyList() {
    assertTrue(RssSearchProvider.parseItems(null).isEmpty());
    assertTrue(RssSearchProvider.parseItems("").isEmpty());
    assertTrue(RssSearchProvider.parseItems("no items here").isEmpty());
    // 有开始标签但没有结束标签：不能死循环或抛异常。
    assertTrue(RssSearchProvider.parseItems("<item><title>x</title>").isEmpty());
  }

  @Test
  void entriesWithoutLinkButWithTitleAreKept() {
    List<WebSearchTool.Result> results =
        RssSearchProvider.parseItems("<item><title>Only title</title></item>");
    assertEquals(1, results.size());
    assertEquals("", results.get(0).getUrl());
  }

  @Test
  void searchPropagatesHttpFailure() {
    FakeHttp http = new FakeHttp();
    http.failure = new java.io.IOException("offline");
    assertThrows(
        Exception.class, () -> new RssSearchProvider(http).search("x", 5));
  }

  @Test
  void nullHttpFallsBackToNonePort() {
    // 构造时 null 端口回落到 HttpPort.none()：调用时给出明确错误而不是 NPE。
    RssSearchProvider provider = new RssSearchProvider(null);
    assertTrue(provider.isConfigured());
    assertThrows(Exception.class, () -> provider.search("x", 5));
  }

  @Test
  void nullEndpointFallsBackToDefault() {
    RssSearchProvider provider = new RssSearchProvider(new FakeHttp(), "  ", "");
    assertTrue(provider.buildUrl("q", 1).startsWith(RssSearchProvider.DEFAULT_ENDPOINT));
  }

  @Test
  void unquotedAttributeValueIsParsed() {
    // 少数 Atom 实现不写引号：<link href=https://x/y/>。
    List<WebSearchTool.Result> results =
        RssSearchProvider.parseItems(
            "<entry><title>t</title><link href=https://x/y/></entry>");
    assertEquals(1, results.size());
    assertEquals("https://x/y/", results.get(0).getUrl());
  }

  @Test
  void attributeEdgeCasesReturnEmptyInsteadOfThrowing() {
    // 无 >、无属性、属性值为空、引号未闭合——都只能返回空串。
    assertTrue(RssSearchProvider.parseItems("<item><title>t</title><link").isEmpty());
    assertTrue(
        RssSearchProvider.parseItems("<item><title>t</title><link rel=\"x\"/></item>")
            .get(0)
            .getUrl()
            .isEmpty());
    assertTrue(
        RssSearchProvider.parseItems("<item><title>t</title><link href=\"unclosed/></item>")
            .get(0)
            .getUrl()
            .isEmpty());
  }

  @Test
  void resultsUseHtmlExtractionAndCdataStripping() {
    List<WebSearchTool.Result> results =
        RssSearchProvider.parseItems(
            "<item><title><![CDATA[<em>Tagged</em>]]></title>"
                + "<link>https://x</link><description><![CDATA[<p>a</p><p>b</p>]]></description></item>");
    assertEquals(1, results.size());
    assertEquals("Tagged", results.get(0).getTitle());
    assertEquals("a\nb", results.get(0).getSnippet());
  }

  @Test
  void resultConstructorNormalizesNulls() {
    WebSearchTool.Result r = new WebSearchTool.Result(null, null, null);
    assertEquals("", r.getTitle());
    assertEquals("", r.getUrl());
    assertEquals("", r.getSnippet());
  }

  @Test
  void providerFailureSurfacesThroughTheTool() {
    // 端到端：provider 抛错时工具返回错误结果而不是让异常冒泡。
    FakeHttp http = new FakeHttp();
    http.failure = new java.io.IOException("timeout");
    ToolResult result =
        new WebSearchTool(new RssSearchProvider(http))
            .execute(new JSONObject().put("query", "x"), null);
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("timeout"), result.getContent());
  }

  @Test
  void emptyRssBodyProducesNoResultsMessage() {
    FakeHttp http = new FakeHttp();
    http.body = "";
    ToolResult result =
        new WebSearchTool(new RssSearchProvider(http))
            .execute(new JSONObject().put("query", "x"), null);
    assertFalse(result.isError());
    assertTrue(result.getContent().contains("没有找到"), result.getContent());
  }

  @Test
  void arraysAsListHelperIsUnusedButKeptCompiling() {
    // 占位：确保 Arrays 导入在无警告配置下仍被使用（避免 IDE 误报）。
    assertTrue(Arrays.asList(1, 2).contains(1));
    assertTrue(Collections.emptyList().isEmpty());
  }
}
