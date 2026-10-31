/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent;

import com.tom.rv2ide.ai.agent.AgentEvent;
import com.tom.rv2ide.ai.agent.conversation.AssistantMessageEntry;
import com.tom.rv2ide.ai.agent.conversation.ConversationEntry;
import com.tom.rv2ide.ai.agent.conversation.ConversationStore;
import com.tom.rv2ide.ai.agent.conversation.ToolResultEntry;
import com.tom.rv2ide.ai.agent.conversation.UserMessageEntry;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把事件流落盘为会话日志，并向下游转发。
 *
 * <p><b>为什么从 {@link AgentOrchestrator} 的内部类提取出来</b>：子代理此前没有落盘，
 * 因为「把事件写成会话条目」这套逻辑一直挂在与编排器强耦合的内部类上——它有十几个
 * 字段（推理缓冲、步骤桶、在途调用表、实时态缓冲）都直接读写编排器的私有状态。
 * 子代理需要的是**同一套**逻辑写进**它自己**的会话，因此把「会话 id + 存储 + 锁」
 * 三个外部依赖抽成构造参数，逻辑一字不改地搬出来。主会话与子会话此后走同一条路径，
 * 不会出现「主会话能回放、子会话回放不了」这类只在子代理上暴露的缺陷。
 *
 * <p><b>实时态缓冲（textBuffer / reasoningBuffer / lastAction / pendingToolCalls）</b>
 * 仍在这里，但只对**主会话**有意义（视图重建时拉快照）。子代理没有视图，这些字段
 * 为空、不产生任何开销——快照方法对它们同样安全。
 */
final class ConversationPersistingListener implements AgentEvent.Listener {

  /** 无调用 id 的事件（文本形态调用）使用的桶键。 */
  static final String NO_CALL_ID = "";

  private final String conversationId;
  private final AgentEvent.Listener downstream;
  private final ConversationStore conversationStore;

  /**
   * 与「订阅 + 读历史」互斥的锁（见 {@link #onEvent}）。
   *
   * <p>由外部传入而不是自建：同一个会话的锁必须是同一把，否则「落盘」与
   * 「读历史」分属两把锁，竞态照旧。子代理没有订阅者，传一把私锁即可。
   */
  private final Object deliveryLock;

  /**
   * 本轮累积的推理文本。
   *
   * <p>模型以增量（{@code REASONING_DELTA}）给出思考过程，而 {@code AgentSession} 只转发
   * 增量、不累积。因此要落盘完整思考，只能在这里累积——它是事件流的唯一汇聚点。
   * 每轮 {@code TURN_FINISHED} 时写进 {@code AssistantMessageEntry.reasoningContent} 后清空。
   */
  private final StringBuilder reasoningBuffer = new StringBuilder();

  /**
   * 按**工具调用 id** 分桶的步骤行。
   *
   * <p><b>为什么必须分桶</b>：并行派遣多个子 agent 时，它们的 PROGRESS 行会交错
   * 到达。用一个共享列表的话，所有子 agent 的步骤会混在一起，并在**第一个**
   * TOOL_FINISHED 时被整批写进那张卡片——用户看到的是「第二个代理的进度显示在
   * 第一个工具下面」，且另一张卡片永远停在旧轮次（实测反馈）。
   *
   * <p>无 id 的事件（旧路径/文本形态调用）落到 {@link #NO_CALL_ID} 桶，
   * 行为与原来的单缓冲一致。
   */
  private final Map<String, List<String>> stepBuffers = new HashMap<>();

  /**
   * 正在执行中的工具调用（TOOL_STARTED 记入、TOOL_FINISHED 清空）。
   *
   * <p>为 {@link AgentOrchestrator.RunSnapshot#pendingToolCalls} 服务：视图切回
   * 「正在跑工具」的会话时，历史里没有那张卡片（只有 TOOL_FINISHED 落盘），
   * 需要这一份在途列表把卡片接回来。
   */
  private final Map<String, ToolCall> pendingToolCalls = new LinkedHashMap<>();

  /**
   * 本轮已产生的**正文**（TEXT_DELTA 累积）。
   *
   * <p>正文只在 TURN_FINISHED 时落盘，而一次运行可能持续几分钟。视图重建
   * （切全屏、系统回收、旋转）后回放磁盘历史时，本轮尚未结束的正文没有任何来源——
   * 用户看到的是「刚写了一半的回答消失了」。
   *
   * <p>不落盘：增量太碎（每个 token 一条），逐条写盘既慢又会把会话文件撑爆。
   */
  private final StringBuilder textBuffer = new StringBuilder();

  /** 本轮累积的**动作文案**（最近一条 PROGRESS）；状态条只表达「此刻在做什么」。 */
  private volatile String lastAction = "";

  ConversationPersistingListener(
      String conversationId,
      String userRequest,
      AgentEvent.Listener downstream,
      List<com.tom.rv2ide.ai.protocol.AttachmentMeta> attachments,
      ConversationStore conversationStore,
      Object deliveryLock) {
    this.conversationId = conversationId == null ? "" : conversationId;
    this.downstream = downstream;
    this.conversationStore = conversationStore;
    this.deliveryLock = deliveryLock == null ? new Object() : deliveryLock;
    // 附件元数据随用户消息一起落盘：不落的话，重新打开会话时气泡上的缩略图
    // 就全没了（模型那边不受影响——图片走 rawInputJson，与这里无关）。
    appendEntry(
        UserMessageEntry.create(
            null,
            System.currentTimeMillis(),
            userRequest,
            attachments == null ? Collections.emptyList() : attachments));
  }

