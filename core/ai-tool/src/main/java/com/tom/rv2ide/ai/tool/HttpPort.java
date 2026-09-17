/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from LineCode Pro (https://github.com/LangLang03/LineCodePro),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

package com.tom.rv2ide.ai.tool;

import java.util.Collections;
import java.util.Map;

/**
 * 工具层所需的网络访问接口。
 *
 * <p><b>为什么要收窄成接口</b>：ACS 已有的 HTTP 实现（{@code SimpleHttpClient}）位于
 * {@code ai-protocol} 模块，而 {@code ai-tool} 刻意不依赖它——工具模块要能在 JVM 上
 * 单测，且它的依赖面越窄越好。这里只声明工具真正需要的两件事：GET 取文本、GET 取字节。
 *
 * <p>实现方（app 层）负责接入既有的 URL 策略、代理与超时配置，因此安全边界仍然只有
 * 一处——不会因为新增一个工具就绕过 {@code UrlPolicy}。
 *
 * <p><b>不提供 POST</b>：当前只有读取网页与搜索两类需求，都用 GET。不预留用不上的能力，
 * 免得日后有人拿它去做未审查的写入请求。
 */
public interface HttpPort {

  /**
   * 发起 GET 请求并返回响应正文。
   *
   * @param url 完整 URL
   * @param headers 附加请求头，可为空
   * @return 响应正文
   * @throws Exception 网络错误、非 2xx 状态码或响应超限
   */
  String getText(String url, Map<String, String> headers) throws Exception;

  /**
   * 发起 GET 请求并返回原始字节与 MIME 类型。
   *
   * <p>与 {@link #getText} 分开是因为字节流不能当字符串处理（图片、二进制资源），
   * 统一转字符串会破坏内容。
   */
  BinaryResponse getBytes(String url, Map<String, String> headers) throws Exception;

  /**
   * 发起 POST 请求（JSON 请求体），返回响应正文**与响应头**。
   *
   * <p><b>为什么需要响应头</b>：MCP 的会话 id 走 {@code Mcp-Session-Id} 响应头下发，
   * 后续请求必须回传它。只返回正文的接口拿不到这个值，会话就无法建立。
   *
   * <p><b>为什么必须有 POST</b>：MCP 用 JSON-RPC over POST。此前的 {@link #getText}
   * 只够读取类工具使用；为 MCP 新增这一个方法是实际需要，而非预留能力。
   *
   * @param url 完整 URL
   * @param jsonBody JSON 请求体
   * @param headers 附加请求头，可为空
   */
  TextResponse postJson(String url, String jsonBody, Map<String, String> headers) throws Exception;

  /** 文本响应：正文 + 响应头。 */
  final class TextResponse {
    private final String body;
    private final Map<String, String> headers;

    public TextResponse(String body, Map<String, String> headers) {
      this.body = body == null ? "" : body;
      this.headers = headers == null ? Collections.<String, String>emptyMap() : headers;
    }

    public String getBody() {
      return body;
    }

    public Map<String, String> getHeaders() {
      return headers;
    }

    /**
     * 按名字取响应头（大小写不敏感）。
     *
     * <p>HTTP 头名不区分大小写，而不同实现回传的大小写不一致
     * （{@code Mcp-Session-Id} / {@code mcp-session-id}）。调用方按小写查询即可。
     */
    public String header(String name) {
      if (name == null) {
        return "";
      }
      String direct = headers.get(name);
      if (direct != null) {
        return direct;
      }
      for (Map.Entry<String, String> entry : headers.entrySet()) {
        if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
          return entry.getValue() == null ? "" : entry.getValue();
        }
      }
      return "";
    }
  }

  /** 二进制响应。 */
  final class BinaryResponse {
    private final String mimeType;
    private final byte[] bytes;

    public BinaryResponse(String mimeType, byte[] bytes) {
      this.mimeType = mimeType == null || mimeType.isEmpty() ? "application/octet-stream" : mimeType;
      this.bytes = bytes == null ? new byte[0] : bytes;
    }

    public String getMimeType() {
      return mimeType;
    }

    public byte[] getBytes() {
      return bytes;
    }

    public int size() {
      return bytes.length;
    }
  }

  /** 未接入网络的实现：任何请求都失败。 */
  static HttpPort none() {
    return new HttpPort() {
      @Override
      public String getText(String url, Map<String, String> headers) throws Exception {
        throw new Exception("当前环境未配置网络访问。");
      }

      @Override
      public BinaryResponse getBytes(String url, Map<String, String> headers) throws Exception {
        throw new Exception("当前环境未配置网络访问。");
      }

      @Override
      public TextResponse postJson(String url, String jsonBody, Map<String, String> headers)
          throws Exception {
        throw new Exception("当前环境未配置网络访问。");
      }
    };
  }

  /** 便捷重载：无附加请求头。 */
  default String getText(String url) throws Exception {
    return getText(url, Collections.emptyMap());
  }
}
