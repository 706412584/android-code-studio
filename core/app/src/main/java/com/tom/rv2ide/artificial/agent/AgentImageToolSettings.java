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

package com.tom.rv2ide.artificial.agent;

import android.content.Context;
import android.content.SharedPreferences;
import com.tom.rv2ide.ai.tool.ImageGenerationEndpoint;

/**
 * 图片生成偏好：能力页选定的服务商与模型，存 "ai_agent_tools" SharedPreferences。
 *
 * <p>只存两个 id（providerId / model），连接信息（baseUrl / 密钥）每次运行时从
 * {@link ProviderConfigStore} 现读——密钥改了立即生效，不必同步两份。
 *
 * <p>与 {@link AgentToolSettings} 同文件但独立类：后者是工具权限域，混进来会让
 * 「图片配置」被权限相关的 beginRun 生命周期误伤。
 */
public final class AgentImageToolSettings {

  private static final String PREFS_NAME = "ai_agent_tools";
  /** 图片生成服务商 id（ProviderConfig.id）。空 = 未配置。 */
  public static final String KEY_IMAGE_PROVIDER = "image_gen_provider";
  /** 图片生成模型名。空 = 未配置。 */
  public static final String KEY_IMAGE_MODEL = "image_gen_model";
  /**
   * 图片保存目录（绝对路径）。空 = 默认（当前项目工作区下的 {@code ai-generated/}）。
   */
  public static final String KEY_IMAGE_OUTPUT_DIR = "image_gen_output_dir";

  private final SharedPreferences prefs;

  public AgentImageToolSettings(Context context) {
    this.prefs =
        context.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  /** 选定的服务商 id；空串表示未配置。 */
  public String providerId() {
    return prefs.getString(KEY_IMAGE_PROVIDER, "");
  }

  /** 选定的模型名；空串表示未配置。 */
  public String model() {
    return prefs.getString(KEY_IMAGE_MODEL, "");
  }

  /** 图片保存目录（绝对路径）；空串 = 默认（项目工作区下 ai-generated/）。 */
  public String outputDir() {
    return prefs.getString(KEY_IMAGE_OUTPUT_DIR, "");
  }

  public void set(String providerId, String model) {
    prefs.edit().putString(KEY_IMAGE_PROVIDER, providerId).putString(KEY_IMAGE_MODEL, model).apply();
  }

  /** 设置保存目录；传空串恢复默认。 */
  public void setOutputDir(String path) {
    if (path == null || path.trim().isEmpty()) {
      prefs.edit().remove(KEY_IMAGE_OUTPUT_DIR).apply();
    } else {
      prefs.edit().putString(KEY_IMAGE_OUTPUT_DIR, path.trim()).apply();
    }
  }

  /** 清除配置（服务商被删除时调用方应同步清理）。 */
  public void clear() {
    prefs.edit().remove(KEY_IMAGE_PROVIDER).remove(KEY_IMAGE_MODEL).apply();
  }

  /**
   * 解析成工具可用的端点。未配置或服务商已被删时返回 null。
   *
   * <p>密钥可空：部分网关（本地 Ollama 等）不鉴权，与对话端点的 {@code "local"} 占位
   * 不同——这里空串即不发 Authorization 头，由工具侧决定。
   */
  public ImageGenerationEndpoint resolveEndpoint() {
    String provider = providerId();
    String model = model();
    if (provider.isEmpty() || model.isEmpty()) {
      return null;
    }
    // 静态上下文来源与 AgentModelConfigs.recordFor 相同。
    android.content.Context ctx = com.tom.rv2ide.app.BaseApplication.getBaseInstance();
    if (ctx == null) {
      return null;
    }
    ProviderConfig record = new ProviderConfigStore(ctx).find(provider);
    if (record == null || record.getBaseUrl().isEmpty()) {
      return null;
    }
    return new ImageGenerationEndpoint(
        record.getBaseUrl(), record.getApiKey(), model, outputDir());
  }
}
