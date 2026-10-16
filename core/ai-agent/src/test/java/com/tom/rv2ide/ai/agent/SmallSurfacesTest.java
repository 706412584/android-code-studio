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
 * along with AndroidCodeStudio.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.agent.builtin.BuiltinAgent;
import com.tom.rv2ide.ai.agent.builtin.BuiltinAgents;
import com.tom.rv2ide.ai.agent.context.RunContextManager;
import com.tom.rv2ide.ai.agent.context.TokenEstimator;
import com.tom.rv2ide.ai.agent.context.TokenUsageTracker;
import com.tom.rv2ide.ai.agent.export.ConversationExporter;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolInfo;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 小面积公共 API 的行为钉桩。
 *
 * <p>这些方法各自只有一两行，散落在多个类里，容易被「改了没人发现」。集中在一个文件里
 * 断言它们的**契约**（而非实现细节）：事件类型工厂产出正确的 Type、估算器把工具定义算进去、
 * 导出器把位置列表转成条目、内置 agent 的派生副本保持 id。
 */
final class SmallSurfacesTest {

  // ---- AgentEvent 工厂：每个 Type 都应有对应的构造入口 ----

  @Test
  void eventFactoriesProduceExpectedTypes() {
    assertSame(AgentEvent.Type.RUN_STARTED, AgentEvent.runStarted().getType());
    assertSame(AgentEvent.Type.RUN_FINISHED, AgentEvent.runFinished().getType());
    assertSame(AgentEvent.Type.TEXT_DELTA, AgentEvent.textDelta("x").getType());
    assertSame(AgentEvent.Type.REASONING_DELTA, AgentEvent.reasoningDelta("y").getType());
    assertSame(AgentEvent.Type.COMPLETED, AgentEvent.completed("done").getType());

    AgentEvent compacted = AgentEvent.contextCompacted(3, 1200);
    assertSame(AgentEvent.Type.CONTEXT_COMPACTED, compacted.getType());
    assertTrue(compacted.getMessage().contains("3"), "压缩事件应说明条数：" + compacted.getMessage());

    ToolCall call = new ToolCall("c1", "file_read", "{}");
    AgentEvent started = AgentEvent.toolStarted(call);
    assertSame(AgentEvent.Type.TOOL_STARTED, started.getType());
    assertSame(call, started.getToolCall());

    ToolResult result = ToolResult.of("c1", "file_read", "ok", false);
    AgentEvent finished = AgentEvent.toolFinished(call, result);
    assertSame(AgentEvent.Type.TOOL_FINISHED, finished.getType());
    assertSame(result, finished.getToolResult());

    AgentEvent turn = AgentEvent.turnFinished("text", List.of(call));
    assertSame(AgentEvent.Type.TURN_FINISHED, turn.getType());
    assertEquals(1, turn.getToolCalls().size());
  }

  @Test
  void progressEventCarriesCallIdAndNullsAreNormalized() {
    AgentEvent progress = AgentEvent.progress("call-9", "进行中");
    assertSame(AgentEvent.Type.PROGRESS, progress.getType());
    assertEquals("call-9", progress.getProgressCallId());
    // 无 id 的重载：进度归到「未标注」而不是丢掉。
    assertSame(AgentEvent.Type.PROGRESS, AgentEvent.progress("无 id 的进度").getType());

    // 空值规整：null 消息 / null 列表不应让事件自身为 null，否则消费方要处处判空。
    AgentEvent nulls = AgentEvent.turnFinished(null, null);
    assertEquals("", nulls.getMessage());
    assertNotNull(nulls.getToolCalls());
    assertTrue(nulls.getToolCalls().isEmpty());
  }

  // ---- TokenEstimator：工具定义必须算进窗口占用 ----

  @Test
  void estimateToolsCountsNameDescriptionAndSchema() {
    ToolInfo tool = new FakeTool("file_read", "读取文件内容");
    int only = TokenEstimator.estimateTools(List.of(tool));
    assertTrue(only > 0, "工具定义应产生正数估算");

    // 空/含 null 的列表不能抛。
    assertEquals(0, TokenEstimator.estimateTools(null));
    assertEquals(only, TokenEstimator.estimateTools(java.util.Arrays.asList(tool, null)),
        "null 元素应被跳过而不是计数");
  }

  @Test
  void estimateToolsGrowsWithSchemaSize() {
    ToolInfo small = new FakeTool("a", "b");
    int smallTokens = TokenEstimator.estimateTools(List.of(small));
    ToolInfo big = new FakeTool("a", "b" + "x".repeat(400));
    int bigTokens = TokenEstimator.estimateTools(List.of(big));
    assertTrue(bigTokens > smallTokens, "描述更长时估算应更大");
  }

