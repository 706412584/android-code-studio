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

  // ---- 轮询 / 参数 / 落盘（此前未覆盖）----

  @Test
  public void agnesForwardsOptionalAspectRatioAndWhitelistedFields() throws Exception {
    // 可选字段必须原样透传给上游；漏掉 aspect_ratio 会让服务端按默认比例出片。
    ScriptedPort port =
        new ScriptedPort(
            ok(new JSONObject().put("video_id", "v1").toString()),
            ok(new JSONObject().put("status", "completed").put("url", "https://cdn/x.mp4").toString()));
    ToolContext ctx =
        ToolContext.builder()
            .homePath("/tmp")
            .videoGeneration(
                new VideoGenerationEndpoint("https://host/v1", "k", "agnes-video-2.5", ""))
            .build();
    ToolResult result =
        new VideoGenerationTool(port)
            .execute(
                new JSONObject()
                    .put("prompt", "x")
                    .put("aspect_ratio", "9:16")
                    .put("seed", 42)
                    .put("negative_prompt", "blurry"),
                ctx);

    JSONObject sent = new JSONObject(port.first()[2]);
    assertEquals("9:16", sent.getString("aspect_ratio"));
    assertEquals(42, sent.getInt("seed"));
    assertEquals("blurry", sent.getString("negative_prompt"));
    // 下载失败是预期（cdn 不可达），但参数已发出。
    assertTrue(result.getContent().contains("保存失败"), result.getContent());
  }

  @Test
  public void grokForwardsAspectRatioAndImageUrl() throws Exception {
    ScriptedPort port =
        new ScriptedPort(
            ok(new JSONObject().put("request_id", "r1").toString()),
            ok(new JSONObject()
                .put("status", "done")
                .put("video", new JSONObject().put("url", "https://cdn/g.mp4"))
                .toString()));
    ToolResult result =
        new VideoGenerationTool(port)
            .execute(
                new JSONObject()
                    .put("prompt", "x")
                    .put("aspect_ratio", "1:1")
                    .put("image_url", "https://cdn/in.png"),
                grokContext());

    JSONObject sent = new JSONObject(port.first()[2]);
    assertEquals("1:1", sent.getString("aspect_ratio"));
    assertEquals("https://cdn/in.png", sent.getString("image_url"));
    assertTrue(result.getContent().contains("保存失败"), result.getContent());
  }

  @Test
  public void agnesGivesUpAfterConsecutiveTransientFailures() throws Exception {
    // 连续 4 次瞬时失败（> MAX_TRANSIENT_FAILURES）必须放弃并报出任务 ID，
    // 而不是一直退避到超时。退避总计 3+6+9 秒，本类是唯一慢用例。
    final java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    HttpRequestPort port =
        (method, url, headers, body, timeoutMs) -> {
          if (calls.getAndIncrement() == 0) {
            return ok(new JSONObject().put("video_id", "v1").toString());
          }
          throw new java.io.IOException("network down");
        };
    ToolResult result =
        new VideoGenerationTool(port)
            .execute(new JSONObject().put("prompt", "x"), agnesContext());

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("连续失败"), result.getContent());
    assertTrue(result.getContent().contains("v1"), result.getContent());
    assertTrue(result.getContent().contains("network down"), result.getContent());
  }

  @Test
  public void agnesPollTimeoutIsReportedWithLastState() throws Exception {
    // 轮询到超时仍未完成：错误里要带任务 ID 与最后状态，便于用户去后台查。
    ScriptedPort port =
        new ScriptedPort(
            ok(new JSONObject().put("video_id", "v9").toString()),
            ok(new JSONObject().put("status", "processing").toString()));
    ToolResult result =
        new VideoGenerationTool(port)
            .execute(
                new JSONObject().put("prompt", "x").put("timeout_seconds", 1), agnesContext());

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("超时"), result.getContent());
    assertTrue(result.getContent().contains("processing"), result.getContent());
  }

  @Test
  public void grokPollTimeoutIsReportedWithLastState() throws Exception {
    ScriptedPort port =
        new ScriptedPort(
            ok(new JSONObject().put("request_id", "r9").toString()),
            ok(new JSONObject().put("status", "processing").toString()));
    ToolResult result =
        new VideoGenerationTool(port)
            .execute(
                new JSONObject().put("prompt", "x").put("timeout_seconds", 1), grokContext());

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("超时"), result.getContent());
    assertTrue(result.getContent().contains("processing"), result.getContent());
  }

  @Test
  public void pollHttpErrorIsReportedWithStatusCodeAndBody() throws Exception {
    // 轮询端返回 5xx：错误里要带状态码与被截断的正文，否则只能看到「超时」。
    StringBuilder longBody = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      longBody.append("detail-");
    }
    ScriptedPort port =
        new ScriptedPort(
            ok(new JSONObject().put("video_id", "v1").toString()),
            err(500, longBody.toString()));
    ToolResult result =
        new VideoGenerationTool(port).execute(new JSONObject().put("prompt", "x"), agnesContext());

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("500"), result.getContent());
    assertTrue(result.getContent().contains("…"), "超长正文应被截断：" + result.getContent());
  }

  @Test
  public void exceptionWithoutMessageFallsBackToClassName() throws Exception {
    // 有些异常没有 message。错误里至少要有类型名，不能出现「失败：」后面空无一物。
    HttpRequestPort port =
        (method, url, headers, body, timeoutMs) -> {
          throw new RuntimeException();
        };
    ToolResult result =
        new VideoGenerationTool(port).execute(new JSONObject().put("prompt", "x"), agnesContext());

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("RuntimeException"), result.getContent());
  }

  @Test
  public void interruptDuringPollingCancelsTheWait() throws Exception {
    // 用户取消（线程中断）必须立刻退出轮询并如实报告取消，而不是睡满间隔。
    ScriptedPort port =
        new ScriptedPort(
            ok(new JSONObject().put("video_id", "v1").toString()),
            ok(new JSONObject().put("status", "processing").toString()),
            ok(new JSONObject().put("status", "processing").toString()));
    final VideoGenerationTool tool = new VideoGenerationTool(port);
    final ToolResult[] outcome = new ToolResult[1];
    Thread runner =
        new Thread(
            () -> outcome[0] = tool.execute(new JSONObject().put("prompt", "x"), agnesContext()));
    runner.setDaemon(true);
    runner.start();
    Thread.sleep(500); // 让它进入 sleep(3s)
    runner.interrupt();
    runner.join(5_000);

    assertFalse(runner.isAlive(), "中断后应立即退出");
    assertNotNull(outcome[0]);
    assertTrue(outcome[0].isError());
    assertTrue(outcome[0].getContent().contains("取消"), outcome[0].getContent());
  }

  @Test
  public void customOutputDirectoryIsUsed(@TempDir Path dir) throws Exception {
    // 用户配置了保存目录时必须用它，而不是工作区下的 ai-generated/。
    byte[] bytes = new byte[1024];
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v.mp4",
        exchange -> {
          exchange.sendResponseHeaders(200, bytes.length);
          try (java.io.OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v.mp4";
      ScriptedPort port =
          new ScriptedPort(
              ok(new JSONObject().put("video_id", "v1").toString()),
              ok(new JSONObject().put("status", "completed").put("url", url).toString()));
      Path custom = dir.resolve("custom-out");
      ToolContext ctx =
          ToolContext.builder()
              .homePath(dir.toString())
              .videoGeneration(
                  new VideoGenerationEndpoint(
                      "https://host/v1", "k", "agnes-video-2.5", custom.toString()))
              .build();

      ToolResult result =
          new VideoGenerationTool(port).execute(new JSONObject().put("prompt", "x"), ctx);

      assertFalse(result.isError(), result.getContent());
      java.io.File[] files = custom.toFile().listFiles();
      assertNotNull(files);
      assertEquals(1, files.length, "应落盘到自定义目录");
      assertTrue(files[0].getName().endsWith(".mp4"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void unwritableOutputDirectoryIsReported(@TempDir Path dir) throws Exception {
    // 输出目录指向一个已存在的**文件**：mkdirs 必然失败，必须明确报错而不是抛原始异常。
    java.io.File occupied = dir.resolve("occupied").toFile();
    java.nio.file.Files.write(occupied.toPath(), new byte[] {1});
    ScriptedPort port =
        new ScriptedPort(
            ok(new JSONObject().put("video_id", "v1").toString()),
            ok(new JSONObject()
                .put("status", "completed")
                .put("url", "https://cdn/v.mp4")
                .toString()));
    ToolContext ctx =
        ToolContext.builder()
            .homePath(dir.toString())
            .videoGeneration(
                new VideoGenerationEndpoint(
                    "https://host/v1", "k", "agnes-video-2.5", occupied.getAbsolutePath()))
            .build();

    ToolResult result =
        new VideoGenerationTool(port).execute(new JSONObject().put("prompt", "x"), ctx);

    // 下载失败按约定仍返回 ok（带 URL），但原因必须是「无法创建目录」。
    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("无法创建目录"), result.getContent());
  }

  @Test
  public void downloadHttpErrorIsReportedWithStatusCode(@TempDir Path dir) throws Exception {
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/missing.mp4",
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/missing.mp4";
      ScriptedPort port =
          new ScriptedPort(
              ok(new JSONObject().put("video_id", "v1").toString()),
              ok(new JSONObject().put("status", "completed").put("url", url).toString()));
      ToolContext ctx =
          ToolContext.builder()
              .homePath(dir.toString())
              .videoGeneration(
                  new VideoGenerationEndpoint("https://host/v1", "k", "agnes-video-2.5", ""))
              .build();

      ToolResult result =
          new VideoGenerationTool(port).execute(new JSONObject().put("prompt", "x"), ctx);

      assertFalse(result.isError(), result.getContent());
      assertTrue(result.getContent().contains("404"), result.getContent());
    } finally {
      server.stop(0);
    }
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
