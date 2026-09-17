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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.protocol.AssistantModelMessage;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.protocol.ToolModelMessage;
import com.tom.rv2ide.ai.protocol.UserModelMessage;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 本地 token 估算的启发式边界。
 *
 * <p>断言的是**具体数值**而非"大于零"：估算公式一旦漂移，裁剪预算就会跟着漂移，
 * 而这种漂移不会以异常形式暴露，只会在长对话里表现为"提前压缩"或"请求超限"。
 */
final class TokenEstimatorTest {

  @Test
  void estimatesCjkAsOneTokenPerChar() {
    assertEquals(4, TokenEstimator.estimate("你好世界"));
    assertEquals(1, TokenEstimator.estimate("中"));
  }

  @Test
  void estimatesAsciiAsFourCharsPerTokenRoundingUp() {
    assertEquals(1, TokenEstimator.estimate("a"));
    assertEquals(1, TokenEstimator.estimate("abcd"));
    // 5 字符向上取整为 2——宁可高估也不低估
    assertEquals(2, TokenEstimator.estimate("abcde"));
    assertEquals(25, TokenEstimator.estimate("a".repeat(100)));
  }

  @Test
  void mixesCjkAndAscii() {
    // 2 个 CJK + 4 个 ASCII = 2 + 1
    assertEquals(3, TokenEstimator.estimate("你好abcd"));
  }

  @Test
  void countsFullwidthPunctuationAsCjk() {
    // 全角逗号与句号属于 HALFWIDTH_AND_FULLWIDTH_FORMS / CJK 标点，按 1 token/字
    assertEquals(2, TokenEstimator.estimate("，。"));
    assertEquals(1, TokenEstimator.estimate("。"));
  }

  @Test
  void handlesNullAndEmptyText() {
    assertEquals(0, TokenEstimator.estimate((String) null));
    assertEquals(0, TokenEstimator.estimate(""));
  }

  @Test
  void messageEstimateIncludesFixedOverhead() {
    // 固定开销 4 + "abcd" 的 1
    assertEquals(5, TokenEstimator.estimate(new UserModelMessage("abcd")));
    // 空消息也占开销——角色标记与分隔符是真实存在的
    assertEquals(4, TokenEstimator.estimate(new UserModelMessage("")));
  }

  @Test
  void messageEstimateCountsToolCallNameAndArguments() {
    AssistantModelMessage message =
        new AssistantModelMessage(
            "",
            "",
            List.of(new ToolCall("c1", "file_read", "{\"file_path\":\"/a\"}")));
    // 4 开销 + "file_read"(9→3) + 参数(18→5)
    assertEquals(12, TokenEstimator.estimate(message));
  }

  @Test
  void toolResultEstimateCountsToolCallIdAndName() {
    // 4 开销 + "abcd"(1) + "c1"(1) + "t"(1)
    assertEquals(7, TokenEstimator.estimate(new ToolModelMessage("abcd", "c1", "t")));
  }

  @Test
  void messageEstimateCountsReasoningContent() {
    // 推理内容同样进入请求体，漏算会让实际用量超出预算
    AssistantModelMessage withReasoning = new AssistantModelMessage("", "abcdefgh", List.of());
    assertEquals(4 + 2, TokenEstimator.estimate(withReasoning));
  }

  @Test
  void listEstimateIsSumOfMessages() {
    List<ModelMessage> messages =
        List.of(
            new UserModelMessage("abcd"), // 5
            new AssistantModelMessage("", "", List.of()), // 4
            new ToolModelMessage("abcd", "c1", "t")); // 7
    assertEquals(16, TokenEstimator.estimate(messages));
  }

  @Test
  void listEstimateHandlesNullAndEmpty() {
    assertEquals(0, TokenEstimator.estimate((List<ModelMessage>) null));
    assertEquals(0, TokenEstimator.estimate(List.of()));
  }

  @Test
  void nullMessageEstimatesToZero() {
    assertEquals(0, TokenEstimator.estimate((ModelMessage) null));
  }

  @Test
  void cjkIsNeverCheaperThanAsciiForSameCharCount() {
    // 这是"宁可高估"的方向性保证：同长度下 CJK 估算不得低于 ASCII
    assertTrue(
        TokenEstimator.estimate("中".repeat(50)) >= TokenEstimator.estimate("a".repeat(50)));
  }

  @Test
  void isToolMessageDistinguishesToolResults() {
    assertTrue(TokenEstimator.isToolMessage(new ToolModelMessage("x")));
    assertFalse(TokenEstimator.isToolMessage(new UserModelMessage("x")));
    assertFalse(TokenEstimator.isToolMessage(new AssistantModelMessage("x")));
  }
}
