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

import com.tom.rv2ide.ai.tool.api.ToolResult;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 工具执行结果。
 *
 * <p>与 {@link AssistantMessageEntry} 分开存，而不是塞进助手条目：一个助手条目可能请求
 * 多个工具，各自结果独立到达、独立失败。分开后 {@code diffId}/{@code reviewState}
 * 也能随结果一起持久化，供 P0-4 的 Diff 回滚使用。
 */
public final class ToolResultEntry extends ConversationEntry {

  static final String FIELD_TOOL_CALL_ID = "toolCallId";
  static final String FIELD_TOOL_NAME = "toolName";
  static final String FIELD_CONTENT = "content";
  static final String FIELD_IS_ERROR = "isError";
  static final String FIELD_DIFF_ID = "diffId";
  static final String FIELD_REVIEW_STATE = "reviewState";
  static final String FIELD_REVIEW_MESSAGE = "reviewMessage";

  private final String toolCallId;
  private final String toolName;
  private final String content;
  private final boolean error;
  private final String diffId;
  private final String reviewState;
  private final String reviewMessage;

  public ToolResultEntry(String uuid, String parentUuid, long timestamp, ToolResult result) {
    super(uuid, parentUuid, timestamp);
    this.toolCallId = result == null ? "" : result.getToolCallId();
    this.toolName = result == null ? "" : result.getToolName();
    this.content = result == null ? "" : result.getContent();
    this.error = result != null && result.isError();
    this.diffId = result == null ? "" : result.getDiffId();
    this.reviewState = result == null ? "" : result.getReviewState();
    this.reviewMessage = result == null ? "" : result.getReviewMessage();
  }

  private ToolResultEntry(
      String uuid,
      String parentUuid,
      long timestamp,
      String toolCallId,
      String toolName,
      String content,
      boolean error,
      String diffId,
      String reviewState,
      String reviewMessage) {
    super(uuid, parentUuid, timestamp);
    this.toolCallId = toolCallId;
    this.toolName = toolName;
    this.content = content;
    this.error = error;
    this.diffId = diffId;
    this.reviewState = reviewState;
    this.reviewMessage = reviewMessage;
  }

  static ToolResultEntry fromFields(
      String uuid,
      String parentUuid,
      long timestamp,
      String toolCallId,
      String toolName,
      String content,
      boolean error,
      String diffId,
      String reviewState,
      String reviewMessage) {
    return new ToolResultEntry(
        uuid, parentUuid, timestamp, toolCallId, toolName, content, error, diffId, reviewState, reviewMessage);
  }

  public static ToolResultEntry create(String parentUuid, long timestamp, ToolResult result) {
    return new ToolResultEntry(null, parentUuid, timestamp, result);
  }

  @Override
  public Type getType() {
    return Type.TOOL_RESULT;
  }

  public String getToolCallId() {
    return toolCallId;
  }

  public String getToolName() {
    return toolName;
  }

  public String getContent() {
    return content;
  }

  public boolean isError() {
    return error;
  }

  public String getDiffId() {
    return diffId;
  }

  public String getReviewState() {
    return reviewState;
  }

  public String getReviewMessage() {
    return reviewMessage;
  }

  @Override
  protected void writeFields(JSONObject json) throws JSONException {
    json.put(FIELD_TOOL_CALL_ID, toolCallId);
    json.put(FIELD_TOOL_NAME, toolName);
    json.put(FIELD_CONTENT, content);
    json.put(FIELD_IS_ERROR, error);
    if (!diffId.isEmpty()) {
      json.put(FIELD_DIFF_ID, diffId);
    }
    if (!reviewState.isEmpty()) {
      json.put(FIELD_REVIEW_STATE, reviewState);
    }
    if (!reviewMessage.isEmpty()) {
      json.put(FIELD_REVIEW_MESSAGE, reviewMessage);
    }
  }
}
