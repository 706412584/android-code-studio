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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

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
}
