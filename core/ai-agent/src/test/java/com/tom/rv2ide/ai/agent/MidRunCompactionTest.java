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

package com.tom.rv2ide.ai.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.agent.context.RunContextManager;
import com.tom.rv2ide.ai.agent.context.TokenUsageTracker;
import com.tom.rv2ide.ai.agent.conversation.ConversationCompaction;
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
import com.tom.rv2ide.ai.protocol.SystemModelMessage;
import com.tom.rv2ide.ai.protocol.AssistantModelMessage;
import com.tom.rv2ide.ai.protocol.ToolModelMessage;
import com.tom.rv2ide.ai.protocol.UserModelMessage;
import com.tom.rv2ide.ai.tool.FileReadTool;
import com.tom.rv2ide.ai.tool.FileWriteTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.ToolExecutor;
import com.tom.rv2ide.ai.tool.ToolRegistry;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * mid-run auto-compact：长任务中段早期历史被**摘要替换**而非直接裁掉。
 *
 * <p>钉住四件事：超触发点时最旧段被摘要（保留段与本次请求不动）、未超触发不压缩、
 * 摘要失败退回原有裁剪、压缩后未积累足够新消息不重复压缩。
 */
final class MidRunCompactionTest {

  private static class ScriptedProtocol implements ModelProtocol {
    private final Deque<ModelCompletionResponse> responses = new ArrayDeque<>();
    final List<List<ModelMessage>> receivedMessages = new ArrayList<>();

    ScriptedProtocol enqueue(String text, List<ToolCall> calls) {
      responses.add(new ModelCompletionResponse(text, "", calls, 0, 0));
      return this;
    }

    @Override
    public boolean supportsNativeTools(ModelConfig model) {
      return true;
    }

    @Override
    public ModelCompletionResponse complete(ModelConfig config, List<ModelMessage> messages) {
      return responses.isEmpty() ? new ModelCompletionResponse("done", "", List.of(), 0, 0)
          : responses.poll();
    }

    @Override
    public ModelCompletionResponse stream(
        ModelConfig config,
        List<ModelMessage> messages,
        ModelStreamCallback callback,
        ModelCancellationToken cancellationToken,
        ModelRequestOptions options) {
      receivedMessages.add(new ArrayList<>(messages));
      return complete(config, messages);
    }
  }

  private static final class RecordingCompactor implements MidRunCompactor {
    int calls = 0;
    List<ModelMessage> segment;
    boolean throwOnCall = false;

    @Override
    public String summarize(List<ModelMessage> segment) {
      calls++;
      this.segment = new ArrayList<>(segment);
      if (throwOnCall) {
        throw new RuntimeException("模拟摘要失败");
      }
      return "早期内容已压缩";
    }
  }

  private static ModelClient clientWith(ScriptedProtocol protocol) {
    ModelProtocolFactory factory = new ModelProtocolFactory();
    factory.register(ModelProtocolType.OPENAI_COMPATIBLE, () -> protocol);
    return new ModelClient(factory);
  }

  private static ModelConfig config() {
    return ModelConfig.builder(
            "test", "test", ModelProtocolType.OPENAI_COMPATIBLE,
            "test-provider", "http://localhost", "k", "test-model")
        .toolCallLimit(10)
        .build();
  }

