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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 新增内置工具（todo_update / web_fetch / web_search）的回归测试。
 *
 * <p><b>为什么需要它</b>：这些工具的错误大多表现为「静默失效」——待办没存下来、
 * 搜索结果解析成空、抓取到的正文是空的。这类问题在真机上不会报错，只会让模型
 * 表现得像「忘了自己做到哪」或「查不到资料」，很难归因。
 */
final class NewBuiltinToolsTest {

  // ---- todo_update ----

  private static JSONObject todos(Object... contentStatusPairs) throws Exception {
    org.json.JSONArray array = new org.json.JSONArray();
    for (int i = 0; i < contentStatusPairs.length; i += 2) {
      array.put(
          new JSONObject()
              .put("content", contentStatusPairs[i])
              .put("status", contentStatusPairs[i + 1]));
    }
    return new JSONObject().put("todos", array);
  }

  @Test
  void todoUpdateStoresItems() throws Exception {
    TodoStateStore store = TodoStateStore.inMemory();
    ToolResult result =
        new TodoUpdateTool(store)
            .execute(
                todos("读取配置", TodoItem.STATUS_COMPLETED, "改依赖", TodoItem.STATUS_IN_PROGRESS),
                null);

    assertFalse(result.isError());
    List<TodoItem> items = store.getItems();
    assertEquals(2, items.size());
    assertEquals("读取配置", items.get(0).getContent());
    assertEquals(TodoItem.STATUS_COMPLETED, items.get(0).getStatus());
    assertEquals(TodoItem.STATUS_IN_PROGRESS, items.get(1).getStatus());
  }

  @Test
  void todoUpdateNormalizesStatusSynonyms() throws Exception {
    // 模型常写 done / 完成。严格拒绝会让它反复重试同一次调用。
    TodoStateStore store = TodoStateStore.inMemory();
    new TodoUpdateTool(store)
        .execute(todos("a", "done", "b", "完成", "c", "doing", "d", "unknown-status"), null);

    List<TodoItem> items = store.getItems();
    assertEquals(TodoItem.STATUS_COMPLETED, items.get(0).getStatus());
    assertEquals(TodoItem.STATUS_COMPLETED, items.get(1).getStatus());
    assertEquals(TodoItem.STATUS_IN_PROGRESS, items.get(2).getStatus());
    // 无法识别的状态退回 pending 而不是报错
    assertEquals(TodoItem.STATUS_PENDING, items.get(3).getStatus());
  }

  @Test
  void todoUpdateReplacesEntireList() throws Exception {
    TodoStateStore store = TodoStateStore.inMemory();
    TodoUpdateTool tool = new TodoUpdateTool(store);

    tool.execute(todos("a", TodoItem.STATUS_PENDING, "b", TodoItem.STATUS_PENDING), null);
    tool.execute(todos("c", TodoItem.STATUS_COMPLETED), null);

    List<TodoItem> items = store.getItems();
    assertEquals(1, items.size());
    assertEquals("c", items.get(0).getContent());
  }

  @Test
  void todoUpdateWithEmptyArrayClears() throws Exception {
    TodoStateStore store = TodoStateStore.inMemory();
    TodoUpdateTool tool = new TodoUpdateTool(store);
    tool.execute(todos("a", TodoItem.STATUS_PENDING), null);
    tool.execute(todos(), null);

    assertTrue(store.getItems().isEmpty());
  }

  @Test
  void todoUpdateRejectsMissingArray() throws Exception {
    ToolResult result = new TodoUpdateTool(TodoStateStore.inMemory()).execute(new JSONObject(), null);
    assertTrue(result.isError());
  }

  @Test
  void todoUpdateRejectsTooManyItems() throws Exception {
    // 防止模型把整个需求文档逐条列进来把上下文撑爆。
    org.json.JSONArray array = new org.json.JSONArray();
    for (int i = 0; i < TodoUpdateTool.MAX_ITEMS + 1; i++) {
      array.put(
          new JSONObject().put("content", "item " + i).put("status", TodoItem.STATUS_PENDING));
    }
    ToolResult result =
        new TodoUpdateTool(TodoStateStore.inMemory())
            .execute(new JSONObject().put("todos", array), null);

    assertTrue(result.isError());
  }

