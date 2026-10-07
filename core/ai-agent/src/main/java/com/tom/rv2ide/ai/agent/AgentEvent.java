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

package com.tom.rv2ide.ai.agent;

import com.tom.rv2ide.ai.tool.api.ToolCall;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.Collections;
import java.util.List;

/**
 * agent 循环向外报告的事件。
 *
 * <p><b>设计说明</b>：上游 LineCode Pro 的循环直接调用一个庞大的 {@code Host} 接口
 * （含 {@code render()}、{@code syncModePermission()}、进度会话等 UI 概念），
 * 使循环无法脱离 Android 与具体界面测试。
 *
 * <p>这里改为单一的事件流：循环只负责产生事件，如何展示由调用方决定。
 * 好处是循环本身是纯 Java、可单测；UI 层只需实现一个 switch 来渲染。
 */
public final class AgentEvent {

  /** 事件类型。 */
  public enum Type {
    /** 一轮模型请求开始。 */
    TURN_STARTED,
    /**
     * 一条用户消息进入本会话。
     *
     * <p><b>为什么需要它</b>：会话级广播下同一会话可能有多个视图在显示。发起方当然
     * 知道自己发了什么（它本地就画了气泡），但**订阅方**（A 在跑、用户随后打开 B）
     * 只能靠事件流得知——否则 B 看到的对话里，用户那一侧的消息是缺失的，
     * 读起来像助手在自言自语。
     */
    USER_MESSAGE,
    /**
     * 一次运行的**起点分界**（仅由 orchestrator 产生，不是模型轮次）。
     *
     * <p><b>为什么需要它</b>：视图可能在运行中途才订阅（用户打开另一个入口）。
     * 它回放的历史里，本轮已有的文本会表现为一条**已完成**的助手气泡；若没有分界，
     * 订阅后到达的增量会继续追加进那个气泡，把「已经说完的一段」和「正在说的新一段」
     * 混在一起，用户看到的是两段拼接的乱码。
     *
     * <p>视图收到它即重置本会话的渲染指针（流式气泡 / 思维块 / 工具卡片），
     * 下一个增量因此另起一段。
     */
    RUN_STARTED,
    /**
     * 一次运行的**终点**（仅由 orchestrator 产生）。
     *
     * <p><b>为什么需要它</b>：视图要把「停止」键收掉、把状态条停掉，而运行可能是
     * **另一个入口**发起的——那种情况下本视图没有对应的 job，收不到任何「我的协程
     * 结束了」的信号。用事件表达后，任何订阅者都能一致地收尾。
     *
     * <p>若该会话还有排队请求，orchestrator 会在发出它之后紧接着发起下一条，
     * 于是订阅者会先收到本事件、再收到新的 {@link #RUN_STARTED}——状态先落后起，
     * 结果正确（中间那次闪烁不可见，两者在同一帧内）。
     */
    RUN_FINISHED,
    /** 模型输出的文本增量（流式）。 */
    TEXT_DELTA,
    /** 模型的推理过程增量（部分模型支持）。 */
    REASONING_DELTA,
    /** 一轮模型响应完成，携带本轮的完整文本与解析出的工具调用。 */
    TURN_FINISHED,
    /** 即将执行一个工具调用。 */
    TOOL_STARTED,
    /** 一个工具调用执行完成。 */
    TOOL_FINISHED,
    /**
     * 早期历史被压缩为摘要，替换掉了更早的消息。
     *
     * <p>必须作为事件上报：压缩是**有损**的，用户有权知道"模型已经看不到某些早期对话了"。
     * 静默压缩会让用户困惑于模型为何遗忘先前说过的要求。
     */
    CONTEXT_COMPACTED,

    /**
     * 工具 / 子 agent 的步骤级进度播报（面向用户的单行文本）。
     *
     * <p><b>为什么需要它</b>：长任务（子 agent 调查、长命令）在两次工具调用之间可能
     * 沉默几十秒，界面看起来和卡死一样。这条事件让「此刻在做什么」可见。
     *
     * <p><b>不落盘</b>：进度是瞬时状态，回放历史时没有意义；持久化只保留结论。
     */
    PROGRESS,

    /**
     * 流中断后即将重发本次请求。
     *
     * <p>UI 收到它必须**丢弃本轮已渲染的部分输出**——重发会产生一份全新的回答，
     * 旧的部分留在列表里就会出现两段内容并存。
     */
    STREAM_RETRYING,
    /** 循环正常结束（模型不再请求工具）。 */
    COMPLETED,
    /** 循环因取消、超预算或错误而终止。 */
    FAILED
  }

  private final Type type;
  private final String message;
  private final ToolCall toolCall;
  private final ToolResult toolResult;
  private final List<ToolCall> toolCalls;

  /** 仅 {@link Type#STREAM_RETRYING} 有值：第几次重试（从 1 开始）。 */
  private final int retryAttempt;

  /** 仅 {@link Type#STREAM_RETRYING} 有值：重试上限。 */
  private final int retryMaxAttempts;

  /** 仅 {@link Type#STREAM_RETRYING} 有值：本次退避等待毫秒数。 */
  private final long retryDelayMs;

  /** 仅 {@link Type#STREAM_RETRYING} 有值：中断原因（面向用户）。 */
  private final String retryReason;

  private AgentEvent(
      Type type, String message, ToolCall toolCall, ToolResult toolResult, List<ToolCall> toolCalls) {
    this(type, message, toolCall, toolResult, toolCalls, 0, 0, 0L, "");
  }

