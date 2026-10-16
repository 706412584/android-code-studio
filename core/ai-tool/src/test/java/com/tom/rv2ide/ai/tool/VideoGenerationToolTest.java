/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
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

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

/**
 * {@link VideoGenerationTool} 的协议行为（Agnes / Grok 两路 + 失败态）。
 *
 * <p>轮询间隔 3s 会让测试变慢——测试里任务第一次轮询即完成，实际只睡一次 3s，
 * 全类可接受。
 */
class VideoGenerationToolTest {

  /** 按调用次序回放 canned 响应的假端口；记录每次调用的快照。 */
  private static final class ScriptedPort implements HttpRequestPort {
    final Deque<HttpRequestPort.Response> responses = new ArrayDeque<>();
    final java.util.List<String[]> calls = new java.util.ArrayList<>();

    ScriptedPort(HttpRequestPort.Response... canned) {
      for (HttpRequestPort.Response r : canned) {
        responses.add(r);
      }
    }

    @Override
    public HttpRequestPort.Response request(
        String method, String url, Map<String, String> headers, String body, int timeoutMs) {
      calls.add(new String[] {method, url, body == null ? "" : body});
      return responses.removeFirst();
    }

    String[] first() {
      return calls.get(0);
    }

    String[] last() {
      return calls.get(calls.size() - 1);
    }
  }

  private static HttpRequestPort.Response ok(String body) {
    return new HttpRequestPort.Response(200, "OK", "application/json", new LinkedHashMap<>(), body);
  }

  private ToolContext agnesContext() {
    return ToolContext.builder()
        .homePath("/tmp")
        .videoGeneration(
            new VideoGenerationEndpoint("https://host/v1", "sk-test", "agnes-video-2.5-flash", ""))
        .build();
  }

  private ToolContext grokContext() {
    return ToolContext.builder()
        .homePath("/tmp")
        .videoGeneration(
            new VideoGenerationEndpoint("https://host/v1", "sk-test", "grok-imagine-video", ""))
        .build();
  }

