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

package com.tom.rv2ide.ai.agent.conversation;

import com.tom.rv2ide.ai.tool.api.ToolCall;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 助手消息：正文 + 推理内容 + 本轮请求的工具调用。
 *
 * <p>三者放在同一条条目里，因为它们在 {@code AgentSession} 中本就是一条
 * {@code AssistantModelMessage}——拆成多条会让「一轮」的边界在日志里丢失，
 * 而折叠历史时必须按轮重组。
 */
public final class AssistantMessageEntry extends ConversationEntry {

  static final String FIELD_CONTENT = "content";
  static final String FIELD_REASONING = "reasoningContent";
  static final String FIELD_TOOL_CALLS = "toolCalls";
  static final String FIELD_TOOL_CALL_ID = "id";
  static final String FIELD_TOOL_CALL_NAME = "name";
  static final String FIELD_TOOL_CALL_ARGS = "arguments";
  static final String FIELD_REFUSED = "refused";

  private final String content;
  private final String reasoningContent;
  private final List<ToolCall> toolCalls;
  private final boolean refused;

  public AssistantMessageEntry(
      String uuid,
      String parentUuid,
      long timestamp,
      String content,
      String reasoningContent,
      List<ToolCall> toolCalls) {
    this(uuid, parentUuid, timestamp, content, reasoningContent, toolCalls, false);
  }

  public AssistantMessageEntry(
      String uuid,
      String parentUuid,
      long timestamp,
      String content,
      String reasoningContent,
      List<ToolCall> toolCalls,
      boolean refused) {
    super(uuid, parentUuid, timestamp);
    this.content = content == null ? "" : content;
    this.reasoningContent = reasoningContent == null ? "" : reasoningContent;
    this.toolCalls =
        toolCalls == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(toolCalls));
    this.refused = refused;
  }

  public static AssistantMessageEntry create(
      String parentUuid, long timestamp, String content, String reasoningContent, List<ToolCall> toolCalls) {
    return new AssistantMessageEntry(null, parentUuid, timestamp, content, reasoningContent, toolCalls);
  }

  public static AssistantMessageEntry createRefused(
      String parentUuid, long timestamp, String content, String reasoningContent, List<ToolCall> toolCalls) {
    return new AssistantMessageEntry(null, parentUuid, timestamp, content, reasoningContent, toolCalls, true);
  }

  @Override
  public Type getType() {
    return Type.ASSISTANT;
  }

  public String getContent() {
    return content;
  }

  public String getReasoningContent() {
    return reasoningContent;
  }

  public List<ToolCall> getToolCalls() {
    return toolCalls;
  }

  /**
   * 该回复是否为「模型拒绝」。
   *
   * <p>被拒的回复**不进入模型上下文**（{@code ConversationHistory.toMessage} 跳过）——
   * 把拒绝原文喂回去，模型大概率顺着「我已经拒过了」继续拒；且拒绝文本对后续任务
   * 没有信息价值。UI 回放仍显示（用户有权看到完整过程），只是模型看不到。
   */
  public boolean isRefused() {
    return refused;
  }

  @Override
  protected void writeFields(JSONObject json) throws JSONException {
    json.put(FIELD_CONTENT, content);
    if (refused) {
      json.put(FIELD_REFUSED, true);
    }
    if (!reasoningContent.isEmpty()) {
      json.put(FIELD_REASONING, reasoningContent);
    }
    if (!toolCalls.isEmpty()) {
      JSONArray array = new JSONArray();
      for (ToolCall call : toolCalls) {
        JSONObject item = new JSONObject();
        item.put(FIELD_TOOL_CALL_ID, call.getId());
        item.put(FIELD_TOOL_CALL_NAME, call.getName());
        item.put(FIELD_TOOL_CALL_ARGS, call.getArguments());
        array.put(item);
      }
      json.put(FIELD_TOOL_CALLS, array);
    }
  }
}
