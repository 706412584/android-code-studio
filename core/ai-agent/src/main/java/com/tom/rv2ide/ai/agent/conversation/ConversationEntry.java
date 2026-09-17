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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 会话日志中的一条条目——JSONL 文件里的一行。
 *
 * <p><b>为什么每条都自描述</b>：条目自带 {@code type} 与 {@code uuid}，因此新增条目类型
 * 无需改动既有行、也无需 schema 迁移。历史文件永远保持可读，这是 append-only 的前提。
 *
 * <p><b>为什么带 {@code parentUuid}</b>：条目成链而非纯数组。压缩（P0-2）与子 agent 分叉
 * （P1-11）都只需追加新条目并让读取端沿链解释，不必改写历史。
 *
 * <p>条目不可变——追加后不再修改，所有"变更"（重命名、压缩）都表达为新条目。
 */
public abstract class ConversationEntry {

  /** 条目类型。线名（wire name）写入 JSON，改动会破坏历史兼容性。 */
  public enum Type {
    SESSION_META("session-meta"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL_RESULT("tool-result"),
    /** 用户显式重命名。 */
    CUSTOM_TITLE("custom-title"),
    /** 模型生成的标题。 */
    AI_TITLE("ai-title"),
    /** 上下文压缩标记。P0-2 实现，当前只定义与读写。 */
    COMPACTION("compaction");

    private static final Map<String, Type> BY_WIRE_NAME = new HashMap<>();

    static {
      for (Type type : values()) {
        BY_WIRE_NAME.put(type.wireName, type);
      }
    }

    private final String wireName;

    Type(String wireName) {
      this.wireName = wireName;
    }

    public String wireName() {
      return wireName;
    }

    /** @return 对应的类型，未知线名返回 {@code null}（调用方应跳过该行而非报错）。 */
    public static Type fromWireName(String wireName) {
      return wireName == null ? null : BY_WIRE_NAME.get(wireName);
    }
  }

  static final String FIELD_TYPE = "type";
  static final String FIELD_UUID = "uuid";
  static final String FIELD_PARENT_UUID = "parentUuid";
  static final String FIELD_TIMESTAMP = "timestamp";

  private final String uuid;
  private final String parentUuid;
  private final long timestamp;

  protected ConversationEntry(String uuid, String parentUuid, long timestamp) {
    this.uuid = uuid == null || uuid.isEmpty() ? newUuid() : uuid;
    this.parentUuid = parentUuid == null ? "" : parentUuid;
    this.timestamp = timestamp;
  }

  /** 生成新的条目 id。 */
  public static String newUuid() {
    return UUID.randomUUID().toString();
  }

  public abstract Type getType();

  public final String getUuid() {
    return uuid;
  }

  /** @return 父条目 id，空串表示无父（会话首条）。 */
  public final String getParentUuid() {
    return parentUuid;
  }

  public final long getTimestamp() {
    return timestamp;
  }

  /** 序列化为 JSON 对象（不含换行；写文件由 {@link ConversationLog} 负责）。 */
  public final JSONObject toJson() throws JSONException {
    JSONObject json = new JSONObject();
    json.put(FIELD_TYPE, getType().wireName());
    json.put(FIELD_UUID, uuid);
    if (!parentUuid.isEmpty()) {
      json.put(FIELD_PARENT_UUID, parentUuid);
    }
    json.put(FIELD_TIMESTAMP, timestamp);
    writeFields(json);
    return json;
  }

  /** 子类写入各自字段。信封字段由 {@link #toJson()} 负责。 */
  protected abstract void writeFields(JSONObject json) throws JSONException;
}
