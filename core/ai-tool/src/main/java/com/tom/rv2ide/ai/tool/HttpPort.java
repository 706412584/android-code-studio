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

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Map;

/**
 * 工具层所需的网络访问接口。
 *
 * <p><b>为什么要收窄成接口</b>：ACS 已有的 HTTP 实现（{@code SimpleHttpClient}）位于
 * {@code ai-protocol} 模块，而 {@code ai-tool} 刻意不依赖它——工具模块要能在 JVM 上
 * 单测，且它的依赖面越窄越好。这里只声明工具真正需要的几件事：GET 取文本、GET 取字节、
 * POST 送 JSON、以及 MCP SSE 传输需要的流式读取。
 *
 * <p>实现方（app 层）负责接入既有的 URL 策略、代理与超时配置，因此安全边界仍然只有
 * 一处——不会因为新增一个工具就绕过 {@code UrlPolicy}。
 *
 * <p><b>不提供通用的 POST</b>：{@link #postJson} 只为 MCP 的 JSON-RPC 存在（见其注释）。
 * 不预留用不上的能力，免得日后有人拿它去做未审查的写入请求。
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

  /**
   * 发起 GET 并**按行**流式读取响应体，供 MCP 的 SSE 传输使用。
   *
   * <p><b>为什么不复用 {@link #getText}</b>：{@code getText} 的语义是「读到流结束为止」，
   * 而 SSE 是一条**永不主动结束**的长连接——用它会在读满 {@code MAX_RESPONSE_BODY_BYTES}
   * 或超时后抛错，且期间拿不到任何已到达的事件。SSE 必须边到边处理。
   *
   * <p><b>为什么按行而不是按字节</b>：SSE 的帧语法是逐行的（{@code event:} /
   * {@code data:} / 空行结束），按行交付使上层不必自己拼缓冲；且 MCP 的每个事件体
   * 都是一行紧凑 JSON，不存在跨行 JSON。
   *
   * <p>实现方必须：返回前已完成状态码与响应头读取（非 2xx 直接抛错）；把
   * {@code Content-Type} 的字符集透传给返回的句柄；调用方关闭句柄时释放底层连接。
   */
  StreamHandle openStream(String url, Map<String, String> headers) throws Exception;

  /**
   * 一条已建立的长连接，按行读取。
   *
   * <p>{@link #readLine()} 返回 null 表示流正常结束（服务端关闭连接）。
   */
  interface StreamHandle extends Closeable {
    /** 响应状态码，已由实现方校验为 2xx。 */
    int getStatusCode();

    /** 读一行（不含行尾符）；流结束返回 null。 */
    String readLine() throws Exception;
  }

  /**
   * 把任意 {@link Reader} 包成 {@link StreamHandle}。
   *
   * <p>放在接口上是因为两个实现（app 的 {@code AppHttpPort}、测试的假实现）都需要同一段
   * 按行读取逻辑；重复实现容易在其中一处漏掉 {@code \r\n} 的处理。
   *
   * @param reader 由调用方负责随句柄一起关闭
   */
  static StreamHandle streamHandle(final int statusCode, final Reader reader) {
    final BufferedReader buffered = new BufferedReader(reader);
    return new StreamHandle() {
      @Override
      public int getStatusCode() {
        return statusCode;
      }

      @Override
      public String readLine() throws Exception {
        return buffered.readLine();
      }

      @Override
      public void close() throws IOException {
        buffered.close();
      }
    };
  }

  /** 用指定字符集把输入流包成 {@link StreamHandle} 的便捷方法。 */
  static StreamHandle streamHandle(final int statusCode, InputStream input, String charset) {
    String name = charset == null || charset.trim().isEmpty() ? "UTF-8" : charset.trim();
    try {
      return streamHandle(statusCode, new InputStreamReader(input, name));
    } catch (UnsupportedEncodingException e) {
      // 服务端报了一个本机不认识的字符集。退回 UTF-8 而不是让整条连接失败——
      // MCP 的事件体几乎总是 ASCII/UTF-8，为这个字符集字段放弃整次接入不值得。
      return streamHandle(statusCode, new InputStreamReader(input, Charset.forName("UTF-8")));
    }
  }

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

      @Override
      public StreamHandle openStream(String url, Map<String, String> headers) throws Exception {
        throw new Exception("当前环境未配置网络访问。");
      }
    };
  }

  /** 便捷重载：无附加请求头。 */
  default String getText(String url) throws Exception {
    return getText(url, Collections.emptyMap());
  }
}
