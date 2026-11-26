/*
 * This file is part of AndroidCodeStudio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.protocol;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一条用户消息中的结构化引用元数据。
 *
 * <p>引用的可见 token 仍属于用户展示文本；这里只保存其稳定身份和 UTF-16 范围，
 * 供会话回放、引用上下文构造和后续编辑使用。它不参与模型协议序列化。
 */
public final class ReferenceMeta {

  private final String kind;
  private final String id;
  private final String label;
  private final String path;
  private final int start;
  private final int end;

  public ReferenceMeta(String kind, String id, String label, String path, int start, int end) {
    this.kind = kind == null ? "" : kind;
    this.id = id == null ? "" : id;
    this.label = label == null ? "" : label;
    this.path = path == null ? "" : path;
    this.start = Math.max(0, start);
    this.end = Math.max(this.start, end);
  }

  public String getKind() {
    return kind;
  }

  public String getId() {
    return id;
  }

  public String getLabel() {
    return label;
  }

  public String getPath() {
    return path;
  }

  public int getStart() {
    return start;
  }

  public int getEnd() {
    return end;
  }

  public JSONObject toJson() throws JSONException {
    return new JSONObject()
        .put("kind", kind)
        .put("id", id)
        .put("label", label)
        .put("path", path)
        .put("start", start)
        .put("end", end);
  }

  /** 解析一条引用；空对象返回 null，坏的范围按构造函数归一化。 */
  public static ReferenceMeta fromJson(JSONObject json) {
    if (json == null) {
      return null;
    }
    return new ReferenceMeta(
        json.optString("kind", ""),
        json.optString("id", ""),
        json.optString("label", ""),
        json.optString("path", ""),
        json.optInt("start", 0),
        json.optInt("end", 0));
  }
}
