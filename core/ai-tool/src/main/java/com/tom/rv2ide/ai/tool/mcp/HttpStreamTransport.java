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

import com.tom.rv2ide.ai.tool.ExceptionUtils;
import com.tom.rv2ide.ai.tool.HttpPort;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/**
 * MCP 的 **SSE 传输**（旧规范，2024-11-05 之前）。
 *
 * <p><b>协议流程</b>（与 streamable HTTP 的差别都在这里）：
 * <ol>
 *   <li>对 SSE 端点发 {@code GET}，服务端保持长连接，以 {@code text/event-stream} 推送事件；</li>
 *   <li>服务端先推一个 {@code endpoint} 事件，其 data 是后续 POST 的 URL（通常带 session id，
 *       且可能是相对路径）；</li>
 *   <li>客户端的每个 JSON-RPC 请求用 {@code POST} 发到那个 URL。响应体往往是
 *       {@code 202 Accepted} 空体——**真正的结果从第 1 步的长连接上推回来**；</li>
 *   <li>POST 与推送之间靠 JSON-RPC 的 {@code id} 配对。</li>
 * </ol>
 *
 * <p><b>为什么要后台读线程</b>：{@link #send} 是同步的（调用方要拿结果），而事件只能从
 * 长连接上读到。读线程负责把事件按 {@code id} 投进 {@link #pending}，
 * {@link #send} 只做「登记等待 → POST → 等投递」。
 *
 * <p><b>为什么配对表要在 POST 之前登记</b>：服务端可能在 POST 返回之前就把结果推回来
 * （本地 server、快网时很常见）。反过来的话，读线程找不到等待者，结果被丢弃，
 * {@link #send} 只能等到超时——表现为「偶发超时」，极难归因。
 *
 * <p><b>为什么按 id 配对而不是按到达顺序</b>：SSE 是单连接单通道，服务端可能交错推送
 * 多条响应（并发请求、服务端主动通知）。按顺序取第一条会让并发调用互相串味。
 *
 * <p><b>断线可重连</b>：读线程退出时会把连接状态清干净，下一次 {@link #send} 会重新建连。
 * 只有调用方显式 {@link #close()} 之后才不再重连——close 的语义是「我不再用它了」，
 * 静默重开等于泄漏。
 */
final class HttpStreamTransport implements McpTransport {

  /** 等待一条响应的上限。MCP server 可能因工具执行慢而晚回，给得比普通 HTTP 宽松。 */
  private static final long RESPONSE_TIMEOUT_MS = 60_000;

  /** 等待 endpoint 事件的上限：它应在建连后立刻到达，久等说明对端不是 SSE 端点。 */
  private static final long ENDPOINT_TIMEOUT_MS = 20_000;

  private final HttpPort http;
  private final String sseUrl;
  private final Map<String, String> extraHeaders;

  /** endpoint 事件给出的 POST 地址（已解析为绝对地址）。 */
  private volatile String messageUrl = "";

  private volatile String sessionId = "";

  /** 当前长连接；null 表示未连接。读线程退出时会被清空。 */
  private final AtomicReference<HttpPort.StreamHandle> stream =
      new AtomicReference<HttpPort.StreamHandle>();

  /** 读到 endpoint 事件时放行，使 {@link #open()} 不必轮询。 */
  private volatile CountDownLatch endpointLatch = new CountDownLatch(1);

  /** 最近一次读线程的终止原因；连接断开时带给等待中的 {@link #send}。 */
  private volatile String failure = "";

  /** 请求 id → 该响应的投递信箱。 */
  private final Map<String, ResponseBox> pending = new ConcurrentHashMap<>();

  /** 调用方显式关闭。与「连接断了」是两回事，后者可以重连。 */
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /** 建连串行化：并发调用时只建一条长连接。 */
  private final Object connectLock = new Object();

  HttpStreamTransport(HttpPort http, String sseUrl, Map<String, String> extraHeaders) {
    this.http = http;
    this.sseUrl = sseUrl == null ? "" : sseUrl.trim();
    this.extraHeaders =
        extraHeaders == null
            ? Collections.<String, String>emptyMap()
            : new HashMap<>(extraHeaders);
  }

