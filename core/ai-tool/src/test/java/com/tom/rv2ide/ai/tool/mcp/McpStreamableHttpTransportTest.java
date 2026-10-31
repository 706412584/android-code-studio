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

package com.tom.rv2ide.ai.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.HttpPort;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * streamable HTTP 传输的线上行为（规范符合性）。
 *
 * <p><b>为什么需要这组测试</b>：MCP 2025-03-26 规范要求客户端在 POST 的 {@code Accept}
 * 头里**同时**声明 {@code application/json} 与 {@code text/event-stream}；而服务端可以
 * 用其中任一种回包。此前实现只声明了 {@code application/json}，且假定响应体一定是纯
 * JSON——两个假设都会让「用官方 SDK 写的 server」直接连不上，而现象只是
 * 「工具列表找不到」：服务端 406 或客户端解析失败，两条路径都被
 * {@code registerMcpTools} 的 catch 吞成一条错误日志。
 *
 * <p>用户实际遇到的正是这个：配置了一个 HTTP MCP server，模型说工具列表里没有它。
 */
final class McpStreamableHttpTransportTest {

  /** 记录请求头、按脚本回包的假 HTTP 端口。 */
  private static final class RecordingHttp implements HttpPort {
    final List<Map<String, String>> postedHeaders = new ArrayList<>();
    final List<HttpPort.TextResponse> responses = new ArrayList<>();

    RecordingHttp enqueue(String body, Map<String, String> headers) {
      responses.add(new TextResponse(body, headers));
      return this;
    }

    RecordingHttp enqueue(String body) {
      return enqueue(body, Collections.<String, String>emptyMap());
    }

    @Override
    public String getText(String url, Map<String, String> headers) {
      throw new UnsupportedOperationException();
    }

    @Override
    public BinaryResponse getBytes(String url, Map<String, String> headers) {
      throw new UnsupportedOperationException();
    }

    @Override
    public StreamHandle openStream(String url, Map<String, String> headers) {
      throw new UnsupportedOperationException("streamable HTTP 不建长连接");
    }

    @Override
    public TextResponse postJson(String url, String jsonBody, Map<String, String> headers) {
      postedHeaders.add(headers == null ? Collections.<String, String>emptyMap() : headers);
      return responses.isEmpty()
          ? new TextResponse("{}", Collections.<String, String>emptyMap())
          : responses.remove(0);
    }
  }

  private static Map<String, String> header(String name, String value) {
    return Collections.singletonMap(name, value);
  }

  private static String initJson() {
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{"
        + "\"protocolVersion\":\"2025-03-26\","
        + "\"serverInfo\":{\"name\":\"mock\",\"version\":\"1.0\"}}}";
  }

  /**
   * 规范要求 Accept 同时声明两种类型。
   *
   * <p>只发 {@code application/json} 时，严格实现的 server（官方 TypeScript/Python SDK）
   * 直接回 406，用户看到的是「连不上」，而不是「少了一个头」。
   */
  @Test
  void acceptHeaderDeclaresBothJsonAndEventStream() throws Exception {
    RecordingHttp http =
        new RecordingHttp()
            .enqueue(initJson(), header("Mcp-Session-Id", "s1"))
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{}}");

    new McpClient(http, "https://mcp.example.com/mcp").initialize();

    Map<String, String> first = http.postedHeaders.get(0);
    String accept = first.get("Accept");
    assertTrue(accept != null, "必须显式声明 Accept，否则部分 server 直接 406");
    assertTrue(
        accept.contains("application/json"), "Accept 必须含 application/json：" + accept);
    assertTrue(
        accept.contains("text/event-stream"),
        "Accept 必须含 text/event-stream（规范要求，缺了会被 406）：" + accept);
  }

  /**
   * 服务端按规范可以用 {@code text/event-stream} 回包。
   *
   * <p>此时响应体是 SSE 帧（{@code event: message\ndata: {...}\n\n}），不是裸 JSON。
   * 直接把整段交给 {@code new JSONObject(...)} 会抛异常，表现为「server 返回的不是
   * JSON（可能地址填错）」——一句会把排查方向带偏的错误。
   */
  @Test
  void sseFramedResponseIsParsed() throws Exception {
    String sseBody = "event: message\ndata: " + initJson() + "\n\n";
    RecordingHttp http =
        new RecordingHttp()
            .enqueue(sseBody, header("Mcp-Session-Id", "s1"))
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{}}")
            .enqueue(
                "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":"
                    + "{\"tools\":[{\"name\":\"echo\",\"description\":\"d\","
                    + "\"inputSchema\":{\"type\":\"object\"}}]}}\n\n");

    McpClient client = new McpClient(http, "https://mcp.example.com/mcp");
    List<McpToolInfo> tools = client.listTools();

    assertEquals(1, tools.size(), "SSE 帧里的 tools/list 结果必须被解析出来");
    assertEquals("echo", tools.get(0).getName());
    assertEquals("mock", client.getServerName());
  }

  /** 多行 {@code data:} 按规范应以换行拼接（虽然 MCP 实际只发单行 JSON）。 */
  @Test
  void multiLineDataFieldIsJoined() throws Exception {
    String sseBody =
        "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\ndata: \"result\":{}}\n\n";
    RecordingHttp http =
        new RecordingHttp()
            .enqueue(sseBody, header("Mcp-Session-Id", "s1"))
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{}}");

    // 只验证不抛异常且能拿到 serverName（空串也算成功解析）
    McpClient client = new McpClient(http, "https://mcp.example.com/mcp");
    client.initialize();
    assertEquals("", client.getServerName());
  }

  /** 纯 JSON 响应必须继续工作（服务端也可以选这种回包方式）。 */
  @Test
  void plainJsonResponseStillWorks() throws Exception {
    RecordingHttp http =
        new RecordingHttp()
            .enqueue(initJson(), header("Mcp-Session-Id", "s1"))
            .enqueue("{\"jsonrpc\":\"2.0\",\"result\":{}}");

    McpClient client = new McpClient(http, "https://mcp.example.com/mcp");
    client.initialize();

    assertEquals("s1", client.getSessionId());
    assertEquals("mock", client.getServerName());
  }

  /** 非 SSE、非 JSON 的正文仍应给出可读错误（不能被 SSE 解析吞掉）。 */
  @Test
  void htmlBodyStillReportsNotJson() {
    RecordingHttp http =
        new RecordingHttp()
            .enqueue("<html>wrong endpoint</html>", Collections.<String, String>emptyMap());

    Exception error =
        assertThrows(
            Exception.class, () -> new McpClient(http, "https://x.example.com").initialize());
    assertTrue(error.getMessage().contains("不是 JSON"), error.getMessage());
  }
}
