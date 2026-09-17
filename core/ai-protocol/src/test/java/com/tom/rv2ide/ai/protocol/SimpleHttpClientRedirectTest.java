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

package com.tom.rv2ide.ai.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 重定向逐跳校验的回归测试。
 *
 * <p><b>为什么需要它</b>：{@code setInstanceFollowRedirects(true)} 只在初始 URL 上过了一次
 * {@link UrlPolicy}，之后每一跳都由 JDK 内部直接跳转，不再回到校验点。于是一个正常的
 * 公网地址只要回一个 {@code 302 Location: http://<内网>/}，请求就会打到内网设备上，
 * 而调用方以为自己在读公网文档——这是 SSRF 的常规形态，也是「用可信地址做跳板」。
 *
 * <p>这里用真实的本地 HTTP 服务构造跳转链，不用 mock：只有走真实连接才能证明
 * 「第二次请求是否真的发出去了」，而「没有发出」正是本修复的核心。
 */
final class SimpleHttpClientRedirectTest {

  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  private String base() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /**
   * 断言：跳转到**不允许的地址**时被 UrlPolicy 拒绝。
   *
   * <p><b>断言必须落在「拒绝的理由」上，不能只断言「抛异常」</b>：自动跟随的旧实现
   * 也会抛异常——它会真的去连 {@code evil.example.com}，然后因 DNS 解析失败而抛
   * {@code UnknownHostException}。只写 {@code assertThrows(Exception.class)} 的话，
   * 修复前后都通过，测试就失去了判别力（这正是脱敏测试犯过的错）。
   * 这里要求异常是 {@link IllegalArgumentException} 且说明是明文 HTTP 限制，
   * 只有「请求发出之前就被策略拦下」才可能满足。
   */
  @Test
  void doesNotFollowRedirectToDisallowedHost() throws Exception {
    AtomicInteger reached = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/start",
        exchange -> {
          // 跳到一个不允许的明文 HTTP 主机（公网域名，非 localhost/私网白名单）
          exchange.getResponseHeaders().add("Location", "http://evil.example.com/secret");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/secret",
        exchange -> {
          reached.incrementAndGet();
          byte[] body = "should-not-be-fetched".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> SimpleHttpClient.get(base() + "/start", 5000, 5000),
            "跳转到不允许的主机必须被 UrlPolicy 拒绝，而不是静默跟随");
    assertTrue(
        error.getMessage().toLowerCase().contains("cleartext"),
        "必须是被明文 HTTP 策略拦下（而非 DNS 失败）：" + error.getMessage());
    assertEquals(0, reached.get(), "被重定向的目标主机不得收到请求");
  }

  /**
   * 断言：跳转链中的**每一跳**都重新校验，而不仅是第一跳。
   *
   * <p>构造 /a → /b → 不允许主机的两段链。若只在第一跳校验，第二跳就会漏过去。
   */
  @Test
  void revalidatesEveryHopNotJustTheFirst() throws Exception {
    AtomicInteger reached = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/a",
        exchange -> {
          exchange.getResponseHeaders().add("Location", base() + "/b");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/b",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "http://evil.example.com/secret");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/secret",
        exchange -> {
          reached.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> SimpleHttpClient.get(base() + "/a", 5000, 5000),
            "第二跳跳向不允许的主机必须被拒绝");
    assertTrue(
        error.getMessage().toLowerCase().contains("cleartext"),
        "必须是被策略拦下，而不是第二跳根本没校验就 DNS 失败：" + error.getMessage());
    assertEquals(0, reached.get(), "被重定向的目标主机不得收到请求");
  }

  /** 允许多跳但全部合法时，仍应正常跟随并返回最终内容。 */
  @Test
  void followsAllowedRedirectChainToCompletion() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/a",
        exchange -> {
          // 相对 Location：必须按当前地址解析成绝对地址后再校验
          exchange.getResponseHeaders().add("Location", "/b");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/b",
        exchange -> {
          byte[] body = "final-content".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();

    assertEquals("final-content", SimpleHttpClient.get(base() + "/a", 5000, 5000));
  }

  /** 跳数超过上限必须失败，避免重定向环把请求拖死。 */
  @Test
  void stopsAfterTooManyRedirects() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/loop",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "/loop");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.start();

    Exception error =
        assertThrows(Exception.class, () -> SimpleHttpClient.get(base() + "/loop", 5000, 5000));
    assertTrue(
        String.valueOf(error.getMessage()).contains("redirect"),
        "错误信息应说明是重定向过多：" + error.getMessage());
  }

  /**
   * 断言：POST 收到 302 时按 HTTP 语义降级为 GET 并丢弃请求体。
   *
   * <p>否则会把请求体（可能含 API key）原样重放到重定向目标上。
   */
  @Test
  void postDowngradesToGetAndDropsBodyOnRedirect() throws Exception {
    AtomicReference<String> method = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/post",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "/after");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/after",
        exchange -> {
          method.set(exchange.getRequestMethod());
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();

    SimpleHttpClient.postJson(base() + "/post", "{\"api_key\":\"sk-secret\"}", 5000, 5000);

    assertEquals("GET", method.get(), "302 之后必须降级为 GET");
    assertFalse(body.get().contains("sk-secret"), "重定向后不得重放请求体：" + body.get());
  }
}
