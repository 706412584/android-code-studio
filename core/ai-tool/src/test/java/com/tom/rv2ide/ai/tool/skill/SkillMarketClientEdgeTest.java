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

package com.tom.rv2ide.ai.tool.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SkillMarketClient} 的剩余分支：SkillHub 来源、空清单、目录创建失败、
 * 搜索降级，以及构造器的默认值。
 *
 * <p>这些路径此前没有任何用例：SkillHub 是第二个上游，它走的是不同的 URL 形态
 * （{@code /v1/skills/...}）；目录创建失败则决定「安装失败时会不会留残留」。
 */
final class SkillMarketClientEdgeTest {

  @TempDir Path tmp;

  private HttpServer server;
  private String base;
  private volatile String manifestBody;
  private final AtomicInteger hits = new AtomicInteger();

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          hits.incrementAndGet();
          String path = exchange.getRequestURI().getPath();
          try {
            // SkillHub 清单：/v1/skills/<slug>/files
            if (path.startsWith("/v1/skills/") && path.endsWith("/files")) {
              respond(exchange, manifestBody);
              return;
            }
            // ClawHub 清单：/skills/<slug>/versions/<version>
            if (path.startsWith("/skills/") && path.contains("/versions/")) {
              respond(exchange, manifestBody);
              return;
            }
            // 下载端点（两个上游的形态都接受）
            if (path.endsWith("/file")) {
              byte[] body = "content".getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(200, body.length);
              try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
              }
              return;
            }
            exchange.sendResponseHeaders(404, -1);
          } finally {
            exchange.close();
          }
        });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
      throws java.io.IOException {
    if (body == null) {
      exchange.sendResponseHeaders(500, -1);
      return;
    }
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private SkillMarketClient client() {
    return new SkillMarketClient(2_000, 2_000, base, base);
  }

  private static SkillMarketClient.MarketSkill skill(String source, String slug) {
    return new SkillMarketClient.MarketSkill(source, slug, "名称", "描述", "", 1);
  }

  // ---- 构造器 ----

  @Test
  void packageConstructorUsesProductionBases() {
    // 只验证能构造且不抛：基址是私有字段，生产常量不联网也不会被触碰。
    assertNotNull(new SkillMarketClient(1_000, 1_000));
    assertNotNull(new SkillMarketClient());
  }

  // ---- 搜索降级 ----

  @Test
  void searchReturnsClawhubResultsWhenSkillhubFails() {
    // 两个上游都指向同一台服务器；让它对搜索返回 500。
    manifestBody = null;
    List<SkillMarketClient.MarketSkill> results = client().search("kotlin", 5);
    assertNotNull(results);
    assertTrue(results.isEmpty(), "两个上游都失败时应为空列表而不是抛异常");
    assertTrue(hits.get() > 0, "确实尝试过请求");
  }

  // ---- 安装：清单与目录失败 ----

  @Test
  void emptyFileListIsRejected() {
    manifestBody = "{\"files\":[]}";
    SkillMarketClient.InstallResult result =
        client().install(skill(SkillMarketClient.SOURCE_CLAWHUB, "empty"), tmp.toFile());

    assertFalse(result.ok);
    assertTrue(result.message.contains("文件清单"), result.message);
  }

  @Test
  void uncreatableTempDirectoryIsReported(@TempDir Path dir) throws Exception {
    // targetRoot 是一个**文件**：临时目录 mkdirs 必然失败。
    File blocker = dir.resolve("blocker").toFile();
    Files.writeString(blocker.toPath(), "x");
    manifestBody =
        new JSONObject()
            .put(
                "files",
                new org.json.JSONArray()
                    .put(new JSONObject().put("path", "SKILL.md").put("size", 1)))
            .toString();

    SkillMarketClient.InstallResult result =
        client().install(skill(SkillMarketClient.SOURCE_CLAWHUB, "s"), blocker);

    assertFalse(result.ok);
    assertTrue(result.message.contains("临时下载目录"), result.message);
  }

  @Test
  void uncreatableSubdirectoryIsReported(@TempDir Path dir) throws Exception {
    // 先写 SKILL.md，再要求写 SKILL.md/child：父路径是文件，mkdirs 必失败。
    manifestBody =
        new JSONObject()
            .put(
                "files",
                new org.json.JSONArray()
                    .put(new JSONObject().put("path", "SKILL.md").put("size", 1))
                    .put(new JSONObject().put("path", "SKILL.md/child.txt").put("size", 1)))
            .toString();

    SkillMarketClient.InstallResult result =
        client().install(skill(SkillMarketClient.SOURCE_CLAWHUB, "nested"), dir.toFile());

    assertFalse(result.ok);
    assertTrue(result.message.contains("子目录"), result.message);
    // 失败路径必须清掉临时目录，不留残留。
    try (java.util.stream.Stream<Path> entries = Files.list(dir)) {
      assertTrue(
          entries.noneMatch(p -> p.getFileName().toString().startsWith(".market-install-")),
          "失败后不应残留临时目录");
    }
  }

  // ---- SkillHub 来源 ----

  @Test
  void skillhubSourceUsesV1FilesEndpointAndInstalls(@TempDir Path dir) throws Exception {
    manifestBody =
        new JSONObject()
            .put(
                "files",
                new org.json.JSONArray()
                    .put(new JSONObject().put("path", "SKILL.md").put("size", 7)))
            .toString();

    SkillMarketClient.InstallResult result =
        client().install(skill(SkillMarketClient.SOURCE_SKILLHUB, "hub-skill"), dir.toFile());

    assertTrue(result.ok, result.message);
    assertTrue(new File(result.dir, "SKILL.md").isFile(), "文件应落盘");
    assertEquals(
        "content",
        Files.readString(new File(result.dir, "SKILL.md").toPath(), StandardCharsets.UTF_8));
  }

  @Test
  void unknownSourceIsRejectedByListFiles(@TempDir Path dir) {
    SkillMarketClient.InstallResult result =
        client().install(skill("bogus", "x"), dir.toFile());

    assertFalse(result.ok);
    assertTrue(result.message.contains("未知来源"), result.message);
  }

  @Test
  void skillhubFilesWithoutArrayParsesToEmpty() {
    assertTrue(SkillMarketClient.parseSkillhubFiles("{}").isEmpty());
    assertTrue(SkillMarketClient.parseSkillhubFiles("{\"files\":[]}").isEmpty());
  }

  @Test
  void skillhubFilesPrefersFilesOverData() {
    List<SkillMarketClient.FileEntry> entries =
        SkillMarketClient.parseSkillhubFiles(
            "{\"files\":[{\"path\":\"a.md\",\"size\":3}],"
                + "\"data\":[{\"path\":\"ignored.md\"}]}");
    assertEquals(1, entries.size());
    assertEquals("a.md", entries.get(0).path);
  }

  @Test
  void skillhubFileFallsBackToNameField() {
    List<SkillMarketClient.FileEntry> entries =
        SkillMarketClient.parseSkillhubFiles("{\"data\":[{\"name\":\"b.md\",\"size\":9}]}");
    assertEquals(1, entries.size());
    assertEquals("b.md", entries.get(0).path);
    assertEquals(9, entries.get(0).size);
  }

  @Test
  void downloadFailureNamesTheFile(@TempDir Path dir) throws Exception {
    // 清单声明一个文件，但下载端点返回 404：错误必须指认是哪个文件。
    String manifest =
        new JSONObject()
            .put(
                "files",
                new org.json.JSONArray()
                    .put(new JSONObject().put("path", "SKILL.md").put("size", 1)))
            .toString();
    HttpServer failing = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    failing.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          try {
            if (path.endsWith("/versions/latest")) {
              respond(exchange, manifest);
            } else {
              exchange.sendResponseHeaders(404, -1);
            }
          } finally {
            exchange.close();
          }
        });
    failing.start();
    try {
      String failingBase = "http://127.0.0.1:" + failing.getAddress().getPort();
      SkillMarketClient.InstallResult result =
          new SkillMarketClient(2_000, 2_000, failingBase, failingBase)
              .install(skill(SkillMarketClient.SOURCE_CLAWHUB, "broken"), dir.toFile());

      assertFalse(result.ok);
      assertTrue(result.message.contains("SKILL.md"), result.message);
      assertTrue(result.message.contains("404"), result.message);
    } finally {
      failing.stop(0);
    }
  }
}