  @Test
  public void agnesFlowPostsToVideosAndPollsAgnesApi() throws Exception {
    ScriptedPort port =
        new ScriptedPort(
            ok(new JSONObject().put("video_id", "v123").toString()),
            ok(new JSONObject().put("status", "processing").toString()),
            ok(new JSONObject().put("status", "completed").put("url", "https://cdn/v.mp4").toString()));
    VideoGenerationTool tool = new VideoGenerationTool(port);

    ToolResult result = tool.execute(new JSONObject().put("prompt", "a cat runs"), agnesContext());

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("v.mp4"));
    // 第一次调用 = POST 提交到 {base}/videos。
    assertEquals("POST", port.first()[0]);
    assertEquals("https://host/v1/videos", port.first()[1]);
    // 最后一次调用 = 轮询 GET，URL 剥掉 /v1 拼 /agnesapi 且必须带 model_name。
    assertEquals("GET", port.last()[0]);
    assertEquals(
        "https://host/agnesapi?video_id=v123&model_name=agnes-video-2.5-flash", port.last()[1]);
    JSONObject sent = new JSONObject(port.first()[2]);
    assertEquals("agnes-video-2.5-flash", sent.getString("model"));
    // Agnes 语义（FrameBaker 实测）：mode 必发、seconds 字符串、size 固定。
    assertEquals("text", sent.getString("mode"));
    assertEquals("5", sent.getString("seconds"));
    assertEquals("720P", sent.getString("size"));
  }

  @Test
  public void grokModelRoutesToGrokProtocol() throws Exception {
    ScriptedPort port =
        new ScriptedPort(
            ok(new JSONObject().put("request_id", "r1").toString()),
            ok(
                new JSONObject()
                    .put("status", "done")
                    .put("video", new JSONObject().put("url", "https://cdn/g.mp4"))
                    .toString()));
    VideoGenerationTool tool = new VideoGenerationTool(port);

    ToolResult result =
        tool.execute(
            new JSONObject().put("prompt", "ocean").put("duration", 5).put("resolution", "720p"),
            grokContext());

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("g.mp4"));
    // 第一次调用 = POST /videos/generations；最后一次 = GET /videos/r1。
    assertEquals("POST", port.first()[0]);
    assertEquals("https://host/v1/videos/generations", port.first()[1]);
    assertEquals("GET", port.last()[0]);
    assertTrue(port.last()[1].endsWith("/videos/r1"));
    JSONObject sent = new JSONObject(port.first()[2]);
    assertEquals(5, sent.getInt("duration"));
    assertEquals("720p", sent.getString("resolution"));
  }

  @Test
  public void agnesFailureStateIsReported() throws Exception {
    ScriptedPort port =
        new ScriptedPort(
            ok(new JSONObject().put("id", "t1").toString()),
            ok(new JSONObject().put("status", "rejected").put("message", "unsafe").toString()));
    VideoGenerationTool tool = new VideoGenerationTool(port);

    ToolResult result = tool.execute(new JSONObject().put("prompt", "x"), agnesContext());

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("rejected"));
    assertTrue(result.getContent().contains("unsafe"));
  }

  @Test
  public void grokExpiredIsReported() throws Exception {
    ScriptedPort port =
        new ScriptedPort(
            ok(new JSONObject().put("request_id", "r2").toString()),
            ok(new JSONObject().put("status", "expired").toString()));
    VideoGenerationTool tool = new VideoGenerationTool(port);

    ToolResult result = tool.execute(new JSONObject().put("prompt", "x"), grokContext());

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("expired"));
  }

  @Test
  public void unconfiguredExplains() {
    ScriptedPort port = new ScriptedPort(ok("{}"));
    VideoGenerationTool tool = new VideoGenerationTool(port);
    ToolContext bare = ToolContext.builder().homePath("/tmp").build();

    ToolResult result = tool.execute(new JSONObject().put("prompt", "x"), bare);

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("未配置"));
  }

  @Test
  public void endpointProtocolRouting() {
    VideoGenerationEndpoint grok =
        new VideoGenerationEndpoint("https://host/v1/", "k", "Grok-Imagine-Video-Fast", "");
    assertTrue(grok.isGrokProtocol());
    assertEquals("https://host/v1/videos/generations", grok.grokCreateUrl());
    assertEquals("https://host/v1/videos/r9", grok.grokPollUrl("r9"));

    VideoGenerationEndpoint agnes =
        new VideoGenerationEndpoint("https://host/v1", "k", "agnes-video-2.5", "");
    assertFalse(agnes.isGrokProtocol());
    assertEquals("https://host/v1/videos", agnes.agnesCreateUrl());
    assertEquals(
        "https://host/agnesapi?video_id=v1&model_name=agnes-video-2.5",
        agnes.agnesPollUrl("v1", "agnes-video-2.5"));
  }

  // ---- 失败分支（此前未覆盖）----

  private static HttpRequestPort.Response err(int code, String body) {
    return new HttpRequestPort.Response(code, "ERR", "application/json", new LinkedHashMap<>(), body);
  }

  @Test
  public void agnesCreateHttpErrorIsReported() throws Exception {
    // 提交端点 4xx：错误应带状态码，而不是一路等到轮询超时。
    ScriptedPort port = new ScriptedPort(err(401, "{\"message\":\"invalid key\"}"));
    ToolResult result =
        new VideoGenerationTool(port).execute(new JSONObject().put("prompt", "x"), agnesContext());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("401"), result.getContent());
  }

  @Test
  public void agnesMissingVideoIdIsReported() throws Exception {
    ScriptedPort port = new ScriptedPort(ok("{}")); // 既无 video_id 也无 id/task_id
    ToolResult result =
        new VideoGenerationTool(port).execute(new JSONObject().put("prompt", "x"), agnesContext());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("video_id"), result.getContent());
  }

  @Test
  public void agnesMetadataUrlIsAccepted() throws Exception {
    // 结果 URL 可能在 metadata.url（FrameBaker 两种形态都见过）。
    ScriptedPort port =
        new ScriptedPort(
            ok(new JSONObject().put("video_id", "v9").toString()),
            ok(new JSONObject()
                .put("status", "completed")
                .put("metadata", new JSONObject().put("url", "https://cdn/meta.mp4"))
                .toString()));
    ToolResult result =
        new VideoGenerationTool(port).execute(new JSONObject().put("prompt", "x"), agnesContext());
    // 下载会失败（cdn 不可达）——但错误文案里应带出 URL，证明 metadata 被解析。
    assertTrue(result.getContent().contains("meta.mp4"), result.getContent());
  }

  @Test
  public void agnesCompletedWithoutUrlIsReported() throws Exception {
    ScriptedPort port =
        new ScriptedPort(
            ok(new JSONObject().put("video_id", "v1").toString()),
            ok(new JSONObject().put("status", "completed").toString()));
    ToolResult result =
        new VideoGenerationTool(port).execute(new JSONObject().put("prompt", "x"), agnesContext());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("未返回 URL"), result.getContent());
  }

  @Test
  public void agnesEmptyPromptIsRejected() {
    ScriptedPort port = new ScriptedPort(ok("{}"));
    ToolResult result =
        new VideoGenerationTool(port).execute(new JSONObject().put("prompt", "   "), agnesContext());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("prompt"), result.getContent());
  }

  @Test
  public void grokMissingRequestIdIsReported() throws Exception {
    ScriptedPort port = new ScriptedPort(ok("{}"));
    ToolResult result =
        new VideoGenerationTool(port).execute(new JSONObject().put("prompt", "x"), grokContext());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("request_id"), result.getContent());
  }

  @Test
  public void grokDoneWithoutVideoUrlIsReported() throws Exception {
    ScriptedPort port =
        new ScriptedPort(
            ok(new JSONObject().put("request_id", "r1").toString()),
            ok(new JSONObject().put("status", "done").toString()));
    ToolResult result =
        new VideoGenerationTool(port).execute(new JSONObject().put("prompt", "x"), grokContext());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("video.url"), result.getContent());
  }

  @Test
  public void grokFailedStateIsReported() throws Exception {
    ScriptedPort port =
        new ScriptedPort(
            ok(new JSONObject().put("request_id", "r1").toString()),
            ok(new JSONObject().put("status", "failed").put("error", "moderation").toString()));
    ToolResult result =
        new VideoGenerationTool(port).execute(new JSONObject().put("prompt", "x"), grokContext());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("moderation"), result.getContent());
  }

  @Test
  public void nullHttpPortExplains() {
    ToolResult result =
        new VideoGenerationTool(null).execute(new JSONObject().put("prompt", "x"), agnesContext());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("网络访问"), result.getContent());
  }

  @Test
  public void downloadFailureReturnsUrlForManualRetry(@TempDir Path dir) throws Exception {
    // 任务成功但下载失败：结果应仍为 ok 并带出 URL 与任务 ID（模型可告知用户稍后重试）。
    ScriptedPort port =
        new ScriptedPort(
            ok(new JSONObject().put("video_id", "v1").toString()),
            ok(new JSONObject()
                .put("status", "completed")
                .put("url", "https://cdn.invalid.example/never.mp4")
                .toString()));
    ToolContext ctx =
        ToolContext.builder()
            .homePath(dir.toString())
            .videoGeneration(
                new VideoGenerationEndpoint("https://host/v1", "k", "agnes-video-2.5", ""))
            .build();
    ToolResult result =
        new VideoGenerationTool(port).execute(new JSONObject().put("prompt", "x"), ctx);
    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("保存失败"), result.getContent());
    assertTrue(result.getContent().contains("never.mp4"), result.getContent());
    assertTrue(result.getContent().contains("v1"), result.getContent());
  }

  @Test
  public void displayCategoryAndConcurrency() {
    VideoGenerationTool tool = new VideoGenerationTool(new ScriptedPort(ok("{}")));
    assertEquals(
        com.tom.rv2ide.ai.tool.api.ToolDisplayCategory.IMAGE_GENERATION,
        tool.getDisplayCategory());
    assertFalse(tool.isConcurrencySafe(), "轮询占线程，不应并发");
  }

  @Test
  public void successfulDownloadWritesFileAndReportsSize(@TempDir Path dir) throws Exception {
    // 起本地 HTTP 服务器提供 mp4 字节：验证「下载成功」路径真正落盘并回报大小。
    // 既有的 agnes/grok 用例用的是不可达的 https://cdn，走的是下载失败分支——
    // 成功分支此前完全没覆盖。
    byte[] mp4 = new byte[4096];
    java.util.Arrays.fill(mp4, (byte) 0x42);
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v.mp4",
        exchange -> {
          exchange.sendResponseHeaders(200, mp4.length);
          try (java.io.OutputStream out = exchange.getResponseBody()) {
            out.write(mp4);
          }
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v.mp4";
      ScriptedPort port =
          new ScriptedPort(
              ok(new JSONObject().put("video_id", "v9").toString()),
              ok(
                  new JSONObject()
                      .put("status", "completed")
                      .put("url", url)
                      .toString()));
      VideoGenerationTool tool = new VideoGenerationTool(port);
      ToolContext ctx =
          ToolContext.builder()
              .homePath(dir.toString())
              .videoGeneration(
                  new VideoGenerationEndpoint(
                      "https://host/v1", "sk-test", "agnes-video-2.5-flash", ""))
              .build();

      ToolResult result =
          tool.execute(new JSONObject().put("prompt", "a cat runs"), ctx);

      assertFalse(result.isError(), result.getContent());
      assertTrue(result.getContent().contains("视频生成成功"), result.getContent());
      assertTrue(result.getContent().contains("文件路径"), result.getContent());
      // 落盘位置：homePath/videos/ 下的 mp4，且内容与服务器一致。
      java.io.File saved = new java.io.File(dir.toFile(), "ai-generated");
      java.io.File[] files = saved.listFiles();
      assertNotNull(files);
      assertEquals(1, files.length, "应恰好落盘一个视频文件");
      assertTrue(files[0].getName().endsWith(".mp4"), files[0].getName());
      assertEquals(4096, files[0].length(), "落盘内容长度应与服务器一致");
    } finally {
      server.stop(0);
    }
  }
}
