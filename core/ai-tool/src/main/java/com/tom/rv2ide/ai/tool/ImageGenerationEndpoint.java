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
 * 图片生成端点配置。
 *
 * <p>与对话端点（{@code AgentModelConfigs.ProviderEndpoint}，app 层）刻意分开：
 * 图片生成的服务商与模型在「能力」页单独配置，不随对话服务商切换而变。本类只承载
 * 解析后的三个值，供 {@link ImageGenerationTool} 经 {@link ToolContext} 读取。
 *
 * <p>{@code baseUrl} 约定与 {@code ModelCatalogFetcher} 一致：含 {@code /v1}
 * 的 API 根（如 {@code https://host/v1}），图片端点在其下拼 {@code /images/generations}。
 *
 * <p>纯 Java 值对象，可直接单测。
 */
public final class ImageGenerationEndpoint {

  private final String baseUrl;
  private final String apiKey;
  private final String model;
  /**
   * 图片保存目录（绝对路径）；空串 = 工具默认（工作区下 {@code ai-generated/}）。
   *
   * <p>由能力页「保存路径」设置，用户显式指定的目录优先于工作区约定。
   */
  private final String outputDir;

  public ImageGenerationEndpoint(String baseUrl, String apiKey, String model) {
    this(baseUrl, apiKey, model, "");
  }

  public ImageGenerationEndpoint(String baseUrl, String apiKey, String model, String outputDir) {
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

  /** 用户指定的保存目录；空串表示未指定。 */
  public String getOutputDir() {
    return outputDir;
  }

  /** 是否具备发请求的最低条件：API 根与模型名齐备（密钥可空，本地网关可能不鉴权）。 */
  public boolean isConfigured() {
    return !baseUrl.isEmpty() && !model.isEmpty();
  }

  /** 图片生成端点 URL：API 根 + {@code /images/generations}。 */
  public String imagesUrl() {
    String root = baseUrl;
    while (root.endsWith("/")) {
      root = root.substring(0, root.length() - 1);
    }
    return root + "/images/generations";
  }
}
