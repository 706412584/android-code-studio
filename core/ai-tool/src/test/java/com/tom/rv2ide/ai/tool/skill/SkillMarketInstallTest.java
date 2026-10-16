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
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SkillMarketClient#install} 的完整安装流程（本地 HTTP 服务器回放，不联网）。
 *
 * <p>安装是这个类里唯一「多步、有副作用、失败要回滚」的路径：清单校验 → 下载到临时目录
 * → 逐文件 sha256 校验 → 原子改名落盘 → 写市场标记。每一步失败都必须**不留残留**，
 * 且错误文案要能指认原因（哪个文件、哪一步）。这里用注入的本地基址把每条分支跑到。
 */
final class SkillMarketInstallTest {

  @TempDir Path tmp;

  private HttpServer server;
  private String base;

  /** 服务器上可用的文件路径 → 内容；清单与下载都从这里取。 */
  private final Map<String, byte[]> files = new HashMap<>();

  /** 清单响应体（null 表示返回 404，模拟清单接口挂掉）。 */
  private String manifestBody;

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      String query = exchange.getRequestURI().getQuery();
      try {
        // 清单端点：/skills/<slug>/versions/<version>
        if (path.startsWith("/skills/") && path.contains("/versions/")) {
          if (manifestBody == null) {
            exchange.sendResponseHeaders(404, -1);
            return;
          }
          byte[] body = manifestBody.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
          return;
        }
        // 文件下载端点：/skills/<slug>/file?path=<path>
        if (path.endsWith("/file") && query != null && query.startsWith("path=")) {
          String requested =
              java.net.URLDecoder.decode(query.substring("path=".length()), "UTF-8");
          byte[] content = files.get(requested);
          if (content == null) {
            exchange.sendResponseHeaders(404, -1);
            return;
          }
          exchange.sendResponseHeaders(200, content.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(content);
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

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  /** 注入本地基址的客户端（两个上游都指向同一台本地服务器）。 */
  private SkillMarketClient client() {
    return new SkillMarketClient(5_000, 5_000, base, base);
  }

  private static SkillMarketClient.MarketSkill skill(String slug, String version) {
    return new SkillMarketClient.MarketSkill(
        SkillMarketClient.SOURCE_CLAWHUB, slug, "名称", "描述", version, 1);
  }

  /** 构造一份合法清单：SKILL.md + 一个子目录文件，sha256 与内容匹配。 */
  private String validManifest() {
    byte[] skillMd = "# 技能说明\n".getBytes(StandardCharsets.UTF_8);
    byte[] extra = "extra content".getBytes(StandardCharsets.UTF_8);
    files.put("SKILL.md", skillMd);
    files.put("docs/guide.md", extra);
    return new JSONObject()
        .put(
            "files",
            new org.json.JSONArray()
                .put(
                    new JSONObject()
                        .put("path", "SKILL.md")
                        .put("size", skillMd.length)
                        .put("sha256", SkillMarketClient.sha256Hex(skillMd)))
                .put(
                    new JSONObject()
                        .put("path", "docs/guide.md")
                        .put("size", extra.length)
                        .put("sha256", SkillMarketClient.sha256Hex(extra))))
        .toString();
  }

  // ---- 成功路径 ----

  @Test
  void installsAllFilesAndWritesMarketMarker() throws Exception {
    manifestBody = validManifest();
    File root = tmp.resolve("skills").toFile();
    assertTrue(root.mkdirs());

    SkillMarketClient.InstallResult result =
        client().install(skill("pdf-tools", "1.0.0"), root);

    assertTrue(result.ok, result.message);
    assertNotNull(result.dir);
    assertTrue(new File(result.dir, "SKILL.md").isFile(), "SKILL.md 应落盘");
    assertEquals(
        "# 技能说明\n",
        Files.readString(new File(result.dir, "SKILL.md").toPath(), StandardCharsets.UTF_8));
    assertTrue(new File(result.dir, "docs/guide.md").isFile(), "子目录文件应落盘");
    // 市场标记：卸载/更新判定全靠它。
    assertTrue(SkillMarketClient.isMarketInstalled(result.dir));
    JSONObject meta =
        new JSONObject(
            Files.readString(
                new File(result.dir, ".market-meta.json").toPath(), StandardCharsets.UTF_8));
    assertEquals("clawhub", meta.getString("source"));
    assertEquals("pdf-tools", meta.getString("slug"));
    assertEquals("1.0.0", meta.getString("version"));
    // 目录名是 sanitize 后的 slug。
    assertEquals("pdf-tools", result.dir.getName());
    // 临时目录不应残留。
    for (File f : root.listFiles()) {
      assertFalse(f.getName().startsWith(".market-install-"), "临时目录应已清理：" + f.getName());
    }
  }

  // ---- 失败路径：校验前置 ----

  @Test
  void rejectsWhenManifestMissingSkillMd() throws Exception {
    // 清单里没有 SKILL.md——不是一个技能包。
    byte[] extra = "x".getBytes(StandardCharsets.UTF_8);
    files.put("readme.txt", extra);
    manifestBody = new JSONObject()
        .put("files", new org.json.JSONArray()
            .put(new JSONObject().put("path", "readme.txt")
                .put("size", extra.length)
                .put("sha256", SkillMarketClient.sha256Hex(extra))))
        .toString();
    File root = tmp.resolve("skills").toFile();
    assertTrue(root.mkdirs());

    SkillMarketClient.InstallResult result = client().install(skill("s", "1"), root);

    assertFalse(result.ok);
    assertTrue(result.message.contains("SKILL.md"), result.message);
    assertNoResidue(root);
  }

  @Test
  void rejectsWhenManifestUnavailable() throws Exception {
    manifestBody = null; // 清单端点 404
    File root = tmp.resolve("skills").toFile();
    assertTrue(root.mkdirs());

    SkillMarketClient.InstallResult result = client().install(skill("s", "1"), root);

    assertFalse(result.ok);
    assertTrue(result.message.contains("安装失败"), result.message);
    assertNoResidue(root);
  }

  @Test
  void rejectsWhenTargetAlreadyExists() throws Exception {
    manifestBody = validManifest();
    File root = tmp.resolve("skills").toFile();
    assertTrue(root.mkdirs());
    // 同名目录已存在 → 409 语义，要求先卸载。
    assertTrue(new File(root, "pdf-tools").mkdirs());

    SkillMarketClient.InstallResult result =
        client().install(skill("pdf-tools", "1.0.0"), root);

    assertFalse(result.ok);
    assertTrue(result.message.contains("已存在"), result.message);
  }

  // ---- 失败路径：下载与校验 ----

  @Test
  void rejectsWhenFileDownloadFailsAndNamesTheFile() throws Exception {
    // 清单列了文件但下载 404——错误必须指认是哪个文件，否则用户无从排查。
    byte[] skillMd = "# x".getBytes(StandardCharsets.UTF_8);
    files.put("SKILL.md", skillMd);
    manifestBody = new JSONObject()
        .put("files", new org.json.JSONArray()
            .put(new JSONObject().put("path", "SKILL.md")
                .put("size", skillMd.length)
                .put("sha256", SkillMarketClient.sha256Hex(skillMd)))
            .put(new JSONObject().put("path", "missing.md")
                .put("size", 1)
                .put("sha256", "aa")))
        .toString();
    File root = tmp.resolve("skills").toFile();
    assertTrue(root.mkdirs());

    SkillMarketClient.InstallResult result = client().install(skill("s", "1"), root);

    assertFalse(result.ok);
    assertTrue(result.message.contains("missing.md"), "错误应指认失败的文件：" + result.message);
    assertNoResidue(root);
  }

  @Test
  void rejectsOnSha256Mismatch() throws Exception {
    byte[] skillMd = "# x".getBytes(StandardCharsets.UTF_8);
    files.put("SKILL.md", skillMd);
    manifestBody = new JSONObject()
        .put("files", new org.json.JSONArray()
            .put(new JSONObject().put("path", "SKILL.md")
                .put("size", skillMd.length)
                .put("sha256", "deadbeef"))) // 与内容不符
        .toString();
    File root = tmp.resolve("skills").toFile();
    assertTrue(root.mkdirs());

    SkillMarketClient.InstallResult result = client().install(skill("s", "1"), root);

    assertFalse(result.ok);
    assertTrue(result.message.contains("sha256"), result.message);
    assertNoResidue(root);
  }

  @Test
  void rejectsOversizedSingleFileBeforeDownload() throws Exception {
    // 单文件声明大小超过 5MB 上限——应在下载前拦下（不浪费流量）。
    byte[] skillMd = "# x".getBytes(StandardCharsets.UTF_8);
    files.put("SKILL.md", skillMd);
    manifestBody = new JSONObject()
        .put("files", new org.json.JSONArray()
            .put(new JSONObject().put("path", "SKILL.md")
                .put("size", skillMd.length)
                .put("sha256", SkillMarketClient.sha256Hex(skillMd)))
            .put(new JSONObject().put("path", "huge.bin")
                .put("size", 6 * 1024 * 1024L)
                .put("sha256", "aa")))
        .toString();
    File root = tmp.resolve("skills").toFile();
    assertTrue(root.mkdirs());

    SkillMarketClient.InstallResult result = client().install(skill("s", "1"), root);

    assertFalse(result.ok);
    assertTrue(result.message.contains("5MB"), result.message);
    assertTrue(result.message.contains("huge.bin"), result.message);
    assertNoResidue(root);
  }

  @Test
  void rejectsTooManyFiles() throws Exception {
    byte[] skillMd = "# x".getBytes(StandardCharsets.UTF_8);
    files.put("SKILL.md", skillMd);
    org.json.JSONArray array = new org.json.JSONArray();
    array.put(new JSONObject().put("path", "SKILL.md")
        .put("size", skillMd.length)
        .put("sha256", SkillMarketClient.sha256Hex(skillMd)));
    for (int i = 0; i < SkillMarketClient.MAX_FILES + 1; i++) {
      array.put(new JSONObject().put("path", "f" + i + ".txt").put("size", 1).put("sha256", "aa"));
    }
    manifestBody = new JSONObject().put("files", array).toString();
    File root = tmp.resolve("skills").toFile();
    assertTrue(root.mkdirs());

    SkillMarketClient.InstallResult result = client().install(skill("s", "1"), root);

    assertFalse(result.ok);
    assertTrue(result.message.contains("文件数超过上限"), result.message);
    assertNoResidue(root);
  }

  @Test
  void rejectsOversizedTotalBytes() throws Exception {
    // 每文件都合法（<5MB）但总数超过 20MB 上限。
    byte[] skillMd = "# x".getBytes(StandardCharsets.UTF_8);
    files.put("SKILL.md", skillMd);
    org.json.JSONArray array = new org.json.JSONArray();
    array.put(new JSONObject().put("path", "SKILL.md")
        .put("size", skillMd.length)
        .put("sha256", SkillMarketClient.sha256Hex(skillMd)));
    for (int i = 0; i < 5; i++) {
      array.put(new JSONObject().put("path", "big" + i + ".bin")
          .put("size", 4 * 1024 * 1024L).put("sha256", "aa"));
    }
    manifestBody = new JSONObject().put("files", array).toString();
    File root = tmp.resolve("skills").toFile();
    assertTrue(root.mkdirs());

    SkillMarketClient.InstallResult result = client().install(skill("s", "1"), root);

    assertFalse(result.ok);
    assertTrue(result.message.contains("20MB"), result.message);
    assertNoResidue(root);
  }

  @Test
  void sanitizesSlugForDirectoryName() throws Exception {
    manifestBody = validManifest();
    File root = tmp.resolve("skills").toFile();
    assertTrue(root.mkdirs());

    SkillMarketClient.InstallResult result =
        client().install(skill("My Skill/../v2", "1"), root);

    assertTrue(result.ok, result.message);
    // 路径穿越字符被 sanitize——目录必须留在 root 之内。
    assertEquals(root.getCanonicalFile(), result.dir.getCanonicalFile().getParentFile());
  }

  /** 断言 root 下没有临时目录残留。 */
  private static void assertNoResidue(File root) {
    File[] children = root.listFiles();
    if (children == null) {
      return;
    }
    for (File f : children) {
      assertFalse(
          f.getName().startsWith(".market-install-"),
          "失败后不应残留临时目录：" + f.getName());
    }
  }
}