  // ---- RunContextManager：只读访问器 ----

  @Test
  void contextManagerAccessorsReflectTrackerState() {
    TokenUsageTracker tracker = new TokenUsageTracker(0);
    RunContextManager manager = new RunContextManager(tracker, 500);

    assertEquals(500, manager.overheadTokens());
    // 未配置窗口 → contextSize 为 0（而非抛异常），currentTokens 从 0 起。
    assertEquals(0, manager.contextSize());
    assertEquals(0, manager.currentTokens());

    // 负开销被夹到 0：负值会让预算算出比实际更大的窗口，属于静默放宽限制。
    assertEquals(0, new RunContextManager(tracker, -100).overheadTokens());
  }

  // ---- ConversationExporter ----

  @Test
  void entriesOfHandlesNullInputGracefully() {
    // EntryLocation 的构造是包私有的（同包测试已覆盖其转换），这里只钉住
    // 「null / 空输入不炸」——导出路径会拿 null 位置列表调它。
    assertTrue(ConversationExporter.entriesOf(null).isEmpty());
    assertTrue(ConversationExporter.entriesOf(java.util.List.of()).isEmpty());
  }

  @Test
  void suggestedFileNameIsNonEmptyAndStripsUnsafeChars() {
    String name = ConversationExporter.suggestedFileName("标题：含/非法\\字符");
    assertNotNull(name);
    assertFalse(name.isEmpty(), "应给出可用文件名");
    assertFalse(name.contains("/"), "文件名不应含路径分隔符：" + name);
    assertFalse(name.contains("\\"), "文件名不应含反斜杠：" + name);
    assertEquals("conversation.md", ConversationExporter.suggestedFileName(null),
        "null 标题应回落到兜底名");
    assertEquals("conversation.md", ConversationExporter.suggestedFileName("   "),
        "空白标题应回落到兜底名");
    assertTrue(ConversationExporter.suggestedFileName("x".repeat(200)).length() <= 84,
        "超长标题应被截断，否则多数文件系统上创建会失败");
  }

  // ---- BuiltinAgent：派生副本与校验 ----

  @Test
  void derivedCopiesKeepIdAndApplyChanges() {
    BuiltinAgent preset = BuiltinAgents.all().get(0);
    BuiltinAgent edited = preset.withDescription("新的描述").withTools(List.of("file_read"));
    assertEquals(preset.getId(), edited.getId(), "派生副本必须保持同一 id（它是覆盖的键）");
    assertEquals("新的描述", edited.getDescription());
    assertEquals(List.of("file_read"), edited.getTools());
    assertFalse(edited.toString().isEmpty());
  }

  @Test
  void validationReportsEachFailureReason() {
    // id 为空
    assertTrue(new BuiltinAgent("", "n", "d", "p".repeat(50), List.of(), true)
        .validationError().contains("id"));
    // 名字为空
    assertTrue(new BuiltinAgent("x", "", "d", "p".repeat(50), List.of(), true)
        .validationError().contains("名字"));
    // 提示词过短
    assertTrue(new BuiltinAgent("x", "n", "d", "short", List.of(), true)
        .validationError().contains("提示词"));
    // 合法 → 空串
    assertEquals("",
        new BuiltinAgent("x", "n", "d", "p".repeat(50), List.of(), true).validationError());
  }

  @Test
  void sanitizeIdIsAppliedThroughConstructor() {
    // sanitizeId 本身是包私有（其专测在同包的 BuiltinAgentTest 里）；这里通过构造器
    // 观察其效果：id 要拼进工具名并作为覆盖存储的键，因此必须规整。
    assertEquals("my-agent", new BuiltinAgent("My Agent", "n", "d", "p".repeat(50),
        List.of(), true).getId());
    assertEquals("a-b-c", new BuiltinAgent("A.B C", "n", "d", "p".repeat(50),
        List.of(), true).getId());
    assertEquals("", new BuiltinAgent(null, "n", "d", "p".repeat(50),
        List.of(), true).getId());
  }

  /** 最小 ToolInfo 实现，供估算器测试。 */
  private static final class FakeTool implements ToolInfo {
    private final String name;
    private final String description;

    FakeTool(String name, String description) {
      this.name = name;
      this.description = description;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getDescription() {
      return description;
    }

    @Override
    public ToolCategory getCategory() {
      return ToolCategory.READ;
    }

    @Override
    public boolean needsConfirmation() {
      return false;
    }

    @Override
    public JSONObject getParameters() {
      return new JSONObject();
    }

    @Override
    public String promptSupplement(String executionMode) {
      return null;
    }

    @Override
    public JSONObject toJson() {
      return new JSONObject();
    }
  }
}
