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

/**
 * 标题条目，承载两种来源：用户显式重命名（{@link Type#CUSTOM_TITLE}）与模型生成
 * （{@link Type#AI_TITLE}）。
 *
 * <p><b>重命名为什么是追加而非改写</b>：append-only 要求历史不可变。重命名因此表达为
 * 「追加一条 custom-title」，读取端折叠时取最后一条生效。好处是历史标题可追溯，
 * 且无需回写文件（回写会让崩溃可能损坏整个会话）。
 */
public final class TitleEntry extends ConversationEntry {

  static final String FIELD_TITLE = "title";

  private final Type type;
  private final String title;

  public TitleEntry(String uuid, String parentUuid, long timestamp, Type type, String title) {
    super(uuid, parentUuid, timestamp);
    if (type != Type.CUSTOM_TITLE && type != Type.AI_TITLE) {
      throw new IllegalArgumentException("TitleEntry 只接受 custom-title / ai-title，收到 " + type);
    }
    this.type = type;
    this.title = title == null ? "" : title;
  }

  public static TitleEntry customTitle(String parentUuid, long timestamp, String title) {
    return new TitleEntry(null, parentUuid, timestamp, Type.CUSTOM_TITLE, title);
  }

  public static TitleEntry aiTitle(String parentUuid, long timestamp, String title) {
    return new TitleEntry(null, parentUuid, timestamp, Type.AI_TITLE, title);
  }

  @Override
  public Type getType() {
    return type;
  }

  public String getTitle() {
    return title;
  }

  @Override
  protected void writeFields(JSONObject json) throws JSONException {
    json.put(FIELD_TITLE, title);
  }
}