  private static ToolRegistry fileRegistry() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(new FileReadTool());
    registry.register(new FileWriteTool());
    return registry;
  }

  /** 构造一段估算用量超过触发点的历史（每条 CJK 约 2000 tokens）。 */
  private static List<ModelMessage> bigHistory(int contextSize) {
    int trigger = (int) (contextSize * TokenUsageTracker.COMPACT_TRIGGER_RATIO);
    StringBuilder chunk = new StringBuilder(2_000);
    for (int i = 0; i < 2_000; i++) {
      chunk.append('汉');
    }
    List<ModelMessage> history = new ArrayList<>();
    int total = 0;
    while (total < trigger + 2_000) {
      history.add(new UserModelMessage("历史" + history.size() + "：" + chunk));
      total += 2_000;
    }
    return history;
  }

  private static AgentRunResult run(
      ScriptedProtocol protocol,
      List<ModelMessage> history,
      int contextSize,
      RecordingCompactor compactor,
      Path workspace) {
    AgentSession session =
        new AgentSession(
            clientWith(protocol), fileRegistry(), new ToolExecutor(fileRegistry(), null, null));
    return session.run(
        config(),
        "sys",
        "本次请求",
        history,
        ToolContext.builder().homePath(workspace.toString()).build(),
        null,
        null,
        new RunContextManager(new TokenUsageTracker(contextSize), 0),
        null,
        null,
        compactor);
  }

  @Test
  void overTriggerCompactsOldestSegmentOnlyOnce(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("a.txt"), "hello");
    int contextSize = 20_000;
    List<ModelMessage> history = bigHistory(contextSize);
    RecordingCompactor compactor = new RecordingCompactor();
    // 两轮：第一轮读文件，第二轮结束——验证压缩后不会每轮都重复压缩
    ScriptedProtocol protocol =
        new ScriptedProtocol()
            .enqueue("读一下", List.of(new ToolCall("c1", "file_read", "{\"file_path\":\"a.txt\"}")))
            .enqueue("完成", List.of());

    AgentRunResult result = run(protocol, history, contextSize, compactor, workspace);

    assertFalse(result.isFailed());
    assertEquals(1, compactor.calls, "超触发点只应压缩一次（节奏控制）");
    assertEquals(
        history.size() - ConversationCompaction.MIN_KEEP_MESSAGES,
        compactor.segment.size(),
        "摘要段 = 历史 − 尾部保留条数");

    List<ModelMessage> sent = protocol.receivedMessages.get(0);
    assertTrue(
        sent.stream().anyMatch(m -> m.getContent().startsWith("[早期历史摘要]")
            && m.getContent().contains("早期内容已压缩")),
        "发给模型的历史里应包含摘要消息");
    assertEquals("本次请求", sent.get(sent.size() - 1).getContent(), "本次请求不可被压缩掉");
    assertTrue(
        sent.stream().noneMatch(m -> m.getContent().startsWith("历史0：")),
        "被摘要段的原文不应再出现");
    // 尾部保留的历史仍在（最近历史属于保留段）
    assertTrue(
        sent.stream().anyMatch(m -> m.getContent().startsWith("历史" + (history.size() - 1) + "：")),
        "最近的历史条目必须保留");
  }

  @Test
  void belowTriggerDoesNotCompact(@TempDir Path workspace) {
    List<ModelMessage> history = new ArrayList<>();
    history.add(new UserModelMessage("短历史一"));
    history.add(new UserModelMessage("短历史二"));
    RecordingCompactor compactor = new RecordingCompactor();
    ScriptedProtocol protocol = new ScriptedProtocol().enqueue("完成", List.of());

    AgentRunResult result = run(protocol, history, 20_000, compactor, workspace);

    assertFalse(result.isFailed());
    assertEquals(0, compactor.calls, "未超触发点不应调用压缩器");
  }

  @Test
  void compactorFailureFallsBackToTrimming(@TempDir Path workspace) {
    int contextSize = 20_000;
    List<ModelMessage> history = bigHistory(contextSize);
    RecordingCompactor compactor = new RecordingCompactor();
    compactor.throwOnCall = true;
    ScriptedProtocol protocol = new ScriptedProtocol().enqueue("完成", List.of());

    AgentRunResult result = run(protocol, history, contextSize, compactor, workspace);

    assertFalse(result.isFailed(), "摘要失败不得让运行失败（退回原有裁剪）");
    assertEquals(1, compactor.calls);
    List<ModelMessage> sent = protocol.receivedMessages.get(0);
    assertTrue(
        sent.stream().noneMatch(m -> m.getContent().startsWith("[早期历史摘要]")),
        "摘要失败时不应出现摘要消息");
    assertInstanceOfSystemAndRequest(sent);
  }

  private static void assertInstanceOfSystemAndRequest(List<ModelMessage> sent) {
    assertTrue(sent.get(0) instanceof SystemModelMessage, "首条仍是系统提示词");
    assertEquals("本次请求", sent.get(sent.size() - 1).getContent(), "本次请求仍在");
  }

  @Test
  void compactionFailureAlsoThrottlesRetries(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("a.txt"), "hello");
    int contextSize = 20_000;
    List<ModelMessage> history = bigHistory(contextSize);
    RecordingCompactor compactor = new RecordingCompactor();
    compactor.throwOnCall = true;
    ScriptedProtocol protocol =
        new ScriptedProtocol()
            .enqueue("读一下", List.of(new ToolCall("c1", "file_read", "{\"file_path\":\"a.txt\"}")))
            .enqueue("完成", List.of());

    AgentRunResult result = run(protocol, history, contextSize, compactor, workspace);

    assertFalse(result.isFailed());
    assertEquals(1, compactor.calls, "摘要失败后不得每轮重试（失败也计入节奏）");
  }

  @Test
  void keptSegmentDoesNotStartWithToolResult(@TempDir Path workspace) {
    int contextSize = 20_000;
    List<ModelMessage> history = bigHistory(contextSize);
    // 末尾 [assistant(tool_call), tool_result, 大 user]：segmentEnd 落在 tool_result 上，
    // 不跳过的话保留段会以 tool_result 开头（其 assistant 已在摘要段内 → 孤儿）。
    history.add(new AssistantModelMessage("", "", List.of(new ToolCall("c1", "t", "{}"))));
    history.add(new ToolModelMessage("结果", "c1", "t"));
    StringBuilder big = new StringBuilder(2_000);
    for (int i = 0; i < 2_000; i++) {
      big.append('汉');
    }
    history.add(new UserModelMessage("尾部用户：" + big));
    RecordingCompactor compactor = new RecordingCompactor();
    ScriptedProtocol protocol = new ScriptedProtocol().enqueue("完成", List.of());

    AgentRunResult result = run(protocol, history, contextSize, compactor, workspace);

    assertFalse(result.isFailed());
    assertEquals(1, compactor.calls);
    List<ModelMessage> sent = protocol.receivedMessages.get(0);
    int summaryIndex = -1;
    for (int i = 0; i < sent.size(); i++) {
      if (sent.get(i).getContent().startsWith("[早期历史摘要]")) {
        summaryIndex = i;
        break;
      }
    }
    assertTrue(summaryIndex >= 0, "应发生了摘要替换");
    assertFalse(
        sent.get(summaryIndex + 1) instanceof ToolModelMessage,
        "摘要消息之后不得紧跟 tool_result（保留段不得以 tool_result 开头）");
  }
}