  @Test
  void todoUpdateSkipsBlankItems() throws Exception {
    TodoStateStore store = TodoStateStore.inMemory();
    new TodoUpdateTool(store)
        .execute(todos("", TodoItem.STATUS_PENDING, "real", TodoItem.STATUS_PENDING), null);

    assertEquals(1, store.getItems().size());
  }

  @Test
  void todoUpdateIsAllowedInReadonlyMode() {
    // 待办只是记录，不改用户代码，只读模式下应放行。
    assertTrue(new TodoUpdateTool(TodoStateStore.inMemory()).isAllowedInReadonlyMode());
  }

  @Test
  void todoStorePersistsAcrossInstances(@TempDir Path tempDir) throws Exception {
    // 待办是模型「记住做到哪一步」的依据；不持久化则进程被回收后从头再来。
    File file = tempDir.resolve("todos.json").toFile();

    FileTodoStateStore first = new FileTodoStateStore(file);
    first.setItems(
        java.util.Arrays.asList(
            new TodoItem("step one", TodoItem.STATUS_COMPLETED),
            new TodoItem("step two", TodoItem.STATUS_IN_PROGRESS)));

    FileTodoStateStore second = new FileTodoStateStore(file);
    List<TodoItem> items = second.getItems();

    assertEquals(2, items.size());
    assertEquals("step one", items.get(0).getContent());
    assertEquals(TodoItem.STATUS_COMPLETED, items.get(0).getStatus());
    assertEquals(TodoItem.STATUS_IN_PROGRESS, items.get(1).getStatus());
  }

  @Test
  void todoStoreRendersForPrompt() {
    TodoStateStore store = TodoStateStore.inMemory();
    store.setItems(
        java.util.Arrays.asList(
            new TodoItem("done thing", TodoItem.STATUS_COMPLETED),
            new TodoItem("active thing", TodoItem.STATUS_IN_PROGRESS),
            new TodoItem("later thing", TodoItem.STATUS_PENDING)));

    String rendered = store.renderForPrompt();
    assertTrue(rendered.contains("[x] done thing"));
    assertTrue(rendered.contains("[>] active thing"));
    assertTrue(rendered.contains("[ ] later thing"));
  }

  @Test
  void todoStoreRendersEmptyStringWhenNoItems() {
    // 空串让调用方跳过注入，避免提示词里出现没有内容的段落。
    assertEquals("", TodoStateStore.inMemory().renderForPrompt());
  }

  @Test
  void todoStoreSurvivesCorruptFile(@TempDir Path tempDir) throws Exception {
    File file = tempDir.resolve("todos.json").toFile();
    Files.write(file.toPath(), "not json at all".getBytes(StandardCharsets.UTF_8));

    // 文件损坏时当作空列表，不抛异常——待办丢失的代价远小于工具不可用。
    assertTrue(new FileTodoStateStore(file).getItems().isEmpty());
  }

  // ---- web_fetch ----

  /** 返回预设响应的假 HTTP 端口。 */
  private static final class FakeHttp implements HttpPort {
    private final String text;
    private final Exception failure;
    private final List<String> requestedUrls = new ArrayList<>();

    FakeHttp(String text) {
      this.text = text;
      this.failure = null;
    }

    FakeHttp(Exception failure) {
      this.text = null;
      this.failure = failure;
    }

    @Override
    public String getText(String url, Map<String, String> headers) throws Exception {
      requestedUrls.add(url);
      if (failure != null) {
        throw failure;
      }
      return text;
    }

    @Override
    public BinaryResponse getBytes(String url, Map<String, String> headers) {
      return new BinaryResponse("application/octet-stream", new byte[0]);
    }
  }