  @Override
  public void open() throws Exception {
    if (isOpen()) {
      return;
    }
    if (closed.get()) {
      throw new Exception("MCP SSE 连接已关闭。");
    }
    synchronized (connectLock) {
      if (isOpen()) {
        return;
      }
      requireHttpUrl(sseUrl);

      // 上一轮断线留下的残留必须清干净，否则新连接会立刻被判成「已断开」。
      detachStream(stream.getAndSet(null));
      failure = "";
      CountDownLatch latch = new CountDownLatch(1);
      endpointLatch = latch;

      Map<String, String> headers = new HashMap<>(extraHeaders);
      headers.put("Accept", "text/event-stream");

      HttpPort.StreamHandle handle = http.openStream(sseUrl, headers);
      stream.set(handle);

      Thread reader = new Thread(new Runnable() {
        @Override
        public void run() {
          readLoop(handle, latch);
        }
      }, "mcp-sse-reader");
      // 守护线程：用户退出界面后不该被一条读线程吊住进程。
      reader.setDaemon(true);
      reader.start();

      // 建连失败只清理这一次的连接，不把传输标记为终局关闭：endpoint 事件没到可能只是
      // 对端慢或网络抖动，下一次调用应当还能重试。
      if (!awaitLatch(latch, ENDPOINT_TIMEOUT_MS)) {
        String reason = failure;
        detachStream(handle);
        throw new Exception(
            "MCP SSE 端点未下发 endpoint 事件"
                + (reason.isEmpty() ? "（超时）" : "：" + reason));
      }
      if (stream.get() != handle) {
        // endpoint 到了，但连接随即被对端关掉。如实报出，而不是把一条已死的连接交给
        // 调用方——那样它只会 POST 上去然后干等到超时，错误信息里看不到真实原因。
        throw new Exception(
            "MCP SSE 连接在建立后立即中断"
                + (failure.isEmpty() ? "。" : "：" + failure));
      }
      if (messageUrl.isEmpty()) {
        detachStream(handle);
        throw new Exception("MCP SSE 端点事件未给出可用的消息地址。");
      }
    }
  }

  @Override
  public String send(String jsonRpcBody) throws Exception {
    open();

    String id = extractId(jsonRpcBody);
    if (id.isEmpty()) {
      // 无 id 的报文是通知，不该走 send——走 send 会永远等不到响应。
      notify(jsonRpcBody);
      return "";
    }

    // 快照地址：POST 期间连接可能被重连换掉，用旧地址发到旧会话上只会静默超时。
    String target = messageUrl;
    requireMessageUrl(target);
    ResponseBox box = new ResponseBox();
    pending.put(id, box);
    try {
      HttpPort.TextResponse response = http.postJson(target, jsonRpcBody, postHeaders());
      // 会话 id 也可能从 POST 的响应头下发（部分实现如此）。POST 响应体本身通常是空的，
      // 不能当结果用——结果只从流里来。
      String fromHeader = response.header(McpClient.SESSION_HEADER);
      if (!fromHeader.isEmpty()) {
        sessionId = fromHeader;
      }
    } catch (Exception e) {
      pending.remove(id);
      throw e;
    }

    if (!box.await(RESPONSE_TIMEOUT_MS)) {
      pending.remove(id);
      String reason = failure;
      throw new Exception(
          "MCP SSE 等待响应超时（id=" + id + "）"
              + (reason.isEmpty() ? "" : "，连接已中断：" + reason));
    }
    return box.body;
  }

  @Override
  public void notify(String jsonRpcBody) throws Exception {
    open();
    String target = messageUrl;
    requireMessageUrl(target);
    http.postJson(target, jsonRpcBody, postHeaders());
  }

  @Override
  public String getSessionId() {
    return sessionId;
  }

  @Override
  public void close() {
    closed.set(true);
    // 先放行所有等待者再断流：反过来的话 send 会一直等到超时，
    // 而它其实已经不可能拿到结果了。
    failPending("MCP SSE 连接已关闭。");
    detachStream(stream.getAndSet(null));
    endpointLatch.countDown();
  }

