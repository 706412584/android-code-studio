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

package com.tom.rv2ide.ai.tool.api;

import java.util.regex.Pattern;

/**
 * 日志脱敏：去掉可能出现在日志里的凭据。
 *
 * <p><b>为什么需要它</b>：日志会被用户复制到 issue、被上报、被截图。而错误信息里很容易
 * 夹带密钥——HTTP 错误响应体会回显请求头、异常消息里可能带 URL 查询串、
 * 工具输出可能包含用户文件里的 {@code api_key = "..."}。
 *
 * <p>这里覆盖四类高风险的形态：
 * <ul>
 *   <li>{@code Authorization: Bearer xxx} 与 {@code x-api-key: xxx} 头；
 *   <li>JSON 里名为 api_key / token / password / secret 等字段的值；
 *   <li>data URL 形式的内嵌 base64（图片理解功能会产出这种内容）；
 *   <li>单独出现的超长 base64 串。
 * </ul>
 *
 * <p><b>长度上限</b>：单次脱敏只处理前 1MB。正则替换会对字符串反复复制，对几十 MB 的
 * 内容（例如嵌了大段 base64 的模型响应体）跑替换会直接 OOM——而日志本就不该这么大。
 */
public final class ErrorLogRedactor {

  /** 单次脱敏的安全输入上限。 */
  private static final int MAX_SAFE_LENGTH = 1 << 20;

  private static final Pattern AUTHORIZATION =
      Pattern.compile("(?i)(Authorization\\s*[:=]\\s*)(Bearer\\s+)?[^\\r\\n,}]+?");

  private static final Pattern API_KEY_HEADER =
      Pattern.compile("(?i)((x-api-key|api-key)\\s*[:=]\\s*)[^\\r\\n,}]+?");

  private static final Pattern JSON_SECRET =
      Pattern.compile(
          "(?i)(\\\"(?:api[_-]?key|authorization|access[_-]?token|refresh[_-]?token|password|secret|private[_-]?key)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")");

  private static final Pattern LONG_BASE64 =
      Pattern.compile("(?i)(data:image/[^;]+;base64,)[A-Za-z0-9+/=]{80,}");

  private static final Pattern B64_JSON =
      Pattern.compile("(?i)(\\\"b64_json\\\"\\s*:\\s*\\\")[^\\\"]{80,}(\\\")");

  /**
   * 查询串里的 key：{@code ?key=xxx} / {@code &api_key=xxx}。
   *
   * <p>MCP server 地址与部分模型端点把密钥放在查询串里，而这类 URL 常出现在异常消息中。
   */
  private static final Pattern URL_SECRET =
      Pattern.compile(
          "(?i)([?&](?:key|api[_-]?key|access[_-]?token|token|password)=)[^&\\s\\\"']+");

  private static final String TRUNCATED_SUFFIX = "\n... [REDACTED_TRUNCATED]";

  private ErrorLogRedactor() {}

  /** 脱敏一段文本；null 或空返回空串。 */
  public static String redact(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    String safe = value;
    boolean truncated = false;
    if (value.length() > MAX_SAFE_LENGTH) {
      safe = value.substring(0, MAX_SAFE_LENGTH);
      truncated = true;
    }
    String redacted = AUTHORIZATION.matcher(safe).replaceAll("$1$2[REDACTED]");
    redacted = API_KEY_HEADER.matcher(redacted).replaceAll("$1[REDACTED]");
    redacted = JSON_SECRET.matcher(redacted).replaceAll("$1[REDACTED]$2");
    redacted = LONG_BASE64.matcher(redacted).replaceAll("$1[BASE64_REDACTED]");
    redacted = B64_JSON.matcher(redacted).replaceAll("$1[BASE64_REDACTED]$2");
    redacted = URL_SECRET.matcher(redacted).replaceAll("$1[REDACTED]");
    return truncated ? redacted + TRUNCATED_SUFFIX : redacted;
  }

  /**
   * 脱敏一个异常。
   *
   * <p><b>不改动原异常</b>：直接改 {@code throwable.getMessage()} 会影响到还在向上传播的
   * 同一个对象，可能破坏上层的判断逻辑。这里派生一个包装异常，消息已脱敏、原因链保留。
   *
   * <p>仅当消息确实含有需要脱敏的内容时才包装——无谓的包装会让日志里的异常类型变化，
   * 给排查增加噪声。
   */
  public static Throwable redactThrowable(Throwable throwable) {
    if (throwable == null) {
      return null;
    }
    String message = throwable.getMessage();
    if (message == null || message.isEmpty()) {
      return throwable;
    }
    String redacted = redact(message);
    if (redacted.equals(message)) {
      return throwable;
    }
    return new RedactedException(throwable.getClass().getName() + ": " + redacted, throwable);
  }

  /**
   * 承载已脱敏消息的包装异常。
   *
   * <p>类名带 {@code Redacted} 前缀，使日志读者知道「这不是原始异常类型，只是为了让消息
   * 可安全记录而包装的」。
   */
  public static final class RedactedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    RedactedException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
