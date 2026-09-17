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

package com.tom.rv2ide.artificial.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.tom.rv2ide.ai.protocol.ModelProtocolType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * 服务商预设表的回归测试。
 *
 * <p><b>为什么需要它</b>：预设表是「加一个服务商要改几处」这个问题的答案——表本身
 * 有错（重复 id、空 baseUrl、模型列表为空、默认服务商没有协议实现）时，症状是
 * 「设置里能选但请求必然失败」，而这在真机上很难归因到一张表。
 */
public class ProviderPresetsTest {

  /** 只给指定服务商提供密钥。 */
  private static ProviderPresets.ApiKeyLookup lookupFor(final String... ids) {
    final Set<String> withKey = new HashSet<>(java.util.Arrays.asList(ids));
    return new ProviderPresets.ApiKeyLookup() {
      @Override
      public String keyFor(String providerId) {
        return withKey.contains(providerId) ? "sk-test-key-1234567890" : "";
      }
    };
  }

  @Test
  public void idsAreUnique() {
    // 重复 id 会让 find() 只返回第一个，另一个永远选不中。
    Set<String> seen = new HashSet<>();
    for (ProviderPresets.Preset preset : ProviderPresets.all()) {
      assertTrue("重复的服务商 id: " + preset.getId(), seen.add(preset.getId()));
    }
  }

  @Test
  public void everyPresetHasLabelAndNonEmptyModels() {
    for (ProviderPresets.Preset preset : ProviderPresets.all()) {
      assertFalse("服务商 " + preset.getId() + " 缺少显示名", preset.getLabel().isEmpty());
      // custom 例外：它的模型名由用户填写，预设里无从给出。
      if ("custom".equals(preset.getId())) {
        continue;
      }
      assertFalse("服务商 " + preset.getId() + " 没有模型列表", preset.getModels().isEmpty());
      assertFalse("服务商 " + preset.getId() + " 缺少推荐模型", preset.getDefaultModel().isEmpty());
    }
  }

  @Test
  public void remotePresetsHaveBaseUrl() {
    // 远程服务商必须预设 baseUrl，否则用户选中后无端点可用。
    for (ProviderPresets.Preset preset : ProviderPresets.all()) {
      if ("custom".equals(preset.getId()) || "localllm".equals(preset.getId())) {
        continue;
      }
      assertFalse(
          "远程服务商 " + preset.getId() + " 的 baseUrl 为空", preset.getBaseUrl().isEmpty());
      assertTrue(
          "服务商 " + preset.getId() + " 的 baseUrl 应当是 http(s)",
          preset.getBaseUrl().startsWith("http"));
    }
  }

  @Test
  public void everyRemotePresetRequiresApiKey() {
    // 远程服务商必须有密钥才算配置完成；漏标会让请求带着空 key 发出去，
    // 得到的是 401 而不是「请先配置密钥」。
    for (ProviderPresets.Preset preset : ProviderPresets.all()) {
      if ("localllm".equals(preset.getId()) || "custom".equals(preset.getId())) {
        continue;
      }
      assertTrue(
          "远程服务商 " + preset.getId() + " 应当要求密钥", preset.isRequiresApiKey());
      assertFalse(
          "服务商 " + preset.getId() + " 应当给出密钥申请地址", preset.getApiKeyHelpUrl().isEmpty());
    }
  }

  @Test
  public void defaultProviderHasAProtocolImplementation() {
    // 这条是本次修复的核心：默认服务商必须能被协议层真正执行。
    // 协议层只注册了 OPENAI_COMPATIBLE 与 ANTHROPIC_MESSAGES；
    // 若默认值指向 gemini（Google 自有协议），首次使用者在配好密钥后仍发不出任何消息。
    ProviderPresets.Preset fallback = ProviderPresets.find(ProviderPresets.DEFAULT_PROVIDER_ID);

    assertNotNull("默认服务商必须存在于预设表", fallback);
    assertTrue(
        "默认服务商必须使用已实现的协议",
        fallback.getProtocolType() == ModelProtocolType.OPENAI_COMPATIBLE
            || fallback.getProtocolType() == ModelProtocolType.ANTHROPIC_MESSAGES);
  }

  @Test
  public void defaultProviderIsUsableWithJustAKey() {
    // 默认服务商不应要求用户额外填写 baseUrl 或模型名。
    ProviderPresets.Preset preset = ProviderPresets.find(ProviderPresets.DEFAULT_PROVIDER_ID);
    AgentModelConfigs.ProviderEndpoint endpoint =
        ProviderPresets.endpointFor(
            ProviderPresets.DEFAULT_PROVIDER_ID, null, lookupFor(ProviderPresets.DEFAULT_PROVIDER_ID));

    assertNotNull("填了密钥就应当能解析出端点", endpoint);
    assertFalse(endpoint.baseUrl.isEmpty());
    assertFalse(preset.getDefaultModel().isEmpty());
  }

  @Test
  public void endpointIsNullWhenKeyMissing() {
    // 缺密钥时返回 null，调用方据此提示「请先配置密钥」而不是发一个注定 401 的请求。
    assertNull(ProviderPresets.endpointFor("openai", null, lookupFor()));
    assertNull(ProviderPresets.endpointFor("deepseek", null, lookupFor()));
  }

  @Test
  public void endpointIsNullForUnknownProvider() {
    assertNull(ProviderPresets.endpointFor("nonexistent", null, lookupFor("nonexistent")));
    assertNull(ProviderPresets.endpointFor(null, null, lookupFor("openai")));
  }

