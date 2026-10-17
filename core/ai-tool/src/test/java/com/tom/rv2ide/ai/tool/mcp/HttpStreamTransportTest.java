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

package com.tom.rv2ide.ai.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.HttpPort;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpStreamTransport} 的边界与失败分支（直接构造传输，不走 {@link McpClient}）。
 *
 * <p>覆盖点：endpoint 事件的异常形态、通知报文、POST 失败后的清理、多行 data、
 * 流末尾无空行、读线程异常、以及等待期间被中断。这些分支在真机上都表现为
 * 「MCP 偶尔不工作」，只能靠显式注入时序来验证。
 */
class HttpStreamTransportTest {

  private static final String SSE_URL = "https://mcp.example.com/sse";

  /** 管道驱动的假 SSE server；可注入 POST 失败、自定义句柄与建连动作。 */
  private static final class Port implements HttpPort {
    PipedWriter writer;
    PipedReader reader;
    final List<String> postedUrls = Collections.synchronizedList(new ArrayList<String>());
    final List<String> postedBodies = Collections.synchronizedList(new ArrayList<String>());
    final List<Runnable> onConnect = Collections.synchronizedList(new ArrayList<Runnable>());
    final AtomicInteger streamOpens = new AtomicInteger();
    volatile boolean postThrows;
    volatile HttpPort.StreamHandle customHandle;

    Port onConnect(Runnable action) {
      onConnect.add(action);
      return this;
    }

    @Override
    public StreamHandle openStream(String url, Map<String, String> headers) throws Exception {
      streamOpens.incrementAndGet();
      HttpPort.StreamHandle custom = customHandle;
      if (custom != null) {
        return custom;
      }
      writer = new PipedWriter();
      reader = new PipedReader(writer, 64 * 1024);
      for (Runnable action : onConnect) {
        action.run();
      }
      // 关句柄时关**写端**：读线程阻塞在 readLine 上时，BufferedReader.close()
      // 会等读锁而卡住；关写端则让 readLine 正常返回 EOF，等价于真实 socket 被对端关闭。
      final PipedWriter writeEnd = writer;
      final BufferedReader buffered = new BufferedReader(reader);
      return new HttpPort.StreamHandle() {
        @Override
        public int getStatusCode() {
          return 200;
        }

        @Override
        public String readLine() throws Exception {
          return buffered.readLine();
        }

        @Override
        public void close() throws IOException {
          writeEnd.close();
        }
      };
    }

    synchronized void push(String raw) throws IOException {
      writer.write(raw);
      writer.flush();
    }

    void pushEvent(String event, String data) throws IOException {
      push("event: " + event + "\ndata: " + data + "\n\n");
    }

    void closeStream() throws IOException {
      writer.close();
    }

    @Override
    public String getText(String url, Map<String, String> headers) {
      throw new UnsupportedOperationException();
    }

    @Override
    public BinaryResponse getBytes(String url, Map<String, String> headers) {
      return new BinaryResponse("application/octet-stream", new byte[0]);
    }

    @Override
    public TextResponse postJson(String url, String jsonBody, Map<String, String> headers)
        throws Exception {
      if (postThrows) {
        throw new IOException("post boom");
      }
      postedUrls.add(url);
      postedBodies.add(jsonBody);
      return new TextResponse("", Collections.<String, String>emptyMap());
    }
  }

  private static HttpStreamTransport transport(Port port) {
    return new HttpStreamTransport(port, SSE_URL, Collections.<String, String>emptyMap());
  }

  /** 建连时推 endpoint 事件，使 open() 能完成。 */
  private static Port portWithEndpoint(final String endpoint) {
    Port port = new Port();
    port.onConnect(
        new Runnable() {
          @Override
          public void run() {
            try {
              port.pushEvent("endpoint", endpoint);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        });
    return port;
  }

  private static String request(long id, String method) {
    return new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("method", method)
        .put("params", new JSONObject())
        .toString();
  }

  /** 在后台线程发起一次 send，返回可读取结果的槽。 */
  private static Thread sendAsync(
      final HttpStreamTransport transport, final String body, final AtomicReference<String> out) {
    Thread thread =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                try {
                  out.set(transport.send(body));
                } catch (Exception e) {
                  out.set("ERR:" + e.getMessage());
                }
              }
            });
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  // ---- 构造与建连 ----

