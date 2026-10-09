package com.tom.rv2ide.ai.agent.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolArgsCleaner;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** 事故数据的实样验证：确认 cleaner 对 a865be80 会话中那条残缺参数的实际修复结果。 */
final class ToolArgsCleanerSanityTest {

  @Test
  void repairsTruncatedRendererCppArguments() {
    // 实样：a865be80 会话 line 47，file_write 的参数在流中断处截止
    String truncated = "{\"file_path\": \"app/src/main/cpp/Renderer.cpp\"";
    String cleaned = ToolArgsCleaner.clean(truncated).trim();
    assertEquals("{\"file_path\": \"app/src/main/cpp/Renderer.cpp\"}", cleaned);
    // 必须能被服务端接受（解析为 JSON 对象）
    JSONObject parsed = new JSONObject(cleaned);
    assertEquals("app/src/main/cpp/Renderer.cpp", parsed.optString("file_path"));
  }

  @Test
  void unrecoverableGarbageStaysInvalid() {
    String garbage = "not json at all {{{";
    String cleaned = ToolArgsCleaner.clean(garbage).trim();
    boolean valid;
    try {
      new JSONObject(cleaned);
      valid = true;
    } catch (org.json.JSONException e) {
      valid = false;
    }
    assertTrue(!valid, "纯文本垃圾不应被修成合法 JSON（否则应保留，实测修不成）: " + cleaned);
  }
}
