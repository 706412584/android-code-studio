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

import java.util.List;

/**
 * 跨会话/跨子代理发消息端口（照 cc-haha 的 {@code SendMessageTool} 语义）。
 *
 * <p><b>解决什么问题</b>：agent 之间此前完全无法通气。用户在 A 会话里说「去问一下 B 会话
 * 那边的进度」，模型只能回答「我做不到」——而两个会话都在同一进程里，只是没有通路。
 * 子 agent 同理：它在后台跑，父 agent 想知道进展只能等它结束。
 *
 * <p><b>核心语义：不打断</b>。cc-haha 的原话是「A listed peer is alive and will process
 * your message — no "busy" state; messages enqueue and drain at the receiver's next tool
 * round」。因此这里没有「收件方忙」这种失败态：
 * <ul>
 *   <li>收件方正在跑 → 消息进它的注入槽，在「上一轮工具执行完、下一轮请求前」被取走
 *       （那个位置是唯一不会破坏 tool_use/tool_result 配对的安全点）；
 *   <li>收件方空闲（有自己的会话文件）→ 消息持久化到待处理队列，下次该会话运行时
 *       作为开场注入；
 *   <li>收件方不存在 → 明确报错，<b>不</b>创建孤儿队列。
 * </ul>
 *
 * <p><b>为什么是接口</b>：与 {@link BackgroundShellPort} / {@link TerminalPort} 同一模式。
 * 本模块纯 JVM、零 Android 依赖，而「会话」「编排」都在 ai-agent 与 app 层，且
 * ai-agent 依赖本模块——反向依赖会成环。所以这里只定义中性契约，由 app 层的
 * {@code AgentOrchestrator} 适配实现（它已持有 {@code activeSessions} 与排队队列）。
 */
public interface SessionMessagePort {

  /** 一次投递的结果分类，工具层据此生成不同的用户可见文案。 */
  enum Delivery {
    /** 收件方正在跑，消息已进它的注入槽，本轮或下一轮就能看到。 */
    INJECTED,
    /** 收件方空闲，消息已持久化排队，下次该会话运行时注入。 */
    QUEUED,
    /** 收件方已停止的子 agent，正从它的会话续跑（第 3 期）。 */
    RESUMED,
    /** 收件人不存在（既不是会话，也不是已知的 agent）。 */
    NOT_FOUND,
    /** 端口不可用（未接入）。 */
    UNAVAILABLE,
  }

  /** 一次投递的结果。 */
  final class DeliveryResult {
    private final Delivery delivery;
    private final String target;
    /** 补充说明（如「它是子 agent，已在后台续跑」）；无补充时为空串。 */
    private final String detail;

    public DeliveryResult(Delivery delivery, String target, String detail) {
      this.delivery = delivery == null ? Delivery.NOT_FOUND : delivery;
      this.target = target == null ? "" : target;
      this.detail = detail == null ? "" : detail;
    }

    public static DeliveryResult of(Delivery delivery, String target, String detail) {
      return new DeliveryResult(delivery, target, detail);
    }

    public Delivery getDelivery() {
      return delivery;
    }

    public String getTarget() {
      return target;
    }

    public String getDetail() {
      return detail;
    }
  }

  /**
   * 一个可投递目标的只读摘要，供模型在决定发给谁之前查看。
   */
  final class Target {
    private final String id;
    private final String kind;
    private final String title;
    private final boolean running;

    /**
     * @param id 投递时用作 {@code to} 的值（会话 id，或 {@code agent:<name>}）
     * @param kind {@code "session"} 或 {@code "agent"}
     * @param title 人类可读标题（会话标题 / 代理名）
     * @param running 此刻是否正在跑
     */
    public Target(String id, String kind, String title, boolean running) {
      this.id = id == null ? "" : id;
      this.kind = kind == null ? "session" : kind;
      this.title = title == null ? "" : title;
      this.running = running;
    }

    public String getId() {
      return id;
    }

    public String getKind() {
      return kind;
    }

    public String getTitle() {
      return title;
    }

    /** 正在跑的目标收到消息会立刻在下个安全点看到；空闲的则排队。 */
    public boolean isRunning() {
      return running;
    }
  }

  /**
   * 列出可投递的目标：除本会话外的全部会话 + 已知的子 agent。
   *
   * <p>无目标时返回空列表（不是 null）——调用方据此区分「没有别的会话」与「端口坏了」。
   *
   * @param selfConversationId 调用方自己的会话 id（结果中排除它，避免自己发给自己）
   */
  List<Target> targets(String selfConversationId);

  /**
   * 投递一条消息。
   *
   * <p><b>不抛异常</b>：失败一律表达为 {@link Delivery#NOT_FOUND} / {@link Delivery#UNAVAILABLE}
   * 的结果——这是 agent 循环不中断的前提，错误要回灌给模型而不是炸掉整轮对话。
   *
   * @param targetId 收件人：会话 id，或 {@code agent:&lt;name&gt;}
   * @param message 纯文本消息
   * @param fromConversationId 发送方会话 id，供收件方显示来源
   */
  DeliveryResult send(String targetId, String message, String fromConversationId);
}
