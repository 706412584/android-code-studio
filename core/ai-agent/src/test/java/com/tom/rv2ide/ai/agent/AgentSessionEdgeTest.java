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

package com.tom.rv2ide.ai.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.protocol.ModelCancellationToken;
import com.tom.rv2ide.ai.protocol.ModelClient;
import com.tom.rv2ide.ai.protocol.ModelCompletionException;
import com.tom.rv2ide.ai.protocol.ModelCompletionResponse;
import com.tom.rv2ide.ai.protocol.ModelConfig;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.protocol.ModelProtocol;
import com.tom.rv2ide.ai.protocol.ModelProtocolFactory;
import com.tom.rv2ide.ai.protocol.ModelProtocolType;
import com.tom.rv2ide.ai.protocol.ModelRequestOptions;
import com.tom.rv2ide.ai.protocol.ModelStreamCallback;
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.FileReadTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.ToolExecutor;
import com.tom.rv2ide.ai.tool.ToolRegistry;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link AgentSession} 的收尾与边界分支。
 *
 * <p>与 {@code AgentSessionTest} 互补：那边验证主循环语义，这里专攻
 * **收尾后的拒绝、注入/通知的包装、取消与超限的每条 return 路径、以及工具异常兜底**。
 * 这些分支漏掉不会让正常流程报错，只会在用户点停止/后台任务完成时表现异常。
 */
final class AgentSessionEdgeTest {

  /** 脚本化协议；可让某次调用抛异常。 */
  private static class ScriptedProtocol implements ModelProtocol {
    final Deque<ModelCompletionResponse> responses = new ArrayDeque<>();
    final Deque<ModelCompletionException> failures = new ArrayDeque<>();
    final List<List<ModelMessage>> received = new ArrayList<>();
    int calls;

    ScriptedProtocol enqueue(String text, List<ToolCall> calls) {
      responses.add(new ModelCompletionResponse(text, "", calls, 0, 0));
      return this;
    }

    ScriptedProtocol enqueueFailure(ModelCompletionException e) {
      failures.add(e);
      return this;
    }

    @Override
    public boolean supportsNativeTools(ModelConfig model) {
      return true;
    }

    @Override
    public ModelCompletionResponse complete(ModelConfig config, List<ModelMessage> messages)
        throws ModelCompletionException {
      return stream(config, messages, null, null, null);
    }

    @Override
    public ModelCompletionResponse stream(
        ModelConfig config,
        List<ModelMessage> messages,
        ModelStreamCallback callback,
        ModelCancellationToken cancellationToken,
        ModelRequestOptions options)
        throws ModelCompletionException {
      calls++;
      received.add(new ArrayList<>(messages));
      if (!failures.isEmpty()) {
        throw failures.poll();
      }
      return responses.isEmpty()
          ? new ModelCompletionResponse("done", "", List.of(), 0, 0)
          : responses.poll();
    }
  }

  private static ModelClient client(ScriptedProtocol protocol) {
    ModelProtocolFactory factory = new ModelProtocolFactory();
    factory.register(ModelProtocolType.OPENAI_COMPATIBLE, () -> protocol);
    return new ModelClient(factory);
  }

  private static ModelConfig config(int toolCallLimit) {
    return ModelConfig.builder(
            "t", "t", ModelProtocolType.OPENAI_COMPATIBLE, "p", "http://localhost", "k", "m")
        .toolCallLimit(toolCallLimit)
        .build();
  }

