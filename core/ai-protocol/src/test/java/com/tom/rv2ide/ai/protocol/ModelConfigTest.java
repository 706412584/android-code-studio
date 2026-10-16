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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 钉住 {@link ModelConfig} 的规范化、Builder、JSON 往返与 copy-on-write 语义。
 * 这些行为决定「配置写盘再读回是否等价」以及兼容旧字段名。
 */
public final class ModelConfigTest {

  private static ModelConfig base() {
    return ModelConfig.builder(
            "id1", "我的模型", ModelProtocolType.OPENAI_COMPATIBLE,
            "标签", "https://api.example.com", "sk-x", "gpt-x")
        .build();
  }

  @Test
  public void builderAppliesDefaults() {
    ModelConfig c = base();
    assertEquals("id1", c.getId());
    assertEquals("我的模型", c.getName());
    assertEquals(ModelProtocolType.OPENAI_COMPATIBLE, c.getProtocolType());
    assertEquals("标签", c.getProviderLabel());
    assertEquals("https://api.example.com", c.getBaseUrl());
    assertEquals("sk-x", c.getApiKey());
    assertEquals("gpt-x", c.getModelId());
    assertEquals(ModelConfig.DEFAULT_TOOL_CALL_LIMIT, c.getToolCallLimit());
    assertFalse(c.isCompressionModelEnabled());
    assertTrue(c.isCompressionModelAuto());
    assertEquals(ModelConfig.CONTEXT_SIZE_UNSET, c.getContextSize());
    assertEquals("", c.getGroupId());
    assertEquals("", c.getSlotRole());
  }

  @Test
  public void nullProtocolFallsBackToOpenAi() {
    ModelConfig c = new ModelConfig("i", "n", null, null, "b", "k", "m", 0, false, true, "", 0);
    assertEquals(ModelProtocolType.OPENAI_COMPATIBLE, c.getProtocolType());
    // providerLabel 缺省时取协议类型的 label
    assertEquals("OpenAI", c.getProviderLabel());
  }

  @Test
  public void nullFieldsBecomeEmptyStrings() {
    ModelConfig c = new ModelConfig(null, null, null, null, null, null, null, 0, false, true, null, 0);
    assertEquals("", c.getId());
    assertEquals("", c.getName());
    assertEquals("", c.getBaseUrl());
    assertEquals("", c.getApiKey());
    assertEquals("", c.getModelId());
    assertEquals("", c.getCompressionModelId());
  }

  @Test
  public void toolCallLimitNormalization() {
    assertEquals(-1, ModelConfig.normalizeToolCallLimit(-1));
    assertEquals(0, ModelConfig.normalizeToolCallLimit(-5));
    assertEquals(7, ModelConfig.normalizeToolCallLimit(7));
  }

  @Test
  public void negativeContextSizeBecomesUnset() {
    ModelConfig c = new ModelConfig("i", "n", ModelProtocolType.OPENAI_COMPATIBLE, "l", "b", "k", "m",
        0, false, true, "", -3);
    assertEquals(ModelConfig.CONTEXT_SIZE_UNSET, c.getContextSize());
    assertEquals(1234, c.withContextSize(1234).getContextSize());
  }

  @Test
  public void dedicatedCompressionOnlyForSupportingProtocols() {
    // Anthropic 不支持独立压缩模型：即便显式开启也应被否定
    ModelConfig anthropic = new ModelConfig("i", "n", ModelProtocolType.ANTHROPIC_MESSAGES, "l", "b", "k", "m",
        0, true, false, "small-model", 0);
    assertFalse(anthropic.isCompressionModelEnabled());

    ModelConfig openai = ModelConfig.builder("i", "n", ModelProtocolType.OPENAI_COMPATIBLE, "l", "b", "k", "m")
        .compressionModelEnabled(true)
        .compressionModelAuto(false)
        .compressionModelId("cheap")
        .build();
    assertTrue(openai.isCompressionModelEnabled());
    assertEquals("cheap", openai.getEffectiveCompressionModelId());
  }

  @Test
  public void effectiveCompressionModelIdFallsBackToMainModel() {
    // 未启用 / 自动 / 空 id 三种情况都回退到主模型
    ModelConfig disabled = base();
    assertEquals("gpt-x", disabled.getEffectiveCompressionModelId());

    ModelConfig auto = ModelConfig.builder("i", "n", ModelProtocolType.OPENAI_COMPATIBLE, "l", "b", "k", "m")
        .compressionModelEnabled(true).compressionModelAuto(true).compressionModelId("cheap").build();
    assertEquals("m", auto.getEffectiveCompressionModelId());

    ModelConfig blank = ModelConfig.builder("i", "n", ModelProtocolType.OPENAI_COMPATIBLE, "l", "b", "k", "m")
        .compressionModelEnabled(true).compressionModelAuto(false).compressionModelId("  ").build();
    assertEquals("m", blank.getEffectiveCompressionModelId());
  }

