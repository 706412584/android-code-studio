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

  @Test
  void toolResultImagePayloadIsCountedAsFixedCost() throws Exception {
    // 工具结果图片（image_generation 等）以 data URL 随请求发出，此前完全不计——
    // 12 张图的会话历史约 18MB，本地估算却接近零，硬压缩阈值永不触发。
    // 回归断言：带图片负载的消息必须比同文本不带图的消息至少多出固定图片成本。
    String tinyBase64 = "iVBORw0KGgo=";
    String imageJson =
        com.tom.rv2ide.ai.protocol.ImageInputPayload.imageResultJson("image/png", tinyBase64);

    ToolModelMessage plain = new ToolModelMessage("abcd", "c1", "t");
    ToolModelMessage withImage = new ToolModelMessage("abcd", "c1", "t", false, imageJson);

    assertTrue(
        TokenEstimator.estimate(withImage) >= TokenEstimator.estimate(plain) + 2000,
        "图片负载应按固定成本计入估算");
  }

  @Test
  void nonImageRawInputJsonIsNotChargedAsImage() {
    // 非图片 kind 的 rawInputJson 原样进请求、按普通文本估算；误加 2000 会双重收费。
    ToolModelMessage nonImage =
        new ToolModelMessage("abcd", "c1", "t", false, "{\"kind\":\"other\",\"x\":1}");
    ToolModelMessage plain = new ToolModelMessage("abcd", "c1", "t");
    assertEquals(TokenEstimator.estimate(plain), TokenEstimator.estimate(nonImage));
  }

  @Test
  void malformedImageMarkerIsNotChargedAndDoesNotThrow() {
    // 含 linecode_ 标记但结构不合法：hasImagePayload 会走到解析分支并返回 null
    // （或抛异常后被兜住）。这种输入不能让估算抛异常——估算跑在发请求的路径上，
    // 抛了整轮对话就断了。
    ToolModelMessage broken =
        new ToolModelMessage("abcd", "c1", "t", false, "{\"linecode_broken\":true}");
    ToolModelMessage justMarker = new ToolModelMessage("abcd", "c1", "t", false, "linecode_");
    ToolModelMessage plain = new ToolModelMessage("abcd", "c1", "t");

    assertEquals(TokenEstimator.estimate(plain), TokenEstimator.estimate(broken),
        "非法图片标记不应被当成图片计费");
    assertEquals(TokenEstimator.estimate(plain), TokenEstimator.estimate(justMarker));
  }
}
