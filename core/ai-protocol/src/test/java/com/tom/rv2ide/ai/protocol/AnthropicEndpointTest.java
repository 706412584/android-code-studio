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

package com.tom.rv2ide.ai.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * 锁定 Anthropic 端点拼接。
 *
 * <p>背景：第三方兼容服务商（newapi / one-api 网关）给的是 OpenAI 风格 baseUrl，
 * 本身带 {@code /v1}。此前一律拼 {@code + "/v1/messages"}，得到 {@code /v1/v1/messages}，
 * 服务端返回 404 —— 用户看到的就是「选了 Anthropic 协议发消息报 404」。
 *
 * <p>两种形态都真实存在，因此这组用例把两种都锁住：官方地址（不带 /v1）要补全，
 * 网关地址（带 /v1）不能再补。
 */
public class AnthropicEndpointTest {

  /** 被测方法是 private，通过反射调用——它没有副作用，不值得为测试放宽可见性。 */
  private static String endpoint(String baseUrl) throws Exception {
    AnthropicMessagesProtocol protocol = new AnthropicMessagesProtocol();
    Method m = AnthropicMessagesProtocol.class.getDeclaredMethod("anthropicMessagesEndpoint", String.class);
    m.setAccessible(true);
    return (String) m.invoke(protocol, baseUrl);
  }

  @Test
  public void officialBaseUrlGetsVersionAndPath() throws Exception {
    assertEquals("https://api.anthropic.com/v1/messages", endpoint("https://api.anthropic.com"));
  }

  @Test
  public void openAiStyleBaseUrlWithV1DoesNotDoubleVersion() throws Exception {
    // 这是用户实际踩到的形态：橙子-国产模型 的 baseUrl。
    assertEquals(
        "https://newapi.smliegame.online/v1/messages",
        endpoint("https://newapi.smliegame.online/v1"));
  }

  @Test
  public void trailingSlashIgnored() throws Exception {
    assertEquals(
        "https://newapi.smliegame.online/v1/messages",
        endpoint("https://newapi.smliegame.online/v1/"));
  }

  @Test
  public void multipleTrailingSlashesIgnored() throws Exception {
    assertEquals("https://api.anthropic.com/v1/messages", endpoint("https://api.anthropic.com///"));
  }

  @Test
  public void fullPathAlreadyPresentIsKept() throws Exception {
    // 用户可能把完整端点粘进来，不能再补。
    assertEquals(
        "https://api.anthropic.com/v1/messages", endpoint("https://api.anthropic.com/v1/messages"));
  }

  @Test
  public void nonV1VersionSegmentRespected() throws Exception {
    // 兼容网关也可能用别的版本段；判据是「有没有版本段」，不是「是不是 v1」。
    assertEquals("https://gw.example.com/v2/messages", endpoint("https://gw.example.com/v2"));
  }

  @Test
  public void versionSegmentInMiddleNotTreatedAsVersion() throws Exception {
    // /v1 出现在路径中间（后面还有段）时不算版本段，仍需补全。
    assertEquals(
        "https://gw.example.com/v1/proxy/v1/messages",
        endpoint("https://gw.example.com/v1/proxy"));
  }

  @Test
  public void blankBaseUrlStillProducesPath() throws Exception {
    // 空 baseUrl 由 UrlPolicy 在真正发请求时拒绝；这里只保证不抛异常。
    assertEquals("/v1/messages", endpoint(""));
    assertEquals("/v1/messages", endpoint(null));
  }
}
