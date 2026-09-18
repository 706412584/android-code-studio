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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.tom.rv2ide.ai.protocol.ModelProtocolType;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 锁定服务商记录的行为。
 *
 * <p>重点是三类容易出错的地方：槽位回退规则、上下文后缀的剥离（后缀不能发给 API）、
 * 以及 JSON 往返（配置文件读写一旦丢字段，用户的服务商就变成空记录）。
 */
public class ProviderConfigTest {

  private static ProviderConfig config(String... slots) {
    return new ProviderConfig(
        "test", "Test", ModelProtocolType.OPENAI_COMPATIBLE, "https://api.example.com/v1", "sk-1", slots);
  }

  @Test
  public void emptySlotFallsBackToMain() {
    ProviderConfig c = config("main-model", "", "", "");
    // 「空 = 与 main 相同」：用户往往只填主模型，其余槽位留空表示也用主模型。
    assertEquals("main-model", c.resolveSlot(ProviderConfig.SLOT_HAIKU));
    assertEquals("main-model", c.resolveSlot(ProviderConfig.SLOT_OPUS));
  }

  @Test
  public void explicitSlotWins() {
    ProviderConfig c = config("main-model", "fast-model", "", "");
    assertEquals("fast-model", c.resolveSlot(ProviderConfig.SLOT_HAIKU));
    assertEquals("main-model", c.resolveSlot(ProviderConfig.SLOT_SONNET));
  }

  @Test
  public void unknownSlotIsEmpty() {
    assertEquals("", config("m").getSlotModel("nonexistent"));
  }

  @Test
  public void slotsNormalizedToFour() {
    // 只给 2 个槽位，其余补空而不是抛异常或返回长度不足的数组。
    ProviderConfig c = config("a", "b");
    assertEquals(4, c.getSlotModels().length);
    assertEquals("", c.getSlotModel(ProviderConfig.SLOT_OPUS));
  }

  @Test
  public void contextSuffixStrippedForApi() {
    // 后缀是本地元数据，绝不能发给服务端——[1m] 不是合法模型 ID。
    ProviderConfig c = config("deepseek-v4-pro[1m]");
    assertEquals("deepseek-v4-pro", c.apiModelId(ProviderConfig.SLOT_MAIN));
    assertEquals(1000000, c.contextSize(ProviderConfig.SLOT_MAIN));
  }

  @Test
  public void apiModelIdUnchangedWithoutSuffix() {
    ProviderConfig c = config("deepseek-chat");
    assertEquals("deepseek-chat", c.apiModelId(ProviderConfig.SLOT_MAIN));
    assertEquals(ContextSizeParser.UNSET, c.contextSize(ProviderConfig.SLOT_MAIN));
  }

  @Test
  public void usableRequiresBaseUrlAndMainModel() {
    assertTrue(config("m").isUsable());
    assertFalse(
        new ProviderConfig("x", "X", ModelProtocolType.OPENAI_COMPATIBLE, "", "k", new String[] {"m"})
            .isUsable());
    assertFalse(
        new ProviderConfig(
                "x", "X", ModelProtocolType.OPENAI_COMPATIBLE, "https://a/v1", "k", new String[] {""})
            .isUsable());
  }

  @Test
  public void localProviderUsableWithoutApiKey() {
    // Ollama / LM Studio 不需要密钥，判据里不能强制要求它。
    ProviderConfig local =
        new ProviderConfig(
            "ollama",
            "Ollama",
            ModelProtocolType.OPENAI_COMPATIBLE,
            "http://127.0.0.1:11434/v1",
            "",
            new String[] {"qwen2.5-coder:7b"});
    assertTrue(local.isUsable());
    assertTrue(local.needsApiKey());
  }

  @Test
  public void jsonRoundTripPreservesEverything() throws Exception {
    ProviderConfig original = config("m[1m]", "h", "s", "o");
    ProviderConfig restored = ProviderConfig.fromJson(new JSONObject(original.toJson().toString()));

    assertEquals(original.getId(), restored.getId());
    assertEquals(original.getLabel(), restored.getLabel());
    assertEquals(original.getProtocolType(), restored.getProtocolType());
    assertEquals(original.getBaseUrl(), restored.getBaseUrl());
    assertEquals(original.getApiKey(), restored.getApiKey());
    for (String slot : ProviderConfig.SLOT_ORDER) {
      assertEquals(original.getSlotModel(slot), restored.getSlotModel(slot));
    }
  }

  @Test
  public void fromJsonToleratesMissingFields() throws Exception {
    // 配置文件可能来自旧版本。缺字段要退化成「未配置」，不能让整表加载失败。
    ProviderConfig c = ProviderConfig.fromJson(new JSONObject());
    assertEquals("", c.getId());
    assertEquals("", c.getBaseUrl());
    assertEquals("", c.getMainModel());
    assertEquals(ModelProtocolType.OPENAI_COMPATIBLE, c.getProtocolType());
  }

  @Test
  public void fromJsonToleratesUnknownProtocol() throws Exception {
    JSONObject json = new JSONObject();
    json.put("id", "x");
    json.put("protocol", "some-future-protocol");
    assertEquals(ModelProtocolType.OPENAI_COMPATIBLE, ProviderConfig.fromJson(json).getProtocolType());
  }

  @Test
  public void labelFallsBackToId() {
    ProviderConfig c =
        new ProviderConfig("my-id", "", ModelProtocolType.OPENAI_COMPATIBLE, "u", "k", new String[] {"m"});
    assertEquals("my-id", c.getLabel());
  }

  @Test
  public void withApiKeyKeepsOtherFields() {
    ProviderConfig c = config("m[1m]").withApiKey("new-key");
    assertEquals("new-key", c.getApiKey());
    assertEquals("m[1m]", c.getMainModel());
    assertEquals("https://api.example.com/v1", c.getBaseUrl());
  }

  @Test
  public void anthropicProtocolPreserved() {
    ProviderConfig c =
        new ProviderConfig(
            "claude",
            "Claude",
            ModelProtocolType.ANTHROPIC_MESSAGES,
            "https://api.anthropic.com",
            "k",
            new String[] {"claude-sonnet-4-5"});
    assertEquals(ModelProtocolType.ANTHROPIC_MESSAGES, c.getProtocolType());
  }
}
