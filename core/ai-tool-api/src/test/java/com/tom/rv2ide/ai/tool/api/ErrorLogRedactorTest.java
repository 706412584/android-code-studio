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

package com.tom.rv2ide.ai.tool.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 日志脱敏的回归测试。
 *
 * <p><b>为什么需要它</b>：日志会被贴进 issue、被上报、被截图。漏掉一个形态就是一次凭据
 * 泄露，而泄露往往在用户已经把日志发出去之后才被发现。这里逐个形态钉住。
 */
final class ErrorLogRedactorTest {

  @Test
  void redactsAuthorizationHeader() {
    String input = "Authorization: Bearer sk-abcdef1234567890\nmore";

    String output = ErrorLogRedactor.redact(input);

    assertFalse(output.contains("sk-abcdef1234567890"), output);
    assertTrue(output.contains("[REDACTED]"));
  }

  @Test
  void redactsAuthorizationWithoutBearerPrefix() {
    String output = ErrorLogRedactor.redact("Authorization: sk-abcdef1234567890");

    assertFalse(output.contains("sk-abcdef1234567890"), output);
  }

  @Test
  void redactsApiKeyHeaders() {
    // 两种大小写写法都要覆盖：不同客户端用的形式不同。
    assertFalse(ErrorLogRedactor.redact("x-api-key: secret1234567890").contains("secret1234567890"));
    assertFalse(ErrorLogRedactor.redact("api-key=secret1234567890").contains("secret1234567890"));
    assertFalse(ErrorLogRedactor.redact("X-API-KEY: secret1234567890").contains("secret1234567890"));
  }

  @Test
  void redactsJsonSecretFields() {
    String input = "{\"api_key\":\"sk-secret-value\",\"model\":\"gpt-4o\"}";

    String output = ErrorLogRedactor.redact(input);

    assertFalse(output.contains("sk-secret-value"), output);
    // 非敏感字段必须保留，否则日志失去诊断价值
    assertTrue(output.contains("gpt-4o"), output);
  }

  @Test
  void redactsAllCommonSecretFieldNames() {
    String[] fields = {
      "api_key", "apiKey", "authorization", "access_token", "refresh_token", "password", "secret",
      "private_key"
    };
    for (String field : fields) {
      String input = "{\"" + field + "\":\"TOP-SECRET-VALUE\"}";
      String output = ErrorLogRedactor.redact(input);
      assertFalse(
          output.contains("TOP-SECRET-VALUE"), "字段 " + field + " 未被脱敏：" + output);
    }
  }

  @Test
  void redactsSecretsInUrlQueryStrings() {
    // 部分端点与 MCP server 把密钥放在查询串里，而这类 URL 常出现在异常消息中。
    String output =
        ErrorLogRedactor.redact("GET https://api.example.com/v1?key=sk-secret123&model=x failed");

    assertFalse(output.contains("sk-secret123"), output);
    // 非敏感参数保留
    assertTrue(output.contains("model=x"), output);
  }

  @Test
  void redactsTokenInQueryString() {
    assertFalse(
        ErrorLogRedactor.redact("?token=abc123secret").contains("abc123secret"));
    assertFalse(
        ErrorLogRedactor.redact("&access_token=abc123secret").contains("abc123secret"));
  }

  @Test
  void redactsDataUrlBase64() {
    // 图片理解功能会把图片编码成 data URL，日志里带这个会非常巨大且无意义。
    StringBuilder base64 = new StringBuilder("data:image/png;base64,");
    for (int i = 0; i < 200; i++) {
      base64.append("QUJD");
    }

    String output = ErrorLogRedactor.redact(base64.toString());

    assertTrue(output.contains("[BASE64_REDACTED]"), output.substring(0, Math.min(80, output.length())));
    assertTrue(output.length() < 200, "实际长度 " + output.length());
  }

  @Test
  void redactsB64JsonField() {
    StringBuilder payload = new StringBuilder("{\"b64_json\":\"");
    for (int i = 0; i < 100; i++) {
      payload.append("QUJD");
    }
    payload.append("\"}");

    String output = ErrorLogRedactor.redact(payload.toString());

    assertTrue(output.contains("[BASE64_REDACTED]"));
    assertTrue(output.length() < 200);
  }

