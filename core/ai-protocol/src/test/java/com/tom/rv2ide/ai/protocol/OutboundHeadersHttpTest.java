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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 真实 HTTP 出站集成测试：起一个本机 HttpServer，用真实协议发一次 complete 请求，
 * 断言对端确实收到了配置的 User-Agent 与额外请求头。证明这些头是真的发到网络上，
 * 而不只是存在于内存 map 里。
 */
class OutboundHeadersHttpTest {

  @Test
  void sendsUserAgentAndExtraHeadersOnTheWire() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<Headers> captured = new AtomicReference<>();
    server.createContext(
        "/chat/completions",
        exchange -> {
          captured.set(exchange.getRequestHeaders());
          byte[] body =
              "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    int port = server.getAddress().getPort();
    try {
      ModelConfig config =
          ModelConfig.builder(
                  "id", "n", ModelProtocolType.OPENAI_COMPATIBLE, "p",
                  "http://127.0.0.1:" + port, "k", "m")
              .userAgent("ACS-Verify/9")
              .extraHeaders(Collections.singletonMap("X-Relay", "abc"))
              .build();

      new OpenAiCompatibleProtocol().complete(config, Collections.emptyList());

      Headers headers = captured.get();
      assertNotNull(headers, "服务端应收到请求");
      assertEquals("ACS-Verify/9", headers.getFirst("User-Agent"));
      assertEquals("abc", headers.getFirst("X-Relay"));
      assertEquals("Bearer k", headers.getFirst("Authorization"));
    } finally {
      server.stop(0);
    }
  }
}
