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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.HttpPort;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * MCP **SSE 传输**的回归测试。
 *
 * <p><b>为什么需要它</b>：SSE 与 streamable HTTP 的失败模式完全不同，而且大多静默：
 * endpoint 事件没等到就 POST、结果推送早于 POST 返回、响应按到达顺序而非 id 配对、
 * 流断了以后继续往一条死连接上 POST。这些在真机上都只表现为「MCP 工具偶尔不工作」。
 *
 * <p>本测试用一个模拟 SSE server 的假 {@link HttpPort}：长连接由 {@code PipedWriter}
 * 驱动，测试可以精确控制「什么时候推哪个事件」，从而复现上面每种时序。
 *
 * <p><b>写这类测试的注意点</b>：{@code initialize()} 的流程是
 * 「POST initialize → 从流里拿结果 → POST initialized 通知」。因此测试**必须先把结果
 * 推进流里**再去等通知的 POST，否则两边互等（测试等 POST、客户端等结果）。
 */
final class McpSseTransportTest {

  private static final String SSE_URL = "https://mcp.example.com/sse";

  /**
   * 模拟 MCP 的 SSE server。
   *
   * <p>长连接通过管道实现：{@link #push(String)} 往管道里写原始 SSE 文本，
   * 被测代码的读线程从另一端按行读。POST 到的 URL 与请求体都被记录，
   * 以便断言 endpoint 事件给出的地址确实被用上了。
   */
  private static final class FakeSseServer implements HttpPort, Closeable {
    private PipedWriter writer;
    private PipedReader reader;

    private final List<String> postedUrls =
        Collections.synchronizedList(new ArrayList<String>());
    private final List<String> postedBodies =
        Collections.synchronizedList(new ArrayList<String>());
    private final List<Map<String, String>> postedHeaders =
        Collections.synchronizedList(new ArrayList<Map<String, String>>());

    /** 每次 openStream 都记一次，用来验证「断线后会重连」而不是复用死连接。 */
    private final AtomicInteger streamOpens = new AtomicInteger();

    /** 每次建连都要执行的动作（模拟服务端行为），在 openStream 内运行。 */
    private final List<Runnable> onConnect =
        Collections.synchronizedList(new ArrayList<Runnable>());

    /** 让测试等待「服务端已收到某次 POST」，避免靠 sleep 赌时序。 */
    private final BlockingQueue<String> postArrived = new ArrayBlockingQueue<>(64);

    /** 在 postJson 内部、返回之前调用的钩子：用来复现「结果抢在 POST 返回前到达」。 */
    private volatile Consumer<String> postHook;

    /** POST 的响应；默认 202 空体（SSE 传输下结果不走 POST 响应）。 */
    private volatile Map<String, String> postHeaders = Collections.emptyMap();

    @Override
    public StreamHandle openStream(String url, Map<String, String> headers) throws Exception {
      streamOpens.incrementAndGet();
      writer = new PipedWriter();
      reader = new PipedReader(writer, 64 * 1024);
      synchronized (onConnect) {
        for (Runnable action : onConnect) {
          action.run();
        }
      }
      return HttpPort.streamHandle(200, new BufferedReader(reader));
    }

    /** 注册「每次建连时执行」的动作。 */
    FakeSseServer onConnect(Runnable action) {
      onConnect.add(action);
      return this;
    }

    /** 往 SSE 流里写原始文本（调用方自己写 {@code \n}）。 */
    synchronized void push(String raw) throws IOException {
      writer.write(raw);
      writer.flush();
    }

    /** 服务端关闭长连接。 */
    synchronized void closeStream() throws IOException {
      writer.close();
    }

    /** 按 SSE 帧语法推一条事件。 */
    void pushEvent(String event, String data) throws IOException {
      push("event: " + event + "\ndata: " + data + "\n\n");
    }