  private AgentEvent(
      Type type,
      String message,
      ToolCall toolCall,
      ToolResult toolResult,
      List<ToolCall> toolCalls,
      int retryAttempt,
      int retryMaxAttempts,
      long retryDelayMs,
      String retryReason) {
    this.type = type;
    this.message = message == null ? "" : message;
    this.toolCall = toolCall;
    this.toolResult = toolResult;
    this.toolCalls = toolCalls == null ? Collections.emptyList() : toolCalls;
    this.retryAttempt = retryAttempt;
    this.retryMaxAttempts = retryMaxAttempts;
    this.retryDelayMs = retryDelayMs;
    this.retryReason = retryReason == null ? "" : retryReason;
  }

  public static AgentEvent turnStarted(int turnIndex) {
    return new AgentEvent(Type.TURN_STARTED, "turn " + turnIndex, null, null, null);
  }

  /** 一条用户消息（内容在 {@link #getMessage()}）。 */
  public static AgentEvent userMessage(String text) {
    return new AgentEvent(Type.USER_MESSAGE, text, null, null, null);
  }

  /** 一次运行的起点分界。见 {@link Type#RUN_STARTED}。 */
  public static AgentEvent runStarted() {
    return new AgentEvent(Type.RUN_STARTED, "", null, null, null);
  }

  /** 一次运行的终点。见 {@link Type#RUN_FINISHED}。 */
  public static AgentEvent runFinished() {
    return new AgentEvent(Type.RUN_FINISHED, "", null, null, null);
  }

  public static AgentEvent textDelta(String delta) {
    return new AgentEvent(Type.TEXT_DELTA, delta, null, null, null);
  }

  public static AgentEvent reasoningDelta(String delta) {
    return new AgentEvent(Type.REASONING_DELTA, delta, null, null, null);
  }

  public static AgentEvent turnFinished(String text, List<ToolCall> calls) {
    return new AgentEvent(Type.TURN_FINISHED, text, null, null, calls);
  }

  public static AgentEvent toolStarted(ToolCall call) {
    return new AgentEvent(Type.TOOL_STARTED, "", call, null, null);
  }

  public static AgentEvent toolFinished(ToolCall call, ToolResult result) {
    return new AgentEvent(Type.TOOL_FINISHED, "", call, result, null);
  }

  public static AgentEvent completed(String finalText) {
    return new AgentEvent(Type.COMPLETED, finalText, null, null, null);
  }

  /**
   * 早期历史已被压缩为摘要。
   *
   * @param replacedMessages 被摘要取代的消息条数
   * @param summaryTokens 摘要占用的估算 token 数
   */
  public static AgentEvent contextCompacted(int replacedMessages, int summaryTokens) {
    return new AgentEvent(
        Type.CONTEXT_COMPACTED,
        "已压缩 " + replacedMessages + " 条早期消息为摘要（" + summaryTokens + " tokens）",
        null,
        null,
        null);
  }

  /**
   * 流中断、即将重发。
   *
   * <p>四个字段**同时**保留在结构化字段与 {@link #getMessage()} 里：UI 需要前者
   * 才能渲染「第 2/10 次 · 3 秒后」这种分栏文案（自己解析 message 里的数字很脆），
   * 而 message 仍是一份可读的完整描述，供日志与不关心结构的调用方使用。
   *
   * @param attempt 第几次重试（从 1 开始）
   * @param maxAttempts 重试上限
   * @param delayMs 本次退避等待时长
   * @param reason 中断原因（面向用户）
   */
  public static AgentEvent streamRetrying(
      int attempt, int maxAttempts, long delayMs, String reason) {
    String safeReason = reason == null ? "" : reason;
    return new AgentEvent(
        Type.STREAM_RETRYING,
        "连接中断，正在重试 "
            + attempt
            + "/"
            + maxAttempts
            + "（等待 "
            + Math.max(1L, delayMs / 1000L)
            + "s）："
            + safeReason,
        null,
        null,
        null,
        attempt,
        maxAttempts,
        delayMs,
        safeReason);
  }

  /**
   * 步骤级进度播报。见 {@link Type#PROGRESS}。
   *
   * @param message 面向用户的单行描述（内容在 {@link #getMessage()}）
   */
  public static AgentEvent progress(String message) {
    return new AgentEvent(Type.PROGRESS, message, null, null, null);
  }

  /** 第几次重试（从 1 开始）；非重试事件为 0。 */
  public int getRetryAttempt() {
    return retryAttempt;
  }

  /** 重试上限；非重试事件为 0。 */
  public int getRetryMaxAttempts() {
    return retryMaxAttempts;
  }

  /** 本次退避等待毫秒数；非重试事件为 0。 */
  public long getRetryDelayMs() {
    return retryDelayMs;
  }

  /** 中断原因；非重试事件为空串。 */
  public String getRetryReason() {
    return retryReason;
  }

  public static AgentEvent failed(String reason) {
    return new AgentEvent(Type.FAILED, reason, null, null, null);
  }

  public Type getType() {
    return type;
  }

  public String getMessage() {
    return message;
  }

  public ToolCall getToolCall() {
    return toolCall;
  }

  public ToolResult getToolResult() {
    return toolResult;
  }

  public List<ToolCall> getToolCalls() {
    return toolCalls;
  }

  /** 事件的接收者。实现方需保证线程安全——事件可能来自流式回调线程。 */
  public interface Listener {
    void onEvent(AgentEvent event);
  }
}
