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

package com.tom.rv2ide.ai.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.protocol.AssistantModelMessage;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.protocol.ToolModelMessage;
import com.tom.rv2ide.ai.protocol.UserModelMessage;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 压缩的降级语义：任何失败都返回 null 而不是抛错。
 *
 * <p>压缩是优化手段。它失败时调用方回退到纯裁剪即可继续工作；若它抛错，
 * 用户就会因为一个"可选的优化"而发不出消息——这是不可接受的失败模式。
 */
final class ContextCompactorTest {

  /** 记录收到的提示词，并按预设结果/异常响应。 */
  private static final class RecordingSummarizer implements ContextCompactor.Summarizer {
    private final String result;
    private final RuntimeException failure;
    private String receivedPrompt;

    RecordingSummarizer(String result) {
      this(result, null);
    }

    RecordingSummarizer(String result, RuntimeException failure) {
      this.result = result;
      this.failure = failure;
    }

    @Override
    public String summarize(String prompt) {
      this.receivedPrompt = prompt;
      if (failure != null) {
        throw failure;
      }
      return result;
    }
  }

  /** 抛受检异常的摘要器——接口声明了 throws Exception，实现方可能是网络调用。 */
  private static final class CheckedFailureSummarizer implements ContextCompactor.Summarizer {
    @Override
    public String summarize(String prompt) throws Exception {
      throw new java.io.IOException("网络不可达");
    }
  }

  @Test
  void returnsNullForNullInput() {
    assertNull(ContextCompactor.compact(null, prompt -> "摘要"));
  }

  @Test
  void returnsNullForEmptyInput() {
    assertNull(ContextCompactor.compact(List.of(), prompt -> "摘要"));
  }

  @Test
  void returnsNullForNullSummarizer() {
    assertNull(ContextCompactor.compact(List.of(new UserModelMessage("x")), null));
  }

  @Test
  void returnsTrimmedSummary() {
    RecordingSummarizer summarizer = new RecordingSummarizer("  要点一\n要点二  ");
    String summary =
        ContextCompactor.compact(List.of(new UserModelMessage("aaaa")), summarizer);
    assertEquals("要点一\n要点二", summary);
  }

  @Test
  void returnsNullWhenSummaryIsNull() {
    assertNull(
        ContextCompactor.compact(List.of(new UserModelMessage("aaaa")), new RecordingSummarizer(null)));
  }

  @Test
  void returnsNullWhenSummaryIsBlank() {
    assertNull(
        ContextCompactor.compact(
            List.of(new UserModelMessage("aaaa")), new RecordingSummarizer("   \n  ")));
  }

  @Test
  void returnsNullOnRuntimeFailure() {
    // 压缩失败不能中断主流程
    assertNull(
        ContextCompactor.compact(
            List.of(new UserModelMessage("aaaa")),
            new RecordingSummarizer(null, new IllegalStateException("boom"))));
  }

  @Test
  void returnsNullOnCheckedFailure() {
    assertNull(
        ContextCompactor.compact(List.of(new UserModelMessage("aaaa")), new CheckedFailureSummarizer()));
  }

  @Test
  void promptCarriesTheSummarizeInstruction() {
    RecordingSummarizer summarizer = new RecordingSummarizer("摘要");
    ContextCompactor.compact(List.of(new UserModelMessage("aaaa")), summarizer);

    assertNotNull(summarizer.receivedPrompt);
    assertTrue(
        summarizer.receivedPrompt.contains(ContextCompactor.SUMMARIZE_INSTRUCTION),
        "提示词必须包含完整指令，否则模型不知道要保留什么");
    assertTrue(summarizer.receivedPrompt.contains("用户提出的约束与偏好"));
    assertTrue(summarizer.receivedPrompt.contains("尚未完成的待办"));
  }

  @Test
  void promptLabelsEachRole() {
    RecordingSummarizer summarizer = new RecordingSummarizer("摘要");
    ContextCompactor.compact(
        List.of(
            new UserModelMessage("用户说的话"),
            new AssistantModelMessage("助手说的话"),
            new ToolModelMessage("工具说的话", "c1", "file_read")),
        summarizer);

    String prompt = summarizer.receivedPrompt;
    assertTrue(prompt.contains("用户: 用户说的话"));
    assertTrue(prompt.contains("助手: 助手说的话"));
    assertTrue(prompt.contains("工具结果: 工具说的话"));
    assertTrue(prompt.contains("=== 对话开始 ==="));
    assertTrue(prompt.contains("=== 对话结束 ==="));
  }

