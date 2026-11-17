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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OutboundHeadersTest {

  private static ModelConfig config(String ua, Map<String, String> extra) {
    return ModelConfig.builder(
            "id", "n", ModelProtocolType.OPENAI_COMPATIBLE, "p", "https://x", "k", "m")
        .userAgent(ua)
        .extraHeaders(extra)
        .build();
  }

  @Test
  void appliesUserAgentAndExtraHeaders() {
    Map<String, String> out = new HashMap<>();
    out.put("Authorization", "Bearer k");
    OutboundHeaders.apply(out, config("MyUA/1.0", Collections.singletonMap("X-Relay-Key", "abc")));
    assertEquals("MyUA/1.0", out.get("User-Agent"));
    assertEquals("abc", out.get("X-Relay-Key"));
    assertEquals("Bearer k", out.get("Authorization"));
  }

  @Test
  void extraHeadersCanOverrideUserAgent() {
    Map<String, String> out = new HashMap<>();
    OutboundHeaders.apply(
        out, config("A", Collections.singletonMap("User-Agent", "B")));
    assertEquals("B", out.get("User-Agent"));
  }

  @Test
  void jsonRoundTripCarriesHeaders() throws Exception {
    ModelConfig back =
        ModelConfig.fromJson(config("MyUA", Collections.singletonMap("X-A", "1")).toJson());
    assertEquals("MyUA", back.getUserAgent());
    assertEquals("1", back.getExtraHeaders().get("X-A"));
  }

  @Test
  void emptyMeansRuntimeDefault() {
    Map<String, String> out = new HashMap<>();
    OutboundHeaders.apply(out, config("", Collections.emptyMap()));
    assertFalse(out.containsKey("User-Agent"));
  }
}
