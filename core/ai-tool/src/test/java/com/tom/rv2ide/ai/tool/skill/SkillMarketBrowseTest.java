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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link SkillMarketClient} 的列表/搜索（两个上游各一台本地服务器，不联网）。
 *
 * <p>关键契约是**降级行为**：一个上游挂掉时仍返回另一个的结果，只有两个都挂才带错误。
 * 之前这条只能靠「设备上 100% 丢包」实测，现在用注入基址把它钉进单测。
 */
final class SkillMarketBrowseTest {

  private final HttpServer clawhub;
  private final HttpServer skillhub;
  private final AtomicInteger clawhubHits = new AtomicInteger();
  private final AtomicInteger skillhubHits = new AtomicInteger();

  /** ClawHub 的响应体（null → 500）；SkillHub 同。 */
  private volatile String clawhubBody;
  private volatile String skillhubBody;

  SkillMarketBrowseTest() throws Exception {
    clawhub = startServer(clawhubHits, () -> clawhubBody);
    skillhub = startServer(skillhubHits, () -> skillhubBody);
  }

  private static HttpServer startServer(AtomicInteger hits, BodySupplier body) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      hits.incrementAndGet();
      String response = body.get();
      try {
        if (response == null) {
          exchange.sendResponseHeaders(500, -1);
          return;
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
          out.write(bytes);
        }
      } finally {
        exchange.close();
      }
    });
    server.start();
    return server;
  }

  @AfterEach
  void stopServers() {
    clawhub.stop(0);
    skillhub.stop(0);
  }

  private SkillMarketClient client() {
    return new SkillMarketClient(
        5_000,
        5_000,
        "http://127.0.0.1:" + clawhub.getAddress().getPort(),
        "http://127.0.0.1:" + skillhub.getAddress().getPort());
  }

  private static final String CLAWHUB_OK =
      "{\"skills\":[{\"slug\":\"pdf-tools\",\"name\":\"PDF\",\"downloads\":900}]}";
  private static final String SKILLHUB_OK =
      "{\"data\":[{\"slug\":\"png-tools\",\"name\":\"PNG\",\"installs\":500}]}";

  @Test
  void listDetailedMergesBothUpstreams() {
    clawhubBody = CLAWHUB_OK;
    skillhubBody = SKILLHUB_OK;

    SkillMarketClient.DetailedList result = client().listDetailed(20);

    assertEquals(2, result.skills.size());
    assertEquals("pdf-tools", result.skills.get(0).slug);
    assertEquals(SkillMarketClient.SOURCE_CLAWHUB, result.skills.get(0).source);
    assertEquals("png-tools", result.skills.get(1).slug);
    assertEquals(SkillMarketClient.SOURCE_SKILLHUB, result.skills.get(1).source);
    assertEquals("", result.error, "两个上游都正常时不应有错误");
  }

  @Test
  void oneUpstreamDownStillReturnsTheOtherWithoutError() {
    // 一个上游挂了不该清空整页，也不该报错——另一个的结果就是全部。
    clawhubBody = null; // 500
    skillhubBody = SKILLHUB_OK;

    SkillMarketClient.DetailedList result = client().listDetailed(20);

    assertEquals(1, result.skills.size());
    assertEquals("png-tools", result.skills.get(0).slug);
    assertEquals("", result.error, "单上游失败不报错");
  }

  @Test
  void bothUpstreamsDownReportsError() {
    // 两个都挂时错误必须有值——UI 靠它区分「市场是空的」与「拉不到」。
    clawhubBody = null;
    skillhubBody = null;

    SkillMarketClient.DetailedList result = client().listDetailed(20);

    assertTrue(result.skills.isEmpty());
    assertFalse(result.error.isEmpty(), "全部失败必须带错误原因");
    assertTrue(result.error.contains("ClawHub"), result.error);
    assertTrue(result.error.contains("SkillHub"), result.error);
  }

  @Test
  void upstreamUpButMalformedIsNotTreatedAsError() {
    // 上游返回 200 但形态不符 → 空列表 + 无错误（与「拉不到」区分开）。
    clawhubBody = "{\"unexpected\":true}";
    skillhubBody = "not json";

    SkillMarketClient.DetailedList result = client().listDetailed(20);

    assertTrue(result.skills.isEmpty());
    assertEquals("", result.error, "HTTP 成功但解析为空不算上游失败");
  }

  @Test
  void listDelegatesToDetailed() {
    clawhubBody = CLAWHUB_OK;
    skillhubBody = null;

    List<SkillMarketClient.MarketSkill> skills = client().list(10);

    assertEquals(1, skills.size());
    assertEquals("pdf-tools", skills.get(0).slug);
  }

  @Test
  void searchQueriesBothUpstreamsAndMerges() {
    clawhubBody = CLAWHUB_OK;
    skillhubBody = SKILLHUB_OK;

    List<SkillMarketClient.MarketSkill> results = client().search("tools", 10);

    assertEquals(2, results.size(), "两个上游的搜索结果都应返回");
    assertEquals(1, clawhubHits.get(), "ClawHub 应被查询一次");
    assertEquals(1, skillhubHits.get(), "SkillHub 应被查询一次");
  }

  @Test
  void searchUpstreamFailureYieldsEmptyFromThatUpstream() {
    // search 没有 Detailed 变体，单个上游失败静默为空——钉住现状，
    // 若未来加错误回报，这个测试会提醒改调用方。
    clawhubBody = null;
    skillhubBody = SKILLHUB_OK;

    List<SkillMarketClient.MarketSkill> results = client().search("tools", 10);

    assertEquals(1, results.size());
    assertEquals("png-tools", results.get(0).slug);
  }

  private interface BodySupplier {
    String get();
  }
}
