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

package com.tom.rv2ide.ai.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 钉住各服务商的思考参数策略：匹配规则与写进请求体的字段名。
 * 这些字段名与服务商 API 强耦合，写错会直接 400，因此逐个锁死。
 */
final class ReasoningStrategiesTest {

  private static ReasoningRequestContext context(boolean enabled, String effort) {
    return new ReasoningRequestContext(enabled, effort, false, "", "", 4096);
  }

  private static ReasoningRequestContext context(boolean enabled, String effort,
                                                 boolean preserve, String baseUrl, String modelId) {
    return new ReasoningRequestContext(enabled, effort, preserve, baseUrl, modelId, 4096);
  }

  @Test
  void deepseekWritesThinkingAndEffort() throws Exception {
    DeepseekReasoningStrategy strategy = new DeepseekReasoningStrategy();
    assertTrue(strategy.matches("https://api.deepseek.com", "x"));
    assertTrue(strategy.matches("https://x", "deepseek-chat"));
    assertFalse(strategy.matches("https://x", "gpt"));
    JSONObject body = new JSONObject();
    strategy.apply(body, context(true, "high"));
    assertEquals("enabled", body.getJSONObject("thinking").getString("type"));
    assertEquals("high", body.getString("reasoning_effort"));
  }

  @Test
  void deepseekDisabledOmitsEffort() throws Exception {
    JSONObject body = new JSONObject();
    new DeepseekReasoningStrategy().apply(body, context(false, "high"));
    assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
    assertFalse(body.has("reasoning_effort"));
  }

  @Test
  void dashscopeWritesEnableThinkingAndPreserveFlag() throws Exception {
    DashscopeReasoningStrategy strategy = new DashscopeReasoningStrategy();
    assertTrue(strategy.matches("https://dashscope.aliyuncs.com", "x"));
    assertTrue(strategy.matches("https://x", "qwen-max"));
    assertFalse(strategy.matches("https://x", "gpt"));
    JSONObject body = new JSONObject();
    strategy.apply(body, context(true, "high", true, "b", "m"));
    assertTrue(body.getBoolean("enable_thinking"));
    assertEquals(4096, body.getInt("thinking_budget"));
    assertTrue(body.getBoolean("preserve_thinking"));
  }

  @Test
  void dashscopeDisabledDropsBudget() throws Exception {
    JSONObject body = new JSONObject();
    new DashscopeReasoningStrategy().apply(body, context(false, "high"));
    assertFalse(body.getBoolean("enable_thinking"));
    assertFalse(body.has("thinking_budget"));
  }

  @Test
  void minimaxWritesAdaptiveThinkingAndSplit() throws Exception {
    MinimaxReasoningStrategy strategy = new MinimaxReasoningStrategy();
    assertTrue(strategy.matches("https://api.minimax.chat", "x"));
    assertTrue(strategy.matches("https://x", "abab6"));
    assertTrue(strategy.matches("https://x", "m2-model"));
    assertFalse(strategy.matches("https://x", "gpt"));
    JSONObject enabled = new JSONObject();
    strategy.apply(enabled, context(true, "high"));
    assertEquals("adaptive", enabled.getJSONObject("thinking").getString("type"));
    assertTrue(enabled.getBoolean("reasoning_split"));

    JSONObject disabled = new JSONObject();
    strategy.apply(disabled, context(false, "high"));
    assertEquals("disabled", disabled.getJSONObject("thinking").getString("type"));
  }

  @Test
  void kimiForcesMinimumTemperature() throws Exception {
    KimiReasoningStrategy strategy = new KimiReasoningStrategy();
    assertTrue(strategy.matches("https://api.moonshot.cn", "x"));
    assertTrue(strategy.matches("https://x", "kimi-k2"));
    assertTrue(strategy.matches("https://x", "moonshot-v1"));
    assertFalse(strategy.matches("https://x", "gpt"));
    JSONObject body = new JSONObject();
    body.put("temperature", 0.2);
    strategy.apply(body, context(true, "high", true, "b", "m"));
    // Kimi 要求 temperature >= 1.0，低于会被服务端拒绝
    assertEquals(1.0, body.getDouble("temperature"));
    assertEquals("enabled", body.getJSONObject("thinking").getString("type"));
    assertEquals("all", body.getJSONObject("thinking").getString("keep"));
  }

