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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolCall;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 流式 tool_calls 解析的回归测试。
 *
 * <p><b>为什么需要它</b>：真机实测发现，某些聚合网关（这里是 agnes）在流式响应里把
 * {@code index} 发成 {@code -1} 且首帧不带 {@code function.name}。修复前的实现按 index
 * 归并，导致：
 * <ul>
 *   <li>多个不同调用被挤进同一个 builder，name 互相覆盖、arguments 拼接成非法 JSON；
 *   <li>无 name 的 builder 在 {@code buildToolCalls} 里被<b>静默丢弃</b>，
 *       表现为「模型什么都没做」（{@code toolCalls=0}），且日志里毫无线索。
 * </ul>
 *
 * <p>这里用一个本地 HTTP 服务回放两种真实响应形态，锁定修复行为。
 * 不用 mock：{@code appendToolCallDeltas} / {@code buildToolCalls} 是 private，
 * 而 {@code stream()} 是真实入口，走 HTTP 才能覆盖完整解析链路。
 */
final class OpenAiToolCallStreamTest {

  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  /** 启动一个回放固定 SSE 内容的本地服务，返回 baseUrl。 */
  private String serveSse(String sseBody) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/chat/completions",
        exchange -> {
          byte[] bytes = sseBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static ModelConfig config(String baseUrl) {
    return ModelConfig.builder(
            "test", "test", ModelProtocolType.OPENAI_COMPATIBLE,
            "test", baseUrl, "k", "test-model")
        .build();
  }

  private static String sse(String... dataPayloads) {
    StringBuilder sb = new StringBuilder();
    for (String payload : dataPayloads) {
      sb.append("data: ").append(payload).append("\n\n");
    }
    sb.append("data: [DONE]\n\n");
    return sb.toString();
  }

  @Test
  void parsesStandardOpenAiToolCallStream() throws Exception {
    // 标准形态：index=0，首帧带 id 与 name，arguments 分帧到达
    String baseUrl =
        serveSse(
            sse(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                    + "\"function\":{\"name\":\"file_write\",\"arguments\":\"\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                    + "\"function\":{\"arguments\":\"{\\\"file_path\\\":\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                    + "\"function\":{\"arguments\":\"\\\"a.txt\\\"}\"}}]}}]}"));

    ModelCompletionResponse response =
        new OpenAiCompatibleProtocol().stream(config(baseUrl), List.of(), null, null, null);

    assertEquals(1, response.getToolCalls().size(), "标准流应解析出 1 个调用");
    ToolCall call = response.getToolCalls().get(0);
    assertEquals("file_write", call.getName());
    assertEquals("{\"file_path\":\"a.txt\"}", call.getArguments());
  }

  @Test
  void recoversToolCallsWhenGatewaySendsIndexMinusOne() throws Exception {
    // 非标准形态（agnes 实测）：index=-1，首帧只有 arguments，name 在后续帧才出现。
    // 修复前：按 index 归并 → 单 builder；name 虽能补上，但多调用会串在一起。
    String baseUrl =
        serveSse(
            sse(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":-1,"
                    + "\"function\":{\"arguments\":\"{\\\"file_path\\\":\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":-1,\"id\":\"call_x\","
                    + "\"function\":{\"name\":\"file_write\",\"arguments\":\"\\\"b.txt\\\"}\"}}]}}]}"));

    ModelCompletionResponse response =
        new OpenAiCompatibleProtocol().stream(config(baseUrl), List.of(), null, null, null);

    assertEquals(1, response.getToolCalls().size(), "index=-1 时也应解析出调用");
    ToolCall call = response.getToolCalls().get(0);
    assertEquals("file_write", call.getName());
    assertEquals("{\"file_path\":\"b.txt\"}", call.getArguments());
  }

  @Test
  void keepsMultipleCallsSeparateWhenIndexIsUnusable() throws Exception {
    // 两个不同调用，index 全为 -1：必须按「带 name/id 的帧开启新调用」切分，
    // 否则 name 会被覆盖、arguments 被拼成一个非法 JSON。
    String baseUrl =
        serveSse(
            sse(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":-1,\"id\":\"c1\","
                    + "\"function\":{\"name\":\"file_read\",\"arguments\":\"{\\\"file_path\\\":\\\"a\\\"}\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":-1,\"id\":\"c2\","
                    + "\"function\":{\"name\":\"list_dir\",\"arguments\":\"{\\\"file_path\\\":\\\"b\\\"}\"}}]}}]}"));

    ModelCompletionResponse response =
        new OpenAiCompatibleProtocol().stream(config(baseUrl), List.of(), null, null, null);

    assertEquals(2, response.getToolCalls().size(), "两个调用必须分开，不能被合并");
    assertEquals("file_read", response.getToolCalls().get(0).getName());
    assertEquals("list_dir", response.getToolCalls().get(1).getName());
    assertEquals("{\"file_path\":\"a\"}", response.getToolCalls().get(0).getArguments());
    assertEquals("{\"file_path\":\"b\"}", response.getToolCalls().get(1).getArguments());
  }

  @Test
  void doesNotDropCallWhenFirstChunkHasNoName() throws Exception {
    // 首帧既无 index 也无 name（只有 arguments 片段），随后才有 name。
    // 修复前这种情况会被静默丢弃，导致「模型什么都没做」且无日志可查。
    String baseUrl =
        serveSse(
            sse(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"file_path\\\":\\\"c\\\"}\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"name\":\"file_write\"}}]}}]}"));

    ModelCompletionResponse response =
        new OpenAiCompatibleProtocol().stream(config(baseUrl), List.of(), null, null, null);

    assertTrue(response.getToolCalls().size() >= 1, "缺少 name 的增量不应导致调用被丢弃");
    assertEquals("file_write", response.getToolCalls().get(0).getName());
  }
}
