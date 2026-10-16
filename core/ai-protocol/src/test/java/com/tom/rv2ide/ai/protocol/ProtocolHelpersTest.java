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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolCall;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 钉住若干协议层纯函数/小类的行为：URL 策略、标识符校验、模型 id 后缀解析、
 * 模板渲染、稳定 JSON、代理判定、错误分类与取消令牌。
 */
public final class ProtocolHelpersTest {

  // ---- UrlPolicy ----

  @Test
  public void normalizesHttpAndHttpsOnly() {
    assertEquals("https://a.com", UrlPolicy.normalizeHttpOrHttpsUrl("  https://a.com "));
    assertEquals("http://a.com", UrlPolicy.normalizeHttpOrHttpsUrl("http://a.com"));
    assertEquals("", UrlPolicy.normalizeHttpOrHttpsUrl("ftp://a.com"));
    assertEquals("", UrlPolicy.normalizeHttpOrHttpsUrl(""));
    assertEquals("", UrlPolicy.normalizeHttpOrHttpsUrl(null));
    assertEquals("", UrlPolicy.normalizeHttpOrHttpsUrl("not a url"));
  }

  @Test
  public void cleartextHttpAllowedOnlyForLocalHosts() {
    assertEquals("http://localhost:8080/x", UrlPolicy.normalizeHttpOrLocalCleartextUrl("http://localhost:8080/x"));
    assertEquals("http://127.0.0.1", UrlPolicy.normalizeHttpOrLocalCleartextUrl("http://127.0.0.1"));
    assertEquals("http://10.0.2.2", UrlPolicy.normalizeHttpOrLocalCleartextUrl("http://10.0.2.2"));
    assertEquals("http://192.168.1.10", UrlPolicy.normalizeHttpOrLocalCleartextUrl("http://192.168.1.10"));
    assertEquals("http://172.20.0.1", UrlPolicy.normalizeHttpOrLocalCleartextUrl("http://172.20.0.1"));
    // 公网明文 HTTP 被拒
    assertEquals("", UrlPolicy.normalizeHttpOrLocalCleartextUrl("http://example.com"));
    // https 一律放行
    assertEquals("https://example.com", UrlPolicy.normalizeHttpOrLocalCleartextUrl("https://example.com"));
  }

  @Test
  public void publicDnsNamesResemblingPrivateRangesAreNotCleartextAllowed() {
    // 10.evil.com 不是字面 IPv4，不能因为以 "10." 开头就当作内网放行
    assertEquals("", UrlPolicy.normalizeHttpOrLocalCleartextUrl("http://10.evil.com"));
    assertEquals("", UrlPolicy.normalizeHttpOrLocalCleartextUrl("http://192.168.attacker.example"));
  }

