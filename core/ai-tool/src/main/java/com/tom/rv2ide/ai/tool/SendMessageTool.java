/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from cc-haha (https://github.com/706412584/cc-haha) SendMessageTool,
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

package com.tom.rv2ide.ai.tool;

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.List;
import org.json.JSONObject;

/**
 * 给另一个 agent 发消息（照 cc-haha {@code SendMessageTool} 的语义裁剪）。
 *
 * <p><b>与 cc-haha 的差异</b>（都是刻意的）：
 * <ul>
 *   <li><b>不做 team / 广播</b>：cc-haha 的 {@code to: "*"} 是「广播给本 team 全体成员」，
 *       依赖 team roster。ACS 没有 team 概念（子 agent 由父 agent 直接派遣，无常设编制），
 *       所以广播没有明确的收件集合。这里只做点对点。
 *   <li><b>不做结构化协议消息</b>：cc-haha 的 {@code shutdown_request} /
 *       {@code plan_approval_response} 是 team 生命周期协议。ACS 没有「批准子代理计划」
 *       这个流程——权限确认走的是 {@code ToolPermissionService} 的弹窗。硬搬会带进一整套
 *       无人消费的状态机。跨会话只能发纯文本，这也是 cc-haha 自己的约束。
 *   <li><b>不做跨机器</b>：cc-haha 的 {@code bridge:} 目标经服务端转发，附带一整套安全
 *       考量（跨机器提示词注入不可被分类器放行）。ACS 本轮只做同进程。
 * </ul>
 *
 * <p><b>为什么必须让模型知道「发出去不等于被处理」</b>：注入是异步的——消息进了收件方的
 * 队列，它可能下一轮才看到，也可能要等它当前任务跑完。如果工具只回「已发送」，模型很容易
 * 接着假设对方已经知道并据此继续推理。因此返回文案显式说明消息处于哪个阶段。
 *
 * <p><b>权限</b>：发消息本身不改文件、不起进程，但它**能影响另一个会话的行为**——
 * 等同于以本会话名义向别处注入指令。归为 WRITE，确认模式下需用户放行（与 cc-haha 一致：
 * 它对跨会话目标强制 ask，且明确标注不可被分类器自动放行）。
 */
public final class SendMessageTool extends BaseTool {

  /** 收件人前缀：指向子 agent 而不是会话。 */
  static final String AGENT_PREFIX = "agent:";

  /** 消息长度上限：与 cc-haha 的 prompt 上限一致。 */
  private static final int MAX_MESSAGE_CHARS = 32_000;

  @Override
  public String getName() {
    return ToolNames.SEND_MESSAGE;
  }

  @Override
  public String getDescription() {
    return "给另一个 agent 发消息。收件人可以是另一个会话的 id（见下方 target 列表），"
        + "或 \"agent:<名字>\" 指向一个子 agent——它在跑就立刻收到，已停止就带着你的消息"
        + "从它原来的会话续跑。"
        + "收件方没有「忙」这个状态：消息不会打断它，而是在它下一个安全点被取走。"
        + "**发出去不等于被处理**——你可以在需要时用这个工具再问一次。"
        + "在决定发给谁之前，先不带 target 调用一次本工具，会列出当前可投递的对象。";
  }

  @Override
  public ToolCategory getCategory() {
    // 能影响另一个会话的行为，等同于向别处注入指令：只读模式下不放行。
    return ToolCategory.WRITE;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.AGENT;
  }

  @Override
  public boolean needsConfirmation() {
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "target",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "收件人：会话 id，或 \"agent:<名字>\"。省略则列出可投递的目标，"
                                + "不发送任何东西。"))
                .put(
                    "message",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "要发送的纯文本内容（target 非空时必填）")))
        .put("required", new org.json.JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    SessionMessagePort port = context == null ? null : context.getSessionMessage();
    if (port == null) {
      return error("跨会话消息功能未接入。");
    }

    String target = input.optString("target", "").trim();

    // 不带 target：列出可投递对象。这是「先看看有谁」的入口，避免模型猜 id。
    if (target.isEmpty()) {
      List<SessionMessagePort.Target> targets =
          port.targets(context == null ? "" : context.getConversationId());
      if (targets.isEmpty()) {
        return ok("当前没有别的会话或子 agent 可以投递。");
      }
      StringBuilder sb = new StringBuilder("可投递的目标：\n");
      for (SessionMessagePort.Target t : targets) {
        sb.append("- ")
            .append(t.getId())
            .append("  [")
            .append(t.getKind())
            .append(t.isRunning() ? " · 运行中" : " · 空闲")
            .append("]  ")
            .append(t.getTitle())
            .append('\n');
      }
      sb.append("\n用 target=<上面某个 id> + message=<内容> 发送。");
      return ok(sb.toString());
    }

    String message = input.optString("message", "").trim();
    if (message.isEmpty()) {
      return error("message 不能为空。");
    }
    if (message.length() > MAX_MESSAGE_CHARS) {
      return error(
          "message 过长（" + message.length() + " 字符，上限 " + MAX_MESSAGE_CHARS + " 字符）。"
              + "请精简到要点。");
    }

    SessionMessagePort.DeliveryResult result =
        port.send(target, message, context == null ? "" : context.getConversationId());

    switch (result.getDelivery()) {
      case INJECTED:
        return ok(
            "已投递给 "
                + target
                + "。它正在运行，会在下一个安全点（当前工具执行完、下一轮请求发出前）看到这条消息。"
                + (result.getDetail().isEmpty() ? "" : " " + result.getDetail()));
      case QUEUED:
        return ok(
            "已排队给 "
                + target
                + "。它当前空闲，消息会在它下次运行时作为开场出现在它的对话里。"
                + (result.getDetail().isEmpty() ? "" : " " + result.getDetail()));
      case RESUMED:
        return ok(
            target
                + " 已停止，正带着你的消息从它原来的会话续跑。"
                + "完成后你会收到通知。"
                + (result.getDetail().isEmpty() ? "" : " " + result.getDetail()));
      case NOT_FOUND:
        return error(
            "找不到收件人 "
                + target
                + "。它既不是现有会话，也不是已知的子 agent。"
                + "不带 target 再调用一次可以看当前有哪些目标。"
                + (result.getDetail().isEmpty() ? "" : "（" + result.getDetail() + "）"));
      case UNAVAILABLE:
      default:
        return error("跨会话消息功能当前不可用。" + result.getDetail());
    }
  }
}