  /**
   * 把指定连接从传输上摘下来（仅当它仍是当前连接时），并关闭它。
   *
   * <p><b>为什么带 handle 参数、为什么用 CAS</b>：读线程可能比它对应的连接活得久
   * （例如建连超时后已重连）。若直接关「当前连接」，一条迟到的读线程会把**新**连接掐掉，
   * 表现为「重连后立刻又断」，且只在超时后才复现。
   *
   * <p>用 CAS 而不是加锁，是为了不与 {@code connectLock} 构成环：{@code open()} 持锁时
   * 会调用本方法，而读线程在别处调用它——若两边都抢同一把锁，就是经典的
   * 锁顺序死锁。
   */
  private void detachStream(HttpPort.StreamHandle handle) {
    if (handle == null || !stream.compareAndSet(handle, null)) {
      return;
    }
    messageUrl = "";
    try {
      handle.close();
    } catch (Exception ignored) {
      // 关闭失败没有补救手段：连接已被对端放弃，或调用方正在退出。
    }
  }

  private boolean isOpen() {
    return stream.get() != null && messageUrl != null && !messageUrl.isEmpty();
  }

  /**
   * 读线程主体：解析 SSE 帧，按事件类型分派。
   *
   * <p>任何异常都终止循环并把原因记进 {@link #failure}——半读的流继续解析只会产出垃圾事件。
   */
  private void readLoop(HttpPort.StreamHandle handle, CountDownLatch latch) {
    try {
      String eventName = "message";
      StringBuilder data = new StringBuilder();
      String line;
      while ((line = handle.readLine()) != null) {
        if (line.isEmpty()) {
          dispatch(handle, latch, eventName, data.toString());
          eventName = "message";
          data.setLength(0);
          continue;
        }
        if (line.charAt(0) == ':') {
          // 注释行（常作心跳）。规范规定必须忽略。
          continue;
        }
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) {
          // 规范：冒号后恰好一个空格是分隔符，不属于数据。
          value = value.substring(1);
        }
        if ("event".equals(field)) {
          eventName = value;
        } else if ("data".equals(field)) {
          if (data.length() > 0) {
            data.append('\n');
          }
          data.append(value);
        }
        // id / retry 字段本实现用不到：断线不做续传，MCP 会话本身也没有续传语义。
      }
      // 流被服务端关闭且没有以空行结尾时，最后一帧仍要投递。
      if (data.length() > 0) {
        dispatch(handle, latch, eventName, data.toString());
      }
      // 与下面的 catch 同理：主动 close() 导致的结束不是故障，不该留下错误信息。
      fail(handle, latch, closed.get() ? "" : "服务端关闭了 SSE 连接。");
    } catch (Exception e) {
      // 调用方主动 close() 时关流会让阻塞中的 readLine 抛异常。那不是故障，
      // 不能把「连接已关闭」写进 failure 去污染后续调用拿到的错误信息。
      fail(handle, latch, closed.get() ? "" : ExceptionUtils.describeException(e));
    }
  }

  private void dispatch(
      HttpPort.StreamHandle handle, CountDownLatch latch, String eventName, String data) {
    if (closed.get()) {
      // 已关闭的连接上到达的事件不再处理：投递给没人等的信箱只会留下垃圾。
      return;
    }
    if ("endpoint".equals(eventName)) {
      String resolved = resolveEndpoint(data);
      if (resolved.isEmpty()) {
        fail(handle, latch, "endpoint 事件给出了空地址。");
        return;
      }
      messageUrl = resolved;
      latch.countDown();
      return;
    }
    if (!"message".equals(eventName)) {
      // 未知事件类型按规范应忽略（例如 ping）。把不认识的帧当响应解析只会污染配对表。
      return;
    }
    String trimmed = data.trim();
    if (trimmed.isEmpty()) {
      return;
    }
    JSONObject json;
    try {
      json = new JSONObject(trimmed);
    } catch (org.json.JSONException e) {
      // 单条报文坏了不该拆掉整条连接：后续请求仍可能正常。丢掉它继续读。
      return;
    }
    Object id = json.opt("id");
    if (id == null || id == JSONObject.NULL) {
      // 服务端主动发起的通知（例如 logging），或对通知的应答。本实现不消费它们。
      return;
    }
    ResponseBox box = pending.remove(String.valueOf(id));
    if (box == null) {
      // 迟到的响应（已超时）或重复投递。丢弃比覆盖别人的信箱安全。
      return;
    }
    box.deliver(trimmed);
  }

  /**
   * 把 endpoint 事件里的地址解析为绝对地址。
   *
   * <p>规范允许相对路径，而相对路径必须相对于 **SSE 端点**解析（不是域名根），
   * 否则 {@code https://host/api/sse} + {@code messages?x=1} 会被拼成
   * {@code https://host/messages?x=1}——丢掉可能存在的路径前缀。
   */
  private String resolveEndpoint(String raw) {
    String value = raw == null ? "" : raw.trim();
    if (value.isEmpty()) {
      return "";
    }
    if (value.startsWith("http://") || value.startsWith("https://")) {
      return value;
    }
    try {
      return new URL(new URL(sseUrl), value).toString();
    } catch (Exception e) {
      return "";
    }
  }

  /** POST 的请求头：JSON 内容类型 + 会话 id + 用户附加头。 */
  private Map<String, String> postHeaders() {
    Map<String, String> headers = new HashMap<>(extraHeaders);
    headers.put("Content-Type", "application/json");
    headers.put("Accept", "application/json");
    if (!sessionId.isEmpty()) {
      headers.put(McpClient.SESSION_HEADER, sessionId);
    }
    return headers;
  }

  private static String extractId(String jsonRpcBody) {
    if (jsonRpcBody == null) {
      return "";
    }
    try {
      Object id = new JSONObject(jsonRpcBody).opt("id");
      if (id == null || id == JSONObject.NULL) {
        return "";
      }
      return String.valueOf(id);
    } catch (org.json.JSONException e) {
      return "";
    }
  }

  /** 读线程终止时收尾：摘掉连接（避免 isOpen 仍为 true）、唤醒所有等待者。 */
  private void fail(HttpPort.StreamHandle handle, CountDownLatch latch, String reason) {
    if (reason != null && !reason.isEmpty()) {
      failure = reason;
    }
    // 只清理属于本读线程的连接：它可能已因超时被替换成新连接。
    detachStream(handle);
    failPending(failure.isEmpty() ? "MCP SSE 连接已中断。" : failure);
    latch.countDown();
  }

  private void failPending(String reason) {
    for (Map.Entry<String, ResponseBox> entry : pending.entrySet()) {
      ResponseBox box = pending.remove(entry.getKey());
      if (box != null) {
        box.fail(reason);
      }
    }
  }

  private static boolean awaitLatch(CountDownLatch latch, long timeoutMs) {
    try {
      return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static void requireHttpUrl(String url) throws Exception {
    if (url == null || url.trim().isEmpty()) {
      throw new Exception("未配置 MCP server 地址。");
    }
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      throw new Exception("MCP server 地址必须是 http(s)：" + url);
    }
  }

  /**
   * 确认消息地址可用。
   *
   * <p>必须显式检查：连接可能在两次调用之间被断开，此时 {@code messageUrl} 已被清空。
   * 不检查就会 POST 到一个空地址，拿到的错误与真实原因（连接断了）毫无关系。
   */
  private static void requireMessageUrl(String url) throws Exception {
    if (url == null || url.isEmpty()) {
      throw new Exception("MCP SSE 连接不可用（尚未收到 endpoint 事件或连接已断开）。");
    }
  }

  /** 一次请求的投递信箱：读线程与发起线程之间的交接点。 */
  private static final class ResponseBox {
    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile String body = "";
    private volatile String error = "";

    void deliver(String rawBody) {
      body = rawBody;
      latch.countDown();
    }

    void fail(String reason) {
      error = reason == null || reason.isEmpty() ? "MCP SSE 连接已中断。" : reason;
      latch.countDown();
    }

    /** @return true 表示已投递；false 表示超时（调用方需自己给出超时错误） */
    boolean await(long timeoutMs) throws Exception {
      boolean arrived;
      try {
        arrived = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new Exception("等待 MCP SSE 响应被中断。");
      }
      if (!arrived) {
        return false;
      }
      if (!error.isEmpty()) {
        throw new Exception(error);
      }
      return true;
    }
  }
}