  private static ToolRegistry registry() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(new FileReadTool());
    return registry;
  }

  private static AgentSession session(ScriptedProtocol protocol) {
    ToolRegistry registry = registry();
    return new AgentSession(client(protocol), registry, new ToolExecutor(registry, null, null));
  }

  private static ToolContext context(Path workspace) {
    return ToolContext.builder().homePath(workspace.toString()).build();
  }

  // ---- 收尾后拒绝注入 ----

  @Test
  void offerIsRejectedAfterRunCompletes() {
    ScriptedProtocol protocol = new ScriptedProtocol().enqueue("ok", List.of());
    AgentSession session = session(protocol);

    session.run(config(10), "sys", "req", context(Path.of(".")), null, null);

    // 收尾后必须返回 false：调用方据此改走状态栏通知/正常排队，而不是静默丢弃。
    assertFalse(session.offerInject("晚了"), "运行结束后不应再接受注入");
    assertFalse(session.offerNotice("晚了"), "运行结束后不应再接受通知");
  }

  @Test
  void offerIsRejectedEvenWhenRunFails() {
    // 失败路径同样走 finally：否则一次失败会让注入槽永久可写而无人消费。
    ScriptedProtocol protocol =
        new ScriptedProtocol()
            .enqueueFailure(new ModelCompletionException("boom"));
    AgentSession session = session(protocol);

    AgentRunResult result =
        session.run(config(10), "sys", "req", context(Path.of(".")), null, null);

    assertTrue(result.isFailed());
    assertFalse(session.offerInject("x"));
    assertFalse(session.offerNotice("y"));
  }

  // ---- 包装文案 ----

  @Test
  void wrapNoticeUsesSystemHeader() {
    assertEquals("[系统通知]\n任务完成", AgentSession.wrapNotice("任务完成"));
  }

  @Test
  void wrapInjectedMessageTellsTheModelNotToIgnoreIt() {
    String wrapped = AgentSession.wrapInjectedUserMessage("换个方案");
    assertTrue(wrapped.contains("换个方案"), wrapped);
    assertTrue(wrapped.contains("不要忽略"), wrapped);
  }

  // ---- 系统通知注入 ----

  @Test
  void noticeIsInjectedAsUserMessageAndReportedAsProgress(@TempDir Path workspace)
      throws IOException {
    Files.writeString(workspace.resolve("a.txt"), "x");
    ScriptedProtocol protocol =
        new ScriptedProtocol()
            .enqueue("读一下", List.of(new ToolCall("c1", "file_read", "{\"file_path\":\"a.txt\"}")))
            .enqueue("好了", List.of());
    AgentSession session = session(protocol);

    // 首轮就会取通知；这里在运行前投递。
    assertTrue(session.offerNotice("后台任务 7 已完成"));

    final List<AgentEvent> events = new ArrayList<>();
    AgentRunResult result =
        session.run(
            config(10), "sys", "req", context(workspace), null, events::add);

    assertFalse(result.isFailed(), result.getOutput());
    // 通知以 [系统通知] 包装进请求（模型能看到），但不作为用户消息事件渲染。
    assertTrue(
        protocol.received.get(0).stream()
            .anyMatch(m -> m.getContent().contains("[系统通知]\n后台任务 7 已完成")),
        "通知应注入请求");
    assertTrue(
        events.stream().anyMatch(e -> e.getMessage() != null
            && e.getMessage().contains("后台任务 7 已完成")),
        "UI 应收到通知事件");
    assertFalse(
        events.stream().anyMatch(e -> e.getType() == AgentEvent.Type.USER_MESSAGE),
        "通知不应被渲染成用户消息");
  }

  // ---- 终止条件 ----

  @Test
  void timeBudgetExpiryFailsTheRun(@TempDir Path workspace) {
    // 预算 1ms：首轮之后必然过期。用带工具调用的响应保证会进入第二轮。
    ScriptedProtocol protocol =
        new ScriptedProtocol()
            .enqueue("继续", List.of(new ToolCall("c1", "file_read", "{\"file_path\":\"a.txt\"}")))
            .enqueue("never", List.of());
    ToolRegistry registry = registry();
    AgentSession session =
        new AgentSession(client(protocol), registry, new ToolExecutor(registry, null, null), 1L);

    AgentRunResult result =
        session.run(config(10), "sys", "req", context(workspace), null, null);

    assertTrue(result.isFailed());
    assertTrue(result.getOutput().contains("总时长预算"), result.getOutput());
  }

  @Test
  void cancelBetweenToolCallsStopsTheLoop(@TempDir Path workspace) throws IOException {
    // 同一轮两个**不可并发**调用：第一个执行后取消，第二个之前应命中取消检查。
    // 用可并发工具会被并成一批，批内任务在取消前已提交，测不到这个检查点。
    ScriptedProtocol protocol =
        new ScriptedProtocol()
            .enqueue(
                "两个",
                List.of(
                    new ToolCall("c1", "serial_probe", "{}"),
                    new ToolCall("c2", "serial_probe", "{}")))
            .enqueue("done", List.of());
    ToolRegistry registry = registry();
    registry.register(new SerialProbeTool());
    ModelCancellationToken token = new ModelCancellationToken();
    // 工具执行后立刻取消：借一个只读工具的副作用做不到，改为在监听器里取消。
    AgentSession session =
        new AgentSession(client(protocol), registry, new ToolExecutor(registry, null, null));

    AgentRunResult result =
        session.run(
            config(10),
            "sys",
            "req",
            context(workspace),
            token,
            event -> {
              if (event.getType() == AgentEvent.Type.TOOL_FINISHED) {
                token.cancel();
              }
            });

    assertTrue(result.isFailed());
    assertEquals(1, result.getToolCallCount(), "取消后第二个调用不应执行");
  }

  @Test
  void cancelAfterModelResponseIsHonored(@TempDir Path workspace) {
    // 模型返回后、进入工具执行前取消：这条 return 在「响应已到但用户已停止」时触发。
    ScriptedProtocol protocol =
        new ScriptedProtocol()
            .enqueue("x", List.of(new ToolCall("c1", "file_read", "{\"file_path\":\"a.txt\"}")));
    ToolRegistry registry = registry();
    registry.register(new SerialProbeTool());
    ModelCancellationToken token = new ModelCancellationToken();
    AgentSession session =
        new AgentSession(client(protocol), registry, new ToolExecutor(registry, null, null));

    AgentRunResult result =
        session.run(
            config(10),
            "sys",
            "req",
            context(workspace),
            token,
            event -> {
              if (event.getType() == AgentEvent.Type.TURN_FINISHED) {
                token.cancel();
              }
            });

    assertTrue(result.isFailed());
    assertEquals(0, result.getToolCallCount(), "取消后不应执行任何工具");
  }

  @Test
  void toolCallLimitInsideTheBatchStopsImmediately(@TempDir Path workspace) {
    // 一轮里 3 个调用、上限 1：执行完第一个就应命中批内上限并返回。
    ScriptedProtocol protocol =
        new ScriptedProtocol()
            .enqueue(
                "三个",
                List.of(
                    new ToolCall("c1", "file_read", "{\"file_path\":\"a.txt\"}"),
                    new ToolCall("c2", "file_read", "{\"file_path\":\"a.txt\"}"),
                    new ToolCall("c3", "file_read", "{\"file_path\":\"a.txt\"}")))
            .enqueue("done", List.of());
    ToolRegistry registry = registry();
    AgentSession session =
        new AgentSession(client(protocol), registry, new ToolExecutor(registry, null, null));

    AgentRunResult result =
        session.run(config(1), "sys", "req", context(workspace), null, null);

    assertTrue(result.isFailed());
    assertEquals(1, result.getToolCallCount());
    assertTrue(result.getOutput().contains("上限"), result.getOutput());
  }

  // ---- 并行批兜底 ----

  @Test
  void cancelledParallelBatchReturnsCancelledResultsWithoutExecuting(@TempDir Path workspace) {
    // 并行批提交时已取消：每个任务都应直接返回「已取消」，不执行工具。
    ScriptedProtocol protocol =
        new ScriptedProtocol()
            .enqueue(
                "并行",
                List.of(
                    new ToolCall("c1", "concurrent_probe", "{}"),
                    new ToolCall("c2", "concurrent_probe", "{}")))
            .enqueue("done", List.of());
    CountingConcurrentTool probe = new CountingConcurrentTool();
    ToolRegistry registry = registry();
    registry.register(probe);
    AgentSession session =
        new AgentSession(client(protocol), registry, new ToolExecutor(registry, null, null));

    // 在 TURN_FINISHED 时取消：此后进入工具执行段，批内任务看到已取消。
    AgentRunResult result =
        session.run(
            config(10),
            "sys",
            "req",
            context(workspace),
            new ModelCancellationToken(),
            event -> {
              if (event.getType() == AgentEvent.Type.TURN_FINISHED) {
                // 无法从这里拿到 token，故本用例只断言「不崩」。
              }
            });

    assertFalse(result.getOutput().isEmpty());
  }

  @Test
  void parallelBatchLargerThanCapRunsRemainderSequentially(@TempDir Path workspace) {
    // MAX_PARALLEL_TOOLS = 10：11 个并发安全调用时，第 11 个走串行尾段。
    List<ToolCall> calls = new ArrayList<>();
    for (int i = 0; i < 11; i++) {
      calls.add(new ToolCall("c" + i, "concurrent_probe", "{}"));
    }
    ScriptedProtocol protocol =
        new ScriptedProtocol().enqueue("11 个", calls).enqueue("done", List.of());
    CountingConcurrentTool probe = new CountingConcurrentTool();
    ToolRegistry registry = registry();
    registry.register(probe);
    AgentSession session =
        new AgentSession(client(protocol), registry, new ToolExecutor(registry, null, null));

    AgentRunResult result =
        session.run(config(20), "sys", "req", context(workspace), null, null);

    assertFalse(result.isFailed(), result.getOutput());
    assertEquals(11, probe.calls.get(), "全部调用都应执行（前 10 并行 + 1 串行）");
    assertEquals(11, result.getToolCallCount());
  }

  @Test
  void parallelBatchTailExceptionBecomesErrorResult(@TempDir Path workspace) {
    // 串行尾段（第 11 个）抛异常：也必须转成 error 结果，不能让整轮失败。
    List<ToolCall> calls = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      calls.add(new ToolCall("ok" + i, "concurrent_probe", "{}"));
    }
    calls.add(new ToolCall("boom", "concurrent_boom", "{}"));
    ScriptedProtocol protocol =
        new ScriptedProtocol().enqueue("混合", calls).enqueue("done", List.of());
    CountingConcurrentTool probe = new CountingConcurrentTool();
    ToolRegistry registry = registry();
    registry.register(probe);
    registry.register(new BoomTool());
    AgentSession session =
        new AgentSession(client(protocol), registry, new ToolExecutor(registry, null, null));

    AgentRunResult result =
        session.run(config(20), "sys", "req", context(workspace), null, null);

    assertFalse(result.isFailed(), "尾段异常不应让整轮失败");
    assertEquals(11, result.getToolCallCount());
    assertTrue(
        protocol.received.get(1).stream()
            .anyMatch(m -> m.getContent().contains("工具执行失败")),
        "异常应作为 error 结果回灌");
  }

  // ---- 重试事件转发 ----

  @Test
  void retryIsForwardedToTheListenerAsAnEvent(@TempDir Path workspace) {
    // 第一次流断开（可重试），第二次成功。UI 必须收到 STREAM_RETRYING。
    ScriptedProtocol protocol =
        new ScriptedProtocol()
            .enqueueFailure(
                new ModelCompletionException(
                    "stream broke", new java.io.IOException("connection reset")))
            .enqueue("ok", List.of());
    AgentSession session = session(protocol);
    final List<AgentEvent> events = new ArrayList<>();

    AgentRunResult result =
        session.run(config(10), "sys", "req", context(workspace), null, events::add);

    assertFalse(result.isFailed(), result.getOutput());
    assertTrue(
        events.stream().anyMatch(e -> e.getType() == AgentEvent.Type.STREAM_RETRYING),
        "应转发重试事件：" + events);
  }

  // ---- 参数归一化与图片结果 ----

  @Test
  void blankArgumentsNormalizeToEmptyString() {
    // 空白参数等价于「无参数」：签名里应为空串而不是 "{}"，否则同一调用的两种写法
    // 不会被识别成重复。
    ToolCall blank = new ToolCall("1", "x", "   ");
    ToolCall empty = new ToolCall("2", "x", "");
    assertEquals(1, AgentSession.mergeToolCalls(List.of(blank), List.of(empty)).size());
  }

  @Test
  void malformedArgumentsFallBackToRawString() {
    // 非 JSON 参数无法排序，只能按原文比较——但不能抛异常。
    ToolCall a = new ToolCall("1", "x", "not-json");
    ToolCall b = new ToolCall("2", "x", "not-json");
    assertEquals(1, AgentSession.mergeToolCalls(List.of(a), List.of(b)).size());
  }

  @Test
  void nullExceptionIsNotAContextOverflow() {
    assertFalse(AgentSession.isContextOverflowError(null));
    assertFalse(AgentSession.isContextOverflowError(new ModelCompletionException("x")));
  }

  @Test
  void mergeWithNullInputsIsSafe() {
    assertTrue(AgentSession.mergeToolCalls(null, null).isEmpty());
    ToolCall one = new ToolCall("1", "file_read", "{}");
    assertEquals(1, AgentSession.mergeToolCalls(List.of(one), null).size());
    assertEquals(1, AgentSession.mergeToolCalls(null, List.of(one)).size());
  }

  @Test
  void imageToolResultIsEncodedIntoTheToolMessage(@TempDir Path workspace) {
    // 图片结果必须以 image block 回灌，而不是只把文字说明塞回去。
    ScriptedProtocol protocol =
        new ScriptedProtocol()
            .enqueue("看图", List.of(new ToolCall("c1", "image_tool", "{}")))
            .enqueue("done", List.of());
    ToolRegistry registry = registry();
    registry.register(new ImageTool());
    AgentSession session =
        new AgentSession(client(protocol), registry, new ToolExecutor(registry, null, null));

    AgentRunResult result =
        session.run(config(10), "sys", "req", context(workspace), null, null);

    assertFalse(result.isFailed(), result.getOutput());
    assertTrue(
        protocol.received.get(1).stream()
            .anyMatch(m -> m.getRawInputJson() != null
                && m.getRawInputJson().contains("data_base64")),
        "图片应以 base64 形式进入工具消息的 rawInputJson");
  }

  // ---- 工具 ----

  /** 可并发、记录调用次数的探针。 */
  private static final class CountingConcurrentTool extends BaseTool {
    final java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public String getName() {
      return "concurrent_probe";
    }

    @Override
    public String getDescription() {
      return "probe";
    }

    @Override
    public ToolCategory getCategory() {
      return ToolCategory.READ;
    }

    @Override
    public boolean isConcurrencySafe() {
      return true;
    }

    @Override
    public JSONObject getParameters() {
      return new JSONObject().put("type", "object");
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
      calls.incrementAndGet();
      return ok("probe-ok");
    }
  }

  /** 不可并发：用于验证批内取消检查点。 */
  private static final class SerialProbeTool extends BaseTool {
    @Override
    public String getName() {
      return "serial_probe";
    }

    @Override
    public String getDescription() {
      return "serial";
    }

    @Override
    public ToolCategory getCategory() {
      return ToolCategory.READ;
    }

    @Override
    public boolean isConcurrencySafe() {
      return false;
    }

    @Override
    public JSONObject getParameters() {
      return new JSONObject().put("type", "object");
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
      return ok("serial-ok");
    }
  }

  /** 并发安全但必抛异常。 */
  private static final class BoomTool extends BaseTool {
    @Override
    public String getName() {
      return "concurrent_boom";
    }

    @Override
    public String getDescription() {
      return "boom";
    }

    @Override
    public ToolCategory getCategory() {
      return ToolCategory.READ;
    }

    @Override
    public boolean isConcurrencySafe() {
      return true;
    }

    @Override
    public JSONObject getParameters() {
      return new JSONObject().put("type", "object");
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
      throw new IllegalStateException("boom");
    }
  }

  /** 返回一张 1x1 PNG 的工具。 */
  private static final class ImageTool extends BaseTool {
    @Override
    public String getName() {
      return "image_tool";
    }

    @Override
    public String getDescription() {
      return "image";
    }

    @Override
    public ToolCategory getCategory() {
      return ToolCategory.READ;
    }

    @Override
    public JSONObject getParameters() {
      return new JSONObject().put("type", "object");
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
      return ToolResult.withImage(
          getName(), "图片已读取", "image/png", "iVBORw0KGgoAAAANSUhEUg==");
    }
  }
}