  @Test
  public void effectiveSlotRoleDefaultsToMain() {
    assertEquals(ModelConfig.SLOT_MAIN, base().getEffectiveSlotRole());
    assertEquals(ModelConfig.SLOT_OPUS,
        base().withGroup("g", ModelConfig.SLOT_OPUS).getEffectiveSlotRole());
  }

  @Test
  public void builderSettersAreAllApplied() {
    ModelConfig c = ModelConfig.builder("i", "n", ModelProtocolType.OPENAI_COMPATIBLE, "l", "b", "k", "m")
        .providerLabel("自定义")
        .toolCallLimit(9)
        .compressionModelEnabled(true)
        .compressionModelAuto(false)
        .compressionModelId("cheap")
        .contextSize(64000)
        .groupId("g1")
        .slotRole(ModelConfig.SLOT_HAIKU)
        .build();
    assertEquals("自定义", c.getProviderLabel());
    assertEquals(9, c.getToolCallLimit());
    assertEquals("cheap", c.getCompressionModelId());
    assertEquals(64000, c.getContextSize());
    assertEquals("g1", c.getGroupId());
    assertEquals(ModelConfig.SLOT_HAIKU, c.getSlotRole());
  }

  @Test
  public void jsonRoundTripPreservesAllFields() throws Exception {
    ModelConfig original = ModelConfig.builder("i1", "n1", ModelProtocolType.ANTHROPIC_MESSAGES, "ant", "u", "k", "claude")
        .toolCallLimit(3).contextSize(200000).groupId("g").slotRole("sonnet").build();
    ModelConfig restored = ModelConfig.fromJson(original.toJson());
    assertEquals(original.getId(), restored.getId());
    assertEquals(original.getProtocolType(), restored.getProtocolType());
    assertEquals(original.getProviderLabel(), restored.getProviderLabel());
    assertEquals(original.getToolCallLimit(), restored.getToolCallLimit());
    assertEquals(original.getContextSize(), restored.getContextSize());
    assertEquals(original.getGroupId(), restored.getGroupId());
    assertEquals(original.getSlotRole(), restored.getSlotRole());
  }

  @Test
  public void fromJsonAcceptsLegacyKeyNames() {
    JSONObject legacy = new JSONObject();
    legacy.put("id", "i");
    legacy.put("provider", "anthropic");
    legacy.put("modelId", "m");
    legacy.put("tool_call_limit", 5);
    legacy.put("compression_model_enabled", true);
    legacy.put("compression_model_id", "cheap");
    legacy.put("context_size", 1234);
    ModelConfig c = ModelConfig.fromJson(legacy);
    assertEquals(ModelProtocolType.ANTHROPIC_MESSAGES, c.getProtocolType());
    assertEquals(5, c.getToolCallLimit());
    assertEquals("cheap", c.getCompressionModelId());
    assertEquals(1234, c.getContextSize());
    // providerLabel 缺省时按协议类型 label 补齐
    assertEquals("Anthropic", c.getProviderLabel());
  }

  @Test
  public void fromJsonFallsBackToLocalModelFileName() {
    JSONObject object = new JSONObject();
    object.put("protocolType", "local");
    JSONObject local = new JSONObject();
    local.put("fileName", "qwen.gguf");
    object.put("localModel", local);
    assertEquals("qwen.gguf", ModelConfig.fromJson(object).getModelId());
  }

  @Test
  public void fromJsonFallsBackToLocalModelPath() {
    JSONObject object = new JSONObject();
    JSONObject local = new JSONObject();
    local.put("localPath", "/sdcard/qwen.gguf");
    object.put("localModel", local);
    assertEquals("/sdcard/qwen.gguf", ModelConfig.fromJson(object).getModelId());
  }

  @Test
  public void fromJsonNegativeContextSizeIsUnset() {
    JSONObject object = new JSONObject();
    object.put("contextSize", -9);
    assertEquals(ModelConfig.CONTEXT_SIZE_UNSET, ModelConfig.fromJson(object).getContextSize());
  }

  @Test
  public void copyOnWriteHelpersReturnNewInstances() {
    ModelConfig c = base();
    assertEquals("next", c.withId("next").getId());
    assertEquals("new-model", c.withModelId("new-model").getModelId());
    assertEquals("id1", c.getId());
    assertEquals("gpt-x", c.getModelId());
  }
}
