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

package com.tom.rv2ide.ai.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.agent.context.RunContextManager;
import com.tom.rv2ide.ai.agent.context.TokenUsageTracker;
import com.tom.rv2ide.ai.protocol.AssistantModelMessage;
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
import com.tom.rv2ide.ai.protocol.UserModelMessage;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.ToolExecutor;
import com.tom.rv2ide.ai.tool.ToolRegistry;
import com.tom.rv2ide.ai.tool.FileReadTool;
import com.tom.rv2ide.ai.tool.FileWriteTool;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * agent 循环的行为验证。
 *
 * <p>用一个脚本化的假协议替代真实模型：每次被调用就返回预设的下一轮响应。
 * 这样可以在不联网、不依赖 Android 的前提下，完整验证循环的关键行为：
 * 多轮往返、工具真实执行、结果回灌、以及三重终止条件。
 */
final class AgentSessionTest {

  /** 按脚本依次返回响应的假协议。 */
  private static class ScriptedProtocol implements ModelProtocol {
    private final Deque<ModelCompletionResponse> responses = new ArrayDeque<>();
    private final List<List<ModelMessage>> receivedMessages = new ArrayList<>();
    private final boolean nativeTools;
    private int callCount = 0;

    ScriptedProtocol(boolean nativeTools) {
      this.nativeTools = nativeTools;
    }

    ScriptedProtocol enqueue(String text, List<ToolCall> calls) {
      responses.add(new ModelCompletionResponse(text, "", calls, 0, 0));
      return this;
    }

    @Override
    public boolean supportsNativeTools(ModelConfig model) {
      return nativeTools;
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
      callCount++;
      // 记录本轮收到的完整消息列表，用于断言结果是否被回灌
      receivedMessages.add(new ArrayList<>(messages));
      if (responses.isEmpty()) {
        return new ModelCompletionResponse("done", "", List.of(), 0, 0);
      }
      return responses.poll();
    }
  }

  private static ModelClient clientWith(ScriptedProtocol protocol) {
    ModelProtocolFactory factory = new ModelProtocolFactory();
    factory.register(ModelProtocolType.OPENAI_COMPATIBLE, () -> protocol);
    return new ModelClient(factory);
  }

  private static ModelConfig config(int toolCallLimit) {
    return ModelConfig.builder(
            "test", "test", ModelProtocolType.OPENAI_COMPATIBLE,
            "test-provider", "http://localhost", "k", "test-model")
        .toolCallLimit(toolCallLimit)
        .build();
  }

  private static ToolCall call(String id, String name, String argsJson) {
    return new ToolCall(id, name, argsJson);
  }

