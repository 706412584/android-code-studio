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

package com.tom.rv2ide.ai.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.HttpPort;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * MCP 客户端的回归测试。
 *
 * <p><b>为什么需要它</b>：MCP 的失败模式大多静默——会话 id 没拿到后续调用会失败、
 * JSON-RPC 错误藏在 200 响应里会被当成成功、非文本内容块被序列化进上下文会灌入
 * 大段 base64。这些在真机上只表现为「工具调用不工作」，很难归因。
 */
final class McpClientTest {

  /** 按顺序返回预设响应的假 HTTP 端口，并记录收到的请求。 */
  private static final class ScriptedHttp implements HttpPort {
    private final List<TextResponse> responses = new ArrayList<>();
    final List<String> postedBodies = new ArrayList<>();
    final List<Map<String, String>> postedHeaders = new ArrayList<>();

    ScriptedHttp enqueue(String body, Map<String, String> headers) {
      responses.add(new TextResponse(body, headers));
      return this;
    }

    ScriptedHttp enqueue(String body) {
      return enqueue(body, Collections.<String, String>emptyMap());
    }

    @Override
    public String getText(String url, Map<String, String> headers) throws Exception {
      throw new UnsupportedOperationException("MCP 只用 POST");
    }

    @Override
    public BinaryResponse getBytes(String url, Map<String, String> headers) {
      return new BinaryResponse("application/octet-stream", new byte[0]);
    }

    @Override
    public TextResponse postJson(String url, String jsonBody, Map<String, String> headers) {
      postedBodies.add(jsonBody);
      postedHeaders.add(headers == null ? Collections.<String, String>emptyMap() : headers);
      if (responses.isEmpty()) {
        return new TextResponse("{}", Collections.<String, String>emptyMap());
      }
      return responses.remove(0);
    }
  }

  private static Map<String, String> headers(String name, String value) {
    Map<String, String> map = new HashMap<>();
    map.put(name, value);
    return map;
  }

  private static String initResponse(String sessionId) {
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{"
        + "\"protocolVersion\":\"2025-03-26\","
        + "\"serverInfo\":{\"name\":\"test-server\",\"version\":\"1.0\"}}}"
        + (sessionId == null ? "" : "");
  }

  // ---- initialize ----

  @Test
  void initializeCapturesSessionIdFromResponseHeader() throws Exception {
    // 会话 id 走响应头下发；拿不到它后续调用就没有会话。
    ScriptedHttp http =
        new ScriptedHttp()
            .enqueue(initResponse("abc"), headers("Mcp-Session-Id", "sess-123"))
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{}}"); // initialized 通知

    McpClient client = new McpClient(http, "https://mcp.example.com/mcp");
    client.initialize();

    assertEquals("sess-123", client.getSessionId());
    assertTrue(client.isInitialized());
    assertEquals("test-server", client.getServerName());
    assertEquals("2025-03-26", client.getServerProtocolVersion());
  }

