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

import org.json.JSONException;
import org.json.JSONObject;

/** 会话起始元信息。每个会话文件的第一条条目。 */
public final class SessionMetaEntry extends ConversationEntry {

  static final String FIELD_CWD = "cwd";
  static final String FIELD_MODEL = "model";
  static final String FIELD_PERMISSION_MODE = "permissionMode";
  /** 父会话 id：非空表示这是一个子 agent 的会话（见 {@link #getParentId()}）。 */
  static final String FIELD_PARENT_ID = "parentId";
  /** 子 agent 类型/名字；仅子 agent 会话有值。 */
  static final String FIELD_SUBAGENT_TYPE = "subagentType";

  private final String cwd;
  private final String model;
  private final String permissionMode;
  private final String parentId;
  private final String subagentType;

  public SessionMetaEntry(
      String uuid, String parentUuid, long timestamp, String cwd, String model, String permissionMode) {
    this(uuid, parentUuid, timestamp, cwd, model, permissionMode, "", "");
  }

  /**
   * 完整构造。
   *
   * @param parentId 父会话 id；空串表示普通根会话
   * @param subagentType 子 agent 的名字/类型；空串表示非子 agent 会话
   */
  public SessionMetaEntry(
      String uuid,
      String parentUuid,
      long timestamp,
      String cwd,
      String model,
      String permissionMode,
      String parentId,
      String subagentType) {
    super(uuid, parentUuid, timestamp);
    this.cwd = cwd == null ? "" : cwd;
    this.model = model == null ? "" : model;
    this.permissionMode = permissionMode == null ? "" : permissionMode;
    this.parentId = parentId == null ? "" : parentId;
    this.subagentType = subagentType == null ? "" : subagentType;
  }

  public static SessionMetaEntry create(long timestamp, String cwd, String model, String permissionMode) {
    return new SessionMetaEntry(null, null, timestamp, cwd, model, permissionMode);
  }

  /**
   * 子 agent 会话的创建入口。
   *
   * <p>子 agent 的会话与普通会话共用同一套存储与折叠逻辑——它只是多带了
   * 「我是谁的子代理」这两个字段。会话列表据此把它挂在父会话下（见
   * {@code ChatDrawer} 的 subSessionsByParent），而不是平铺成一堆无名条目。
   */
  public static SessionMetaEntry createSubAgent(
      long timestamp, String cwd, String model, String permissionMode,
      String parentId, String subagentType) {
    return new SessionMetaEntry(
        null, null, timestamp, cwd, model, permissionMode, parentId, subagentType);
  }

  @Override
  public Type getType() {
    return Type.SESSION_META;
  }

  public String getCwd() {
    return cwd;
  }

  public String getModel() {
    return model;
  }

  public String getPermissionMode() {
    return permissionMode;
  }

  /** 父会话 id；空串表示普通根会话。 */
  public String getParentId() {
    return parentId;
  }

  /** 子 agent 的名字/类型；空串表示非子 agent 会话。 */
  public String getSubagentType() {
    return subagentType;
  }

  /** 本会话是否属于一个子 agent。 */
  public boolean isSubAgent() {
    return !parentId.isEmpty() || !subagentType.isEmpty();
  }

  @Override
  protected void writeFields(JSONObject json) throws JSONException {
    json.put(FIELD_CWD, cwd);
    json.put(FIELD_MODEL, model);
    json.put(FIELD_PERMISSION_MODE, permissionMode);
    // 只在有值时写：普通会话的历史文件保持与改动前逐字节一致，
    // 回归时 diff 里不会出现一堆无意义的空字段。
    if (!parentId.isEmpty()) {
      json.put(FIELD_PARENT_ID, parentId);
    }
    if (!subagentType.isEmpty()) {
      json.put(FIELD_SUBAGENT_TYPE, subagentType);
    }
  }
}
