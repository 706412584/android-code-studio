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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.agent.conversation.ConversationHistory;
import com.tom.rv2ide.ai.protocol.AssistantModelMessage;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.protocol.SystemModelMessage;
import com.tom.rv2ide.ai.protocol.ToolModelMessage;
import com.tom.rv2ide.ai.protocol.UserModelMessage;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 运行期裁剪的两条底线：系统提示词与本次用户请求永不消失，工具调用不与其结果拆散。
 *
 * <p>token 数按 {@link TokenEstimator} 手算：4 字符 ASCII 用户消息 = 5 tokens。
 */
final class RunContextManagerTest {

  private static UserModelMessage user(String text) {
    return new UserModelMessage(text);
  }

  /** 请求一次工具调用的助手消息：4 + name(1) + args "{}"(1) = 6 tokens。 */
  private static AssistantModelMessage assistantCalling() {
    return new AssistantModelMessage("", "", List.of(new ToolCall("c1", "t", "{}")));
  }

  /** 对应的工具结果：4 + content(1) + id(1) + name(1) = 7 tokens。 */
  private static ToolModelMessage toolResult() {
    return new ToolModelMessage("aaaa", "c1", "t");
  }

  private static RunContextManager manager(int contextSize, int overhead) {
    return new RunContextManager(new TokenUsageTracker(contextSize), overhead);
  }

  /** 组装 {@code [系统提示词, 历史..., 本次请求]} 并返回本次请求的下标。 */
  private static List<ModelMessage> messages(List<ModelMessage> history, ModelMessage request) {
    List<ModelMessage> messages = new ArrayList<>();
    messages.add(new SystemModelMessage("sys"));
    messages.addAll(history);
    messages.add(request);
    return messages;
  }

