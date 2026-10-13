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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * {@link U2JsonRpc} 回归测试：请求体必须纯 ASCII。
 *
 * <p>本测试钉住设备实测 bug（2026-10-12）：原始 UTF-8 请求体会被 u2 server
 * 错误解码（中文逐字节变替换符），必须发 &#92;u 转义。
 */
class U2JsonRpcTest {

  @Test
  void chineseTextIsEscapedToAscii() {
    String params = new JSONArray()
        .put(new JSONObject().put("mask", 16))
        .put("中文测试ABC")
        .toString();
    String body = U2JsonRpc.buildBody("setText", params);
    // 必须纯 ASCII（原始 UTF-8 会被 u2 server 错误解码）
    for (int i = 0; i < body.length(); i++) {
      assertTrue(body.charAt(i) <= 0x7E, "请求体含非 ASCII 字符（位置 " + i + "）");
    }
    assertTrue(body.contains("\\u4e2d\\u6587"), "中文应被 \\uXXXX 转义：" + body);
    // 解析回来必须与原文一致（转义是等价变换）
    JSONObject parsed = new JSONObject(body);
    assertEquals("中文测试ABC", parsed.getJSONArray("params").getString(1));
  }

  @Test
  void asciiPassesThroughUnchanged() {
    String body = U2JsonRpc.buildBody("dump", "[]");
    assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"dump\",\"params\":[]}", body);
  }

  @Test
  void emptyParamsBecomesEmptyArray() {
    assertEquals(
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":[]}",
        U2JsonRpc.buildBody("ping", null));
    assertEquals(
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":[]}",
        U2JsonRpc.buildBody("ping", "  "));
  }

  @Test
  void existingUnicodeEscapesSurvive() {
    // 上游风格的已转义文本（纯 ASCII）原样保留，不双重转义
    String params = "[\"\\u4e2d\\u6587\"]";
    String body = U2JsonRpc.buildBody("setText", params);
    assertTrue(body.contains("\\u4e2d\\u6587"), body);
    assertFalse(body.contains("\\\\u4e2d"), "不得双重转义：" + body);
    assertEquals("中文", new JSONObject(body).getJSONArray("params").getString(0));
  }

  @Test
  void quoteAndControlCharsInMethodAreEscaped() {
    String body = U2JsonRpc.buildBody("me\"th\nod", "[]");
    JSONObject parsed = new JSONObject(body);
    assertEquals("me\"th\nod", parsed.getString("method"));
  }

  @Test
  void surrogatePairsAreEscapedAsTwoHalves() {
    // emoji（U+1F600，UTF-16 代理对）逐半转义；解析回来必须仍是原字符
    String emoji = "\uD83D\uDE00";
    String params = new JSONArray().put(emoji).toString();
    String body = U2JsonRpc.buildBody("setText", params);
    for (int i = 0; i < body.length(); i++) {
      assertTrue(body.charAt(i) <= 0x7E, "代理对转义后仍含非 ASCII");
    }
    assertEquals(emoji, new JSONObject(body).getJSONArray("params").getString(0));
  }
}
