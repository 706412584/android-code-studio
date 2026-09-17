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
}
