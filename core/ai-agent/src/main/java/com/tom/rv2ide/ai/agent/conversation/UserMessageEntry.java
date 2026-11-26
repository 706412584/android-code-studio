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

package com.tom.rv2ide.ai.agent.conversation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.tom.rv2ide.ai.protocol.AttachmentMeta;
import com.tom.rv2ide.ai.protocol.ReferenceMeta;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 用户消息。 */
public final class UserMessageEntry extends ConversationEntry {

  static final String FIELD_CONTENT = "content";
  /** 系统注入的消息（如工具结果摘要）标记为 meta，不计入用户可见消息数。 */
  static final String FIELD_META = "isMeta";
  /** 该消息携带的附件元数据（不含图片内容本身）。 */
  static final String FIELD_ATTACHMENTS = "attachments";
  /** 模型看到的请求文本；缺失时回退到 content，兼容旧日志。 */
  static final String FIELD_MODEL_CONTENT = "modelContent";
  /** 结构化引用元数据；只用于回放与后续引用上下文，不参与协议序列化。 */
  static final String FIELD_REFERENCES = "references";
  static final String FIELD_USER_AUTHORED = "userAuthored";

  private final String content;
  private final String modelContent;
  private final boolean meta;
  private final boolean userAuthored;
  private final List<AttachmentMeta> attachments;
  private final List<ReferenceMeta> references;

  public UserMessageEntry(String uuid, String parentUuid, long timestamp, String content, boolean meta) {
    this(uuid, parentUuid, timestamp, content, content, meta, Collections.emptyList(), Collections.emptyList());
  }

  public UserMessageEntry(
      String uuid,
      String parentUuid,
      long timestamp,
      String content,
      boolean meta,
      List<AttachmentMeta> attachments) {
    this(
        uuid,
        parentUuid,
        timestamp,
        content,
        content,
        meta,
        attachments,
        Collections.emptyList());
  }

  public UserMessageEntry(
      String uuid,
      String parentUuid,
      long timestamp,
      String content,
      String modelContent,
      boolean meta,
      List<AttachmentMeta> attachments,
      List<ReferenceMeta> references) {
    this(uuid, parentUuid, timestamp, content, modelContent, meta, attachments, references, !meta);
  }

  public UserMessageEntry(
      String uuid,
      String parentUuid,
      long timestamp,
      String content,
      String modelContent,
      boolean meta,
      List<AttachmentMeta> attachments,
      List<ReferenceMeta> references,
      boolean userAuthored) {
    super(uuid, parentUuid, timestamp);
    this.content = content == null ? "" : content;
    this.modelContent = modelContent == null ? this.content : modelContent;
    this.meta = meta;
    this.userAuthored = userAuthored;
    this.attachments =
        attachments == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(attachments));
    this.references =
        references == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(references));
  }

  public static UserMessageEntry create(String parentUuid, long timestamp, String content) {
    return new UserMessageEntry(null, parentUuid, timestamp, content, false);
  }

  public static UserMessageEntry create(
      String parentUuid, long timestamp, String content, List<AttachmentMeta> attachments) {
    return new UserMessageEntry(null, parentUuid, timestamp, content, false, attachments);
  }

  public static UserMessageEntry create(
      String parentUuid,
      long timestamp,
      String content,
      String modelContent,
      List<AttachmentMeta> attachments,
      List<ReferenceMeta> references) {
    return create(parentUuid, timestamp, content, modelContent, attachments, references, true);
  }

  public static UserMessageEntry create(
      String parentUuid,
      long timestamp,
      String content,
      String modelContent,
      List<AttachmentMeta> attachments,
      List<ReferenceMeta> references,
      boolean userAuthored) {
    return new UserMessageEntry(
        null, parentUuid, timestamp, content, modelContent, false, attachments, references, userAuthored);
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

  public boolean isUserAuthored() {
    return userAuthored;
  }

  /** 模型续接使用的纯文本投影；旧日志没有该字段时等于 {@link #getContent()}。 */
  public String getModelContent() {
    return modelContent;
  }

  /** 该条消息中的结构化引用元数据；不参与模型协议序列化。 */
  public List<ReferenceMeta> getReferences() {
    return references;
  }

  /**
   * 该条消息携带的附件；无附件时为空列表。
   *
   * <p>只存**元数据**（文件名 / 落盘路径 / MIME / 大小 / 是否图片），不存 base64：
   * 图片本体已在发送时落进 `filesDir/ai/attachments/`，日志里再存一份会让 JSONL
   * 膨胀到几十 MB，而它的唯一用途是「重新打开会话时把缩略图放回气泡」。
   *
   * <p>这也意味着**旧的会话日志没有这个字段**——解析时缺字段即空列表，
   * 历史消息照常显示，只是没有缩略图。这是刻意的向后兼容取舍。
   */
  public List<AttachmentMeta> getAttachments() {
    return attachments;
  }

  @Override
  protected void writeFields(JSONObject json) throws JSONException {
    json.put(FIELD_CONTENT, content);
    json.put(FIELD_USER_AUTHORED, userAuthored);
    if (meta) {
      json.put(FIELD_META, true);
    }
    if (!attachments.isEmpty()) {
      JSONArray array = new JSONArray();
      for (AttachmentMeta attachment : attachments) {
        array.put(attachment.toJson());
      }
      json.put(FIELD_ATTACHMENTS, array);
    }
    if (!modelContent.equals(content)) {
      json.put(FIELD_MODEL_CONTENT, modelContent);
    }
    if (!references.isEmpty()) {
      JSONArray array = new JSONArray();
      for (ReferenceMeta reference : references) {
        array.put(reference.toJson());
      }
      json.put(FIELD_REFERENCES, array);
    }
  }
}
