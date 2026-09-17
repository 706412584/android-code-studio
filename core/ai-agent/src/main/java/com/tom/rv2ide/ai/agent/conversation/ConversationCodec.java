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
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 条目与 JSON 之间的编解码。
 *
 * <p><b>未知类型与坏行一律跳过，不抛异常</b>：日志是 append-only 且可能被外部编辑
 * （ACS 是 IDE，用户可能手动打开这个文件）。某一行损坏不应让整个会话不可读——
 * 这与会话日志的"尽力而为"语义一致。
 */
public final class ConversationCodec {

  private ConversationCodec() {}

  /**
   * 解析一行 JSON 为条目。
   *
   * @param line 一行 JSON 文本（不含换行）
   * @return 解析结果；行为空、格式错误或类型未知时返回 {@code null}
   */
  public static ConversationEntry parse(String line) {
    if (line == null || line.trim().isEmpty()) {
      return null;
    }
    JSONObject json;
    try {
      json = new JSONObject(line);
    } catch (JSONException e) {
      return null;
    }
    ConversationEntry.Type type =
        ConversationEntry.Type.fromWireName(json.optString(ConversationEntry.FIELD_TYPE, ""));
    if (type == null) {
      return null;
    }
    String uuid = json.optString(ConversationEntry.FIELD_UUID, "");
    String parentUuid = json.optString(ConversationEntry.FIELD_PARENT_UUID, "");
    long timestamp = json.optLong(ConversationEntry.FIELD_TIMESTAMP, 0L);
    try {
      return build(type, json, uuid, parentUuid, timestamp);
    } catch (JSONException e) {
      return null;
    }
  }

  private static ConversationEntry build(
      ConversationEntry.Type type,
      JSONObject json,
      String uuid,
      String parentUuid,
      long timestamp)
      throws JSONException {
    switch (type) {
      case SESSION_META:
        return new SessionMetaEntry(
            uuid,
            parentUuid,
            timestamp,
            json.optString(SessionMetaEntry.FIELD_CWD, ""),
            json.optString(SessionMetaEntry.FIELD_MODEL, ""),
            json.optString(SessionMetaEntry.FIELD_PERMISSION_MODE, ""));
      case USER:
        return new UserMessageEntry(
            uuid,
            parentUuid,
            timestamp,
            json.optString(UserMessageEntry.FIELD_CONTENT, ""),
            json.optBoolean(UserMessageEntry.FIELD_META, false));
      case ASSISTANT:
        return new AssistantMessageEntry(
            uuid,
            parentUuid,
            timestamp,
            json.optString(AssistantMessageEntry.FIELD_CONTENT, ""),
            json.optString(AssistantMessageEntry.FIELD_REASONING, ""),
            readToolCalls(json.optJSONArray(AssistantMessageEntry.FIELD_TOOL_CALLS)));
      case TOOL_RESULT:
        return ToolResultEntry.fromFields(
            uuid,
            parentUuid,
            timestamp,
            json.optString(ToolResultEntry.FIELD_TOOL_CALL_ID, ""),
            json.optString(ToolResultEntry.FIELD_TOOL_NAME, ""),
            json.optString(ToolResultEntry.FIELD_CONTENT, ""),
            json.optBoolean(ToolResultEntry.FIELD_IS_ERROR, false),
            json.optString(ToolResultEntry.FIELD_DIFF_ID, ""),
            json.optString(ToolResultEntry.FIELD_REVIEW_STATE, ""),
            json.optString(ToolResultEntry.FIELD_REVIEW_MESSAGE, ""));
      case CUSTOM_TITLE:
      case AI_TITLE:
        return new TitleEntry(
            uuid, parentUuid, timestamp, type, json.optString(TitleEntry.FIELD_TITLE, ""));
      case COMPACTION:
        return new CompactionEntry(
            uuid,
            parentUuid,
            timestamp,
            json.optString(CompactionEntry.FIELD_SUMMARY, ""),
            json.optInt(CompactionEntry.FIELD_UP_TO_ORDINAL, 0));
      default:
        return null;
    }
  }

  private static List<ToolCall> readToolCalls(JSONArray array) {
    List<ToolCall> calls = new ArrayList<>();
    if (array == null) {
      return calls;
    }
    for (int i = 0; i < array.length(); i++) {
      JSONObject item = array.optJSONObject(i);
      if (item == null) {
        continue;
      }
      calls.add(
          new ToolCall(
              item.optString(AssistantMessageEntry.FIELD_TOOL_CALL_ID, ""),
              item.optString(AssistantMessageEntry.FIELD_TOOL_CALL_NAME, ""),
              item.optString(AssistantMessageEntry.FIELD_TOOL_CALL_ARGS, "{}")));
    }
    return calls;
  }

  /** 序列化为一行 JSON（不含换行符）。 */
  public static String toLine(ConversationEntry entry) throws JSONException {
    return entry.toJson().toString();
  }
}
