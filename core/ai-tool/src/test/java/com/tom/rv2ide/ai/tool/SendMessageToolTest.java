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

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.SessionMessagePort.Delivery;
import com.tom.rv2ide.ai.tool.SessionMessagePort.DeliveryResult;
import com.tom.rv2ide.ai.tool.SessionMessagePort.Target;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 跨会话消息工具的行为。
 *
 * <p><b>为什么值得单独测</b>：这个工具把一条消息送到**另一个** agent 的上下文里，
 * 而投递结果是异步的——工具返回后收件方还没看到。因此有三处极易出错且不会报错的地方：
 * <ul>
 *   <li><b>文案必须区分「已投递」与「会被处理」</b>：只说「已发送」会让模型接着假设
 *       对方已经知道并据此继续推理。测试钉住三种阶段（INJECTED / QUEUED / RESUMED）
 *       各自的文案含明确的时点说明。
 *   <li><b>不带 target 是「列目标」而不是「报错」</b>：模型需要先看有谁才能发对。
 *       若把它当参数错误，模型只能靠猜 id。
 *   <li><b>端口未接入必须报错</b>，不能静默成功——静默成功会让模型以为消息已送达。
 * </ul>
 */
final class SendMessageToolTest {

  /** 记录调用、返回预设结果的假端口。 */
  private static final class FakePort implements SessionMessagePort {
    final List<Target> targets = new ArrayList<>();
    DeliveryResult next = DeliveryResult.of(Delivery.INJECTED, "t", "");
    String lastTarget;
    String lastMessage;
    String lastFrom;

    @Override
    public List<Target> targets(String selfConversationId) {
      List<Target> out = new ArrayList<>();
      for (Target t : targets) {
        if (!t.getId().equals(selfConversationId)) {
          out.add(t);
        }
      }
      return out;
    }

    @Override
    public DeliveryResult send(String targetId, String message, String fromConversationId) {
      lastTarget = targetId;
      lastMessage = message;
      lastFrom = fromConversationId;
      return new DeliveryResult(next.getDelivery(), targetId, next.getDetail());
    }
  }

  private static ToolContext ctx(SessionMessagePort port, String conversationId) {
    return ToolContext.builder()
        .homePath(System.getProperty("java.io.tmpdir"))
        .conversationId(conversationId)
        .sessionMessage(port)
        .build();
  }

  private static String content(ToolResult result) {
    return result.getContent();
  }

  // ── 端口未接入 ────────────────────────────────────────────────────────

  @Test
  void missingPortFailsLoudly() {
    ToolResult result = new SendMessageTool().execute(new JSONObject(), ctx(null, "me"));
    assertTrue(result.isError(), "端口未接入必须报错，不能静默成功");
    assertTrue(content(result).contains("未接入"));
  }

  // ── 不带 target：列目标 ───────────────────────────────────────────────

  @Test
  void listingTargetsIsSuccessNotError() {
    FakePort port = new FakePort();
    port.targets.add(new Target("conv-a", "session", "修 bug", true));
    port.targets.add(new Target("agent:reviewer", "agent", "reviewer", false));

    ToolResult result = new SendMessageTool().execute(new JSONObject(), ctx(port, "me"));

    assertFalse(result.isError(), "列目标不是参数错误，模型需要它来找到正确的 id");
    String text = content(result);
    assertTrue(text.contains("conv-a"), "应列出会话 id");
    assertTrue(text.contains("agent:reviewer"), "应列出子 agent");
    assertTrue(text.contains("运行中"), "应标注运行态");
    assertTrue(text.contains("空闲"), "应标注空闲态");
  }

  @Test
  void listingExcludesSelf() {
    FakePort port = new FakePort();
    port.targets.add(new Target("me", "session", "我自己", true));
    port.targets.add(new Target("other", "session", "别的会话", false));

    String text = content(new SendMessageTool().execute(new JSONObject(), ctx(port, "me")));

    assertFalse(text.contains("\"me\"") || text.contains("- me "), "不应把自己列为可投递目标");
    assertTrue(text.contains("other"));
  }

