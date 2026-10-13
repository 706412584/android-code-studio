/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
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

package com.tom.rv2ide.ai.tool;

/**
 * u2 server 的 JSON-RPC 请求体构造。
 *
 * <p><b>为什么请求体必须纯 ASCII（设备实测 bug，2026-10-12）</b>：u2 server
 * 的解码路径不能正确处理原始 UTF-8 请求体——实测把「中文测试ABC」按原始
 * UTF-8 字节发送后，设备输入框显示为每个字节一个替换符（中文全丢，ASCII
 * 幸存）；同一内容改用 &#92;u 转义（纯 ASCII）发送则完全正确。
 * 上游 Python 客户端默认 {@code ensure_ascii=True}（json.dumps 把非 ASCII
 * 全转义），因此从未踩到——我们的 Java 客户端发原始 UTF-8 才暴露。
 *
 * <p>转义是 JSON 规范内的等价变换，服务端解析后得到完全相同的字符串；
 * 对纯 ASCII 输入零改动。
 */
public final class U2JsonRpc {

  private U2JsonRpc() {}

  /** 构造 POST /jsonrpc/0 的请求体（保证纯 ASCII 输出）。 */
  public static String buildBody(String method, String paramsJson) {
    return asciiEscape(
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\""
            + escapeString(method)
            + "\",\"params\":"
            + (paramsJson == null || paramsJson.trim().isEmpty() ? "[]" : paramsJson)
            + "}");
  }

  /**
   * 把 JSON 文本里的非 ASCII 字符转义为 &#92;u 形式（BMP 之外的字符按其
   * UTF-16 代理对逐半转义——同样是合法 JSON，解析后还原原字符）。
   *
   * <p>已有的 &#92;u 序列（如上游转义过的文本）不受影响：转义序列的
   * 每个字符（反斜杠 / u / 十六进制位）都在 ASCII 区间，原样保留。
   */
  static String asciiEscape(String json) {
    if (json == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(json.length() + 16);
    for (int i = 0; i < json.length(); i++) {
      char c = json.charAt(i);
      if (c >= 0x20 && c <= 0x7E) {
        sb.append(c);
      } else {
        sb.append(String.format("\\u%04x", (int) c));
      }
    }
    return sb.toString();
  }

  /** 最小 JSON 字符串转义（method 名来自模型，含 " 会生成非法 JSON）。 */
  static String escapeString(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    return sb.toString();
  }
}
