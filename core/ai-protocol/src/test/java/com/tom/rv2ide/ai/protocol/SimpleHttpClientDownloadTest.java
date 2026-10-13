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

package com.tom.rv2ide.ai.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SimpleHttpClient#downloadToFile} 的回归测试。
 *
 * <p><b>为什么这组测试重要</b>：这个方法承载云编译的 artifact / 日志下载，
 * 有两个只在真实跳转链里才会暴露的语义：
 * <ol>
 *   <li><b>重定向后必须剥离 Authorization</b>——GitHub 的下载端点 302 到对象存储的
 *       预签名 URL，而预签名 URL 带上 Authorization 会被 400 拒绝。若剥离逻辑失效，
 *       下载会在第二跳失败，且错误信息（400）完全指不到根因；</li>
 *   <li><b>逐跳校验仍然生效</b>——下载同样必须经过 UrlPolicy，不能因为是「下载」
 *       就绕过安全边界。</li>
 * </ol>
 * 用真实本地 HTTP 服务构造跳转链，因为「第二跳是否带上了头」只能在服务端观测到。
 */
final class SimpleHttpClientDownloadTest {

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

  @Test
  void downloadsContentToFile(@TempDir File tempDir) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/file",
        exchange -> {
          byte[] body = "downloaded-content".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();

    File target = new File(tempDir, "out.bin");
    long written = SimpleHttpClient.downloadToFile(
        base() + "/file", null, 5000, 5000, target);

    assertEquals("downloaded-content".length(), written);
    assertEquals("downloaded-content",
        new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
  }

  /**
   * 核心断言：第一跳带 Authorization，重定向后的第二跳**不得**再带它。
   *
   * <p>这正是 GitHub artifact 下载的真实形态（api.github.com → 对象存储预签名 URL）。
   */
  @Test
  void stripsAuthorizationAfterRedirect(@TempDir File tempDir) throws Exception {
    AtomicReference<String> firstHopAuth = new AtomicReference<>();
    AtomicReference<String> secondHopAuth = new AtomicReference<>();

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api",
        exchange -> {
          firstHopAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.getResponseHeaders().add("Location", "/storage/signed");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/storage/signed",
        exchange -> {
          secondHopAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] body = "artifact-bytes".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", "Bearer ghp_secret");

    File target = new File(tempDir, "artifact.zip");
    SimpleHttpClient.downloadToFile(base() + "/api", headers, 5000, 5000, target);

    assertEquals("Bearer ghp_secret", firstHopAuth.get(), "第一跳必须携带 Authorization");
    assertEquals(null, secondHopAuth.get(),
        "重定向后必须剥离 Authorization（预签名 URL 带上会被拒绝）");
    assertEquals("artifact-bytes",
        new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
  }

  /** 非鉴权头（如 Accept）在重定向后应保留。 */
  @Test
  void keepsNonSensitiveHeadersAfterRedirect(@TempDir File tempDir) throws Exception {
    AtomicReference<String> secondHopAccept = new AtomicReference<>();

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "/next");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/next",
        exchange -> {
          secondHopAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Accept", "application/vnd.github+json");
    headers.put("Authorization", "Bearer x");

    SimpleHttpClient.downloadToFile(
        base() + "/api", headers, 5000, 5000, new File(tempDir, "out"));

    assertEquals("application/vnd.github+json", secondHopAccept.get(),
        "非敏感头应随重定向保留");
  }

  /** 下载同样受 UrlPolicy 保护：跳向不允许的主机必须被拒绝。 */
  @Test
  void rejectsRedirectToDisallowedHost(@TempDir File tempDir) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/start",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "http://evil.example.com/secret");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.start();

    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> SimpleHttpClient.downloadToFile(
            base() + "/start", null, 5000, 5000, new File(tempDir, "x")));
    assertTrue(error.getMessage().toLowerCase().contains("cleartext"),
        "必须是 UrlPolicy 拦截（而非 DNS 失败）：" + error.getMessage());
  }

  /** 非 2xx 响应必须抛错，且不得留下半截目标文件供调用方误用。 */
  @Test
  void throwsOnNonSuccessStatus(@TempDir File tempDir) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/missing",
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    server.start();

    Exception error = assertThrows(
        Exception.class,
        () -> SimpleHttpClient.downloadToFile(
            base() + "/missing", null, 5000, 5000, new File(tempDir, "x")));
    assertTrue(String.valueOf(error.getMessage()).contains("404"), error.getMessage());
  }
}
