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

  private AgentEvent(
      Type type, String message, ToolCall toolCall, ToolResult toolResult, List<ToolCall> toolCalls) {
    this.type = type;
    this.message = message == null ? "" : message;
    this.toolCall = toolCall;
    this.toolResult = toolResult;
    this.toolCalls = toolCalls == null ? Collections.emptyList() : toolCalls;
  }

  public static AgentEvent turnStarted(int turnIndex) {
    return new AgentEvent(Type.TURN_STARTED, "turn " + turnIndex, null, null, null);
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
