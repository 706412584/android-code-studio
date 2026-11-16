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
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
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
  static final String FIELD_IMAGE_MIME_TYPE = "imageMimeType";
  static final String FIELD_IMAGE_BASE64 = "imageBase64";
  /** 外置截图文件名（相对会话 .images 目录）。新写入用它，避免 base64 撑大 JSONL。 */
  static final String FIELD_IMAGE_REF = "imageRef";
  static final String FIELD_STEPS = "steps";

  private final String toolCallId;
  private final String toolName;
  private final String content;
  private final boolean error;
  private final String diffId;
  private final String reviewState;
  private final String reviewMessage;
  /** 工具结果图片的 MIME 类型；无图片时为空串。 */
  private final String imageMimeType;
  /** 工具结果图片的 base64 数据；无图片时为空串（外置时为读入缓存）。 */
  private final String imageBase64;
  /** 外置截图的文件名（相对会话 .images 目录）；为空表示内联或无心图片。 */
  private final String imageRef;
  /** 懒加载外置截图用的基目录；内联图片时为 null。 */
  private final File imageBaseDir;
  /** 外置截图首次读取后的 base64 缓存（避免每次 getImageBase64 都读盘）。 */
  private volatile String cachedExternalBase64;

  /**
   * 工具执行期间的步骤进度记录（子 agent 这类长任务才会产生）。
   *
   * <p><b>为什么必须落盘</b>：子代理的中间过程不进主对话，这些步骤行是用户回看时
   * 唯一的凭据。仅存在内存里的话，重启应用后步骤区就是空的——用户实测反馈过
   * 「步骤确实不见了」。cc-haha 的进度消息随 transcript 持久化，同一考量。
   */
  private final List<String> steps;

  public ToolResultEntry(String uuid, String parentUuid, long timestamp, ToolResult result) {
    this(uuid, parentUuid, timestamp, result, Collections.emptyList());
  }

  /**
   * 带步骤记录的构造。
   *
   * @param steps 该次调用期间累积的步骤行；null 视作空
   */
  public ToolResultEntry(
      String uuid, String parentUuid, long timestamp, ToolResult result, List<String> steps) {
    super(uuid, parentUuid, timestamp);
    this.toolCallId = result == null ? "" : result.getToolCallId();
    this.toolName = result == null ? "" : result.getToolName();
    this.content = result == null ? "" : result.getContent();
    this.error = result != null && result.isError();
    this.diffId = result == null ? "" : result.getDiffId();
    this.reviewState = result == null ? "" : result.getReviewState();
    this.reviewMessage = result == null ? "" : result.getReviewMessage();
    this.imageMimeType = result == null ? "" : result.getImageMimeType();
    this.imageBase64 = result == null ? "" : result.getImageBase64();
    this.imageRef = "";
    this.imageBaseDir = null;
    this.steps = steps == null ? Collections.emptyList() : new ArrayList<>(steps);
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
      String reviewMessage,
      String imageMimeType,
      String imageBase64,
      String imageRef,
      File imageBaseDir,
      List<String> steps) {
    super(uuid, parentUuid, timestamp);
    this.toolCallId = toolCallId;
    this.toolName = toolName;
    this.content = content;
    this.error = error;
    this.diffId = diffId;
    this.reviewState = reviewState;
    this.reviewMessage = reviewMessage;
    this.imageMimeType = imageMimeType == null ? "" : imageMimeType;
    this.imageBase64 = imageBase64 == null ? "" : imageBase64;
    this.imageRef = imageRef == null ? "" : imageRef;
    this.imageBaseDir = imageBaseDir;
    this.steps = steps == null ? Collections.emptyList() : steps;
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
      String reviewMessage,
      String imageMimeType,
      String imageBase64,
      String imageRef,
      File imageBaseDir,
      List<String> steps) {
    return new ToolResultEntry(
        uuid, parentUuid, timestamp, toolCallId, toolName, content, error, diffId, reviewState,
        reviewMessage, imageMimeType, imageBase64, imageRef, imageBaseDir, steps);
  }

  public static ToolResultEntry create(String parentUuid, long timestamp, ToolResult result) {
    return new ToolResultEntry(null, parentUuid, timestamp, result);
  }

  /**
   * 带步骤记录的构造（落盘用）。
   *
   * @param steps 该次调用期间累积的步骤行；null 视作空
   */
  public static ToolResultEntry create(
      String parentUuid, long timestamp, ToolResult result, List<String> steps) {
    return new ToolResultEntry(null, parentUuid, timestamp, result, steps);
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

  /** 工具结果图片的 MIME 类型；无图片时为空串。 */
  public String getImageMimeType() {
    return imageMimeType;
  }

  /**
   * 工具结果图片的 base64 数据；无图片时为空串。
   *
   * <p>新版记录把 base64 外置到会话的 .images 目录，这里首次调用时才从文件读入并缓存；
   * 旧版记录内联 base64，直接返回。因此「读盘/续接」不再把全部截图读进内存。
   */
  public String getImageBase64() {
    if (!imageBase64.isEmpty()) {
      return imageBase64;
    }
    if (imageRef.isEmpty() || imageBaseDir == null) {
      return "";
    }
    String cached = cachedExternalBase64;
    if (cached != null) {
      return cached;
    }
    try {
      byte[] bytes = Files.readAllBytes(new File(imageBaseDir, imageRef).toPath());
      String value = new String(bytes, StandardCharsets.UTF_8).trim();
      cachedExternalBase64 = value;
      return value;
    } catch (Exception e) {
      // 文件缺失（被清理/移动）时退化为无图，不影响对话继续。
      return "";
    }
  }

  /** 是否带图片——**不触发文件读取**，供 fold 在决定水合前快速判断。 */
  public boolean hasImage() {
    return !imageBase64.isEmpty() || !imageRef.isEmpty();
  }

  /** 内联的 base64（落盘外置用）；外置或无心图片时为空。 */
  public String getInlineImageBase64() {
    return imageBase64;
  }

  /** 外置截图的文件名；内联或无心图片时为空。 */
  public String getImageRef() {
    return imageRef;
  }

  /** 该次调用期间的步骤进度记录；无步骤时为空列表。 */
  public List<String> getSteps() {
    return steps;
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
    // 图片负载只在存在时写入，避免给纯文本结果塞两个空字段（JSONL 里每行都有）。
    // 优先写外置引用（新记录），兼容旧的内联 base64。
    if (!imageRef.isEmpty()) {
      json.put(FIELD_IMAGE_MIME_TYPE, imageMimeType);
      json.put(FIELD_IMAGE_REF, imageRef);
    } else if (!imageBase64.isEmpty()) {
      json.put(FIELD_IMAGE_MIME_TYPE, imageMimeType);
      json.put(FIELD_IMAGE_BASE64, imageBase64);
    }
    // 步骤同理：普通工具没有步骤，不写空数组（JSONL 每行都会出现）。
    if (!steps.isEmpty()) {
      JSONArray array = new JSONArray();
      for (String step : steps) {
        array.put(step);
      }
      json.put(FIELD_STEPS, array);
    }
  }
}