  @Test
  void emptyTargetListIsStatedExplicitly() {
    String text = content(new SendMessageTool().execute(new JSONObject(), ctx(new FakePort(), "me")));
    assertTrue(text.contains("没有别的会话"), "无目标应明说，而不是返回空字符串");
  }

  // ── 参数校验 ──────────────────────────────────────────────────────────

  @Test
  void targetWithoutMessageIsAnError() {
    JSONObject input = new JSONObject().put("target", "conv-a");
    ToolResult result = new SendMessageTool().execute(input, ctx(new FakePort(), "me"));
    assertTrue(result.isError());
    assertTrue(content(result).contains("message 不能为空"));
  }

  @Test
  void overlongMessageIsRejected() {
    JSONObject input =
        new JSONObject().put("target", "conv-a").put("message", "x".repeat(33_000));
    ToolResult result = new SendMessageTool().execute(input, ctx(new FakePort(), "me"));
    assertTrue(result.isError());
    assertTrue(content(result).contains("过长"));
  }

  @Test
  void messageIsPassedThroughVerbatim() {
    FakePort port = new FakePort();
    JSONObject input =
        new JSONObject().put("target", "conv-a").put("message", "  两行\n文本  ");
    new SendMessageTool().execute(input, ctx(port, "me"));
    // trim 掉首尾空白，但内部换行必须保留——那是消息内容的一部分。
    assertEquals("两行\n文本", port.lastMessage);
    assertEquals("conv-a", port.lastTarget);
    assertEquals("me", port.lastFrom, "必须把发送方会话 id 传下去，收件方才能显示来源");
  }

  // ── 三种投递阶段的文案 ────────────────────────────────────────────────

  @Test
  void injectedCopyStatesWhenItWillBeSeen() {
    FakePort port = new FakePort();
    port.next = DeliveryResult.of(Delivery.INJECTED, "conv-a", "");
    ToolResult result =
        new SendMessageTool()
            .execute(
                new JSONObject().put("target", "conv-a").put("message", "hi"), ctx(port, "me"));
    String text = content(result);
    assertFalse(result.isError());
    assertTrue(text.contains("下一个安全点"), "必须说明何时被看到，否则模型会假设已处理");
  }

  @Test
  void queuedCopySaysItWaitsForNextRun() {
    FakePort port = new FakePort();
    port.next = DeliveryResult.of(Delivery.QUEUED, "conv-a", "");
    String text =
        content(
            new SendMessageTool()
                .execute(
                    new JSONObject().put("target", "conv-a").put("message", "hi"),
                    ctx(port, "me")));
    assertTrue(text.contains("空闲"), "空闲目标的文案要说明它没在跑");
    assertTrue(text.contains("下次运行"), "必须说明消息会等到下次运行才出现");
  }

  @Test
  void resumedCopySaysItContinuesInBackground() {
    FakePort port = new FakePort();
    port.next = DeliveryResult.of(Delivery.RESUMED, "agent:reviewer", "");
    String text =
        content(
            new SendMessageTool()
                .execute(
                    new JSONObject().put("target", "agent:reviewer").put("message", "继续"),
                    ctx(port, "me")));
    assertTrue(text.contains("已停止"), "续跑场景要说清它此前是停着的");
    assertTrue(text.contains("通知"), "必须告诉模型结果会异步回来——它不能立刻假设对方做完了");
  }

  @Test
  void notFoundSuggestsListingTargets() {
    FakePort port = new FakePort();
    port.next = DeliveryResult.of(Delivery.NOT_FOUND, "nope", "");
    ToolResult result =
        new SendMessageTool()
            .execute(new JSONObject().put("target", "nope").put("message", "hi"), ctx(port, "me"));
    assertTrue(result.isError());
    assertTrue(content(result).contains("不带 target"), "找不到时应指路列目标的用法");
  }

  // ── 工具元数据 ────────────────────────────────────────────────────────

  @Test
  void requiresConfirmationAndIsWriteOnly() {
    SendMessageTool tool = new SendMessageTool();
    assertTrue(tool.needsConfirmation(), "能影响另一个会话的行为，确认模式下应放行前询问");
    assertFalse(tool.isAllowedInReadonlyMode(), "只读模式不应放行——它等同于向别处注入指令");
    assertEquals("send_message", tool.getName());
  }
}