  @Test
  void kimiKeepsHigherTemperature() throws Exception {
    JSONObject body = new JSONObject();
    body.put("temperature", 1.5);
    new KimiReasoningStrategy().apply(body, context(false, "high"));
    assertEquals(1.5, body.getDouble("temperature"));
    assertFalse(body.getJSONObject("thinking").has("keep"));
  }

  @Test
  void moonshotGlmWritesEffortAndClearThinking() throws Exception {
    MoonshotReasoningStrategy strategy = new MoonshotReasoningStrategy();
    assertTrue(strategy.matches("https://open.bigmodel.cn", "x"));
    assertTrue(strategy.matches("https://x", "glm-4"));
    assertTrue(strategy.matches("https://api.xiaomi.com", "mimo"));
    assertFalse(strategy.matches("https://x", "gpt"));
    JSONObject body = new JSONObject();
    strategy.apply(body, context(true, "high", true, "https://open.bigmodel.cn", "glm-4"));
    assertEquals("enabled", body.getJSONObject("thinking").getString("type"));
    assertEquals("high", body.getString("reasoning_effort"));
    assertFalse(body.getBoolean("clear_thinking"));
  }

  @Test
  void moonshotNonGlmOmitsEffort() throws Exception {
    JSONObject body = new JSONObject();
    new MoonshotReasoningStrategy().apply(body,
        context(true, "high", true, "https://api.xiaomi.com", "mimo"));
    assertEquals("enabled", body.getJSONObject("thinking").getString("type"));
    assertFalse(body.has("reasoning_effort"));
    assertFalse(body.has("clear_thinking"));
  }

  @Test
  void openAiChatWritesEffortForReasoningModels() throws Exception {
    OpenAiChatReasoningStrategy strategy = new OpenAiChatReasoningStrategy();
    assertTrue(strategy.matches("https://api.openai.com", "gpt-5"));
    assertTrue(strategy.matches("https://x", "o1-preview"));
    assertTrue(strategy.matches("https://x", "o3-mini"));
    assertTrue(strategy.matches("https://x", "o4-mini"));
    assertFalse(strategy.matches("https://x", "gpt-4o"));
    JSONObject body = new JSONObject();
    strategy.apply(body, context(true, "auto"));
    assertEquals("medium", body.getString("reasoning_effort"));
  }

  @Test
  void openAiChatMapsMaxToXhighAndSkipsWhenDisabled() throws Exception {
    JSONObject max = new JSONObject();
    new OpenAiChatReasoningStrategy().apply(max, context(true, "max"));
    assertEquals("xhigh", max.getString("reasoning_effort"));

    JSONObject disabled = new JSONObject();
    new OpenAiChatReasoningStrategy().apply(disabled, context(false, "high"));
    assertFalse(disabled.has("reasoning_effort"));
  }

  @Test
  void defaultStrategyAlwaysMatchesAndWritesNestedEffort() throws Exception {
    DefaultReasoningStrategy strategy = new DefaultReasoningStrategy();
    assertTrue(strategy.matches("anything", "anything"));
    JSONObject body = new JSONObject();
    strategy.apply(body, context(true, "max"));
    assertEquals("xhigh", body.getJSONObject("reasoning").getString("effort"));

    JSONObject disabled = new JSONObject();
    strategy.apply(disabled, context(false, "high"));
    assertFalse(disabled.has("reasoning"));
  }

  @Test
  void registryFindsFirstMatchElseNull() {
    ReasoningStrategyRegistry registry = new ReasoningStrategyRegistry();
    registry.register(new DeepseekReasoningStrategy());
    assertEquals(DeepseekReasoningStrategy.class,
        registry.find("https://api.deepseek.com", "x").getClass());
    assertNull(registry.find("https://api.openai.com", "gpt-4o"));
  }
}