    /** 等到服务端收到一条 POST 请求（超时即失败）。 */
    String awaitPost() throws InterruptedException {
      String body = postArrived.poll(5, TimeUnit.SECONDS);
      assertTrue(body != null, "等待 POST 超时：客户端没有发出请求");
      return body;
    }

    /**
     * 等到收到一条**内容含指定标记**的 POST。
     *
     * <p>握手会依次发出 initialize、notifications/initialized 两条 POST，因此不能靠
     * 「下一条 POST」来定位后续的 tools/list——那样拿到的往往是通知。
     */
    String awaitPostContaining(String marker) throws InterruptedException {
      long deadline = System.currentTimeMillis() + 5000;
      while (System.currentTimeMillis() < deadline) {
        String body = postArrived.poll(500, TimeUnit.MILLISECONDS);
        if (body != null && body.contains(marker)) {
          return body;
        }
      }
      throw new AssertionError("等待含 " + marker + " 的 POST 超时");
    }

    @Override
    public String getText(String url, Map<String, String> headers) {
      throw new UnsupportedOperationException("MCP 不用 getText");
    }

    @Override
    public BinaryResponse getBytes(String url, Map<String, String> headers) {
      return new BinaryResponse("application/octet-stream", new byte[0]);
    }

    @Override
    public TextResponse postJson(String url, String jsonBody, Map<String, String> headers) {
      postedUrls.add(url);
      postedBodies.add(jsonBody);
      postedHeaders.add(headers == null ? Collections.<String, String>emptyMap() : headers);
      Consumer<String> hook = postHook;
      if (hook != null) {
        // 在返回之前调用：这样推回去的结果确实「早于 POST 返回」。
        hook.accept(jsonBody);
      }
      postArrived.add(jsonBody);
      return new TextResponse("", postHeaders);
    }

    @Override
    public void close() throws IOException {
      if (writer != null) {
        writer.close();
      }
      if (reader != null) {
        reader.close();
      }
    }
  }

