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

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ImageGenerationTool} 的请求构造 / 响应解析 / 落盘行为。 */
class ImageGenerationToolTest {

  @TempDir java.nio.file.Path tmpDir;

  private String tmpRoot() {
    return tmpDir.toString();
  }

  /** 记录请求并返回 canned 响应的假端口。 */
  private static final class FakePort implements HttpRequestPort {
    String method;
    String url;
    Map<String, String> headers;
    String body;
    Response canned;

    FakePort(Response canned) {
      this.canned = canned;
    }

    @Override
    public Response request(
        String method, String url, Map<String, String> headers, String body, int timeoutMs)
        throws Exception {
      this.method = method;
      this.url = url;
      this.headers = headers;
      this.body = body;
      return canned;
    }
  }

  private static HttpRequestPort.Response ok(String body) {
    return new HttpRequestPort.Response(200, "OK", "application/json", new LinkedHashMap<>(), body);
  }

  private static HttpRequestPort.Response httpError(int code, String body) {
    return new HttpRequestPort.Response(code, "Err", "application/json", new LinkedHashMap<>(), body);
  }

  private static final String PNG_BASE64 =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

  private ToolContext context() {
    return ToolContext.builder()
        .homePath(tmpRoot())
        .imageGeneration(new ImageGenerationEndpoint("https://host/v1", "sk-test", "agnes-image-2.5-flash"))
        .build();
  }

  private static JSONObject input(String prompt) {
    return new JSONObject().put("prompt", prompt);
  }

  @Test
  public void postsToImagesGenerationsWithB64Format() throws Exception {
    FakePort port =
        new FakePort(
            ok(new JSONObject()
                .put("data", new org.json.JSONArray().put(new JSONObject().put("b64_json", PNG_BASE64)))
                .toString()));
    ImageGenerationTool tool = new ImageGenerationTool(port);

    ToolResult result = tool.execute(input("a red circle").put("size", "512x512"), context());

    assertFalse(result.isError(), result.getContent());
    assertEquals("POST", port.method);
    assertEquals("https://host/v1/images/generations", port.url);
    JSONObject sent = new JSONObject(port.body);
    assertEquals("agnes-image-2.5-flash", sent.getString("model"));
    assertEquals("b64_json", sent.getString("response_format"));
    assertEquals("512x512", sent.getString("size"));
    assertEquals("Bearer sk-test", port.headers.get("Authorization"));
  }

  @Test
  public void returnsImagePayloadAndWritesFile() throws Exception {
    FakePort port =
        new FakePort(
            ok(new JSONObject()
                .put("data", new org.json.JSONArray().put(new JSONObject().put("b64_json", PNG_BASE64)))
                .toString()));
    ImageGenerationTool tool = new ImageGenerationTool(port);
    ToolContext ctx = context();

    ToolResult result = tool.execute(input("a red circle"), ctx);

    assertFalse(result.isError());
    assertTrue(result.hasImage());
    assertEquals("image/png", result.getImageMimeType());
    byte[] decoded = java.util.Base64.getDecoder().decode(result.getImageBase64());
    // PNG magic
    assertEquals((byte) 0x89, decoded[0]);
    // 落盘在 homePath/ai-generated 下
    Path dir = tmpDir.resolve("ai-generated");
    try (java.util.stream.Stream<Path> files = Files.list(dir)) {
      assertEquals(1, files.count());
    }
  }

  @Test
  public void dataUrlFallbackParsed() throws Exception {
    String dataUrl = "data:image/jpeg;base64," + PNG_BASE64;
    FakePort port =
        new FakePort(
            ok(new JSONObject()
                .put("data", new org.json.JSONArray().put(new JSONObject().put("url", dataUrl)))
                .toString()));
    ImageGenerationTool tool = new ImageGenerationTool(port);

    ToolResult result = tool.execute(input("cat"), context());

    assertFalse(result.isError());
    assertTrue(result.hasImage());
    assertEquals("image/jpeg", result.getImageMimeType());
  }

  @Test
  public void httpErrorSurfacesBody() throws Exception {
    FakePort port = new FakePort(httpError(400, "{\"error\":\"bad model\"}"));
    ImageGenerationTool tool = new ImageGenerationTool(port);

    ToolResult result = tool.execute(input("x"), context());

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("400"));
    assertTrue(result.getContent().contains("bad model"));
  }

  @Test
  public void unconfiguredEndpointExplains() {
    FakePort port = new FakePort(ok("{}"));
    ImageGenerationTool tool = new ImageGenerationTool(port);
    ToolContext bare = ToolContext.builder().homePath(tmpRoot()).build();

    ToolResult result = tool.execute(input("x"), bare);

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("未配置"));
    // 没发请求
    assertEquals(null, port.method);
  }

  @Test
  public void rejectsBlankPromptAndBadSize() {
    FakePort port = new FakePort(ok("{}"));
    ImageGenerationTool tool = new ImageGenerationTool(port);

    assertTrue(tool.execute(new JSONObject(), context()).isError());
    assertTrue(
        tool.execute(new JSONObject(input("x")).put("size", "abc"), context()).isError());
  }

  @Test
  public void endpointUrlAndConfiguredFlag() {
    ImageGenerationEndpoint e =
        new ImageGenerationEndpoint("https://host/v1/", "k", "m");
    assertEquals("https://host/v1/images/generations", e.imagesUrl());
    assertTrue(e.isConfigured());
    assertFalse(new ImageGenerationEndpoint("", "", "").isConfigured());
  }
}
