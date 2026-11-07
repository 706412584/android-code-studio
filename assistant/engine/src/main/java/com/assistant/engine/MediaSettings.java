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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.assistant.engine;

import android.content.Context;
import android.content.SharedPreferences;
import com.tom.rv2ide.ai.tool.ImageGenerationEndpoint;
import com.tom.rv2ide.ai.tool.VideoGenerationEndpoint;

/**
 * 图片 / 视频生成的**选择**（不是端点配置）。
 *
 * <p><b>与 ACS 的 {@code AgentImageToolSettings} 同一模型</b>：这里只存
 * 「用哪个服务商 + 哪个模型 + 输出目录」三项，baseUrl 与 apiKey 从
 * {@link ProviderRecords} 解析。用户不必为画图再填一遍地址和密钥——
 * 那正是我第一版做错的地方。
 *
 * <p>为什么服务商可以与对话不同：画图/视频模型往往和对话模型不是同一家。
 * 但**服务商记录是共享的**：用户在「服务商管理」里配一次，对话和生成都能选它。
 */
public final class MediaSettings {

  private static final String PREFS_NAME = "assistant_media";

  private static final String KEY_IMAGE_PROVIDER = "image_provider";
  private static final String KEY_IMAGE_MODEL = "image_model";
  private static final String KEY_IMAGE_OUTPUT = "image_output_dir";

  private static final String KEY_VIDEO_PROVIDER = "video_provider";
  private static final String KEY_VIDEO_MODEL = "video_model";
  private static final String KEY_VIDEO_OUTPUT = "video_output_dir";

  private final SharedPreferences prefs;
  private final ProviderRecords records;

  public MediaSettings(Context context) {
    this.prefs =
        context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    this.records = new ProviderRecords(context);
  }

  // ---- 图片 ----

  public String imageProviderId() {
    return prefs.getString(KEY_IMAGE_PROVIDER, "");
  }

  public String imageModel() {
    return prefs.getString(KEY_IMAGE_MODEL, "");
  }

  public String imageOutputDir() {
    return prefs.getString(KEY_IMAGE_OUTPUT, "");
  }

  public void setImage(String providerId, String model, String outputDir) {
    prefs
        .edit()
        .putString(KEY_IMAGE_PROVIDER, safe(providerId))
        .putString(KEY_IMAGE_MODEL, safe(model))
        .putString(KEY_IMAGE_OUTPUT, safe(outputDir))
        .apply();
  }

  public void clearImage() {
    prefs.edit().remove(KEY_IMAGE_PROVIDER).remove(KEY_IMAGE_MODEL).remove(KEY_IMAGE_OUTPUT).apply();
  }

  /**
   * 解析成内核认的端点。未配置或服务商已被删时返回 null。
   *
   * <p>密钥可空：本地网关（Ollama 等）不鉴权，空串即不发 Authorization 头。
   */
  public ImageGenerationEndpoint imageEndpoint() {
    String providerId = imageProviderId();
    String model = imageModel();
    if (providerId.isEmpty() || model.isEmpty()) {
      return null;
    }
    ProviderRecords.Record record = records.find(providerId);
    if (record == null || record.baseUrl.isEmpty()) {
      return null;
    }
    return new ImageGenerationEndpoint(record.baseUrl, record.apiKey, model, imageOutputDir());
  }

  // ---- 视频 ----

  public String videoProviderId() {
    return prefs.getString(KEY_VIDEO_PROVIDER, "");
  }

  public String videoModel() {
    return prefs.getString(KEY_VIDEO_MODEL, "");
  }

  public String videoOutputDir() {
    return prefs.getString(KEY_VIDEO_OUTPUT, "");
  }

  public void setVideo(String providerId, String model, String outputDir) {
    prefs
        .edit()
        .putString(KEY_VIDEO_PROVIDER, safe(providerId))
        .putString(KEY_VIDEO_MODEL, safe(model))
        .putString(KEY_VIDEO_OUTPUT, safe(outputDir))
        .apply();
  }

  public void clearVideo() {
    prefs.edit().remove(KEY_VIDEO_PROVIDER).remove(KEY_VIDEO_MODEL).remove(KEY_VIDEO_OUTPUT).apply();
  }

  public VideoGenerationEndpoint videoEndpoint() {
    String providerId = videoProviderId();
    String model = videoModel();
    if (providerId.isEmpty() || model.isEmpty()) {
      return null;
    }
    ProviderRecords.Record record = records.find(providerId);
    if (record == null || record.baseUrl.isEmpty()) {
      return null;
    }
    return new VideoGenerationEndpoint(record.baseUrl, record.apiKey, model, videoOutputDir());
  }

  private static String safe(String value) {
    return value == null ? "" : value.trim();
  }
}