  /** 等到条件成立，避免测试里散落 sleep。 */
  private static void waitUntil(String what, java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(5);
    }
    assertTrue(condition.getAsBoolean(), "等待超时：" + what);
  }

  private static McpClient sseClient(FakeSseServer server) {
    return new McpClient(
        server, SSE_URL, Collections.<String, String>emptyMap(), McpClient.Transport.SSE);
  }

  private static String initResult(long id) {
    return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{"
        + "\"protocolVersion\":\"2024-11-05\","
        + "\"serverInfo\":{\"name\":\"sse-server\",\"version\":\"1.0\"}}}";
  }

  /** 在后台线程跑 initialize，异常收集起来由调用方断言。 */
  private static List<Exception> initializeAsync(final McpClient client) {
    final List<Exception> failures = Collections.synchronizedList(new ArrayList<Exception>());
    Thread thread = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          client.initialize();
        } catch (Exception e) {
          failures.add(e);
        }
      }
    });
    thread.setDaemon(true);
    thread.start();
    return failures;
  }

  /** 等握手完成。用 serverName 而不是 isInitialized：无状态 server 不下发会话 id。 */
  private static void awaitHandshake(McpClient client) throws InterruptedException {
    waitUntil("握手完成", () -> !client.getServerName().isEmpty());
  }

  /** 建连后立刻推 endpoint 事件（多数测试的开场）。 */
  private static void endpointOnConnect(final FakeSseServer server, final String endpoint) {
    server.onConnect(new Runnable() {
      @Override
      public void run() {
        try {
          server.pushEvent("endpoint", endpoint);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    });
  }

  // ---- endpoint 事件 ----

  @Test
  void endpointEventDecidesWhereRequestsArePosted() throws Exception {
    // 核心语义：POST 不能发到 SSE 端点本身，必须发到 endpoint 事件给出的地址。
    FakeSseServer server = new FakeSseServer();
    endpointOnConnect(server, "/messages?sessionId=abc123");

    McpClient client = sseClient(server);
    initializeAsync(client);

    server.awaitPost();
    // 相对路径必须相对 SSE 端点解析
    assertEquals("https://mcp.example.com/messages?sessionId=abc123", server.postedUrls.get(0));
    client.close();
    server.close();
  }

  @Test
  void absoluteEndpointUrlIsUsedAsIs() throws Exception {
    FakeSseServer server = new FakeSseServer();
    endpointOnConnect(server, "https://other.example.com/msg?id=1");

    McpClient client = sseClient(server);
    initializeAsync(client);

    server.awaitPost();
    assertEquals("https://other.example.com/msg?id=1", server.postedUrls.get(0));
    client.close();
    server.close();
  }

  @Test
  void missingEndpointEventFailsInsteadOfPostingToSseUrl() throws Exception {
    // 对端不是 SSE 端点时（例如把 streamable HTTP 地址填成了 sse），必须明确失败。
    // 静默地把 POST 打到 SSE 端点上只会得到一个看不懂的响应。
    FakeSseServer server = new FakeSseServer();
    server.onConnect(new Runnable() {
      @Override
      public void run() {
        try {
          // 推一个无关事件再关流 —— 相当于「这里不是 SSE 端点」
          server.pushEvent("message", "{}");
          server.closeStream();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    });

    McpClient client = sseClient(server);
    Exception error = assertThrows(Exception.class, client::initialize);
    assertTrue(
        error.getMessage().contains("endpoint") || error.getMessage().contains("SSE"),
        error.getMessage());
    assertTrue(server.postedUrls.isEmpty(), "不应向 SSE 端点 POST");
    client.close();
    server.close();
  }

  // ---- id 配对 ----

  @Test
  void responseIsMatchedByIdNotByArrivalOrder() throws Exception {
    // 服务端可能交错推送多条响应。按到达顺序取第一条会让并发调用互相串味，
    // 表现为「工具返回了别人的结果」——比报错更难发现。
    FakeSseServer server = new FakeSseServer();
    endpointOnConnect(server, "/messages");

    McpClient client = sseClient(server);
    initializeAsync(client);

    String initBody = server.awaitPost();
    long initId = new JSONObject(initBody).optLong("id");

    // 先推一条 id 对不上的响应，再推正确的那条
    server.pushEvent("message", "{\"jsonrpc\":\"2.0\",\"id\":999,\"result\":{\"junk\":true}}");
    server.pushEvent("message", initResult(initId));

    awaitHandshake(client);
    assertEquals("sse-server", client.getServerName());
    client.close();
    server.close();
  }

  @Test
  void resultDeliveredBeforePostReturnsIsStillMatched() throws Exception {
    // 本地 server / 快网下，结果可能早于 POST 的响应返回。若配对表在 POST 之后才登记，
    // 这条结果会被丢弃、调用方干等到超时——典型的「偶发超时」。
    FakeSseServer server = new FakeSseServer();
    endpointOnConnect(server, "/messages");

    // 在 postJson 内部就把结果推回去：这是字面意义上的「抢在 POST 返回之前」。
    server.postHook = new Consumer<String>() {
      @Override
      public void accept(String body) {
        try {
          JSONObject json = new JSONObject(body);
          if (!"initialize".equals(json.optString("method"))) {
            return;
          }
          server.pushEvent("message", initResult(json.optLong("id")));
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    };

    McpClient client = sseClient(server);
    initializeAsync(client);

    awaitHandshake(client);
    assertEquals("2024-11-05", client.getServerProtocolVersion());
    client.close();
    server.close();
  }

  @Test
  void toolsListResultComesFromStreamNotPostBody() throws Exception {
    // POST 只回 202 空体；结果从流里来。若实现去读 POST 响应体，会拿到空字符串。
    FakeSseServer server = new FakeSseServer();
    endpointOnConnect(server, "/messages");

    final McpClient client = sseClient(server);
    final List<McpToolInfo> tools =
        Collections.synchronizedList(new ArrayList<McpToolInfo>());
    final List<Exception> failures = Collections.synchronizedList(new ArrayList<Exception>());
    Thread caller = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          tools.addAll(client.listTools());
        } catch (Exception e) {
          failures.add(e);
        }
      }
    });
    caller.setDaemon(true);
    caller.start();

    String initBody = server.awaitPost();
    server.pushEvent("message", initResult(new JSONObject(initBody).optLong("id")));
    awaitHandshake(client);

    String listBody = server.awaitPostContaining("\"tools/list\"");
    server.pushEvent(
        "message",
        "{\"jsonrpc\":\"2.0\",\"id\":" + new JSONObject(listBody).optLong("id")
            + ",\"result\":{\"tools\":[{\"name\":\"query_db\",\"description\":\"run a query\"}]}}");

    waitUntil("listTools 返回", () -> !tools.isEmpty() || !failures.isEmpty());
    assertTrue(failures.isEmpty(), "不应失败：" + failures);
    assertEquals(1, tools.size());
    assertEquals("query_db", tools.get(0).getName());
    client.close();
    server.close();
  }

  @Test
  void jsonRpcErrorFromStreamIsSurfaced() throws Exception {
    // 错误在流里的 error 字段里，POST 仍回 202 空体。只看 POST 结果会把失败当成功。
    FakeSseServer server = new FakeSseServer();
    endpointOnConnect(server, "/messages");

    McpClient client = sseClient(server);
    List<Exception> failures = initializeAsync(client);

    String initBody = server.awaitPost();
    server.pushEvent(
        "message",
        "{\"jsonrpc\":\"2.0\",\"id\":" + new JSONObject(initBody).optLong("id")
            + ",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}");

    waitUntil("收到错误", () -> !failures.isEmpty());
    assertTrue(failures.get(0).getMessage().contains("-32601"), failures.get(0).getMessage());
    assertTrue(
        failures.get(0).getMessage().contains("Method not found"), failures.get(0).getMessage());
    client.close();
    server.close();
  }

  // ---- 流中断 ----

  @Test
  void streamBreakFailsThePendingRequestWithAReason() throws Exception {
    // 流断了而请求还在等，必须立刻报错并说明原因，不能干等到 60s 超时——
    // 那样用户看到的是「超时」，而真实原因是连接断了。
    FakeSseServer server = new FakeSseServer();
    endpointOnConnect(server, "/messages");

    McpClient client = sseClient(server);
    List<Exception> failures = initializeAsync(client);

    server.awaitPost();
    // 不回结果，直接断流
    server.closeStream();

    waitUntil("请求失败", () -> !failures.isEmpty());
    String message = failures.get(0).getMessage();
    assertTrue(message.contains("中断") || message.contains("关闭"), message);
    client.close();
    server.close();
  }

  @Test
  void reconnectsAfterStreamBreakInsteadOfReusingDeadConnection() throws Exception {
    // 断线后若不重连，后续调用会 POST 到一条已死的会话上，永远等不到响应。
    final FakeSseServer server = new FakeSseServer();
    final AtomicInteger connects = new AtomicInteger();
    endpointOnConnect(server, "/messages");
    // onConnect 的动作每次建连都跑，但 URL 需要递增，所以单独再注册一个
    server.onConnect(new Runnable() {
      @Override
      public void run() {
        connects.incrementAndGet();
      }
    });

    McpClient client = sseClient(server);
    List<Exception> failures = initializeAsync(client);

    server.awaitPost();
    server.closeStream();
    waitUntil("首次调用失败", () -> !failures.isEmpty());
    assertEquals(1, connects.get());

    // 断线后重连：openStream 会被再次调用
    initializeAsync(client);
    server.awaitPost();
    waitUntil("已重连", () -> connects.get() >= 2);
    assertTrue(server.streamOpens.get() >= 2, "应重新建立长连接");
    client.close();
    server.close();
  }

  @Test
  void deadConnectionIsNotReusedAfterBreak() throws Exception {
    // 断线后 isOpen() 必须立刻变 false，否则后续请求会被 POST 到一条已死的会话上，
    // 表现为「工具调用永远超时」而不是报错。
    FakeSseServer server = new FakeSseServer();
    endpointOnConnect(server, "/messages");

    McpClient client = sseClient(server);
    List<Exception> failures = initializeAsync(client);
    server.awaitPost();
    server.closeStream();
    waitUntil("首次调用失败", () -> !failures.isEmpty());

    int postsBefore = server.postedUrls.size();
    // 第二次调用必须先重连（openStream）再 POST
    initializeAsync(client);
    waitUntil("重新建连", () -> server.streamOpens.get() >= 2);
    waitUntil("再次 POST", () -> server.postedUrls.size() > postsBefore);
    client.close();
    server.close();
  }

  // ---- SSE 帧解析 ----

  @Test
  void commentsAndBlankLinesAreIgnored() throws Exception {
    // 服务端常发 `: ping` 心跳。把它当事件解析会污染配对表。
    FakeSseServer server = new FakeSseServer();
    server.onConnect(new Runnable() {
      @Override
      public void run() {
        try {
          server.push(": ping\n\n");
          server.push(": another heartbeat\n\n");
          server.pushEvent("endpoint", "/messages");
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    });

    McpClient client = sseClient(server);
    initializeAsync(client);
    String initBody = server.awaitPost();
    server.pushEvent("message", initResult(new JSONObject(initBody).optLong("id")));

    awaitHandshake(client);
    assertEquals("sse-server", client.getServerName());
    client.close();
    server.close();
  }

  @Test
  void malformedJsonEventDoesNotKillTheConnection() throws Exception {
    // 单条报文坏了不该拆掉整条连接：后续请求仍可能正常。
    FakeSseServer server = new FakeSseServer();
    endpointOnConnect(server, "/messages");

    McpClient client = sseClient(server);
    initializeAsync(client);
    String initBody = server.awaitPost();
    server.pushEvent("message", "{ this is not json ");
    server.pushEvent("message", initResult(new JSONObject(initBody).optLong("id")));

    awaitHandshake(client);
    assertEquals("sse-server", client.getServerName());
    client.close();
    server.close();
  }

  @Test
  void notificationWithoutIdIsNotTreatedAsAResponse() throws Exception {
    // 服务端会主动推通知（无 id）。把它当响应会让某个等待者收到垃圾。
    FakeSseServer server = new FakeSseServer();
    endpointOnConnect(server, "/messages");

    McpClient client = sseClient(server);
    initializeAsync(client);
    String initBody = server.awaitPost();
    server.pushEvent(
        "message", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{}}");
    server.pushEvent("message", initResult(new JSONObject(initBody).optLong("id")));

    awaitHandshake(client);
    assertEquals("sse-server", client.getServerName());
    client.close();
    server.close();
  }

  @Test
  void unknownEventTypeIsIgnored() throws Exception {
    // 规范要求忽略不认识的 event 类型。把它当 message 处理会污染配对表。
    FakeSseServer server = new FakeSseServer();
    server.onConnect(new Runnable() {
      @Override
      public void run() {
        try {
          server.pushEvent("ping", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"bogus\":true}}");
          server.pushEvent("endpoint", "/messages");
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    });

    McpClient client = sseClient(server);
    initializeAsync(client);
    String initBody = server.awaitPost();
    server.pushEvent("message", initResult(new JSONObject(initBody).optLong("id")));

    awaitHandshake(client);
    assertEquals("sse-server", client.getServerName());
    client.close();
    server.close();
  }

  // ---- 通知与会话 ----

  @Test
  void initializedNotificationIsPostedWithoutWaitingForResponse() throws Exception {
    // 通知没有响应。若实现等它，握手会永远卡住。
    FakeSseServer server = new FakeSseServer();
    endpointOnConnect(server, "/messages");

    McpClient client = sseClient(server);
    initializeAsync(client);
    String initBody = server.awaitPost();
    server.pushEvent("message", initResult(new JSONObject(initBody).optLong("id")));

    // 先推结果再等通知的 POST，否则两边互等
    String notifyBody = server.awaitPost();
    assertTrue(notifyBody.contains("notifications/initialized"), notifyBody);
    assertFalse(notifyBody.contains("\"id\""), "通知不应带 id：" + notifyBody);
    client.close();
    server.close();
  }

  @Test
  void sessionIdFromPostResponseHeaderIsSentOnLaterRequests() throws Exception {
    // 部分实现把会话 id 放在 POST 的响应头里，而不是 endpoint URL 里。
    FakeSseServer server = new FakeSseServer();
    endpointOnConnect(server, "/messages");
    Map<String, String> withSession = new HashMap<>();
    withSession.put("Mcp-Session-Id", "sse-sess-9");
    server.postHeaders = withSession;

    McpClient client = sseClient(server);
    initializeAsync(client);
    String initBody = server.awaitPost();
    server.pushEvent("message", initResult(new JSONObject(initBody).optLong("id")));

    // 通知应该带上会话 id
    server.awaitPost();
    waitUntil("通知已发出", () -> server.postedHeaders.size() >= 2);
    assertEquals(
        "sse-sess-9",
        server.postedHeaders.get(server.postedHeaders.size() - 1).get("Mcp-Session-Id"));
    assertEquals("sse-sess-9", client.getSessionId());
    client.close();
    server.close();
  }

  // ---- 传输选择 ----

  @Test
  void transportTypeDefaultsToHttpForUnknownValues() {
    // 已有用户的配置里没有 type 字段。它必须落到 http，否则升级后旧配置会改用 SSE
    // 连接一个 HTTP 端点，全部失效。
    assertEquals(McpClient.Transport.HTTP, McpClient.Transport.fromId(null));
    assertEquals(McpClient.Transport.HTTP, McpClient.Transport.fromId(""));
    assertEquals(McpClient.Transport.HTTP, McpClient.Transport.fromId("http"));
    // stdio 现在是受支持的传输（Termux 本地进程），不再是未知值。
    assertEquals(McpClient.Transport.STDIO, McpClient.Transport.fromId("stdio"));
    assertEquals(McpClient.Transport.HTTP, McpClient.Transport.fromId("garbage"));
    assertEquals(McpClient.Transport.SSE, McpClient.Transport.fromId("sse"));
    assertEquals(McpClient.Transport.SSE, McpClient.Transport.fromId(" SSE "));
    assertEquals(McpClient.Transport.SSE, McpClient.Transport.fromId("SSE"));
  }

  @Test
  void sseTransportOpensALongConnectionInsteadOfPostingToServerUrl() throws Exception {
    // 传 SSE 类型时必须走长连接路径，而不是退化回 POST 到 serverUrl。
    FakeSseServer server = new FakeSseServer();
    server.onConnect(new Runnable() {
      @Override
      public void run() {
        try {
          server.pushEvent("endpoint", "/messages");
          server.closeStream();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    });

    McpClient client = sseClient(server);
    assertThrows(Exception.class, client::initialize);
    // 它确实去建了长连接（说明走的是 SSE 传输）
    assertTrue(server.streamOpens.get() >= 1);
    client.close();
    server.close();
  }

  @Test
  void closeIsIdempotentAndStopsReconnecting() throws Exception {
    FakeSseServer server = new FakeSseServer();
    endpointOnConnect(server, "/messages");

    McpClient client = sseClient(server);
    client.close();
    client.close();

    Exception error = assertThrows(Exception.class, client::initialize);
    assertTrue(error.getMessage().contains("已关闭"), error.getMessage());
    assertEquals(0, server.streamOpens.get(), "关闭后不应再建连");
    server.close();
  }
}