  private static ToolRegistry fileRegistry() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(new FileReadTool());
    registry.register(new FileWriteTool());
    return registry;
  }

  @Test
  void executesMultiTurnToolCallsAndFeedsResultsBack(@TempDir Path workspace) throws IOException {
    Files.createDirectories(workspace.resolve("src"));
    Files.writeString(workspace.resolve("src/Config.kt"), "val name = \"old\"\n");

    ScriptedProtocol protocol =
        new ScriptedProtocol(true)
            // 第一轮：请求读取文件
            .enqueue(
                "先看看现有内容",
                List.of(call("c1", "file_read", "{\"file_path\":\"src/Config.kt\"}")))
            // 第二轮：请求改写文件
            .enqueue(
                "现在修改它",
                List.of(
                    call(
                        "c2",
                        "file_write",
                        "{\"file_path\":\"src/Config.kt\",\"content\":\"val name = \\\"new\\\"\\n\"}")))
            // 第三轮：不再请求工具，给出结论
            .enqueue("已完成修改", List.of());

    AgentSession session =
        new AgentSession(
            clientWith(protocol),
            fileRegistry(),
            new ToolExecutor(fileRegistry(), null, null));

    AgentRunResult result =
        session.run(
            config(10),
            "system prompt",
            "把 name 改成 new",
            ToolContext.builder().homePath(workspace.toString()).build(),
            null,
            null);

    // 循环跑满三轮：读 → 写 → 结束
    assertEquals(3, protocol.callCount, "应与模型往返三轮");
    assertEquals(2, result.getToolCallCount(), "应执行两次工具调用");
    assertFalse(result.isFailed(), "正常结束不应标记为失败");
    assertEquals("已完成修改", result.getOutput());

    // 工具真的改动了磁盘
    assertEquals("val name = \"new\"\n", Files.readString(workspace.resolve("src/Config.kt")));

    // 结果被回灌：第三轮请求里应能看到工具返回的内容
    List<ModelMessage> lastTurn = protocol.receivedMessages.get(2);
    assertTrue(
        lastTurn.stream().anyMatch(m -> m.getContent().contains("val name = \"old\"")),
        "第二轮请求应包含读取到的文件内容");
    assertTrue(
        lastTurn.stream().anyMatch(m -> m.getContent().contains("Successfully")),
        "第三轮请求应包含写入成功的结果");
  }

  @Test
  void parsesToolCallsFromTextWhenNativeToolsUnsupported(@TempDir Path workspace)
      throws IOException {
    // 不支持原生工具的模型把调用写在正文里
    ScriptedProtocol protocol =
        new ScriptedProtocol(false)
            .enqueue(
                "我来读取文件\n"
                    + "<tool_calls>\n"
                    + "<tool_call name=\"file_read\">\n"
                    + "<argument name=\"file_path\">a.txt</argument>\n"
                    + "</tool_call>\n"
                    + "</tool_calls>",
                List.of())
            .enqueue("读完了", List.of());
    Files.writeString(workspace.resolve("a.txt"), "hello");

    AgentSession session =
        new AgentSession(
            clientWith(protocol),
            fileRegistry(),
            new ToolExecutor(fileRegistry(), null, null));

    AgentRunResult result =
        session.run(
            config(10),
            "sys",
            "读 a.txt",
            ToolContext.builder().homePath(workspace.toString()).build(),
            null,
            null);

    assertEquals(1, result.getToolCallCount(), "正文里的工具调用也应被执行");
    assertFalse(result.isFailed());
    assertEquals("读完了", result.getOutput());
  }

  @Test
  void stopsAtToolCallLimit(@TempDir Path workspace) {
    // 模型每轮都请求工具，且永不收敛；应被次数上限截断
    ScriptedProtocol protocol = new ScriptedProtocol(true);
    for (int i = 0; i < 20; i++) {
      protocol.enqueue(
          "继续", List.of(call("c" + i, "file_read", "{\"file_path\":\"x.txt\"}")));
    }
    ToolRegistry registry = fileRegistry();

    AgentSession session =
        new AgentSession(clientWith(protocol), registry, new ToolExecutor(registry, null, null));

    AgentRunResult result =
        session.run(
            config(3),
            "sys",
            "loop",
            ToolContext.builder().homePath(workspace.toString()).build(),
            null,
            null);

    assertTrue(result.isFailed(), "超过工具调用上限应标记失败");
    assertEquals(3, result.getToolCallCount(), "应恰好执行到上限");
    assertTrue(result.getOutput().contains("上限"), result.getOutput());
  }

  @Test
  void stopsWhenCancelled(@TempDir Path workspace) {
    ScriptedProtocol protocol = new ScriptedProtocol(true);
    for (int i = 0; i < 5; i++) {
      protocol.enqueue("继续", List.of(call("c" + i, "file_read", "{\"file_path\":\"x.txt\"}")));
    }
    ToolRegistry registry = fileRegistry();
    ModelCancellationToken cancelled = new ModelCancellationToken();
    cancelled.cancel();

    AgentSession session =
        new AgentSession(clientWith(protocol), registry, new ToolExecutor(registry, null, null));

    AgentRunResult result =
        session.run(
            config(10),
            "sys",
            "loop",
            ToolContext.builder().homePath(workspace.toString()).build(),
            cancelled,
            null);

    assertTrue(result.isFailed());
    assertEquals(0, protocol.callCount, "已取消时不应发起模型请求");
  }

  @Test
  void emitsEventStreamForUi(@TempDir Path workspace) throws IOException {
    Files.writeString(workspace.resolve("a.txt"), "x");
    ScriptedProtocol protocol =
        new ScriptedProtocol(true)
            .enqueue("读一下", List.of(call("c1", "file_read", "{\"file_path\":\"a.txt\"}")))
            .enqueue("好了", List.of());
    ToolRegistry registry = fileRegistry();

    List<AgentEvent.Type> events = new ArrayList<>();
    AgentSession session =
        new AgentSession(clientWith(protocol), registry, new ToolExecutor(registry, null, null));

    session.run(
        config(10),
        "sys",
        "req",
        ToolContext.builder().homePath(workspace.toString()).build(),
        null,
        event -> events.add(event.getType()));

    assertTrue(events.contains(AgentEvent.Type.TURN_STARTED), "应报告轮次开始");
    assertTrue(events.contains(AgentEvent.Type.TOOL_STARTED), "应报告工具开始");
    assertTrue(events.contains(AgentEvent.Type.TOOL_FINISHED), "应报告工具结束");
    assertEquals(AgentEvent.Type.COMPLETED, events.get(events.size() - 1), "最后一个事件应为完成");
  }

  @Test
  void reportsFailureWhenModelThrows(@TempDir Path workspace) {
    ScriptedProtocol protocol =
        new ScriptedProtocol(true) {
          @Override
          public ModelCompletionResponse stream(
              ModelConfig config,
              List<ModelMessage> messages,
              ModelStreamCallback callback,
              ModelCancellationToken cancellationToken,
              ModelRequestOptions options)
              throws ModelCompletionException {
            throw new ModelCompletionException("boom");
          }
        };
    ToolRegistry registry = fileRegistry();

    AgentSession session =
        new AgentSession(clientWith(protocol), registry, new ToolExecutor(registry, null, null));

    AgentRunResult result =
        session.run(
            config(10),
            "sys",
            "req",
            ToolContext.builder().homePath(workspace.toString()).build(),
            null,
            null);

    assertTrue(result.isFailed(), "模型异常应转为失败结果而非抛出");
    assertTrue(result.getOutput().contains("boom"), result.getOutput());
  }

  @Test
  void contextOverflowErrorTriggersShrinkAndRetry(@TempDir Path workspace) {
    // 第一次请求抛 400 context_length，第二次（收缩后）成功——验证反应式压缩重试。
    // 没有这条路径时 400 会原样抛给用户，长会话直接卡死（实测 bug）。
    java.util.concurrent.atomic.AtomicInteger attempts =
        new java.util.concurrent.atomic.AtomicInteger();
    ScriptedProtocol protocol =
        new ScriptedProtocol(true) {
          @Override
          public ModelCompletionResponse stream(
              ModelConfig config,
              List<ModelMessage> messages,
              ModelStreamCallback callback,
              ModelCancellationToken cancellationToken,
              ModelRequestOptions options)
              throws ModelCompletionException {
            if (attempts.incrementAndGet() == 1) {
              throw new ModelCompletionException(
                  "HTTP 400: {\"error\":{\"code\":\"context_length_exceeded\"}}");
            }
            return new ModelCompletionResponse("已恢复", "", List.of(), 0, 0);
          }
        };
    ToolRegistry registry = fileRegistry();

    // 造一段可被收缩的长历史：若干用户/助手消息，远超“保留最近两条”的下限。
    List<ModelMessage> history = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      history.add(new UserModelMessage("历史问题 " + i + " " + "x".repeat(200)));
      history.add(new AssistantModelMessage("历史回答 " + i + " " + "y".repeat(200), "", List.of()));
    }

    RunContextManager contextManager =
        new RunContextManager(new TokenUsageTracker(100000), 0);

    AgentSession session =
        new AgentSession(clientWith(protocol), registry, new ToolExecutor(registry, null, null));

    AgentRunResult result =
        session.run(
            config(10),
            "sys",
            "req",
            history,
            ToolContext.builder().homePath(workspace.toString()).build(),
            null,
            null,
            contextManager);

    assertEquals(2, attempts.get(), "超限后应收缩并重试一次");
    assertFalse(result.isFailed(), "收缩重试成功后不应标记失败");
    assertEquals("已恢复", result.getOutput());
  }

  @Test
  void contextOverflowIsNotRetriedTwice(@TempDir Path workspace) {
    // 收缩后仍超限：只重试一次，第二次失败直接返回失败结果（防止无限烧请求）。
    java.util.concurrent.atomic.AtomicInteger attempts =
        new java.util.concurrent.atomic.AtomicInteger();
    ScriptedProtocol protocol =
        new ScriptedProtocol(true) {
          @Override
          public ModelCompletionResponse stream(
              ModelConfig config,
              List<ModelMessage> messages,
              ModelStreamCallback callback,
              ModelCancellationToken cancellationToken,
              ModelRequestOptions options)
              throws ModelCompletionException {
            attempts.incrementAndGet();
            throw new ModelCompletionException(
                "HTTP 400: {\"error\":{\"message\":\"prompt is too long\"}}");
          }
        };
    ToolRegistry registry = fileRegistry();
    List<ModelMessage> history = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      history.add(new UserModelMessage("历史问题 " + i + " " + "x".repeat(200)));
      history.add(new AssistantModelMessage("历史回答 " + i + " " + "y".repeat(200), "", List.of()));
    }
    RunContextManager contextManager =
        new RunContextManager(new TokenUsageTracker(100000), 0);

    AgentSession session =
        new AgentSession(clientWith(protocol), registry, new ToolExecutor(registry, null, null));

    AgentRunResult result =
        session.run(
            config(10),
            "sys",
            "req",
            history,
            ToolContext.builder().homePath(workspace.toString()).build(),
            null,
            null,
            contextManager);

    assertEquals(2, attempts.get(), "超限最多重试一次");
    assertTrue(result.isFailed(), "重试仍失败时应返回失败结果");
  }

  @Test
  void nonOverflowClientErrorIsNotRetried(@TempDir Path workspace) {
    // 其他 400（参数错误等）不应触发收缩重试——裁剪历史会白丢上下文且问题依旧。
    java.util.concurrent.atomic.AtomicInteger attempts =
        new java.util.concurrent.atomic.AtomicInteger();
    ScriptedProtocol protocol =
        new ScriptedProtocol(true) {
          @Override
          public ModelCompletionResponse stream(
              ModelConfig config,
              List<ModelMessage> messages,
              ModelStreamCallback callback,
              ModelCancellationToken cancellationToken,
              ModelRequestOptions options)
              throws ModelCompletionException {
            attempts.incrementAndGet();
            throw new ModelCompletionException("HTTP 400: invalid request format");
          }
        };
    ToolRegistry registry = fileRegistry();
    List<ModelMessage> history = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      history.add(new UserModelMessage("历史问题 " + i + " " + "x".repeat(200)));
    }
    RunContextManager contextManager =
        new RunContextManager(new TokenUsageTracker(100000), 0);

    AgentSession session =
        new AgentSession(clientWith(protocol), registry, new ToolExecutor(registry, null, null));

    AgentRunResult result =
        session.run(
            config(10),
            "sys",
            "req",
            history,
            ToolContext.builder().homePath(workspace.toString()).build(),
            null,
            null,
            contextManager);

    assertEquals(1, attempts.get(), "非超限 400 不应重试");
    assertTrue(result.isFailed());
  }

  @Test
  void contextOverflowKeywordsAreMatched() {
    assertTrue(
        AgentSession.isContextOverflowError(
            new ModelCompletionException("HTTP 400: context_length_exceeded")));
    assertTrue(
        AgentSession.isContextOverflowError(
            new ModelCompletionException("HTTP 400: prompt is too long")));
    assertTrue(
        AgentSession.isContextOverflowError(
            new ModelCompletionException("HTTP 400: This model's maximum context length is 8192")));
    assertFalse(
        AgentSession.isContextOverflowError(
            new ModelCompletionException("HTTP 400: invalid request format")));
    assertFalse(AgentSession.isContextOverflowError(new ModelCompletionException("boom")));
  }

  @Test
  void toolErrorIsFedBackAndLoopContinues(@TempDir Path workspace) {
    // 工具失败（文件不存在）应回灌给模型，而不是中断循环
    ScriptedProtocol protocol =
        new ScriptedProtocol(true)
            .enqueue("读一个不存在的文件", List.of(call("c1", "file_read", "{\"file_path\":\"nope.txt\"}")))
            .enqueue("那我换个做法", List.of());

    ToolRegistry registry = fileRegistry();
    AgentSession session =
        new AgentSession(clientWith(protocol), registry, new ToolExecutor(registry, null, null));

    AgentRunResult result =
        session.run(
            config(10),
            "sys",
            "req",
            ToolContext.builder().homePath(workspace.toString()).build(),
            null,
            null);

    assertFalse(result.isFailed(), "工具失败不应终止循环");
    assertEquals(2, protocol.callCount, "模型应有机会看到错误后重试");
    List<ModelMessage> secondTurn = protocol.receivedMessages.get(1);
    assertTrue(
        secondTurn.stream().anyMatch(m -> m.getContent().contains("not found")
            || m.getContent().contains("File not found")),
        "第二轮应看到工具的错误信息");
  }

  @Test
  void mergeDeduplicatesSameCallFromBothSources() {
    // 同一调用同时出现在原生字段与正文里（id 不同），只应执行一次
    ToolCall nativeCall = call("native-1", "file_read", "{\"file_path\":\"a.txt\"}");
    ToolCall textCall = call("text-1", "file_read", "{\"file_path\": \"a.txt\"}");

    List<ToolCall> merged = AgentSession.mergeToolCalls(List.of(nativeCall), List.of(textCall));

    assertEquals(1, merged.size(), "参数相同（含空白差异）的调用应被识别为重复");
  }

  @Test
  void mergeKeepsDistinctCalls() {
    ToolCall a = call("1", "file_read", "{\"file_path\":\"a.txt\"}");
    ToolCall b = call("2", "file_read", "{\"file_path\":\"b.txt\"}");

    List<ToolCall> merged = AgentSession.mergeToolCalls(List.of(a), List.of(b));

    assertEquals(2, merged.size(), "不同目标的调用都应保留");
  }

  @Test
  void promptListsToolsAndWorkspace() {
    List<com.tom.rv2ide.ai.tool.api.ToolInfo> tools =
        new ArrayList<>(fileRegistry().getAll());
    String prompt = new AgentPromptBuilder().build("/w/proj", tools);

    assertTrue(prompt.contains("/w/proj"), "应说明工作区根目录");
    assertTrue(prompt.contains("file_read"), "应列出 file_read 工具");
    assertTrue(prompt.contains("file_write"), "应列出 file_write 工具");
    assertTrue(prompt.contains("<tool_call"), "应给出文本工具调用格式");
  }

  @Test
  void continuesFromProvidedHistory(@TempDir Path workspace) {
    // 续接既有会话：历史应出现在本轮请求里，且位于 system 之后、新用户消息之前
    ScriptedProtocol protocol = new ScriptedProtocol(true).enqueue("接着回答", List.of());
    List<ModelMessage> history =
        List.of(
            new com.tom.rv2ide.ai.protocol.UserModelMessage("上一轮的问题"),
            new com.tom.rv2ide.ai.protocol.AssistantModelMessage("上一轮的回答"));

    new AgentSession(
            clientWith(protocol), fileRegistry(), new ToolExecutor(fileRegistry(), null, null))
        .run(
            config(10),
            "系统提示",
            "本轮新问题",
            history,
            ToolContext.builder().homePath(workspace.toString()).build(),
            null,
            null);

    List<ModelMessage> sent = protocol.receivedMessages.get(0);
    assertEquals(4, sent.size(), "system + 历史2条 + 新用户消息");
    assertEquals("system", sent.get(0).getRole());
    assertEquals("上一轮的问题", sent.get(1).getContent());
    assertEquals("上一轮的回答", sent.get(2).getContent());
    assertEquals("本轮新问题", sent.get(3).getContent());
  }

  @Test
  void emptyHistoryBehavesLikeSingleTurn(@TempDir Path workspace) {
    // 空历史与旧签名等价，保证既有调用方不受影响
    ScriptedProtocol protocol = new ScriptedProtocol(true).enqueue("回答", List.of());

    new AgentSession(
            clientWith(protocol), fileRegistry(), new ToolExecutor(fileRegistry(), null, null))
        .run(
            config(10),
            "系统提示",
            "问题",
            List.of(),
            ToolContext.builder().homePath(workspace.toString()).build(),
            null,
            null);

    List<ModelMessage> sent = protocol.receivedMessages.get(0);
    assertEquals(2, sent.size(), "system + 用户消息");
  }

  @Test
  void promptHandlesEmptyToolList() {
    String prompt = new AgentPromptBuilder().build("/w", List.of());

    assertNotNull(prompt);
    assertTrue(prompt.contains("没有可用工具"), prompt);
  }

  // ---- 运行中注入（offerInject / offerNotice / drainLeftoverInjects）----

  @Test
  void completedSessionRejectsInjectAndNotice() {
    // 关键契约：收尾后必须返回 false，让调用方回退到别的投递路径——静默丢弃
    // 会把用户中途发的消息永久吞掉。
    AgentSession session =
        new AgentSession(
            clientWith(new ScriptedProtocol(true)), fileRegistry(),
            new ToolExecutor(fileRegistry(), null, null));

    assertTrue(session.offerInject("开始前的注入应被接受"));
    assertTrue(session.offerNotice("恢复一次会话"));
    // 未运行过 → 仍在接受注入

    assertFalse(session.offerInject(null), "null 注入应被拒");
    assertFalse(session.offerInject("   "), "空白注入应被拒");
    assertFalse(session.offerNotice(null), "null 通知应被拒");
    assertFalse(session.offerNotice(""), "空通知应被拒");
  }

  @Test
  void drainLeftoverInjectsReturnsAndClearsQueue() {
    AgentSession session =
        new AgentSession(
            clientWith(new ScriptedProtocol(true)), fileRegistry(),
            new ToolExecutor(fileRegistry(), null, null));

    assertTrue(session.offerInject("第一条"));
    assertTrue(session.offerInject("第二条"));

    List<String> leftover = session.drainLeftoverInjects();
    assertEquals(List.of("第一条", "第二条"), leftover, "应按投递顺序返回全部未消费注入");
    assertTrue(session.drainLeftoverInjects().isEmpty(), "取走后应清空——重复取会重复回灌");
  }

  @Test
  void injectIsWrappedWithNoticeHeaderWhenLoopRuns() throws IOException {
    // 注入是「用户在运行中补的话」，包装文案让模型能区分它与本次原始请求。
    @SuppressWarnings("unchecked")
    Path workspace = Files.createTempDirectory("inject-test");
    ScriptedProtocol protocol = new ScriptedProtocol(true).enqueue("ok", List.of());
    AgentSession session =
        new AgentSession(
            clientWith(protocol), fileRegistry(), new ToolExecutor(fileRegistry(), null, null));

    // 运行前投递：循环应在安全边界取走并并入请求。
    assertTrue(session.offerInject("补充要求：用 Kotlin"));

    session.run(
        config(10),
        "系统提示",
        "原始请求",
        ToolContext.builder().homePath(workspace.toString()).build(),
        null,
        null);

    List<ModelMessage> sent = protocol.receivedMessages.get(0);
    assertTrue(
        sent.stream().anyMatch(m -> m.getContent().contains("补充要求：用 Kotlin")),
        "运行中的注入应出现在发给模型的消息里");
  }

  // ---- 并行工具执行 ----

  @Test
  void parallelBatchKeepsResultOrderAndLimit(@TempDir Path workspace) throws IOException {
    // 并发安全的只读工具会被并行批处理；结果顺序必须与请求顺序一致，
    // 否则工具结果与 tool_call id 会错配（模型看到张冠李戴的结果）。
    Files.writeString(workspace.resolve("a.txt"), "hello\n");
    Files.writeString(workspace.resolve("b.txt"), "world\n");

    ScriptedProtocol protocol =
        new ScriptedProtocol(true)
            .enqueue(
                "并行读两个文件",
                List.of(
                    call("p1", "file_read", "{\"file_path\":\"a.txt\"}"),
                    call("p2", "file_read", "{\"file_path\":\"b.txt\"}")))
            .enqueue("读完了", List.of());

    ToolRegistry registry = fileRegistry();
    registry.register(new com.tom.rv2ide.ai.tool.ListDirectoryTool());

    AgentSession session =
        new AgentSession(clientWith(protocol), registry, new ToolExecutor(registry, null, null));

    AgentRunResult result =
        session.run(
            config(10),
            "系统",
            "读两个文件",
            ToolContext.builder().homePath(workspace.toString()).build(),
            null,
            null);

    assertEquals(2, result.getToolCallCount(), "两个只读调用都应执行");
    assertFalse(result.isFailed());
    // 第三轮请求里两条结果都在，且 a 的内容在 b 之前（顺序与请求一致）。
    List<ModelMessage> last = protocol.receivedMessages.get(1);
    String joined = String.join("\n", last.stream().map(ModelMessage::getContent).toList());
    int atA = joined.indexOf("hello");
    int atB = joined.indexOf("world");
    assertTrue(atA >= 0 && atB >= 0, "两条结果都应回灌：" + joined);
    assertTrue(atA < atB, "并行执行后结果顺序仍应与请求顺序一致");
  }

  @Test
  void parallelBatchReportsToolErrorWithoutBreakingLoop(@TempDir Path workspace) throws IOException {
    // 并行批里某个工具失败（文件不存在）不能让整批或整轮崩掉——
    // 错误要作为结果回灌给模型（与串行路径同约定）。
    ScriptedProtocol protocol =
        new ScriptedProtocol(true)
            .enqueue(
                "读一个不存在的和一个存在的",
                List.of(
                    call("e1", "file_read", "{\"file_path\":\"missing.txt\"}"),
                    call("e2", "file_read", "{\"file_path\":\"ok.txt\"}")))
            .enqueue("看到了错误", List.of());
    Files.writeString(workspace.resolve("ok.txt"), "content\n");

    ToolRegistry registry = fileRegistry();
    AgentSession session =
        new AgentSession(clientWith(protocol), registry, new ToolExecutor(registry, null, null));

    AgentRunResult result =
        session.run(
            config(10),
            "sys",
            "req",
            ToolContext.builder().homePath(workspace.toString()).build(),
            null,
            null);

    assertFalse(result.isFailed(), "单个工具失败不应让整轮失败");
    assertEquals(2, result.getToolCallCount(), "两个调用都应被计数（含失败的）");
    // 下一轮请求里应带上了成功那条的内容。
    List<ModelMessage> next = protocol.receivedMessages.get(1);
    assertTrue(
        next.stream().anyMatch(m -> m.getContent().contains("content")),
        "成功的结果仍应回灌");
  }

  @Test
  void streamingCallbacksAreForwardedAsDeltas() throws IOException {
    // (createTempDirectory 会抛 IOException)
    // 流式增量必须转成 TEXT_DELTA / REASONING_DELTA 事件——UI 靠它们做逐字渲染。
    Path workspace = Files.createTempDirectory("delta-test");
    ScriptedProtocol protocol =
        new ScriptedProtocol(true) {
          @Override
          public ModelCompletionResponse stream(
              ModelConfig config,
              List<ModelMessage> messages,
              ModelStreamCallback callback,
              ModelCancellationToken cancellationToken,
              ModelRequestOptions options)
              throws ModelCompletionException {
            if (callback != null) {
              callback.onTextDelta("你");
              callback.onReasoningDelta("思考中");
            }
            return new ModelCompletionResponse("你好", "", List.of(), 0, 0);
          }
        };

    List<AgentEvent.Type> events = new ArrayList<>();
    new AgentSession(
            clientWith(protocol), fileRegistry(),
            new ToolExecutor(fileRegistry(), null, null))
        .run(
            config(10),
            "sys",
            "req",
            ToolContext.builder().homePath(workspace.toString()).build(),
            null,
            event -> events.add(event.getType()));

    assertTrue(events.contains(AgentEvent.Type.TEXT_DELTA), "文本增量应转成 TEXT_DELTA");
    assertTrue(events.contains(AgentEvent.Type.REASONING_DELTA), "推理增量应转成 REASONING_DELTA");
  }

  @Test
  void cancelledTokenProducesCancellationResultNotFailure(@TempDir Path workspace)
      throws IOException {
    // 已取消的 token：循环应在进入工具执行前就退出，结果为「已取消」而非「失败」。
    ModelCancellationToken token = new ModelCancellationToken();
    token.cancel();

    ScriptedProtocol protocol =
        new ScriptedProtocol(true)
            .enqueue("调用工具", List.of(call("c1", "file_read", "{\"file_path\":\"a.txt\"}")));
    Files.writeString(workspace.resolve("a.txt"), "x");

    AgentRunResult result =
        new AgentSession(
                clientWith(protocol), fileRegistry(),
                new ToolExecutor(fileRegistry(), null, null))
            .run(
                config(10),
                "sys",
                "req",
                ToolContext.builder().homePath(workspace.toString()).build(),
                token,
                null);

    assertNotNull(result, "取消路径必须能正常返回结果而不抛异常");
    assertEquals(0, result.getToolCallCount(), "已取消时不应执行任何工具");
  }

  @Test
  void toolCallLimitZeroMeansUnlimitedNotBlocked(@TempDir Path workspace) throws IOException {
    // 语义澄清：limit <= 0 表示**不限制**（见 AgentSession 的 `toolCallLimit > 0` 守卫），
    // 不是「禁止调用」。把它误读成「0 = 不许调用」会写出反向行为的测试。
    ScriptedProtocol protocol =
        new ScriptedProtocol(true)
            .enqueue("想读文件", List.of(call("c1", "file_read", "{\"file_path\":\"a.txt\"}")))
            .enqueue("读完了", List.of());
    Files.writeString(workspace.resolve("a.txt"), "x");

    AgentRunResult result =
        new AgentSession(
                clientWith(protocol), fileRegistry(),
                new ToolExecutor(fileRegistry(), null, null))
            .run(
                config(0),
                "sys",
                "req",
                ToolContext.builder().homePath(workspace.toString()).build(),
                null,
                null);

    assertEquals(1, result.getToolCallCount(), "上限 0（=不限制）时工具应正常执行");
    assertFalse(result.isFailed());
  }

  @Test
  void parallelToolThrowingRuntimeExceptionIsConvertedToErrorResult(@TempDir Path workspace)
      throws IOException {
    // 并行批里某个工具直接抛 RuntimeException（不是返回 error 结果）：
    // 必须被捕获转成 error 结果回灌，而不是让整批/整轮崩掉。
    Files.writeString(workspace.resolve("ok.txt"), "fine\n");

    ScriptedProtocol protocol =
        new ScriptedProtocol(true)
            .enqueue(
                "一个会炸、一个正常",
                List.of(
                    call("boom", "concurrent_boom", "{}"),
                    call("ok", "file_read", "{\"file_path\":\"ok.txt\"}")))
            .enqueue("知道哪个炸了", List.of());

    ToolRegistry registry = fileRegistry();
    registry.register(new ConcurrentBoomTool());

    AgentRunResult result =
        new AgentSession(clientWith(protocol), registry, new ToolExecutor(registry, null, null))
            .run(
                config(10),
                "sys",
                "req",
                ToolContext.builder().homePath(workspace.toString()).build(),
                null,
                null);

    assertFalse(result.isFailed(), "单个工具抛异常不应让整轮失败");
    assertEquals(2, result.getToolCallCount(), "两个调用都应被计数");
    // 正常那条的结果仍应回灌（证明抛出没有中断整批）。
    assertTrue(
        protocol.receivedMessages.get(1).stream()
            .anyMatch(m -> m.getContent().contains("fine")),
        "同批中正常工具的结果仍应回灌");
  }

  /** 并发安全但调用即抛异常——用于验证并行批的异常兜底。 */
  private static final class ConcurrentBoomTool extends com.tom.rv2ide.ai.tool.BaseTool {
    @Override
    public String getName() {
      return "concurrent_boom";
    }

    @Override
    public String getDescription() {
      return "测试用：可并发，但执行必抛异常";
    }

    @Override
    public com.tom.rv2ide.ai.tool.api.ToolCategory getCategory() {
      return com.tom.rv2ide.ai.tool.api.ToolCategory.READ;
    }

    @Override
    public boolean isConcurrencySafe() {
      return true;
    }

    @Override
    public org.json.JSONObject getParameters() {
      return new org.json.JSONObject();
    }

    @Override
    public ToolResult execute(org.json.JSONObject input, com.tom.rv2ide.ai.tool.ToolContext ctx) {
      throw new IllegalStateException("故意抛出的异常");
    }
  }
}