  @Test
  void keepsOrdinaryContentIntact() {
    // 脱敏不能把日志变成一堆标记，否则失去诊断价值。
    String input = "Tool failed: file_read\nCall: abc\nCannot find file app/build.gradle";

    assertEquals(input, ErrorLogRedactor.redact(input));
  }

  @Test
  void doesNotRedactShortBase64LookingStrings() {
    // 短串可能是正常的 id 或 hash，误伤会让日志难以阅读。
    String input = "id=QUJDREVG";

    assertEquals(input, ErrorLogRedactor.redact(input));
  }

  @Test
  void handlesNullAndEmpty() {
    assertEquals("", ErrorLogRedactor.redact(null));
    assertEquals("", ErrorLogRedactor.redact(""));
  }

  @Test
  void truncatesHugeInputAndSaysSo() {
    // 正则替换会对字符串反复复制，对几十 MB 的内容跑替换会 OOM。
    StringBuilder huge = new StringBuilder();
    for (int i = 0; i < 1_100_000; i++) {
      huge.append('x');
    }

    String output = ErrorLogRedactor.redact(huge.toString());

    assertTrue(output.contains("[REDACTED_TRUNCATED]"), "截断必须注明");
    assertTrue(output.length() < huge.length());
  }

  // ---- 异常脱敏 ----

  @Test
  void redactsThrowableMessage() {
    Throwable original = new IllegalStateException("request to https://api.example.com?key=sk-secret123 failed");

    Throwable redacted = ErrorLogRedactor.redactThrowable(original);

    assertNotNull(redacted);
    assertFalse(redacted.getMessage().contains("sk-secret123"), redacted.getMessage());
    // 原始类型与原因链保留，便于排查
    assertTrue(redacted.getMessage().contains("IllegalStateException"));
    assertSame(original, redacted.getCause());
  }

  @Test
  void returnsSameThrowableWhenNothingToRedact() {
    // 无谓的包装会让日志里的异常类型变化，给排查增加噪声。
    Throwable original = new IllegalStateException("plain message");

    assertSame(original, ErrorLogRedactor.redactThrowable(original));
  }

  @Test
  void handlesThrowableWithoutMessage() {
    Throwable original = new IllegalStateException();

    assertSame(original, ErrorLogRedactor.redactThrowable(original));
  }

  @Test
  void handlesNullThrowable() {
    assertNull(ErrorLogRedactor.redactThrowable(null));
  }

  // ---- ErrorLog 入口 ----

  @Test
  void errorLogRedactsEveryFieldBeforeReachingTheSink() {
    // 调用方不必记得脱敏：漏一处就是一次凭据泄露。
    final String[] captured = new String[4];
    ErrorLog.Sink previous = null;
    try {
      ErrorLog.setSink(
          (type, summary, throwable, details) -> {
            captured[0] = type;
            captured[1] = summary;
            captured[2] = throwable == null ? null : throwable.getMessage();
            captured[3] = details;
          });

      ErrorLog.record(
          "api",
          "failed calling https://x.example.com?key=sk-summary-secret",
          new IllegalStateException("body: {\"api_key\":\"sk-throwable-secret\"}"),
          "Authorization: Bearer sk-details-secret");

      assertFalse(captured[1].contains("sk-summary-secret"), "summary 未脱敏：" + captured[1]);
      assertFalse(captured[2].contains("sk-throwable-secret"), "throwable 未脱敏：" + captured[2]);
      assertFalse(captured[3].contains("sk-details-secret"), "details 未脱敏：" + captured[3]);
    } finally {
      ErrorLog.setSink(null);
      // 恢复可能存在的既有 sink 不必要：测试进程内没有其它使用者。
      assertNull(previous);
    }
  }

  @Test
  void errorLogSurvivesSinkThrowing() {
    // 日志失败不得影响主流程。
    try {
      ErrorLog.setSink(
          (type, summary, throwable, details) -> {
            throw new RuntimeException("sink is broken");
          });

      ErrorLog.record("api", "summary", null, "details");
      // 走到这里即通过：异常被吞掉并退回默认通道
    } finally {
      ErrorLog.setSink(null);
    }
  }
}