  @Test
  void webFetchConvertsHtmlToText() throws Exception {
    FakeHttp http = new FakeHttp("<html><body><h1>Title</h1><p>Body text</p></body></html>");
    ToolResult result =
        new WebFetchTool(http)
            .execute(new JSONObject().put("url", "https://example.com/doc"), null);

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("Title"));
    assertTrue(result.getContent().contains("Body text"));
    // 不应残留标签
    assertFalse(result.getContent().contains("<h1>"));
  }

  @Test
  void webFetchKeepsPlainTextAsIs() throws Exception {
    FakeHttp http = new FakeHttp("plain text content");
    ToolResult result =
        new WebFetchTool(http).execute(new JSONObject().put("url", "https://example.com/a.txt"), null);

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("plain text content"));
  }

  @Test
  void webFetchRejectsNonHttpUrl() throws Exception {
    ToolResult result =
        new WebFetchTool(new FakeHttp("x"))
            .execute(new JSONObject().put("url", "file:///etc/passwd"), null);

    assertTrue(result.isError());
    // 不得发起请求
    assertTrue(((FakeHttp) new FakeHttp("x")).requestedUrls.isEmpty());
  }

  @Test
  void webFetchRejectsEmptyUrl() throws Exception {
    assertTrue(new WebFetchTool(new FakeHttp("x")).execute(new JSONObject(), null).isError());
  }

  @Test
  void webFetchReportsNetworkFailure() throws Exception {
    FakeHttp http = new FakeHttp(new java.io.IOException("connection refused"));
    ToolResult result =
        new WebFetchTool(http).execute(new JSONObject().put("url", "https://example.com"), null);

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("connection refused"));
  }

  @Test
  void webFetchExplainsEmptyResult() throws Exception {
    // 全靠 JS 渲染的页面抓下来是空壳。必须明说，否则模型以为页面本来就是空的。
    FakeHttp http = new FakeHttp("<html><body><div id=\"app\"></div></body></html>");
    ToolResult result =
        new WebFetchTool(http).execute(new JSONObject().put("url", "https://spa.example.com"), null);

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("JavaScript"));
  }

  @Test
  void webFetchTruncatesVeryLongContent() throws Exception {
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 20000; i++) {
      big.append("word ");
    }
    ToolResult result =
        new WebFetchTool(new FakeHttp(big.toString()))
            .execute(new JSONObject().put("url", "https://example.com/big.txt"), null);

    assertFalse(result.isError());
    assertTrue(result.getContent().length() < big.length());
    assertTrue(result.getContent().contains("截断"));
  }

  @Test
  void webFetchIsAllowedInReadonlyMode() {
    assertTrue(new WebFetchTool(HttpPort.none()).isAllowedInReadonlyMode());
  }

  // ---- web_search ----

  @Test
  void webSearchFormatsResults() throws Exception {
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
            return java.util.Arrays.asList(
                new WebSearchTool.Result("Doc title", "https://example.com/doc", "snippet text"));
          }
        };

    ToolResult result =
        new WebSearchTool(provider).execute(new JSONObject().put("query", "kotlin coroutines"), null);

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("Doc title"));
    assertTrue(result.getContent().contains("https://example.com/doc"));
    assertTrue(result.getContent().contains("snippet text"));
  }

  @Test
  void webSearchExplainsWhenProviderUnavailable() throws Exception {
    // 未配置时必须给出可执行的提示，而不是静默失败。
    WebSearchTool.Provider provider =
        new WebSearchTool.Provider() {
          @Override
          public boolean isConfigured() {
            return false;
          }

          @Override
          public String unavailableReason() {
            return "未填写搜索 API key";
          }

          @Override
          public List<WebSearchTool.Result> search(String query, int limit) {
            throw new AssertionError("未配置时不应调用 search");
          }
        };

    ToolResult result = new WebSearchTool(provider).execute(new JSONObject().put("query", "x"), null);

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("未填写搜索 API key"));
  }

  @Test
  void webSearchDistinguishesNoResultsFromFailure() throws Exception {
    // 「没有结果」模型应换关键词；「搜索失败」模型应报告故障。两者不能混为一谈。
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
            return Collections.emptyList();
          }
        };

    ToolResult result = new WebSearchTool(provider).execute(new JSONObject().put("query", "x"), null);

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("没有找到"));
  }

  @Test
  void webSearchRejectsEmptyQuery() throws Exception {
    ToolResult result = new WebSearchTool(null).execute(new JSONObject(), null);
    assertTrue(result.isError());
  }

  @Test
  void webSearchClampsLimit() throws Exception {
    final int[] observed = new int[1];
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
            observed[0] = limit;
            return Collections.emptyList();
          }
        };

    new WebSearchTool(provider)
        .execute(new JSONObject().put("query", "x").put("limit", 999), null);

    assertEquals(WebSearchTool.MAX_RESULTS, observed[0]);
  }

  // ---- RSS 解析 ----

  @Test
  void rssParsesRssItems() {
    String rss =
        "<rss><channel>"
            + "<item><title>First</title><link>https://a.example/1</link>"
            + "<description>desc one</description></item>"
            + "<item><title>Second</title><link>https://a.example/2</link>"
            + "<description>desc two</description></item>"
            + "</channel></rss>";

    List<WebSearchTool.Result> results = RssSearchProvider.parseItems(rss);

    assertEquals(2, results.size());
    assertEquals("First", results.get(0).getTitle());
    assertEquals("https://a.example/1", results.get(0).getUrl());
    assertEquals("desc one", results.get(0).getSnippet());
  }

  @Test
  void rssParsesAtomEntriesWithHrefAttribute() {
    // Atom 把地址放在属性里而不是元素文本。只认 RSS 会在换端点后静默返回空结果。
    String atom =
        "<feed>"
            + "<entry><title>Atom title</title><link href=\"https://b.example/x\"/>"
            + "<summary>atom summary</summary></entry>"
            + "</feed>";

    List<WebSearchTool.Result> results = RssSearchProvider.parseItems(atom);

    assertEquals(1, results.size());
    assertEquals("Atom title", results.get(0).getTitle());
    assertEquals("https://b.example/x", results.get(0).getUrl());
    assertEquals("atom summary", results.get(0).getSnippet());
  }

  @Test
  void rssStripsCdataAndHtmlFromSnippets() {
    String rss =
        "<item><title>T</title><link>https://c.example</link>"
            + "<description><![CDATA[<b>bold</b> text]]></description></item>";

    List<WebSearchTool.Result> results = RssSearchProvider.parseItems(rss);

    assertEquals(1, results.size());
    assertEquals("bold text", results.get(0).getSnippet());
  }

  @Test
  void rssReturnsEmptyForGarbageInput() {
    assertTrue(RssSearchProvider.parseItems("not xml").isEmpty());
    assertTrue(RssSearchProvider.parseItems(null).isEmpty());
    assertTrue(RssSearchProvider.parseItems("").isEmpty());
  }

  @Test
  void rssHandlesTruncatedFeedWithoutCrashing() {
    // 网络中断会留下截断的 XML。
    String truncated = "<rss><channel><item><title>Half";
    assertTrue(RssSearchProvider.parseItems(truncated).isEmpty());
  }

  @Test
  void rssEncodesQueryParameters() {
    // 不编码时含空格或 & 的查询会把 URL 拆坏，表现为「搜什么都返回同样结果」。
    RssSearchProvider provider = new RssSearchProvider(HttpPort.none());
    String url = provider.buildUrl("kotlin & coroutines", 5);

    assertTrue(url.contains("q=kotlin+%26+coroutines") || url.contains("q=kotlin%20%26%20coroutines"),
        url);
    assertFalse(url.contains("q=kotlin & coroutines"));
  }

  @Test
  void rssProviderIsAlwaysConfigured() {
    // 免密钥实现让功能开箱可用——需要 key 意味着多数用户永远用不上。
    assertTrue(new RssSearchProvider(HttpPort.none()).isConfigured());
  }

  @Test
  void rssSearchTrimsToRequestedLimit() throws Exception {
    StringBuilder rss = new StringBuilder("<rss><channel>");
    for (int i = 0; i < 10; i++) {
      rss.append("<item><title>t")
          .append(i)
          .append("</title><link>https://x.example/")
          .append(i)
          .append("</link></item>");
    }
    rss.append("</channel></rss>");

    List<WebSearchTool.Result> results =
        new RssSearchProvider(new FakeHttp(rss.toString())).search("q", 3);

    assertEquals(3, results.size());
  }

  @Test
  void todoStateStoreNoneDoesNotThrow() {
    TodoStateStore none = TodoStateStore.none();
    none.setItems(java.util.Arrays.asList(new TodoItem("x", TodoItem.STATUS_PENDING)));
    assertTrue(none.getItems().isEmpty());
    none.clear();
    assertNotNull(none.renderForPrompt());
  }
}
