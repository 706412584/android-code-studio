/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
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

package com.tom.rv2ide.ai.tool;

/**
 * 视频生成端点配置（与 {@link ImageGenerationEndpoint} 平行的能力页配置）。
 *
 * <p>服务商与模型在「能力 → 视频生成」单独选定，不随对话服务商切换。
 * 协议语义照 cc-haha media-gen 插件：Agnes 兼容端点（{@code POST {base}/videos} →
 * 轮询 {@code /agnesapi?video_id=}）与 Grok 端点（{@code /videos/generations} →
 * 轮询 {@code GET /videos/{id}}）两套，按模型名前缀 {@code grok-imagine-video} 分流。
 *
 * <p>{@code baseUrl} 约定同图片端点：含 {@code /v1} 的 API 根。
 */
public final class VideoGenerationEndpoint {

  private final String baseUrl;
  private final String apiKey;
  private final String model;
  /** 视频保存目录（绝对路径）；空串 = 工具默认（工作区下 ai-generated/）。 */
  private final String outputDir;

  public VideoGenerationEndpoint(
      String baseUrl, String apiKey, String model, String outputDir) {
    this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.model = model == null ? "" : model.trim();
    this.outputDir = outputDir == null ? "" : outputDir.trim();
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public String getApiKey() {
    return apiKey;
  }

  public String getModel() {
    return model;
  }

  public String getOutputDir() {
    return outputDir;
  }

  /** 是否具备发请求的最低条件：API 根与模型名齐备。 */
  public boolean isConfigured() {
    return !baseUrl.isEmpty() && !model.isEmpty();
  }

  /** Grok 视频模型（grok-imagine-video / grok-imagine-video-fast…）走另一套协议。 */
  public boolean isGrokProtocol() {
    return model.toLowerCase(java.util.Locale.ROOT).matches("^grok-imagine-video(?:-.*)?$");
  }

  /** Agnes 兼容提交端点：API 根 + {@code /videos}。 */
  public String agnesCreateUrl() {
    return trimSlash(baseUrl) + "/videos";
  }

  /**
   * Agnes 兼容轮询端点：剥掉结尾 /v1 后拼 /agnesapi。
   *
   * <p><b>必须带 model_name</b>（官方文档与 FrameBaker 实测一致）：缺了它服务端无法
   * 找到对应任务。text 模式下官方说可省，但带上无害且省一个分支。
   */
  public String agnesPollUrl(String videoId, String model) {
    String root = trimSlash(baseUrl).replaceAll("/v1$", "");
    return root
        + "/agnesapi?video_id="
        + urlEncode(videoId)
        + "&model_name="
        + urlEncode(model);
  }

  /** Grok 提交端点：API 根 + {@code /videos/generations}。 */
  public String grokCreateUrl() {
    return trimSlash(baseUrl) + "/videos/generations";
  }

  /** Grok 轮询端点：API 根 + {@code /videos/{id}}。 */
  public String grokPollUrl(String requestId) {
    return trimSlash(baseUrl) + "/videos/" + urlEncode(requestId);
  }

  private static String trimSlash(String value) {
    String root = value;
    while (root.endsWith("/")) {
      root = root.substring(0, root.length() - 1);
    }
    return root;
  }

  private static String urlEncode(String value) {
    try {
      return java.net.URLEncoder.encode(value, "UTF-8");
    } catch (java.io.UnsupportedEncodingException e) {
      return value; // UTF-8 恒可用
    }
  }
}