  @Test
  void sessionIdIsSentOnSubsequentRequests() throws Exception {
    ScriptedHttp http =
        new ScriptedHttp()
            .enqueue(initResponse("x"), headers("Mcp-Session-Id", "sess-abc"))
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{}}")
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[]}}");

    McpClient client = new McpClient(http, "https://mcp.example.com/mcp");
    client.listTools();

    // 最后一次请求（tools/list）必须带上会话 id
    Map<String, String> last = http.postedHeaders.get(http.postedHeaders.size() - 1);
    assertEquals("sess-abc", last.get("Mcp-Session-Id"));
  }

  @Test
  void headerLookupIsCaseInsensitive() {
    // 不同 server 回传的大小写不一致，按小写查询也必须命中。
    HttpPort.TextResponse response =
        new HttpPort.TextResponse("body", headers("MCP-SESSION-ID", "v"));

    assertEquals("v", response.header("mcp-session-id"));
    assertEquals("v", response.header("Mcp-Session-Id"));
    assertEquals("", response.header("nonexistent"));
    assertEquals("", response.header(null));
  }

  @Test
  void initializeIsIdempotent() throws Exception {
    // 每次拉工具列表都重新握手是浪费，且某些 server 会因此换掉会话。
    ScriptedHttp http =
        new ScriptedHttp()
            .enqueue(initResponse("x"), headers("Mcp-Session-Id", "s1"))
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{}}")
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[]}}");

    McpClient client = new McpClient(http, "https://mcp.example.com/mcp");
    client.initialize();
    client.initialize();
    client.initialize();

    // 只有首次握手发了 initialize（+1 条通知），没有重复握手
    assertEquals(2, http.postedBodies.size());
  }

  @Test
  void missingSessionIdIsNotFatal() throws Exception {
    // 无状态 server 不下发会话 id。把它当失败会排除一类合法实现。
    ScriptedHttp http =
        new ScriptedHttp()
            .enqueue(initResponse(null))
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{}}");

    McpClient client = new McpClient(http, "https://mcp.example.com/mcp");
    client.initialize();

    assertEquals("", client.getSessionId());
    assertFalse(client.isInitialized());
  }

  @Test
  void initializeRejectsNonHttpUrl() {
    McpClient client = new McpClient(new ScriptedHttp(), "ftp://example.com");
    assertThrows(Exception.class, client::initialize);
  }

  @Test
  void initializeRejectsEmptyUrl() {
    McpClient client = new McpClient(new ScriptedHttp(), "");
    assertThrows(Exception.class, client::initialize);
  }

  // ---- tools/list ----

  @Test
  void listToolsParsesNamesDescriptionsAndSchemas() throws Exception {
    ScriptedHttp http =
        new ScriptedHttp()
            .enqueue(initResponse("x"), headers("Mcp-Session-Id", "s"))
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{}}")
            .enqueue(
                "{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":["
                    + "{\"name\":\"query_db\",\"description\":\"run a query\","
                    + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}}}},"
                    + "{\"name\":\"ping\",\"description\":\"health check\"}]}}");

    List<McpToolInfo> tools = new McpClient(http, "https://mcp.example.com/mcp").listTools();

    assertEquals(2, tools.size());
    assertEquals("query_db", tools.get(0).getName());
    assertEquals("run a query", tools.get(0).getDescription());
    assertEquals("string", tools.get(0).getInputSchema().getJSONObject("properties")
        .getJSONObject("sql").optString("type"));
    // 缺 schema 时给出宽松的 object schema 而不是 null
    assertEquals("object", tools.get(1).getInputSchema().optString("type"));
  }

  @Test
  void listToolsSkipsNamelessEntries() throws Exception {
    // 没有名字的工具无法被调用，跳过它而不是让整个列表失败。
    ScriptedHttp http =
        new ScriptedHttp()
            .enqueue(initResponse("x"), headers("Mcp-Session-Id", "s"))
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{}}")
            .enqueue(
                "{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":["
                    + "{\"description\":\"no name\"},"
                    + "{\"name\":\"good\"}]}}");

    List<McpToolInfo> tools = new McpClient(http, "https://mcp.example.com/mcp").listTools();

    assertEquals(1, tools.size());
    assertEquals("good", tools.get(0).getName());
  }

  @Test
  void listToolsReturnsEmptyWhenServerProvidesNone() throws Exception {
    ScriptedHttp http =
        new ScriptedHttp()
            .enqueue(initResponse("x"), headers("Mcp-Session-Id", "s"))
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{}}")
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{}}");

    assertTrue(new McpClient(http, "https://mcp.example.com/mcp").listTools().isEmpty());
  }

  // ---- 错误处理 ----

  @Test
  void jsonRpcErrorIsSurfacedNotTreatedAsSuccess() throws Exception {
    // JSON-RPC 错误通常仍以 HTTP 200 返回，错误在 error 字段里。只看状态码会把失败
    // 当成成功，拿到一个空结果。
    ScriptedHttp http =
        new ScriptedHttp()
            .enqueue(initResponse("x"), headers("Mcp-Session-Id", "s"))
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{}}")
            .enqueue(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"error\":{\"code\":-32601,"
                    + "\"message\":\"Method not found\"}}");

    Exception error =
        assertThrows(Exception.class, () -> new McpClient(http, "https://mcp.example.com/mcp").listTools());

    assertTrue(error.getMessage().contains("-32601"), error.getMessage());
    assertTrue(error.getMessage().contains("Method not found"), error.getMessage());
  }

  @Test
  void nonJsonResponseExplainsLikelyCause() throws Exception {
    // 地址填错时打到的往往是网页。把正文前若干字符带进错误里，
    // 否则用户只看到「解析失败」而不知道该检查什么。
    ScriptedHttp http =
        new ScriptedHttp()
            .enqueue(initResponse("x"), headers("Mcp-Session-Id", "s"))
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{}}")
            .enqueue("<html><body>404 Not Found</body></html>");

    Exception error =
        assertThrows(Exception.class, () -> new McpClient(http, "https://wrong.example.com").listTools());

    assertTrue(error.getMessage().contains("不是 JSON"), error.getMessage());
    assertTrue(error.getMessage().contains("404 Not Found"), error.getMessage());
  }

  // ---- tools/call ----

  @Test
  void callToolReturnsTextContent() throws Exception {
    ScriptedHttp http =
        new ScriptedHttp()
            .enqueue(initResponse("x"), headers("Mcp-Session-Id", "s"))
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{}}")
            .enqueue(
                "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":["
                    + "{\"type\":\"text\",\"text\":\"first\"},"
                    + "{\"type\":\"text\",\"text\":\"second\"}]}}");

    String output =
        new McpClient(http, "https://mcp.example.com/mcp")
            .callTool("query_db", new JSONObject().put("sql", "select 1"));

    assertEquals("first\nsecond", output);
  }

  @Test
  void callToolIgnoresNonTextContentBlocks() throws Exception {
    // 图片块会带大段 base64。把它序列化进上下文既撑爆窗口又对模型无用。
    ScriptedHttp http =
        new ScriptedHttp()
            .enqueue(initResponse("x"), headers("Mcp-Session-Id", "s"))
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{}}")
            .enqueue(
                "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":["
                    + "{\"type\":\"image\",\"data\":\"AAAABBBBCCCC\"},"
                    + "{\"type\":\"text\",\"text\":\"caption\"}]}}");

    String output =
        new McpClient(http, "https://mcp.example.com/mcp").callTool("shot", new JSONObject());

    assertEquals("caption", output);
    assertFalse(output.contains("AAAABBBB"));
  }

  @Test
  void callToolSendsToolNameAndArguments() throws Exception {
    ScriptedHttp http =
        new ScriptedHttp()
            .enqueue(initResponse("x"), headers("Mcp-Session-Id", "s"))
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{}}")
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[]}}");

    new McpClient(http, "https://mcp.example.com/mcp")
        .callTool("my_tool", new JSONObject().put("arg", 42));

    String body = http.postedBodies.get(http.postedBodies.size() - 1);
    assertTrue(body.contains("\"tools/call\""), body);
    assertTrue(body.contains("my_tool"), body);
    assertTrue(body.contains("42"), body);
  }

  @Test
  void callToolRejectsBlankName() {
    McpClient client = new McpClient(new ScriptedHttp(), "https://mcp.example.com/mcp");
    assertThrows(IllegalArgumentException.class, () -> client.callTool("  ", new JSONObject()));
  }

  @Test
  void extractTextFallsBackToDirectTextField() {
    // 某些 server 直接返回 {"text": "..."} 而不是内容块数组。
    assertEquals("direct", McpClient.extractText(new JSONObject().put("text", "direct")));
    assertEquals("", McpClient.extractText(null));
  }

  // ---- 适配器 ----

  @Test
  void adapterPrefixesNameToAvoidShadowingBuiltinTools() throws Exception {
    // 远程 server 的工具可能与内置工具撞名（都叫 file_read）。不加前缀会让内置工具被
    // 静默覆盖——模型以为在写本地文件，实际调用了远程服务。
    McpClient client = new McpClient(new ScriptedHttp(), "https://mcp.example.com/mcp");
    McpToolAdapter adapter =
        new McpToolAdapter(client, new McpToolInfo("file_read", "remote read", null), "srv");

    assertEquals("mcpx_file_read", adapter.getName());
    assertEquals("file_read", adapter.getRemoteName());
    assertTrue(adapter.getName().startsWith(McpToolAdapter.PREFIX));
  }

  @Test
  void adapterSanitizesUnusualToolNames() {
    // MCP 允许比模型预期更宽的命名（点号、斜杠、空格）。
    assertEquals("a_b_c", McpToolAdapter.sanitize("a.b/c"));
    assertEquals("with_space", McpToolAdapter.sanitize("with space"));
    assertEquals("keep_under_1", McpToolAdapter.sanitize("keep_under_1"));
    assertEquals("", McpToolAdapter.sanitize(null));
  }

  @Test
  void adapterRequiresConfirmation() {
    // 远程工具行为不可预知，且用户配置时未必想到这一层。默认要求确认比默认放行安全。
    McpToolAdapter adapter =
        new McpToolAdapter(
            new McpClient(new ScriptedHttp(), "https://x.example.com"),
            new McpToolInfo("t", "", null),
            "srv");

    assertTrue(adapter.needsConfirmation());
    // 保守归为 WRITE：只读模式下不放行
    assertFalse(adapter.isAllowedInReadonlyMode());
  }

  @Test
  void adapterMentionsServerLabelInDescription() {
    McpToolAdapter adapter =
        new McpToolAdapter(
            new McpClient(new ScriptedHttp(), "https://x.example.com"),
            new McpToolInfo("t", "does things", null),
            "my-server");

    assertTrue(adapter.getDescription().contains("does things"));
    assertTrue(adapter.getDescription().contains("my-server"));
  }

  @Test
  void adapterReportsEmptyOutputExplicitly() throws Exception {
    // 空输出要明说，否则模型会以为调用没发生而重试。
    ScriptedHttp http =
        new ScriptedHttp()
            .enqueue(initResponse("x"), headers("Mcp-Session-Id", "s"))
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{}}")
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[]}}");

    McpToolAdapter adapter =
        new McpToolAdapter(
            new McpClient(http, "https://mcp.example.com/mcp"),
            new McpToolInfo("silent", "", null),
            "srv");

    ToolResult result = adapter.execute(new JSONObject(), null);

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("没有返回文本内容"), result.getContent());
  }

  @Test
  void adapterTurnsRemoteFailureIntoErrorResult() {
    // 远程失败要变成 error 结果回灌给模型（让它调整策略），而不是抛异常中断循环。
    McpClient client = new McpClient(new ScriptedHttp(), "");
    McpToolAdapter adapter =
        new McpToolAdapter(client, new McpToolInfo("t", "", null), "srv");

    ToolResult result = adapter.execute(new JSONObject(), null);

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("MCP 调用失败"));
  }

  @Test
  void adapterPassesSchemaThrough() throws Exception {
    JSONObject schema = new JSONObject().put("type", "object");
    McpToolAdapter adapter =
        new McpToolAdapter(
            new McpClient(new ScriptedHttp(), "https://x.example.com"),
            new McpToolInfo("t", "", schema),
            "srv");

    assertEquals("object", adapter.getParameters().optString("type"));
    assertNotNull(adapter.toJson());
  }

  @Test
  void adapterReportsProgressWhenContextProvided() throws Exception {
    ScriptedHttp http =
        new ScriptedHttp()
            .enqueue(initResponse("x"), headers("Mcp-Session-Id", "s"))
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{}}")
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}");

    final List<String> progress = new ArrayList<>();
    ToolContext context =
        ToolContext.builder()
            .homePath("/w")
            .progressListener(progress::add)
            .build();

    McpToolAdapter adapter =
        new McpToolAdapter(
            new McpClient(http, "https://mcp.example.com/mcp"),
            new McpToolInfo("t", "", null),
            "srv");
    adapter.execute(new JSONObject(), context);

    assertFalse(progress.isEmpty());
    assertTrue(progress.get(0).contains("t"));
  }
}