  @Test
  void promptIncludesToolCallNameAndArguments() {
    RecordingSummarizer summarizer = new RecordingSummarizer("摘要");
    ContextCompactor.compact(
        List.of(
            new AssistantModelMessage(
                "", "", List.of(new ToolCall("c1", "file_write", "{\"file_path\":\"/a\"}")))),
        summarizer);

    String prompt = summarizer.receivedPrompt;
    assertTrue(prompt.contains("[调用工具 file_write]"));
    assertTrue(prompt.contains("/a"));
  }

  @Test
  void promptMarksFailedToolResults() {
    RecordingSummarizer summarizer = new RecordingSummarizer("摘要");
    ContextCompactor.compact(
        List.of(new ToolModelMessage("出错了", "c1", "file_read", true)), summarizer);
    assertTrue(summarizer.receivedPrompt.contains("[失败]"));
  }

  @Test
  void promptOmitsToolNameSuffixWhenAbsent() {
    RecordingSummarizer summarizer = new RecordingSummarizer("摘要");
    ContextCompactor.compact(List.of(new ToolModelMessage("内容")), summarizer);
    assertFalse(summarizer.receivedPrompt.contains("（来自"));
  }

  @Test
  void promptAbbreviatesLongToolArguments() {
    RecordingSummarizer summarizer = new RecordingSummarizer("摘要");
    String longArgs = "{\"content\":\"" + "x".repeat(1000) + "\"}";
    ContextCompactor.compact(
        List.of(new AssistantModelMessage("", "", List.of(new ToolCall("c1", "file_write", longArgs)))),
        summarizer);

    String prompt = summarizer.receivedPrompt;
    // 截断到 300 字符加省略号——否则摘要请求自身就可能超限
    assertTrue(prompt.contains("…"));
    assertFalse(prompt.contains("x".repeat(500)), "长参数必须被截断");
  }

  @Test
  void promptKeepsShortToolArgumentsIntact() {
    RecordingSummarizer summarizer = new RecordingSummarizer("摘要");
    ContextCompactor.compact(
        List.of(new AssistantModelMessage("", "", List.of(new ToolCall("c1", "t", "{\"a\":1}")))),
        summarizer);
    assertTrue(summarizer.receivedPrompt.contains("{\"a\":1}"));
    assertFalse(summarizer.receivedPrompt.contains("…"));
  }

  @Test
  void buildPromptHandlesNullArgumentsInToolCall() {
    // ToolCall 会把 null 参数归一为 "{}"，这里确认渲染不会因空参数出错
    String prompt =
        ContextCompactor.buildPrompt(
            List.of(new AssistantModelMessage("", "", List.of(new ToolCall("c1", "t", null)))));
    assertTrue(prompt.contains("[调用工具 t] {}"));
  }

  @Test
  void summarizeInstructionDiscardsNoise() {
    // 指令必须明确要求丢弃噪声，否则摘要会退化为复述过程，省不下 token
    assertTrue(ContextCompactor.SUMMARIZE_INSTRUCTION.contains("寒暄"));
    assertTrue(ContextCompactor.SUMMARIZE_INSTRUCTION.contains("重复的工具输出"));
  }

  @Test
  void instructionIsPassedThroughEvenWithNoMessages() {
    // 空列表直接返回 null，不应触发摘要调用
    RecordingSummarizer summarizer = new RecordingSummarizer("摘要");
    ContextCompactor.compact(List.of(), summarizer);
    assertNull(summarizer.receivedPrompt);
  }

  @Test
  void promptRendersContentlessMessagesAsEmptyRoleLine() {
    String prompt =
        ContextCompactor.buildPrompt(List.of(new AssistantModelMessage("", "", List.of())));
    assertTrue(prompt.contains("助手: \n"), "无内容消息仍应保留角色行，供模型识别轮次边界");
  }

  @Test
  void promptOrderFollowsMessageOrder() {
    String prompt =
        ContextCompactor.buildPrompt(
            List.of(new UserModelMessage("第一句"), new UserModelMessage("第二句")));
    assertTrue(prompt.indexOf("第一句") < prompt.indexOf("第二句"));
  }
}
