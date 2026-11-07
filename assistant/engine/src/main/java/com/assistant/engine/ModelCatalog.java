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

import com.tom.rv2ide.ai.protocol.ModelProtocolType;
import com.tom.rv2ide.ai.protocol.SimpleHttpClient;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 从服务商的 {@code /models} 拉取真实模型清单——**与 ACS 的 {@code ModelCatalogFetcher}
 * 同一行为**。
 *
 * <p><b>为什么必须保留这个能力</b>：服务商编辑表单要靠它把真实清单注入槽位下拉框，
 * 用户点输入框右侧箭头就能直选，不必去控制台抄模型名（抄错一个字符就是 404）。
 * 这是 ACS 里明确实现过的东西，砍掉等于把最实用的那一步删了。
 *
 * <p><b>失败要区分原因</b>：旧实现只回空列表，调用方分不清「目录里确实没有」与
 * 「网络拉不到」——实测踩过：设备直连海外端点丢包，提示却是「没有找到模型」，
 * 用户被误导去改模型名。因此这里把错误文案一并带回。
 */
public final class ModelCatalog {

  private static final int CONNECT_TIMEOUT_MS = 15_000;
  private static final int READ_TIMEOUT_MS = 20_000;

  /** 拉取结果。 */
  public static final class Result {
    /** 模型 id 清单（已去重，保持服务端顺序）。 */
    public final List<String> models;
    /** 失败原因；空串表示成功。 */
    public final String error;

    Result(List<String> models, String error) {
      this.models = models == null ? new ArrayList<>() : models;
      this.error = error == null ? "" : error;
    }

    public boolean isSuccess() {
      return error.isEmpty();
    }
  }

  private ModelCatalog() {}

  /**
   * 拉取模型清单。
   *
   * @param baseUrl API 根（含 {@code /v1}）；为空时直接失败
   * @param apiKey 密钥；为空时不发鉴权头
   * @param protocolType 协议类型；Anthropic 的鉴权头与 OpenAI 不同
   */
  public static Result fetch(String baseUrl, String apiKey, ModelProtocolType protocolType) {
    String root = baseUrl == null ? "" : baseUrl.trim();
    while (root.endsWith("/")) {
      root = root.substring(0, root.length() - 1);
    }
    if (root.isEmpty()) {
      return new Result(null, "未配置 API 地址");
    }

    String url = root + "/models";
    try {
      SimpleHttpClient.Request request = new SimpleHttpClient.Request(url, "GET", null);
      request.connectTimeoutMs = CONNECT_TIMEOUT_MS;
      request.readTimeoutMs = READ_TIMEOUT_MS;
      String key = apiKey == null ? "" : apiKey.trim();
      if (!key.isEmpty()) {
        request.headers.put("Authorization", "Bearer " + key);
      }
      // Anthropic 的鉴权头与 OpenAI 不同，用它自己的头才拉得到目录。
      if (protocolType == ModelProtocolType.ANTHROPIC_MESSAGES) {
        request.headers.put("x-api-key", key);
        request.headers.put("anthropic-version", "2023-06-01");
      }
      SimpleHttpClient.Response response = SimpleHttpClient.execute(request);
      // 非 2xx 直接当失败：401 会返回一个 JSON 错误对象，硬解析得到空列表，
      // 但那掩盖了「密钥不对」这个真正原因。
      if (response.code < 200 || response.code >= 300) {
        return new Result(null, "HTTP " + response.code + "（密钥或端点有误）");
      }
      return new Result(parse(response.body), "");
    } catch (Throwable e) {
      String message = e.getMessage();
      String detail = message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
      return new Result(null, "网络请求失败：" + detail);
    }
  }

  /**
   * 解析 {@code /models} 响应。
   *
   * <p>兼容两种形态：OpenAI 的 {@code {"data":[{"id":...}]}} 与少数服务商直接返回数组。
   */
  private static List<String> parse(String body) {
    List<String> out = new ArrayList<>();
    if (body == null || body.isEmpty()) {
      return out;
    }
    try {
      JSONArray arr;
      String trimmed = body.trim();
      if (trimmed.startsWith("[")) {
        arr = new JSONArray(trimmed);
      } else {
        JSONObject root = new JSONObject(trimmed);
        arr = root.optJSONArray("data");
        if (arr == null) {
          arr = root.optJSONArray("models");
        }
      }
      if (arr == null) {
        return out;
      }
      for (int i = 0; i < arr.length(); i++) {
        Object item = arr.opt(i);
        String id = null;
        if (item instanceof JSONObject) {
          id = ((JSONObject) item).optString("id", "");
        } else if (item instanceof String) {
          id = (String) item;
        }
        if (id != null && !id.isEmpty() && !out.contains(id)) {
          out.add(id);
        }
      }
    } catch (Exception e) {
      // 解析失败当空列表：调用方会提示「目录为空」，用户仍可手填模型名。
    }
    return out;
  }
}
