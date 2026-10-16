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

package com.tom.rv2ide.ai.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 钉住 {@link SimpleHttpClient} 的请求/响应基础设施行为：POST/GET 的状态校验、
 * 响应头收集、下载（含进度与上限）、错误状态与响应体过大。
 */
final class SimpleHttpClientOperationsTest {

  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  private interface Handler {
    void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
  }

  private String serve(Handler handler) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> handler.handle(exchange));
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                              String contentType, byte[] body) throws IOException {
    if (contentType != null) {
      exchange.getResponseHeaders().add("Content-Type", contentType);
    }
    exchange.sendResponseHeaders(status, body.length);
    try (var out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  @Test
  void postJsonReturnsBodyAndSetsDefaultHeaders() throws Exception {
    final String[] seenContentType = new String[1];
    final String[] seenBody = new String[1];
    String base = serve(exchange -> {
      seenContentType[0] = exchange.getRequestHeaders().getFirst("Content-Type");
      seenBody[0] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      respond(exchange, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
    });
    String body = SimpleHttpClient.postJson(base + "/x", "{\"q\":1}", 5000, 5000);
    assertEquals("{\"ok\":true}", body);
    assertEquals("application/json", seenContentType[0]);
    assertEquals("{\"q\":1}", seenBody[0]);
  }

  @Test
  void postJsonThrowsOnNonSuccessStatus() throws Exception {
    String base = serve(exchange -> respond(exchange, 400, "application/json",
        "bad request".getBytes(StandardCharsets.UTF_8)));
    Exception e = assertThrows(Exception.class, () -> SimpleHttpClient.postJson(base + "/x", "{}", 5000, 5000));
    assertTrue(e.getMessage().contains("400"), e.getMessage());
  }

  @Test
  void postJsonAcceptsCustomHeaders() throws Exception {
    final String[] auth = new String[1];
    String base = serve(exchange -> {
      auth[0] = exchange.getRequestHeaders().getFirst("Authorization");
      respond(exchange, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
    });
    Map<String, String> headers = new HashMap<>();
    headers.put("Authorization", "Bearer tok");
    SimpleHttpClient.postJson(base + "/x", "{}", 5000, 5000, headers);
    assertEquals("Bearer tok", auth[0]);
  }

  @Test
  void getReturnsBody() throws Exception {
    String base = serve(exchange -> respond(exchange, 200, "application/json",
        "hello".getBytes(StandardCharsets.UTF_8)));
    assertEquals("hello", SimpleHttpClient.get(base + "/x", 5000, 5000));
    assertEquals("hello",
        SimpleHttpClient.get(base + "/x", 5000, 5000, new HashMap<String, String>()));
  }

  @Test
  void getThrowsOnErrorStatus() throws Exception {
    String base = serve(exchange -> respond(exchange, 503, null, "unavailable".getBytes(StandardCharsets.UTF_8)));
    Exception e = assertThrows(Exception.class, () -> SimpleHttpClient.get(base + "/x", 5000, 5000));
    assertTrue(e.getMessage().contains("503"), e.getMessage());
  }

  @Test
  void executeCollectsStatusMessageAndHeaders() throws Exception {
    String base = serve(exchange -> respond(exchange, 200, "text/plain; charset=utf-8",
        "body".getBytes(StandardCharsets.UTF_8)));
    SimpleHttpClient.Request request = new SimpleHttpClient.Request(base + "/x", "GET", null);
    SimpleHttpClient.Response response = SimpleHttpClient.execute(request);
    assertEquals(200, response.code);
    assertEquals("body", response.body);
    assertEquals("text/plain; charset=utf-8", response.contentType);
    // 响应头按原样收集（com.sun 服务器会把首字母规范化为 Content-type）
    assertTrue(response.headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("content-type")),
        "应收集到 Content-Type 响应头：" + response.headers);
  }

  @Test
  void executeReadsErrorStreamFor4xx() throws Exception {
    String base = serve(exchange -> respond(exchange, 404, "text/plain", "nope".getBytes(StandardCharsets.UTF_8)));
    SimpleHttpClient.Response response = SimpleHttpClient.execute(
        new SimpleHttpClient.Request(base + "/x", "GET", null));
    assertEquals(404, response.code);
    assertEquals("nope", response.body);
  }

  @Test
  void executeSendsByteBodyWhenProvided() throws Exception {
    final String[] seen = new String[1];
    String base = serve(exchange -> {
      seen[0] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      respond(exchange, 200, null, "ok".getBytes(StandardCharsets.UTF_8));
    });
    SimpleHttpClient.Request request = new SimpleHttpClient.Request(base + "/x", "POST", null);
    request.bodyBytes = "raw".getBytes(StandardCharsets.UTF_8);
    SimpleHttpClient.execute(request);
    assertEquals("raw", seen[0]);
  }

  @Test
  void downloadReturnsMimeTypeAndBytes() throws Exception {
    String base = serve(exchange -> respond(exchange, 200, "image/png; charset=x",
        new byte[] {1, 2, 3}));
    SimpleHttpClient.DownloadResult result = SimpleHttpClient.download(base + "/f", 5000, 5000);
    assertEquals("image/png", result.mimeType);
    assertArrayEquals(new byte[] {1, 2, 3}, result.bytes);
  }

  @Test
  void downloadDefaultsMimeTypeWhenHeaderMissing() throws Exception {
    String base = serve(exchange -> respond(exchange, 200, null, new byte[] {9}));
    assertEquals("application/octet-stream", SimpleHttpClient.download(base + "/f", 5000, 5000).mimeType);
  }

  @Test
  void downloadThrowsWhenResponseTooLargeForLimit() throws Exception {
    String base = serve(exchange -> respond(exchange, 200, "application/octet-stream",
        new byte[1024 * 1024]));
    Exception e = assertThrows(Exception.class,
        () -> SimpleHttpClient.download(base + "/f", 5000, 5000, 512 * 1024));
    assertTrue(e.getMessage().contains("too large"), e.getMessage());
  }

  @Test
  void downloadThrowsOnErrorStatus() throws Exception {
    String base = serve(exchange -> respond(exchange, 500, null, new byte[0]));
    Exception e = assertThrows(Exception.class, () -> SimpleHttpClient.download(base + "/f", 5000, 5000));
    assertTrue(e.getMessage().contains("download failed"), e.getMessage());
  }

  @Test
  void downloadWithProgressReportsReadAndTotal() throws Exception {
    byte[] payload = new byte[4096];
    String base = serve(exchange -> respond(exchange, 200, "application/octet-stream", payload));
    final long[] lastRead = {0};
    final long[] lastTotal = {0};
    SimpleHttpClient.DownloadResult result = SimpleHttpClient.downloadWithProgress(base + "/f", 5000, 5000,
        (read, total) -> {
          lastRead[0] = read;
          lastTotal[0] = total;
        });
    assertEquals(4096, result.bytes.length);
    assertEquals(4096, lastRead[0]);
    assertEquals(4096, lastTotal[0]);
  }

  @Test
  void downloadWithProgressWithoutListenerStillWorks() throws Exception {
    String base = serve(exchange -> respond(exchange, 200, "application/octet-stream", new byte[] {7, 7}));
    assertEquals(2, SimpleHttpClient.downloadWithProgress(base + "/f", 5000, 5000, null).bytes.length);
  }

  @Test
  void downloadToFileStreamsToDisk(@TempDir File tempDir) throws Exception {
    String base = serve(exchange -> respond(exchange, 200, "application/octet-stream",
        "file-content".getBytes(StandardCharsets.UTF_8)));
    File target = new File(tempDir, "out.bin");
    long written = SimpleHttpClient.downloadToFile(base + "/f", null, 5000, 5000, target);
    assertEquals("file-content".length(), written);
    assertEquals("file-content", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
  }

  @Test
  void downloadToFileFollowsRedirectAndDropsAuthorization(@TempDir File tempDir) throws Exception {
    final String[] secondAuth = {null};
    String base = serve(exchange -> {
      if (exchange.getRequestURI().getPath().equals("/first")) {
        exchange.getResponseHeaders().add("Location", "/second");
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
        return;
      }
      secondAuth[0] = exchange.getRequestHeaders().getFirst("Authorization");
      respond(exchange, 200, "application/octet-stream", "z".getBytes(StandardCharsets.UTF_8));
    });
    File target = new File(tempDir, "out.bin");
    Map<String, String> headers = new HashMap<>();
    headers.put("Authorization", "Bearer secret");
    assertEquals(1, SimpleHttpClient.downloadToFile(base + "/first", headers, 5000, 5000, target));
    assertEquals(null, secondAuth[0], "重定向后鉴权头必须被剥离");
  }

  @Test
  void downloadToFileThrowsOnErrorStatus(@TempDir File tempDir) throws Exception {
    String base = serve(exchange -> respond(exchange, 403, null, new byte[0]));
    Exception e = assertThrows(Exception.class, () -> SimpleHttpClient.downloadToFile(
        base + "/f", null, 5000, 5000, new File(tempDir, "x")));
    assertTrue(e.getMessage().contains("download failed"), e.getMessage());
  }

  @Test
  void readStreamReturnsEmptyForNull() throws Exception {
    assertEquals("", SimpleHttpClient.readStream(null));
  }

  @Test
  void readStreamDecodesUtf8AndRejectsOversize() throws Exception {
    assertEquals("中文", SimpleHttpClient.readStream(
        new ByteArrayInputStream("中文".getBytes(StandardCharsets.UTF_8))));

    InputStream huge = new InputStream() {
      private long count;

      @Override
      public int read() {
        return 0;
      }

      @Override
      public int read(byte[] buffer, int offset, int length) {
        if (count > SimpleHttpClient.MAX_RESPONSE_BODY_BYTES) {
          return -1;
        }
        count += length;
        return length;
      }
    };
    Exception e = assertThrows(Exception.class, () -> SimpleHttpClient.readStream(huge));
    assertTrue(e.getMessage().contains("too large"), e.getMessage());
  }

  @Test
  void readBytesHandlesNullAndLimit() throws Exception {
    assertArrayEquals(new byte[0], SimpleHttpClient.readBytes(null, 10));
    assertArrayEquals(new byte[] {1, 2}, SimpleHttpClient.readBytes(new ByteArrayInputStream(new byte[] {1, 2}), 10));
    Exception e = assertThrows(Exception.class, () -> SimpleHttpClient.readBytes(
        new ByteArrayInputStream(new byte[100]), 10));
    assertTrue(e.getMessage().contains("too large"), e.getMessage());
  }

  @Test
  void responseConstructorNormalizesNulls() {
    SimpleHttpClient.Response response = new SimpleHttpClient.Response(200, null, null, null, null);
    assertEquals("", response.message);
    assertEquals("", response.contentType);
    assertEquals("", response.body);
    assertTrue(response.headers.isEmpty());
    assertEquals(200, response.code);
  }

  @Test
  void downloadResultDefaultsMimeTypeAndBytes() {
    SimpleHttpClient.DownloadResult result = new SimpleHttpClient.DownloadResult(null, null);
    assertEquals("image/png", result.mimeType);
    assertArrayEquals(new byte[0], result.bytes);
    assertEquals("image/webp", new SimpleHttpClient.DownloadResult("image/webp", new byte[0]).mimeType);
  }

  @Test
  void requestDefaultsTimeouts() {
    SimpleHttpClient.Request request = new SimpleHttpClient.Request("u", "GET", null);
    assertEquals(15000, request.connectTimeoutMs);
    assertEquals(30000, request.readTimeoutMs);
    assertEquals("u", request.url);
    assertEquals("GET", request.method);
  }

  @Test
  void rejectsDisallowedSchemeUpFront() {
    Exception e = assertThrows(Exception.class,
        () -> SimpleHttpClient.get("ftp://example.com", 1000, 1000));
    assertTrue(e.getMessage().contains("must start with"), e.getMessage());
  }

  @Test
  void collectedHeadersAreSingleValued() {
    List<String> headers = new java.util.ArrayList<>();
    SimpleHttpClient.Response response = new SimpleHttpClient.Response(200, "ok", "t", "b",
        Map.of("X-One", "1"));
    headers.add(response.headers.get("X-One"));
    assertEquals("1", headers.get(0));
  }
}
