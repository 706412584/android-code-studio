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

/** 用户消息。 */
public final class UserMessageEntry extends ConversationEntry {

  static final String FIELD_CONTENT = "content";
  /** 系统注入的消息（如工具结果摘要）标记为 meta，不计入用户可见消息数。 */
  static final String FIELD_META = "isMeta";

  private final String content;
  private final boolean meta;

  public UserMessageEntry(String uuid, String parentUuid, long timestamp, String content, boolean meta) {
    super(uuid, parentUuid, timestamp);
    this.content = content == null ? "" : content;
    this.meta = meta;
  }

  public static UserMessageEntry create(String parentUuid, long timestamp, String content) {
    return new UserMessageEntry(null, parentUuid, timestamp, content, false);
  }

  @Override
  public Type getType() {
    return Type.USER;
  }

  public String getContent() {
    return content;
  }

  public boolean isMeta() {
    return meta;
  }

  @Override
  protected void writeFields(JSONObject json) throws JSONException {
    json.put(FIELD_CONTENT, content);
    if (meta) {
      json.put(FIELD_META, true);
    }
  }
}