  @Test
  void nullServerUrlIsRejectedBeforeConnecting() {
    // 构造时 sseUrl 为 null 必须回落成空串，再由 requireHttpUrl 给出明确错误。
    HttpStreamTransport transport = new HttpStreamTransport(new Port(), null, null);
    Exception e = assertThrows(Exception.class, transport::open);
    assertTrue(e.getMessage().contains("未配置"), e.getMessage());
  }

  @Test
  void openIsIdempotentWhenAlreadyConnected() throws Exception {
    Port port = portWithEndpoint("/messages");
    HttpStreamTransport transport = transport(port);
    transport.open();
    // 第二次 open 直接返回：不应再建一条长连接（否则每条连接都会泄漏一个读线程）。
    transport.open();
    assertEquals(1, port.streamOpens.get(), "第二次 open 不应重建连接");
    transport.close();
  }

  // ---- endpoint 事件异常 ----

  @Test
  void endpointTimeoutIsReportedWhenWaitIsInterrupted() throws Exception {
    // 对端不发 endpoint 事件，open 会等到超时。中断等待线程可让这条超时路径
    // 立刻返回（与真实超时同一分支），且不必让测试真的睡 20 秒。
    final Port port = new Port();
    final HttpStreamTransport transport = transport(port);
    final AtomicReference<Exception> failure = new AtomicReference<>();
    Thread opener =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                try {
                  transport.open();
                } catch (Exception e) {
                  failure.set(e);
                }
              }
            });
    opener.setDaemon(true);
    opener.start();
    Thread.sleep(200);
    opener.interrupt();
    opener.join(5_000);

    assertNotNull(failure.get(), "open 应因等待 endpoint 失败而抛错");
    assertTrue(failure.get().getMessage().contains("endpoint"), failure.get().getMessage());
    transport.close();
  }

  @Test
  void emptyEndpointAddressFailsTheConnection() throws Exception {
    // endpoint 事件给了空地址：不能继续用一条没有消息地址的连接。
    Port port = portWithEndpoint("");
    HttpStreamTransport transport = transport(port);
    Exception e = assertThrows(Exception.class, transport::open);
    assertTrue(e.getMessage().contains("空地址"), e.getMessage());
    transport.close();
  }

  @Test
  void unresolvableRelativeEndpointFailsTheConnection() throws Exception {
    // 无法相对 SSE 端点解析的地址（未知协议）必须报错，而不是拼出一个坏 URL。
    Port port = portWithEndpoint("foo://bar/baz");
    HttpStreamTransport transport = transport(port);
    Exception e = assertThrows(Exception.class, transport::open);
    assertTrue(e.getMessage().contains("空地址"), e.getMessage());
    transport.close();
  }

  // ---- send / notify 分支 ----

  @Test
  void sendWithoutIdActsAsNotification() throws Exception {
    Port port = portWithEndpoint("/messages");
    HttpStreamTransport transport = transport(port);
    transport.open();
    String body =
        new JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")
            .toString();
    assertEquals("", transport.send(body), "无 id 报文应走 notify 并立即返回空串");
    assertEquals(1, port.postedBodies.size());
    transport.close();
  }

  @Test
  void sendWithNullBodyActsAsNotification() throws Exception {
    Port port = portWithEndpoint("/messages");
    HttpStreamTransport transport = transport(port);
    transport.open();
    // null 报文：extractId 必须容忍（返回空 id），否则会 NPE 而不是走通知路径。
    assertEquals("", transport.send(null));
    transport.close();
  }

  @Test
  void sendWithNullJsonIdActsAsNotification() throws Exception {
    Port port = portWithEndpoint("/messages");
    HttpStreamTransport transport = transport(port);
    transport.open();
    // JSON null 的 id 等同于没有 id：不能拿 "null" 当配对键去等一条永不来的响应。
    assertEquals("", transport.send("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"x\"}"));
    transport.close();
  }

  @Test
  void malformedBodyIsTreatedAsNotification() throws Exception {
    Port port = portWithEndpoint("/messages");
    HttpStreamTransport transport = transport(port);
    transport.open();
    assertEquals("", transport.send("this is not json"));
    transport.close();
  }

  @Test
  void postFailureClearsPendingRequestAndPropagates() throws Exception {
    final Port port = portWithEndpoint("/messages");
    final HttpStreamTransport transport = transport(port);
    transport.open();
    port.postThrows = true;
    Exception e = assertThrows(Exception.class, () -> transport.send(request(1, "ping")));
    assertTrue(e.getMessage().contains("post boom"), e.getMessage());

    // 失败后配对表必须清理干净：恢复 POST 后同一 id 仍可正常使用。
    port.postThrows = false;
    AtomicReference<String> out = new AtomicReference<>();
    Thread sender = sendAsync(transport, request(1, "ping"), out);
    Thread.sleep(200); // 让 POST 完成、进入等待
    port.pushEvent("message", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}");
    sender.join(5_000);
    assertNotNull(out.get());
    assertTrue(out.get().contains("\"ok\":true"), out.get());
    transport.close();
  }

  // ---- SSE 帧解析 ----

  @Test
  void multiLineDataIsJoinedWithNewline() throws Exception {
    // 规范允许多个 data: 行组成一条事件，行间以 \n 连接。
    Port port = portWithEndpoint("/messages");
    HttpStreamTransport transport = transport(port);
    transport.open();

    AtomicReference<String> out = new AtomicReference<>();
    Thread sender = sendAsync(transport, request(5, "ping"), out);
    Thread.sleep(200);
    // JSON 在空白处拆成两行：拼接后仍是合法报文。
    port.push(
        "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":5,\ndata: \"result\":{\"ok\":true}}\n\n");
    sender.join(5_000);
    assertNotNull(out.get());
    assertTrue(out.get().contains("\"ok\":true"), out.get());
    transport.close();
  }

  @Test
  void finalEventWithoutTrailingBlankLineIsDispatched() throws Exception {
    // 服务端直接关流、最后一帧没有空行结尾：这条事件仍必须投递。
    Port port = portWithEndpoint("/messages");
    HttpStreamTransport transport = transport(port);
    transport.open();

    AtomicReference<String> out = new AtomicReference<>();
    Thread sender = sendAsync(transport, request(6, "ping"), out);
    Thread.sleep(200);
    // 注意：没有结尾的空行。
    port.push("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":6,\"result\":{\"ok\":1}}\n");
    port.closeStream();
    sender.join(5_000);
    assertNotNull(out.get());
    assertTrue(out.get().contains("\"ok\":1"), out.get());
    transport.close();
  }

  @Test
  void readErrorIsReportedWithItsMessage() throws Exception {
    // 读线程遇到异常必须把原因带给等待者，而不是静默重连。
    final Port port = new Port();
    port.customHandle =
        new HttpPort.StreamHandle() {
          private int calls;

          @Override
          public int getStatusCode() {
            return 200;
          }

          @Override
          public String readLine() throws Exception {
            if (calls++ == 0) {
              return "event: endpoint";
            }
            throw new IOException("stream boom");
          }

          @Override
          public void close() {
            // 无需释放。
          }
        };
    HttpStreamTransport transport = transport(port);
    Exception e = assertThrows(Exception.class, transport::open);
    assertTrue(e.getMessage().contains("stream boom"), e.getMessage());
    transport.close();
  }

  @Test
  void interruptedResponseWaitIsReported() throws Exception {
    // 等待响应时被中断：必须抛出明确错误，而不是把空串当成功返回。
    Port port = portWithEndpoint("/messages");
    HttpStreamTransport transport = transport(port);
    transport.open();

    AtomicReference<String> out = new AtomicReference<>();
    Thread sender = sendAsync(transport, request(9, "never-answered"), out);
    Thread.sleep(200);
    sender.interrupt();
    sender.join(5_000);
    assertNotNull(out.get());
    assertTrue(out.get().startsWith("ERR:"), out.get());
    assertTrue(out.get().contains("中断"), out.get());
    transport.close();
  }
}