  @Test
  public void requireThrowsForNonHttpAndPublicCleartext() {
    assertEquals("https://a.com", UrlPolicy.requireHttpOrLocalCleartextUrl("https://a.com", "模型地址"));
    IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> UrlPolicy.requireHttpOrLocalCleartextUrl("ftp://a.com", "模型地址"));
    assertTrue(e.getMessage().contains("模型地址"));
    assertTrue(e.getMessage().contains("must start with"));
    IllegalArgumentException e2 = org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> UrlPolicy.requireHttpOrLocalCleartextUrl("http://example.com", null));
    assertTrue(e2.getMessage().startsWith("URL"));
  }

  @Test
  public void relaxedModeAllowsAnyCleartextHttp() {
    UrlPolicy policy = UrlPolicy.getDefault();
    try {
      policy.setRelaxedHttpEnabled(true);
      assertTrue(UrlPolicy.isRelaxedHttpEnabled());
      assertEquals("http://example.com", UrlPolicy.normalizeHttpOrLocalCleartextUrl("http://example.com"));
      assertTrue(UrlPolicy.isAllowedCleartextHttpUrl("http://example.com"));
    } finally {
      policy.setRelaxedHttpEnabled(false);
    }
    assertFalse(UrlPolicy.isAllowedCleartextHttpUrl("http://example.com"));
    assertTrue(UrlPolicy.isAllowedCleartextHttpUrl("http://localhost"));
    assertFalse(UrlPolicy.isAllowedCleartextHttpUrl("https://example.com"));
  }

  @Test
  public void customPolicyTracksAddedCleartextHosts() {
    UrlPolicy policy = new UrlPolicy();
    assertFalse(policy.isAllowedCleartextHttpHost("dev.internal"));
    policy.addCleartextHost("Dev.Internal");
    assertTrue(policy.isAllowedCleartextHttpHost("dev.internal"));
    // null / 空被忽略
    policy.addCleartextHost(null);
    policy.addCleartextHost("");
    assertFalse(policy.isAllowedCleartextHttpHost(null));
  }

  @Test
  public void privateNetworkPredicatesClassifyLiteralIpv4Ranges() {
    UrlPolicy policy = new UrlPolicy();
    assertTrue(policy.isPrivateNetwork("10.1.2.3"));
    assertTrue(policy.isPrivateNetwork("172.16.0.1"));
    assertTrue(policy.isPrivateNetwork("172.31.255.255"));
    assertFalse(policy.isPrivateNetwork("172.32.0.1"));
    assertTrue(policy.isPrivateNetwork("192.168.5.5"));
    assertTrue(policy.isPrivateNetwork("127.0.0.1"));
    assertFalse(policy.isPrivateNetwork("8.8.8.8"));
    assertFalse(policy.isPrivateNetwork("10.1.2"));
    assertFalse(policy.isPrivateNetwork("10.1.2.3.4"));
    assertFalse(policy.isPrivateNetwork("10.1.2.999"));
    assertFalse(policy.isPrivateNetwork("10.1.x.3"));
    assertFalse(policy.isPrivateNetwork("10.1.2.1234"));
  }

  @Test
  public void customPrivateNetworkPredicateIsConsulted() {
    UrlPolicy policy = new UrlPolicy();
    policy.addPrivateNetworkPredicate(host -> "internal".equals(host));
    policy.addPrivateNetworkPredicate(null);
    assertTrue(policy.isPrivateNetwork("internal"));
  }

  // ---- StringUtils ----

  @Test
  public void identifierValidationHonorsAllowedSpecials() {
    assertFalse(StringUtils.isAllowedIdentifier(null, ""));
    assertFalse(StringUtils.isAllowedIdentifier("", ""));
    assertTrue(StringUtils.isAllowedIdentifier("abc123", ""));
    assertFalse(StringUtils.isAllowedIdentifier("abc-123", ""));
    assertTrue(StringUtils.isAllowedIdentifier("abc-123", "-"));
    assertTrue(StringUtils.isAllowedIdentifier("a", null));
  }

  @Test
  public void isAllowedCharChecksSingleChar() {
    assertTrue(StringUtils.isAllowedChar('z', null));
    assertTrue(StringUtils.isAllowedChar('Z', null));
    assertTrue(StringUtils.isAllowedChar('0', null));
    assertFalse(StringUtils.isAllowedChar('_', null));
    assertTrue(StringUtils.isAllowedChar('_', "_"));
  }

  @Test
  public void unicodeEscapeDecoding() {
    assertEquals("中", StringUtils.decodeUnicodeEscapes("\\u4e2d"));
    assertEquals("A中B", StringUtils.decodeUnicodeEscapes("A\\u4e2dB"));
    // 大写十六进制同样解码
    assertEquals("A", StringUtils.decodeUnicodeEscapes("\\u0041"));
  }

  @Test
  public void unicodeEscapeDecodingLeavesNonMatchesAlone() {
    // Windows 路径里的 \Users 不能被误当转义
    assertEquals("C:\\Users\\x", StringUtils.decodeUnicodeEscapes("C:\\Users\\x"));
    // 不完整的转义原样保留
    assertEquals("\\u12", StringUtils.decodeUnicodeEscapes("\\u12"));
    // 非法十六进制原样保留
    assertEquals("\\uXYZ1", StringUtils.decodeUnicodeEscapes("\\uXYZ1"));
    assertEquals("short", StringUtils.decodeUnicodeEscapes("short"));
    assertNull(StringUtils.decodeUnicodeEscapes(null));
  }

  // ---- ModelContextParser ----

  @Test
  public void parsesContextSuffixWithUnits() {
    ModelContextInfo k = ModelContextParser.parse("gpt-4[128k]");
    assertEquals("gpt-4", k.getApiModelId());
    assertEquals(128000, k.getContextTokens());
    assertEquals("128k", k.getContextLabel());

    ModelContextInfo m = ModelContextParser.parse("claude[1m]");
    assertEquals(1000000, m.getContextTokens());
    assertEquals("1m", m.getContextLabel());

    ModelContextInfo plain = ModelContextParser.parse("x[200]");
    assertEquals(200, plain.getContextTokens());
  }

  @Test
  public void parserDefaultsWhenNoSuffix() {
    ModelContextInfo info = ModelContextParser.parse("gpt-4");
    assertEquals("gpt-4", info.getApiModelId());
    assertEquals(250000, info.getContextTokens());
    assertEquals("250k", info.getContextLabel());
    assertEquals("gpt-4", ModelContextParser.apiModelId("gpt-4"));
    assertEquals("", ModelContextParser.apiModelId((String) null));
  }

  @Test
  public void parserHandlesEmptyApiIdAfterStripping() {
    ModelContextInfo info = ModelContextParser.parse("[8k]");
    // 剥离后为空则保留原串，避免发出空 model
    assertEquals("[8k]", info.getApiModelId());
    assertEquals(8000, info.getContextTokens());
  }

  @Test
  public void configContextSizeTakesPrecedenceOverSuffix() {
    ModelConfig withExplicit = ModelConfig.builder("i", "n", ModelProtocolType.OPENAI_COMPATIBLE, "l", "b", "k", "m[8k]")
        .contextSize(64000).build();
    assertEquals(64000, ModelContextParser.parse(withExplicit).getContextTokens());
    // 显式设置时不再剥离后缀
    assertEquals("m[8k]", ModelContextParser.apiModelId(withExplicit));
    // 未设置时回退到后缀解析
    ModelConfig legacy = ModelConfig.builder("i", "n", ModelProtocolType.OPENAI_COMPATIBLE, "l", "b", "k", "m[8k]").build();
    assertEquals("m", ModelContextParser.apiModelId(legacy));
    assertEquals("", ModelContextParser.apiModelId((ModelConfig) null));
    assertEquals(250000, ModelContextParser.parse((ModelConfig) null).getContextTokens());
  }

  @Test
  public void formatContextSizeCoversAllMagnitudes() {
    assertEquals("500", ModelContextParser.formatContextSize(500));
    assertEquals("8k", ModelContextParser.formatContextSize(8000));
    assertEquals("1m", ModelContextParser.formatContextSize(1000000));
    assertEquals("1.5m", ModelContextParser.formatContextSize(1500000));
    assertEquals("1.5k", ModelContextParser.formatContextSize(1500));
  }

  // ---- StringTemplate ----

  @Test
  public void rendersKnownPlaceholdersAndKeepsUnknownOnes() {
    StringTemplate template = new StringTemplate("你好 {{name}}，未知 {{x}}");
    Map<String, String> values = new HashMap<>();
    values.put("name", "世界");
    assertEquals("你好 世界，未知 {{x}}", template.render(values));
  }

  @Test
  public void insertedTextIsLiteralSoNoRecursiveSubstitution() {
    StringTemplate template = new StringTemplate("{{a}}");
    Map<String, String> values = new HashMap<>();
    values.put("a", "{{b}}");
    assertEquals("{{b}}", template.render(values));
  }

  @Test
  public void nullTemplateAndNullValueBecomeEmpty() {
    assertEquals("", new StringTemplate(null).render(new HashMap<>()));
    Map<String, String> values = new HashMap<>();
    values.put("a", null);
    assertEquals("", new StringTemplate("{{a}}").render(values).trim());
  }

  // ---- StableJson ----

  @Test
  public void stableJsonSortsObjectKeysButKeepsArrayOrder() throws Exception {
    org.json.JSONObject object = new org.json.JSONObject();
    object.put("b", 2);
    object.put("a", 1);
    org.json.JSONArray array = new org.json.JSONArray();
    array.put(2);
    array.put(1);
    object.put("arr", array);
    assertEquals("{\"a\":1,\"arr\":[2,1],\"b\":2}", StableJson.stringify(object));
  }

  @Test
  public void stableJsonHandlesScalars() throws Exception {
    assertEquals("null", StableJson.stringify(null));
    assertEquals("null", StableJson.stringify(org.json.JSONObject.NULL));
    assertEquals("true", StableJson.stringify(true));
    assertEquals("3", StableJson.stringify(3));
    assertEquals("\"x\"", StableJson.stringify("x"));
  }

  // ---- AppProxy ----

  @Test
  public void proxyIsDisabledByDefaultAndForInvalidInput() {
    AppProxy.apply(null, 0);
    assertFalse(AppProxy.isEnabled());
    AppProxy.apply("  ", 8080);
    assertFalse(AppProxy.isEnabled());
    AppProxy.apply("h", 0);
    assertFalse(AppProxy.isEnabled());
    AppProxy.apply("h", 70000);
    assertFalse(AppProxy.isEnabled());
    assertEquals("", AppProxy.proxyUrl());
  }

  @Test
  public void proxyAppliesAndRoutesLocalHostsDirect() {
    try {
      AppProxy.apply("  proxy.local  ", 3128);
      assertTrue(AppProxy.isEnabled());
      assertEquals("proxy.local", AppProxy.host());
      assertEquals(3128, AppProxy.port());
      assertEquals("http://proxy.local:3128", AppProxy.proxyUrl());
      assertEquals(java.net.Proxy.NO_PROXY, AppProxy.proxyFor("localhost"));
      assertEquals(java.net.Proxy.NO_PROXY, AppProxy.proxyFor("10.0.2.2"));
      assertFalse(AppProxy.proxyFor("example.com").equals(java.net.Proxy.NO_PROXY));
    } finally {
      AppProxy.apply("", 0);
    }
    assertEquals(java.net.Proxy.NO_PROXY, AppProxy.proxyFor("example.com"));
  }

  @Test
  public void isLocalHostRecognizesLoopbackAliases() {
    assertTrue(AppProxy.isLocalHost("localhost"));
    assertTrue(AppProxy.isLocalHost("127.0.0.1"));
    assertTrue(AppProxy.isLocalHost("::1"));
    assertTrue(AppProxy.isLocalHost("10.0.2.2"));
    assertTrue(AppProxy.isLocalHost("LOCALHOST"));
    assertFalse(AppProxy.isLocalHost("example.com"));
    assertFalse(AppProxy.isLocalHost(null));
  }

  // ---- ModelApiError ----

  @Test
  public void retryableKindsSetIsExplicit() {
    assertTrue(ModelApiError.retryable(ModelApiError.Kind.RATE_LIMIT));
    assertTrue(ModelApiError.retryable(ModelApiError.Kind.SERVER_OVERLOAD));
    assertTrue(ModelApiError.retryable(ModelApiError.Kind.SERVER_ERROR));
    assertTrue(ModelApiError.retryable(ModelApiError.Kind.TIMEOUT));
    assertTrue(ModelApiError.retryable(ModelApiError.Kind.CONNECTION_ERROR));
    assertTrue(ModelApiError.retryable(ModelApiError.Kind.WATCHDOG_IDLE));
    assertFalse(ModelApiError.retryable(ModelApiError.Kind.AUTH));
    assertFalse(ModelApiError.retryable(ModelApiError.Kind.CLIENT_ERROR));
    assertFalse(ModelApiError.retryable(ModelApiError.Kind.UNKNOWN));
    assertTrue(new ModelApiError(ModelApiError.Kind.TIMEOUT, -1, 0, "m").retryable());
  }

  @Test
  public void classifiesHttpStatusFromMessage() {
    assertEquals(ModelApiError.Kind.RATE_LIMIT, ModelApiError.fromThrowable(new Exception("HTTP 429: slow")).kind());
    assertEquals(ModelApiError.Kind.SERVER_OVERLOAD, ModelApiError.fromThrowable(new Exception("HTTP 529: busy")).kind());
    assertEquals(ModelApiError.Kind.AUTH, ModelApiError.fromThrowable(new Exception("HTTP 401: no")).kind());
    assertEquals(ModelApiError.Kind.AUTH, ModelApiError.fromThrowable(new Exception("HTTP 403: no")).kind());
    assertEquals(ModelApiError.Kind.TIMEOUT, ModelApiError.fromThrowable(new Exception("HTTP 408: t")).kind());
    assertEquals(ModelApiError.Kind.SERVER_ERROR, ModelApiError.fromThrowable(new Exception("HTTP 500")).kind());
    assertEquals(ModelApiError.Kind.CLIENT_ERROR, ModelApiError.fromThrowable(new Exception("HTTP 400")).kind());
  }

  @Test
  public void classifiesSseServerErrorEventsBeforeStatus() {
    ModelApiError overload = ModelApiError.fromThrowable(new Exception("stream error: overloaded_error"));
    assertEquals(ModelApiError.Kind.SERVER_OVERLOAD, overload.kind());
    assertEquals(529, overload.httpStatus());
    ModelApiError rate = ModelApiError.fromThrowable(new Exception("rate_limit exceeded"));
    assertEquals(ModelApiError.Kind.RATE_LIMIT, rate.kind());
    assertEquals(429, rate.httpStatus());
  }

  @Test
  public void watchdogFiredForcesWatchdogIdle() {
    ModelApiError error = ModelApiError.fromThrowable(new Exception("HTTP 500: x"), true);
    assertEquals(ModelApiError.Kind.WATCHDOG_IDLE, error.kind());
    assertEquals(500, error.httpStatus());
  }

  @Test
  public void classifiesIoCauseChain() {
    assertEquals(ModelApiError.Kind.TIMEOUT,
        ModelApiError.fromThrowable(new java.net.SocketTimeoutException("read timed out")).kind());
    assertEquals(ModelApiError.Kind.CONNECTION_ERROR,
        ModelApiError.fromThrowable(new Exception("wrapper", new java.io.IOException("Connection reset"))).kind());
    assertEquals(ModelApiError.Kind.CONNECTION_ERROR,
        ModelApiError.fromThrowable(new Exception("Broken pipe")).kind());
    assertEquals(ModelApiError.Kind.CONNECTION_ERROR,
        ModelApiError.fromThrowable(new Exception("socket closed")).kind());
    assertEquals(ModelApiError.Kind.TIMEOUT,
        ModelApiError.fromThrowable(new Exception("connect timed out")).kind());
  }

  @Test
  public void unknownWhenNothingMatches() {
    assertEquals(ModelApiError.Kind.UNKNOWN, ModelApiError.fromThrowable(new Exception("boom")).kind());
    assertEquals(ModelApiError.Kind.UNKNOWN, ModelApiError.fromThrowable(null).kind());
  }

  @Test
  public void retryAfterIsParsedFromMessageSuffix() {
    ModelApiError error = ModelApiError.fromThrowable(new Exception("HTTP 429: slow retry-after=30"));
    assertEquals(30000L, error.retryAfterMs());
    assertEquals(0, ModelApiError.fromThrowable(new Exception("HTTP 429: retry-after=x")).retryAfterMs());
    assertEquals(0, ModelApiError.fromThrowable(new Exception("HTTP 429: retry-after=")).retryAfterMs());
  }

  @Test
  public void accessorsExposeConstructorArguments() {
    ModelApiError error = new ModelApiError(ModelApiError.Kind.AUTH, 401, 1500, "bad key");
    assertEquals(ModelApiError.Kind.AUTH, error.kind());
    assertEquals(401, error.httpStatus());
    assertEquals(1500, error.retryAfterMs());
    assertEquals("bad key", error.message());
    assertEquals("", new ModelApiError(ModelApiError.Kind.AUTH, 401, 0, null).message());
  }

  // ---- ModelCancellationToken ----

  @Test
  public void cancellationRunsListenersOnce() {
    ModelCancellationToken token = new ModelCancellationToken();
    assertFalse(token.isCancelled());
    final int[] calls = {0};
    token.onCancel(() -> calls[0]++);
    token.cancel();
    assertTrue(token.isCancelled());
    assertEquals(1, calls[0]);
    // 重复取消不重复触发
    token.cancel();
    assertEquals(1, calls[0]);
  }

  @Test
  public void registeringAfterCancelRunsImmediately() {
    ModelCancellationToken token = new ModelCancellationToken();
    token.cancel();
    final int[] calls = {0};
    token.onCancel(() -> calls[0]++);
    assertEquals(1, calls[0]);
  }

  // ---- 消息与响应小类 ----

  @Test
  public void modelMessageHierarchyExposesRolesAndDefaults() {
    assertEquals("user", new UserModelMessage("hi").getRole());
    assertEquals("system", new SystemModelMessage("sys").getRole());
    assertEquals("assistant", new AssistantModelMessage("out").getRole());
    assertEquals("tool", new ToolModelMessage("res").getRole());

    ModelMessage nullContent = new UserModelMessage(null, null);
    assertEquals("", nullContent.getContent());
    assertEquals("", nullContent.getRawInputJson());
    assertEquals("", nullContent.getReasoningContent());
    assertTrue(nullContent.getToolCalls().isEmpty());
    assertEquals("", nullContent.getToolCallId());
    assertEquals("", nullContent.getToolName());
    assertFalse(nullContent.isToolError());
  }

  @Test
  public void assistantMessageToolCallsAreDefensivelyCopied() {
    List<ToolCall> source = new ArrayList<>();
    source.add(new ToolCall("c1", "glob", "{}"));
    AssistantModelMessage message = new AssistantModelMessage("out", "reasoning", source);
    source.clear();
    assertEquals(1, message.getToolCalls().size());
    assertTrue(new AssistantModelMessage("x", "y").getToolCalls().isEmpty());
    assertTrue(new AssistantModelMessage("x", "y", null).getToolCalls().isEmpty());
  }

  @Test
  public void toolMessageCarriesIdentityErrorAndSteps() {
    ToolModelMessage message = new ToolModelMessage("body", "cid", "file_read", true, null, Arrays.asList("s1"));
    assertEquals("cid", message.getToolCallId());
    assertEquals("file_read", message.getToolName());
    assertTrue(message.isToolError());
    assertEquals(1, message.getSteps().size());
    assertEquals(Arrays.asList("s1"), message.getSteps());

    ToolModelMessage minimal = new ToolModelMessage("body");
    assertEquals("", minimal.getToolCallId());
    assertEquals("", minimal.getToolName());
    assertFalse(minimal.isToolError());
    assertTrue(minimal.getSteps().isEmpty());
    assertEquals("", new ToolModelMessage("b", null, null, false, null, null).getToolCallId());
  }

  @Test
  public void completionResponseNormalizesAndCopies() {
    List<ToolCall> calls = new ArrayList<>();
    calls.add(new ToolCall("c1", "glob", "{}"));
    ModelCompletionResponse response = new ModelCompletionResponse("t", "r", calls, 10, 20);
    calls.clear();
    assertEquals("t", response.getText());
    assertEquals("r", response.getReasoningContent());
    assertEquals(1, response.getToolCalls().size());
    assertTrue(response.hasToolCalls());
    assertEquals(10, response.getInputTokens());
    assertEquals(20, response.getOutputTokens());

    ModelCompletionResponse bare = new ModelCompletionResponse(null, null, null, -5, -5);
    assertEquals("", bare.getText());
    assertFalse(bare.hasToolCalls());
    assertEquals(0, bare.getInputTokens());
    assertEquals(0, bare.getOutputTokens());
  }

  @Test
  public void completionExceptionBuildersAreChainable() {
    ModelCompletionException e = new ModelCompletionException("m")
        .withHttpStatus(503)
        .withKind(ModelApiError.Kind.SERVER_ERROR)
        .withRetryAfterMs(1000)
        .withWatchdogFired(true)
        .withPartial("part", "think", true);
    assertEquals(503, e.httpStatus());
    assertEquals(ModelApiError.Kind.SERVER_ERROR, e.kind());
    assertEquals(1000, e.retryAfterMs());
    assertTrue(e.watchdogFired());
    assertTrue(e.hasPartial());
    assertTrue(e.crossedToolBoundary());
    assertEquals("part", e.partialText());
    assertEquals("think", e.partialReasoning());

    ModelCompletionException plain = new ModelCompletionException("x", new RuntimeException());
    assertEquals(-1, plain.httpStatus());
    assertEquals(ModelApiError.Kind.UNKNOWN, plain.kind());
    assertFalse(plain.hasPartial());
    assertFalse(plain.crossedToolBoundary());
    assertFalse(plain.watchdogFired());

    ModelCompletionException emptyPartial = new ModelCompletionException("x").withPartial(null, null, false);
    assertFalse(emptyPartial.hasPartial());
    assertEquals("", emptyPartial.partialText());
  }

  // ---- ModelProtocolType ----

  @Test
  public void protocolTypeParsesStorageAliases() {
    assertEquals(ModelProtocolType.OPENAI_COMPATIBLE, ModelProtocolType.fromStorage(null));
    assertEquals(ModelProtocolType.OPENAI_COMPATIBLE, ModelProtocolType.fromStorage("openai"));
    assertEquals(ModelProtocolType.OPENAI_COMPATIBLE, ModelProtocolType.fromStorage("openai_compatible"));
    assertEquals(ModelProtocolType.CODEX_RESPONSES, ModelProtocolType.fromStorage("codex"));
    assertEquals(ModelProtocolType.CODEX_RESPONSES, ModelProtocolType.fromStorage("codex_responses"));
    assertEquals(ModelProtocolType.ANTHROPIC_MESSAGES, ModelProtocolType.fromStorage("claude"));
    assertEquals(ModelProtocolType.ANTHROPIC_MESSAGES, ModelProtocolType.fromStorage("anthropic_messages"));
    assertEquals(ModelProtocolType.LOCAL_GGUF, ModelProtocolType.fromStorage("gguf"));
    assertEquals(ModelProtocolType.LOCAL_GGUF, ModelProtocolType.fromStorage("local_gguf"));
    // 枚举名本身（大小写不敏感前先试别名，再落回 name 匹配）
    assertEquals(ModelProtocolType.ANTHROPIC_MESSAGES, ModelProtocolType.fromStorage("ANTHROPIC_MESSAGES"));
    // 未知值保守回退
    assertEquals(ModelProtocolType.OPENAI_COMPATIBLE, ModelProtocolType.fromStorage("nonsense"));
  }

  @Test
  public void protocolTypeLabelsAndCompressionSupport() {
    assertEquals("OpenAI", ModelProtocolType.OPENAI_COMPATIBLE.getLabel());
    assertEquals("Anthropic", ModelProtocolType.ANTHROPIC_MESSAGES.getLabel());
    assertTrue(ModelProtocolType.OPENAI_COMPATIBLE.supportsDedicatedCompression());
    assertFalse(ModelProtocolType.ANTHROPIC_MESSAGES.supportsDedicatedCompression());
    assertFalse(ModelProtocolType.LOCAL_GGUF.supportsDedicatedCompression());
  }

  // ---- 工厂与客户端能力 ----

  @Test
  public void factoryCreatesRegisteredProtocolsAndFallsBack() {
    ModelProtocolFactory factory = new ModelProtocolFactory();
    assertTrue(factory.create(ModelProtocolType.ANTHROPIC_MESSAGES) instanceof AnthropicMessagesProtocol);
    assertTrue(factory.create(ModelProtocolType.OPENAI_COMPATIBLE) instanceof OpenAiCompatibleProtocol);
    // 未注册类型回退到 OpenAI 兼容
    assertTrue(factory.create(ModelProtocolType.CODEX_RESPONSES) instanceof OpenAiCompatibleProtocol);
    assertTrue(factory.create(null) instanceof OpenAiCompatibleProtocol);
  }

  @Test
  public void factoryRegisterIgnoresNullsAndOverrides() {
    ModelProtocolFactory factory = new ModelProtocolFactory();
    factory.register(null, OpenAiCompatibleProtocol::new);
    factory.register(ModelProtocolType.ANTHROPIC_MESSAGES, null);
    final boolean[] used = {false};
    factory.register(ModelProtocolType.ANTHROPIC_MESSAGES, () -> {
      used[0] = true;
      return new OpenAiCompatibleProtocol();
    });
    assertTrue(factory.create(ModelProtocolType.ANTHROPIC_MESSAGES) instanceof OpenAiCompatibleProtocol);
    assertTrue(used[0]);
  }

  @Test
  public void modelClientDelegatesCapabilitiesToProtocol() {
    ModelClient client = new ModelClient();
    ModelConfig openai = ModelConfig.builder("i", "n", ModelProtocolType.OPENAI_COMPATIBLE, "l", "b", "k", "m").build();
    assertTrue(client.supportsNativeTools(openai));
    assertFalse(client.supportsNativeTools(null));
  }

  @Test
  public void modelClientNullFactoryFallsBackToDefault() {
    assertTrue(new ModelClient(null).supportsNativeTools(
        ModelConfig.builder("i", "n", ModelProtocolType.OPENAI_COMPATIBLE, "l", "b", "k", "m").build()));
  }

  // ---- 能力判定 ----

  @Test
  public void nvidiaGatewayDisablesToolsAndReasoningParams() {
    ModelConfig nvidia = ModelConfig.builder("i", "n", ModelProtocolType.OPENAI_COMPATIBLE,
            "NVIDIA NIM", "https://integrate.api.nvidia.com", "k", "m")
        .build();
    assertTrue(OpenAiCompatibleCapabilities.isNvidiaCompatibleGateway(nvidia));
    assertFalse(OpenAiCompatibleCapabilities.supportsNativeTools(nvidia));
    assertFalse(OpenAiCompatibleCapabilities.supportsReasoningRequestParameters(nvidia));
    assertFalse(OpenAiCompatibleCapabilities.isNvidiaCompatibleGateway(null));
    assertFalse(OpenAiCompatibleCapabilities.supportsNativeTools(null));
  }

  @Test
  public void nvidiaByUrlAndProviderLabelAreBothDetected() {
    assertTrue(OpenAiCompatibleCapabilities.isNvidiaCompatibleGateway(
        ModelConfig.builder("i", "n", ModelProtocolType.OPENAI_COMPATIBLE, "l", "https://api.nvidia.com/v1", "k", "m").build()));
    assertTrue(OpenAiCompatibleCapabilities.isNvidiaCompatibleGateway(
        ModelConfig.builder("i", "n", ModelProtocolType.OPENAI_COMPATIBLE, "Nvidia", "https://x", "k", "m").build()));
  }

  // ---- ReasoningCompatibility ----

  @Test
  public void glmEffortIsMappedToItsSupportedSubset() {
    ModelConfig glm = ModelConfig.builder("i", "n", ModelProtocolType.OPENAI_COMPATIBLE,
        "智谱", "https://open.bigmodel.cn", "k", "glm-4").build();
    assertTrue(ReasoningCompatibility.isGlm(glm));
    assertEquals("max", ReasoningCompatibility.compatibleEffort(glm, "max"));
    assertEquals("low", ReasoningCompatibility.compatibleEffort(glm, "off"));
    assertEquals("low", ReasoningCompatibility.compatibleEffort(glm, "low"));
    assertEquals("high", ReasoningCompatibility.compatibleEffort(glm, "medium"));
    assertEquals("high", ReasoningCompatibility.compatibleEffort(glm, "high"));
    assertFalse(ReasoningCompatibility.isGlm(null));
    assertFalse(ReasoningCompatibility.isGlm(
        ModelConfig.builder("i", "n", ModelProtocolType.OPENAI_COMPATIBLE, "l", "https://api.openai.com", "k", "gpt-x").build()));
  }

  @Test
  public void adaptReturnsSameInstanceWhenEffortUnchanged() {
    ModelConfig nonGlm = ModelConfig.builder("i", "n", ModelProtocolType.OPENAI_COMPATIBLE, "l", "https://x", "k", "m").build();
    ModelRequestOptions options = new ModelRequestOptions("high", true);
    assertSame(options, ReasoningCompatibility.adapt(nonGlm, options));
    // null options → defaults（medium）
    assertEquals("medium", ReasoningCompatibility.adapt(null, null).getReasoningEffort());
  }

  @Test
  public void adaptRewritesEffortForGlm() {
    ModelConfig glm = ModelConfig.builder("i", "n", ModelProtocolType.OPENAI_COMPATIBLE, "glm", "https://x", "k", "m").build();
    ModelRequestOptions adapted = ReasoningCompatibility.adapt(glm, new ModelRequestOptions("medium", false));
    assertEquals("high", adapted.getReasoningEffort());
  }

  // ---- ModelRequestOptions ----

  @Test
  public void requestOptionsNormalizeEffortAndSortTools() {
    List<com.tom.rv2ide.ai.tool.api.ToolInfo> tools = Arrays.asList(
        stubTool("zzz"), stubTool("aaa"));
    ModelRequestOptions options = new ModelRequestOptions("bogus", false, tools);
    assertEquals("medium", options.getReasoningEffort());
    assertEquals("aaa", options.getTools().get(0).getName());
    assertEquals("zzz", options.getTools().get(1).getName());
    assertEquals("medium", ModelRequestOptions.defaults().getReasoningEffort());
    assertFalse(ModelRequestOptions.defaults().isPreserveReasoning());
    assertTrue(new ModelRequestOptions("low", true, null).getTools().isEmpty());
  }

  @Test
  public void requestOptionsExposeReasoningContextAccessors() {
    ReasoningRequestContext ctx = new ReasoningRequestContext(true, "high", true, "b", "m", 4096);
    assertTrue(ctx.isEnabled());
    assertEquals("high", ctx.getEffort());
    assertTrue(ctx.isPreserveReasoning());
    assertEquals("b", ctx.getBaseUrl());
    assertEquals("m", ctx.getModelId());
    assertEquals(4096, ctx.getThinkingBudget());
  }

  // ---- AI 行为设置 ----

  @Test
  public void behaviorSettingsNormalizeToneAndEffort() {
    AiBehaviorSettings settings = new AiBehaviorSettings("chat", true, false, "high", true, false, false);
    assertEquals("chat", settings.getToneMode());
    assertTrue(settings.isThinkingScrollEnabled());
    assertFalse(settings.isThinkingAutoExpandEnabled());
    assertEquals("high", settings.getReasoningEffort());
    assertTrue(settings.isPreserveReasoningEnabled());
    assertFalse(settings.isLearningModeEnabled());
    assertFalse(settings.isSoftCompactionEnabled());
  }

  @Test
  public void behaviorSettingsSixArgConstructorDefaultsSoftCompactionOn() {
    assertTrue(new AiBehaviorSettings("coding", true, true, "off", false, false).isSoftCompactionEnabled());
  }

  @Test
  public void normalizeToneFallsBackToCoding() {
    assertEquals("chat", AiBehaviorSettings.normalizeTone("chat"));
    assertEquals("coding", AiBehaviorSettings.normalizeTone("coding"));
    assertEquals("coding", AiBehaviorSettings.normalizeTone("weird"));
    assertEquals("coding", AiBehaviorSettings.normalizeTone(null));
  }

  @Test
  public void normalizeEffortWhitelistAndFallback() {
    assertEquals("off", AiBehaviorSettings.normalizeReasoningEffort("off"));
    assertEquals("auto", AiBehaviorSettings.normalizeReasoningEffort("auto"));
    assertEquals("low", AiBehaviorSettings.normalizeReasoningEffort("low"));
    assertEquals("high", AiBehaviorSettings.normalizeReasoningEffort("high"));
    assertEquals("max", AiBehaviorSettings.normalizeReasoningEffort("max"));
    assertEquals("medium", AiBehaviorSettings.normalizeReasoningEffort("nope"));
    assertEquals("medium", AiBehaviorSettings.normalizeReasoningEffort(null));
  }

  @Test
  public void concreteEffortResolvesAutoAndReasoningEnabled() {
    assertEquals("medium", AiBehaviorSettings.concreteReasoningEffort("auto"));
    assertEquals("high", AiBehaviorSettings.concreteReasoningEffort("high"));
    assertTrue(AiBehaviorSettings.isReasoningEnabled("high"));
    assertTrue(AiBehaviorSettings.isReasoningEnabled("auto"));
    assertFalse(AiBehaviorSettings.isReasoningEnabled("off"));
  }

  // ---- ModelContextInfo ----

  @Test
  public void contextInfoNormalizesNulls() {
    ModelContextInfo info = new ModelContextInfo(null, 100, null);
    assertEquals("", info.getApiModelId());
    assertEquals(100, info.getContextTokens());
    assertEquals("", info.getContextLabel());
  }

  // ---- ImageInputPayload ----

  @Test
  public void imagePayloadRoundTripForUserAttachment() throws Exception {
    String raw = ImageInputPayload.rawInputJson("描述这张图", "image/jpg", "QUJD");
    ImageInputPayload.Payload payload = ImageInputPayload.fromRawInputJson(raw);
    assertEquals("描述这张图", payload.getPrompt());
    // image/jpg 归一化为 image/jpeg
    assertEquals("image/jpeg", payload.getMimeType());
    assertEquals("QUJD", payload.getDataBase64());
    assertEquals("data:image/jpeg;base64,QUJD", payload.dataUrl());
  }

  @Test
  public void toolResultPayloadUsesDistinctKindAndHasNoPrompt() throws Exception {
    String raw = ImageInputPayload.imageResultJson("image/png", "QUJD");
    // 用户附件的解析入口不认工具结果 kind
    assertNull(ImageInputPayload.fromRawInputJson(raw));
    ImageInputPayload.Payload payload = ImageInputPayload.fromImageResult(raw);
    assertEquals("image/png", payload.getMimeType());
    assertEquals("QUJD", payload.getDataBase64());
    assertEquals("", payload.getPrompt());
    // 反向：用户附件的 kind 不被工具结果入口接受
    assertNull(ImageInputPayload.fromImageResult(
        ImageInputPayload.rawInputJson("p", "image/png", "QUJD")));
  }

  @Test
  public void imagePayloadRejectsMalformedInput() {
    assertNull(ImageInputPayload.fromRawInputJson(null));
    assertNull(ImageInputPayload.fromRawInputJson("   "));
    assertNull(ImageInputPayload.fromRawInputJson("not json"));
    assertNull(ImageInputPayload.fromRawInputJson("{not valid"));
    assertNull(ImageInputPayload.fromRawInputJson("{\"kind\":\"linecode_image_understanding\"}"));
    assertNull(ImageInputPayload.fromRawInputJson("{\"kind\":\"linecode_image_understanding\",\"data_base64\":\"  \"}"));
    assertNull(ImageInputPayload.fromImageResult(null));
    assertNull(ImageInputPayload.fromImageResult("plain"));
    assertNull(ImageInputPayload.fromImageResult("{\"kind\":\"other\",\"data_base64\":\"x\"}"));
  }

  @Test
  public void mimeTypeNormalizationAndSupportChecks() {
    assertEquals("image/jpeg", ImageInputPayload.normalizeMimeType("image/jpg"));
    assertEquals("image/png", ImageInputPayload.normalizeMimeType("IMAGE/PNG"));
    assertEquals("image/gif", ImageInputPayload.normalizeMimeType("image/gif"));
    // 不支持的类型回退 png
    assertEquals("image/png", ImageInputPayload.normalizeMimeType("application/pdf"));
    assertEquals("image/png", ImageInputPayload.normalizeMimeType(null));
    assertTrue(ImageInputPayload.isSupportedMimeType("image/webp"));
    assertFalse(ImageInputPayload.isSupportedMimeType("image/bmp"));
    assertFalse(ImageInputPayload.isSupportedMimeType(null));
  }

  // ---- AbstractToolCallBuilder ----

  @Test
  public void toolCallBuilderFillsDefaults() {
    TestBuilder builder = new TestBuilder();
    assertFalse(builder.hasName());
    ToolCall call = builder.build(3);
    assertEquals("default-3", call.getId());
    assertEquals("{}", call.getArguments());

    builder.id = "given";
    builder.name = "glob";
    builder.arguments.append("{\"p\":1}");
    assertTrue(builder.hasName());
    assertEquals("given", builder.build(0).getId());
    assertEquals("{\"p\":1}", builder.build(0).getArguments());
    assertEquals("given", builder.buildWithId().getId());
    assertEquals("{\"p\":1}", builder.buildWithId().getArguments());
  }

  private static final class TestBuilder extends AbstractToolCallBuilder {
    @Override
    protected String defaultId(int index) {
      return "default-" + index;
    }
  }

  private static com.tom.rv2ide.ai.tool.api.ToolInfo stubTool(String name) {
    return new com.tom.rv2ide.ai.tool.api.ToolInfo() {
      @Override public String getName() { return name; }
      @Override public String getDescription() { return ""; }
      @Override public com.tom.rv2ide.ai.tool.api.ToolCategory getCategory() {
        return com.tom.rv2ide.ai.tool.api.ToolCategory.READ;
      }
      @Override public boolean needsConfirmation() { return false; }
      @Override public String promptSupplement(String executionMode) { return null; }
      @Override public org.json.JSONObject getParameters() { return new org.json.JSONObject(); }
      @Override public org.json.JSONObject toJson() { return new org.json.JSONObject(); }
    };
  }

  // ---- AssistantCommitBuffer ----

  @Test
  public void commitBufferTracksPartialAndToolBoundary() {
    AssistantCommitBuffer buffer = new AssistantCommitBuffer();
    assertFalse(buffer.hasPartial());
    assertFalse(buffer.crossedToolBoundary());
    buffer.appendText("");
    buffer.appendReasoning(null);
    assertFalse(buffer.hasPartial());
    buffer.appendText("hi");
    buffer.appendReasoning("think");
    assertTrue(buffer.hasPartial());
    assertEquals("hi", buffer.text());
    assertEquals("think", buffer.reasoning());
    buffer.markToolUseStarted();
    assertTrue(buffer.crossedToolBoundary());
  }

  @Test
  public void emptyToolNameListIsStable() {
    assertEquals(Collections.emptyList(), new ModelRequestOptions("low", false).getTools());
  }
}