  /** 带图片负载的工具结果（rawInputJson 用协议层的真实格式，否则不会被识别为图）。 */
  private static ToolModelMessage imageResult(String callId, String content) {
    try {
      return new ToolModelMessage(
          content,
          callId,
          "vscreen",
          false,
          com.tom.rv2ide.ai.protocol.ImageInputPayload.imageResultJson("image/png", "QUJD"));
    } catch (org.json.JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean hasImage(ModelMessage message) {
    String raw = message.getRawInputJson();
    return raw != null && !raw.isEmpty();
  }

  @Test
  void limitsImagesEvenWhenWindowIsUnconfigured() {
    // 事故原型：窗口未配置时 fit 原样返回，几十张截图的 base64 全部驻留 → OOM。
    // 图片数量限制必须独立于 token 预算生效。
    List<ModelMessage> messages = new ArrayList<>();
    messages.add(new SystemModelMessage("sys"));
    int total = ConversationHistory.MAX_IMAGES_IN_HISTORY + 4;
    for (int i = 0; i < total; i++) {
      messages.add(assistantCalling());
      messages.add(imageResult("c1", "shot " + i));
    }
    int requestIndex = messages.size();
    messages.add(user("继续"));

    List<ModelMessage> fitted = manager(0, 0).fit(messages, requestIndex);

    long withImage = fitted.stream().filter(RunContextManagerTest::hasImage).count();
    assertEquals(
        ConversationHistory.MAX_IMAGES_IN_HISTORY,
        withImage,
        "运行中的消息列表也必须限制图片数量: " + withImage);
    // 文字结果不能丢——模型仍要能读到「截过图」这件事。
    assertTrue(
        fitted.stream().anyMatch(m -> m.getContent().startsWith("shot 0")),
        "被剥图的工具结果正文应保留");
  }

  @Test
  void protectedRequestImageIsKept() {
    // 本次用户请求附带的图是「当前问题」本身，不能被历史图片限额挤掉。
    List<ModelMessage> messages = new ArrayList<>();
    messages.add(new SystemModelMessage("sys"));
    for (int i = 0; i < ConversationHistory.MAX_IMAGES_IN_HISTORY; i++) {
      messages.add(assistantCalling());
      messages.add(imageResult("c1", "shot " + i));
    }
    int requestIndex = messages.size();
    try {
      messages.add(
          new UserModelMessage(
              "看这张图",
              com.tom.rv2ide.ai.protocol.ImageInputPayload.rawInputJson(
                  "看这张图", "image/png", "QUJD")));
    } catch (org.json.JSONException e) {
      throw new IllegalStateException(e);
    }

    List<ModelMessage> fitted = manager(0, 0).fit(messages, requestIndex);
    assertTrue(hasImage(fitted.get(requestIndex)), "本次请求的附件图必须保留");
  }

  @Test
  void unconfiguredWindowKeepsEverything() {
    List<ModelMessage> messages =
        messages(List.of(user("aaaa"), user("bbbb"), user("cccc")), user("dddd"));
    List<ModelMessage> fitted = manager(0, 0).fit(messages, 4);
    assertEquals(5, fitted.size());
  }

  @Test
  void keepsSystemPromptAndCurrentRequestEvenWhenOverBudget() {
    List<ModelMessage> messages =
        messages(List.of(user("aaaa"), user("bbbb"), user("cccc")), user("dddd"));

    // 窗口极小：历史必须被丢光，但系统提示词与本次请求必须留下
    List<ModelMessage> fitted = manager(100, 0).fit(messages, 4);

    assertInstanceOf(SystemModelMessage.class, fitted.get(0));
    assertEquals("sys", fitted.get(0).getContent());
    assertEquals("dddd", fitted.get(fitted.size() - 1).getContent(), "本次请求不能被裁掉");
    assertTrue(fitted.size() >= 2, "系统提示词与本次请求之间至少要有系统提示词本身");
  }

  @Test
  void dropsOldestHistoryFirst() {
    List<ModelMessage> messages =
        messages(List.of(user("aaaa"), user("bbbb"), user("cccc")), user("dddd"));
    // 本次请求下标 = 1(系统) + 3(历史) = 4
    int requestIndex = 4;

    // 窗口 100 - 预留 8192 为负 → 预算归零 → 历史全丢，只剩系统提示词与请求
    List<ModelMessage> fitted = manager(100, 0).fit(messages, requestIndex);
    assertEquals(2, fitted.size());

    // 窗口足够大时历史原样保留
    List<ModelMessage> roomy = manager(1_000_000, 0).fit(messages, requestIndex);
    assertEquals(5, roomy.size());
  }

  @Test
  void budgetSubtractsOverheadAndOutputReserve() {
    // 20000 - 1000(开销) - 8192(预留) = 10808，够装 3 条 5-token 历史消息
    List<ModelMessage> messages =
        messages(List.of(user("aaaa"), user("bbbb"), user("cccc")), user("dddd"));
    List<ModelMessage> fitted = manager(20_000, 1_000).fit(messages, 4);
    assertEquals(5, fitted.size(), "10808 的预算足以装下 15 tokens 的历史");
  }

  @Test
  void zeroBudgetDoesNotMeanUnlimited() {
    // 这是最容易搞反的一处：ContextTrimmer 把预算 <= 0 解释为"不限"，
    // 因此预算归零时必须绕开它，否则最该裁剪的场景反而不裁。
    List<ModelMessage> messages =
        messages(List.of(user("aaaa"), user("bbbb")), user("cccc"));
    List<ModelMessage> fitted = manager(8_000, 0).fit(messages, 3);

    assertEquals(2, fitted.size(), "预算归零时历史必须被丢弃，而非因 <=0 被当作不限");
    assertInstanceOf(SystemModelMessage.class, fitted.get(0));
    assertEquals("cccc", fitted.get(1).getContent());
  }

  @Test
  void keepsToolCallAndResultTogether() {
    List<ModelMessage> history =
        List.of(user("aaaa"), assistantCalling(), toolResult(), user("bbbb"));
    List<ModelMessage> messages = messages(history, user("cccc"));

    // 预算充足时调用与结果必须同进同出
    List<ModelMessage> fitted = manager(1_000_000, 0).fit(messages, 5);
    boolean sawCall = false;
    boolean sawResult = false;
    for (ModelMessage message : fitted) {
      if (message instanceof AssistantModelMessage && !message.getToolCalls().isEmpty()) {
        sawCall = true;
      }
      if (message instanceof ToolModelMessage) {
        sawResult = true;
      }
    }
    assertEquals(sawCall, sawResult, "工具调用与其结果不得分离");
  }

  @Test
  void dropsLeadingOrphanToolResult() {
    // 磁盘上的旧历史可能以孤立工具结果开头；以它开头发请求会被服务商判 400
    List<ModelMessage> messages = messages(List.of(toolResult(), user("aaaa")), user("bbbb"));
    List<ModelMessage> fitted = manager(1_000_000, 0).fit(messages, 3);

    for (ModelMessage message : fitted) {
      assertFalse(message instanceof ToolModelMessage, "开头的孤立工具结果必须被丢弃");
    }
  }

  @Test
  void resultIsACopyNotTheSameList() {
    List<ModelMessage> messages =
        messages(List.of(user("aaaa")), user("bbbb"));
    List<ModelMessage> fitted = manager(0, 0).fit(messages, 2);
    fitted.clear();
    assertEquals(3, messages.size(), "裁剪结果不应把调用方传入的列表暴露出去");
  }

  @Test
  void requestIndexOutOfRangeIsClamped() {
    // 循环末尾会传入 messages.size()，此时"本次请求"已不在列表里；
    // 夹取到末位可避免 IndexOutOfBounds——裁剪永远不该让对话崩掉。
    List<ModelMessage> messages =
        messages(List.of(user("aaaa")), user("bbbb"));
    List<ModelMessage> fitted = manager(1_000_000, 0).fit(messages, 99);
    assertEquals(3, fitted.size());
  }

  @Test
  void emptyInputYieldsEmptyResult() {
    assertTrue(manager(1_000, 0).fit(null, 0).isEmpty());
    assertTrue(manager(1_000, 0).fit(List.of(), 0).isEmpty());
  }

  @Test
  void singleMessageListIsReturnedAsIs() {
    List<ModelMessage> messages = new ArrayList<>();
    messages.add(new SystemModelMessage("sys"));
    assertEquals(1, manager(100, 0).fit(messages, 0).size());
  }

  // ── 反应式收缩（shrinkForOverflow，服务端报超限后的补救路径）──

  @Test
  void shrinkForOverflowShrinksInPlaceAndKeepsAnchors() {
    // 造一段可收缩历史：若干用户消息，每条约 25 tokens（100 ASCII 字符）。
    List<ModelMessage> history = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      history.add(user(("msg-" + i + "-").concat("x".repeat(100))));
    }
    List<ModelMessage> messages = messages(history, user("request"));
    int requestIndex = messages.size() - 1;
    int before = messages.size();

    // 窗口 1000：绝对预算 = 1000 - 0 - RESERVE_OUTPUT_TOKENS。
    // 目标取 min(current/2, absolute/2)，必然远小于当前量 → 应发生实际收缩。
    RunContextManager mgr = manager(1_000, 0);
    assertTrue(mgr.shrinkForOverflow(messages, requestIndex), "应发生实际收缩");

    // 就地修改：同一个列表对象变小。
    assertTrue(messages.size() < before, "消息数应减少");
    // 首条仍是系统提示词、末条仍是本次请求（两条锚点不可丢）。
    assertInstanceOf(SystemModelMessage.class, messages.get(0));
    assertEquals("request", messages.get(messages.size() - 1).getContent());
  }

  @Test
  void shrinkForOverflowReturnsFalseWhenNothingToShrink() {
    // 只有系统提示词 + 请求两条：无可收缩空间，必须返回 false（调用方据此不重试）。
    List<ModelMessage> messages = messages(List.of(), user("req"));
    assertFalse(manager(1_000, 0).shrinkForOverflow(messages, 1), "无可收缩空间应返回 false");
    assertFalse(manager(1_000, 0).shrinkForOverflow(null, 0), "null 输入应返回 false");
  }

  @Test
  void shrinkForOverflowUsesAbsoluteBudgetWhenEstimateIsLow() {
    // 模拟「本地估算低估」：窗口很小（绝对预算小），历史很长但每条 token 少——
    // current/2 可能仍大于绝对预算。修复后 target 取 min(current/2, absolute/2)，
    // 保证收缩幅度以「窗口的一半」为下界，不会出现空操作。
    List<ModelMessage> history = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      history.add(user("aaaa")); // 每条 5 tokens，总量 250
    }
    List<ModelMessage> messages = messages(history, user("req"));
    int requestIndex = messages.size() - 1;

    // 窗口 100：绝对预算 = 100 - 0 - RESERVE_OUTPUT_TOKENS（远小于估算总量 250+），
    // current/2 ≈ 130 但 absolute/2 更小 → 收缩到保底集。
    // 保底 = 系统提示词 + 本次请求 + ContextTrimmer.MIN_KEEP_MESSAGES(2) 条最近历史 = 4 条。
    RunContextManager mgr = manager(100, 0);
    assertTrue(mgr.shrinkForOverflow(messages, requestIndex), "小窗口下应发生收缩");
    assertTrue(
        messages.size() <= 4,
        "应收缩到锚点 + MIN_KEEP_MESSAGES 条历史（实际 " + messages.size() + "）");
  }
}