  @Test
  public void localProviderWorksWithoutApiKey() {
    // 本地服务（llama.cpp / Ollama / LM Studio）通常无鉴权，不应要求密钥。
    ProviderPresets.Preset preset = ProviderPresets.find("localllm");
    assertFalse(preset.isRequiresApiKey());

    AgentModelConfigs.ProviderEndpoint endpoint =
        ProviderPresets.endpointFor("localllm", "http://192.168.1.5:1234/v1", lookupFor());
    assertNotNull(endpoint);
    assertEquals("http://192.168.1.5:1234/v1", endpoint.baseUrl);
    // 无鉴权服务仍需一个非空的占位值，否则请求头里会出现空 Authorization。
    assertFalse(endpoint.apiKey.isEmpty());
  }

  @Test
  public void customBaseUrlOverridesPreset() {
    AgentModelConfigs.ProviderEndpoint endpoint =
        ProviderPresets.endpointFor("openai", "https://proxy.example.com/v1", lookupFor("openai"));

    assertNotNull(endpoint);
    assertEquals("https://proxy.example.com/v1", endpoint.baseUrl);
  }

  @Test
  public void customPresetNeedsUserSuppliedBaseUrl() {
    // 自定义端点的预设 baseUrl 是空的，必须由用户提供；没有就返回 null，
    // 否则会拿空 URL 去发请求。
    assertNull(ProviderPresets.endpointFor("custom", null, lookupFor("custom")));
    assertNotNull(
        ProviderPresets.endpointFor("custom", "https://gw.example.com/v1", lookupFor("custom")));
  }

  @Test
  public void anthropicUsesMessagesProtocol() {
    // 协议选错会让请求格式与服务端不匹配（Anthropic 不接受 OpenAI 的 messages 结构）。
    assertEquals(
        ModelProtocolType.ANTHROPIC_MESSAGES,
        ProviderPresets.find("claude").getProtocolType());
  }

  @Test
  public void openAiCompatibleProvidersShareOneProtocol() {
    // 这些服务商讲同一种协议，只是 baseUrl 与模型名不同——这正是「加一行即可接入」
    // 的前提。若某个被误标成别的协议，接入成本就不再是一行。
    String[] ids = {"openai", "deepseek", "grok", "groq", "glm", "kimi", "qwen", "openrouter"};
    for (String id : ids) {
      ProviderPresets.Preset preset = ProviderPresets.find(id);
      assertNotNull("预设表缺少 " + id, preset);
      assertEquals(
          "服务商 " + id + " 应当是 OpenAI 兼容协议",
          ModelProtocolType.OPENAI_COMPATIBLE,
          preset.getProtocolType());
    }
  }

  @Test
  public void labelForFallsBackToId() {
    // 未知服务商显示 id 本身，比显示空白更利于定位问题。
    assertEquals("some-new-provider", ProviderPresets.labelFor("some-new-provider"));
    assertEquals("", ProviderPresets.labelFor(null));
    assertEquals("OpenAI", ProviderPresets.labelFor("openai"));
  }

  @Test
  public void modelsForReturnsEmptyForUnknownProvider() {
    assertTrue(ProviderPresets.modelsFor("nonexistent").isEmpty());
    assertTrue(ProviderPresets.modelsFor(null).isEmpty());
    assertFalse(ProviderPresets.modelsFor("deepseek").isEmpty());
  }

  @Test
  public void providerForModelFindsOwningProvider() {
    assertEquals("deepseek", ProviderPresets.providerForModel("deepseek-chat"));
    assertEquals("claude", ProviderPresets.providerForModel("claude-sonnet-4-5-20250929"));
    assertNull(ProviderPresets.providerForModel("no-such-model"));
    assertNull(ProviderPresets.providerForModel(null));
  }

  @Test
  public void allIdsMatchesAllPresetsInOrder() {
    List<ProviderPresets.Preset> presets = ProviderPresets.all();
    List<String> ids = ProviderPresets.allIds();

    assertEquals(presets.size(), ids.size());
    for (int i = 0; i < presets.size(); i++) {
      assertEquals(presets.get(i).getId(), ids.get(i));
    }
  }

  @Test
  public void modelListsAreNotSharedBetweenPresets() {
    // 预设之间共享同一个可变列表的话，一处改动会污染另一处。
    Map<String, List<String>> byId = new HashMap<>();
    for (ProviderPresets.Preset preset : ProviderPresets.all()) {
      byId.put(preset.getId(), preset.getModels());
    }
    assertEquals(ProviderPresets.all().size(), byId.size());
    // getModels() 返回不可修改视图，试图改动应当抛异常
    try {
      ProviderPresets.find("openai").getModels().add("injected");
      org.junit.Assert.fail("模型列表应当是只读的");
    } catch (UnsupportedOperationException expected) {
      // 预期
    }
  }

  @Test
  public void modelNamesAreNotBlank() {
    for (ProviderPresets.Preset preset : ProviderPresets.all()) {
      for (String model : preset.getModels()) {
        assertFalse(
            "服务商 " + preset.getId() + " 含空白模型名", model == null || model.trim().isEmpty());
      }
    }
  }

  @Test
  public void defaultProviderIdIsPresentInTable() {
    assertNotNull(ProviderPresets.find(ProviderPresets.DEFAULT_PROVIDER_ID));
    assertTrue(ProviderPresets.allIds().contains(ProviderPresets.DEFAULT_PROVIDER_ID));
  }

  @Test
  public void endpointLabelComesFromPreset() {
    AgentModelConfigs.ProviderEndpoint endpoint =
        ProviderPresets.endpointFor("deepseek", null, lookupFor("deepseek"));

    assertNotNull(endpoint);
    assertEquals("deepseek", endpoint.id);
    assertEquals(ProviderPresets.labelFor("deepseek"), endpoint.label);
    assertEquals("sk-test-key-1234567890", endpoint.apiKey);
  }
}