  @Override
  public void onEvent(AgentEvent event) {
    // 「落盘 + 转发」整体持锁，与「订阅 + 读历史」互斥。
    // 少了这把锁就会出现丢消息：订阅方读到的历史里没有这个事件（还没落盘）、
    // 它又发生在订阅生效之前（收不到），于是那段内容在界面上凭空消失。
    synchronized (deliveryLock) {
      persist(event);
      if (downstream != null) {
        downstream.onEvent(event);
      }
    }
  }

  private void persist(AgentEvent event) {
    if (event == null) {
      return;
    }
    switch (event.getType()) {
      case TOOL_STARTED:
        // 记下在途调用，供视图重建时接回卡片（见 pendingToolCalls 注释）。
        if (event.getToolCall() != null) {
          pendingToolCalls.put(callKey(event.getToolCall()), event.getToolCall());
        }
        break;
      case REASONING_DELTA:
        // 累积本轮思考；不落盘（增量太碎），由 TURN_FINISHED 一并写入。
        // 加锁理由同 TEXT_DELTA：快照会读它。
        if (event.getMessage() != null) {
          synchronized (this) {
            reasoningBuffer.append(event.getMessage());
          }
        }
        break;
      case PROGRESS:
        // 累积**该调用**的步骤行；由对应的 TOOL_FINISHED 一并写入（见 stepBuffers 注释）。
        if (event.getMessage() != null && !event.getMessage().isEmpty()) {
          String key = event.getProgressCallId() == null ? NO_CALL_ID : event.getProgressCallId();
          stepBuffers.computeIfAbsent(key, k -> new ArrayList<>()).add(event.getMessage());
          // 状态条文案：只留最近一条。
          lastAction = event.getMessage();
        }
        break;
      case TEXT_DELTA:
        // 正文累积：**不落盘**，只作进程内实时态。加锁与 snapshot() 的读取互斥，
        // 否则快照可能读到 append 到一半的 StringBuilder。
        if (event.getMessage() != null) {
          synchronized (this) {
            textBuffer.append(event.getMessage());
          }
        }
        break;
      case TURN_FINISHED:
        appendEntry(
            AssistantMessageEntry.create(
                null,
                System.currentTimeMillis(),
                event.getMessage(),
                reasoningBuffer.toString(),
                event.getToolCalls()));
        // 本轮正文与推理已落盘，实时态缓冲不再需要——不清的话快照会与磁盘历史
        // **重复**渲染同一段内容（气泡出现两遍）。
        synchronized (this) {
          reasoningBuffer.setLength(0);
          textBuffer.setLength(0);
          lastAction = "";
        }
        break;
      case TOOL_FINISHED:
        if (event.getToolResult() != null) {
          // 只取**这个调用**的步骤行：并行子 agent 的进度不能混进别人的卡片。
          String key = callKey(event.getToolCall());
          List<String> steps = stepBuffers.remove(key);
          appendEntry(
              ToolResultEntry.create(
                  null,
                  System.currentTimeMillis(),
                  event.getToolResult(),
                  steps == null ? new ArrayList<>() : new ArrayList<>(steps)));
        }
        // 并行执行时 TOOL_FINISHED 可能与另一调用的 TOOL_STARTED 交错——
        // 按 id 精确移除，不会误清新调用。
        if (event.getToolCall() != null) {
          pendingToolCalls.remove(callKey(event.getToolCall()));
        }
        break;
      case USER_MESSAGE:
        // 运行中注入的用户消息（含跨会话 send_message 到达的）：落盘——不落盘的话
        // 它只活在内存里，运行结束后的回放就看不到这条消息，历史出现断层。
        //
        // 落盘**原文**（不含 "用户在你工作期间…" 包装）：包装只是给当轮请求的
        // 瞬时增强，与 cc-haha 的 attachment 语义一致——fold 时模型看到干净消息。
        if (event.getMessage() != null && !event.getMessage().isEmpty()) {
          appendEntry(
              UserMessageEntry.create(null, System.currentTimeMillis(), event.getMessage()));
        }
        break;
      default:
        break;
    }
  }

  /** 工具调用的桶键；无 id 时返回 {@link #NO_CALL_ID}（与无 id 的 PROGRESS 同桶）。 */
  private String callKey(ToolCall call) {
    if (call == null) {
      return NO_CALL_ID;
    }
    String id = call.getId();
    return id == null || id.isEmpty() ? NO_CALL_ID : id;
  }

  /** 见 {@link AgentOrchestrator#currentRunSnapshot}。调用方可能来自任意线程，读取做最小同步。 */
  AgentOrchestrator.RunSnapshot snapshot() {
    String text;
    String reasoning;
    String action;
    synchronized (this) {
      text = textBuffer.toString();
      reasoning = reasoningBuffer.toString();
      action = lastAction;
    }
    // 在途调用按开始顺序全部带上：视图重建时逐张恢复，后续 PROGRESS 才能归位。
    List<ToolCall> pending;
    synchronized (pendingToolCalls) {
      pending = new ArrayList<>(pendingToolCalls.values());
    }
    return new AgentOrchestrator.RunSnapshot(text, reasoning, action, pending);
  }

  /** 追加一条会话条目。包内可见：编排器的压缩路径也要写（{@code CompactionEntry}）。 */
  void appendEntry(ConversationEntry entry) {
    if (conversationId.isEmpty()) {
      return;
    }
    try {
      conversationStore.append(conversationId, entry);
    } catch (IOException | RuntimeException e) {
      // 单条落盘失败不应中断对话；内存中的会话仍在继续。
    }
  }
}
