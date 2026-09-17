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

  private final String cwd;
  private final String model;
  private final String permissionMode;

  public SessionMetaEntry(
      String uuid, String parentUuid, long timestamp, String cwd, String model, String permissionMode) {
    super(uuid, parentUuid, timestamp);
    this.cwd = cwd == null ? "" : cwd;
    this.model = model == null ? "" : model;
    this.permissionMode = permissionMode == null ? "" : permissionMode;
  }

  public static SessionMetaEntry create(long timestamp, String cwd, String model, String permissionMode) {
    return new SessionMetaEntry(null, null, timestamp, cwd, model, permissionMode);
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

  @Override
  protected void writeFields(JSONObject json) throws JSONException {
    json.put(FIELD_CWD, cwd);
    json.put(FIELD_MODEL, model);
    json.put(FIELD_PERMISSION_MODE, permissionMode);
  }
}
