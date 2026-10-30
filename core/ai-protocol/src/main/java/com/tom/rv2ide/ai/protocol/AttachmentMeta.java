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
 * 一条用户消息携带的附件元数据。
 *
 * <p><b>为什么只有元数据、没有图片内容</b>：图片本体走
 * {@link ImageInputPayload} 的多模态通道；这里描述的是「用户附了哪几个文件」，
 * 用于把缩略图放回聊天气泡。存 base64 会让会话日志膨胀到几十 MB，而缩略图
 * 完全可以从 {@link #getLocalPath()} 现读。
 *
 * <p><b>为什么放在协议模块</b>：它要跟着 {@code ModelMessage} 从会话日志一路
 * 走到 UI 回放，而 {@code ConversationEntry} 与 {@code ModelMessage} 分属
 * ai-agent 与 ai-protocol。放在这里，两端都能引用同一个类型，不必各定义一份再转换。
 */
public final class AttachmentMeta {

  private final String fileName;
  private final String localPath;
  private final String mimeType;
  private final long sizeBytes;
  private final boolean image;

  public AttachmentMeta(
      String fileName, String localPath, String mimeType, long sizeBytes, boolean image) {
    this.fileName = fileName == null ? "" : fileName;
    this.localPath = localPath == null ? "" : localPath;
    this.mimeType = mimeType == null ? "" : mimeType;
    this.sizeBytes = sizeBytes;
    this.image = image;
  }

  public String getFileName() {
    return fileName;
  }

  /** 落盘绝对路径；为空表示落盘失败，UI 应退化为文件图标而不是破图。 */
  public String getLocalPath() {
    return localPath;
  }

  public String getMimeType() {
    return mimeType;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public boolean isImage() {
    return image;
  }

  /** 序列化进会话日志的一行。 */
  public JSONObject toJson() throws JSONException {
    return new JSONObject()
        .put("fileName", fileName)
        .put("localPath", localPath)
        .put("mimeType", mimeType)
        .put("sizeBytes", sizeBytes)
        .put("isImage", image);
  }

  /**
   * 解析一条；[json] 为 null 时返回 null。
   *
   * <p>缺字段按空串/0/false 兜底而不是抛异常：会话日志是 append-only 且可能被手工
   * 编辑过，一条坏记录不该让整个会话打不开。
   */
  public static AttachmentMeta fromJson(JSONObject json) {
    if (json == null) {
      return null;
    }
    return new AttachmentMeta(
        json.optString("fileName", ""),
        json.optString("localPath", ""),
        json.optString("mimeType", ""),
        json.optLong("sizeBytes", 0L),
        json.optBoolean("isImage", false));
  }
}
