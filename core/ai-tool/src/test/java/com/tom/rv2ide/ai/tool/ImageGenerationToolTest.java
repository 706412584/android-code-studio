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
  public void returnsTextOnlyResultAndWritesFile() throws Exception {
    FakePort port =
        new FakePort(
            ok(new JSONObject()
                .put("data", new org.json.JSONArray().put(new JSONObject().put("b64_json", PNG_BASE64)))
                .toString()));
    ImageGenerationTool tool = new ImageGenerationTool(port);
    ToolContext ctx = context();

    ToolResult result = tool.execute(input("a red circle"), ctx);

    assertFalse(result.isError(), result.getContent());
    // 图片不进上下文（实测决策）：结果纯文本，含成功确认与落盘路径。
    assertFalse(result.hasImage());
    assertTrue(result.getContent().contains("生成成功"));
    assertTrue(result.getContent().contains("无需再次生成"));
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

    assertFalse(result.isError(), result.getContent());
    assertFalse(result.hasImage());
    assertTrue(result.getContent().contains("ai-generated"));
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

  /**
   * 普通 https 外链结果要下载后落盘。
   *
   * <p>回归背景：loomy2api 等网关忽略 {@code response_format=b64_json}，只回外链。
   * 此前只认 b64_json / data URL，这类网关的生成会被报成「服务商未返回图片数据」。
   */
  @Test
  public void plainHttpUrlResultIsDownloaded() throws Exception {
    byte[] png = java.util.Base64.getDecoder().decode(PNG_BASE64);
    HttpPort downloader =
        new HttpPort() {
          @Override
          public String getText(String url, Map<String, String> headers) {
            throw new UnsupportedOperationException();
          }

          @Override
          public BinaryResponse getBytes(String url, Map<String, String> headers) {
            return new BinaryResponse("image/png", png);
          }

          @Override
          public TextResponse postJson(String url, String jsonBody, Map<String, String> headers) {
            throw new UnsupportedOperationException();
          }

          @Override
          public StreamHandle openStream(String url, Map<String, String> headers) {
            throw new UnsupportedOperationException();
          }
        };
    FakePort port =
        new FakePort(
            ok(new JSONObject()
                .put(
                    "data",
                    new org.json.JSONArray()
                        .put(new JSONObject().put("url", "https://cdn.example.com/a.png?x=1")))
                .toString()));
    ImageGenerationTool tool = new ImageGenerationTool(port, downloader);

    ToolResult result = tool.execute(input("cat"), context());

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("生成成功"));
    Path dir = tmpDir.resolve("ai-generated");
    try (java.util.stream.Stream<Path> files = Files.list(dir)) {
      assertEquals(1, files.count());
    }
  }

  /** 只给了请求口（无下载口）时，外链结果要明确报错而不是静默算作「没有数据」。 */
  @Test
  public void plainUrlWithoutDownloadChannelFailsClearly() throws Exception {
    FakePort port =
        new FakePort(
            ok(new JSONObject()
                .put(
                    "data",
                    new org.json.JSONArray()
                        .put(new JSONObject().put("url", "https://cdn.example.com/a.png")))
                .toString()));
    ImageGenerationTool tool = new ImageGenerationTool(port);

    ToolResult result = tool.execute(input("cat"), context());

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("下载"));
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

  // ---- 参数校验 / 失败分支（此前未覆盖）----

  @Test
  public void toolMetadata() {
    ImageGenerationTool tool = new ImageGenerationTool(new FakePort(ok("{}")));
    assertEquals(
        com.tom.rv2ide.ai.tool.api.ToolDisplayCategory.IMAGE_GENERATION,
        tool.getDisplayCategory());
    assertTrue(tool.isConcurrencySafe(), "无共享可变状态，可并发");
  }

  @Test
  public void nullHttpPortExplains() {
    ToolResult result = new ImageGenerationTool(null).execute(input("x"), context());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("网络访问"), result.getContent());
  }

  @Test
  public void invalidSizeIsRejected() {
    FakePort port = new FakePort(ok("{}"));
    ImageGenerationTool tool = new ImageGenerationTool(port);
    for (String size : new String[] {"abc", "0x100", "100x0", "5000x5000", "1024", "x1024"}) {
      ToolResult result = tool.execute(input("x").put("size", size), context());
      assertTrue(result.isError(), "应拒绝尺寸 " + size);
      assertTrue(result.getContent().contains("size"), result.getContent());
    }
    assertEquals(null, port.method, "校验失败不应发请求");
  }

  @Test
  public void emptySizeFallsBackToDefault() throws Exception {
    FakePort port =
        new FakePort(
            ok(new JSONObject()
                .put("data", new org.json.JSONArray().put(new JSONObject().put("b64_json", PNG_BASE64)))
                .toString()));
    new ImageGenerationTool(port).execute(input("x").put("size", ""), context());
    assertEquals("1024x1024", new JSONObject(port.body).getString("size"));
  }

  @Test
  public void countIsClampedToValidRange() throws Exception {
    FakePort port =
        new FakePort(
            ok(new JSONObject()
                .put("data", new org.json.JSONArray().put(new JSONObject().put("b64_json", PNG_BASE64)))
                .toString()));
    ImageGenerationTool tool = new ImageGenerationTool(port);
    // n=0 → 1；n=99 → MAX_COUNT(4)。服务端不该收到越界值。
    tool.execute(input("x").put("n", 0), context());
    assertEquals(1, new JSONObject(port.body).getInt("n"));
    tool.execute(input("x").put("n", 99), context());
    assertEquals(4, new JSONObject(port.body).getInt("n"));
  }

  @Test
  public void networkExceptionIsReported() {
    HttpRequestPort port =
        (method, url, headers, body, timeoutMs) -> {
          throw new java.io.IOException("connection refused");
        };
    ToolResult result = new ImageGenerationTool(port).execute(input("x"), context());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("connection refused"), result.getContent());
  }

  @Test
  public void longHttpErrorBodyIsTruncated() {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < 500; i++) {
      body.append("detail-");
    }
    FakePort port = new FakePort(httpError(500, body.toString()));
    ToolResult result = new ImageGenerationTool(port).execute(input("x"), context());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("…"), "超长正文应被截断");
    assertTrue(result.getContent().length() < 1200, "错误正文不应超过 800 字符");
  }

  @Test
  public void nonJsonResponseIsReported() {
    FakePort port = new FakePort(ok("<html>oops</html>"));
    ToolResult result = new ImageGenerationTool(port).execute(input("x"), context());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("不是 JSON"), result.getContent());
    assertTrue(result.getContent().contains("oops"), result.getContent());
  }

  @Test
  public void emptyDataArrayIsReported() {
    FakePort port = new FakePort(ok("{\"data\":[]}"));
    ToolResult result = new ImageGenerationTool(port).execute(input("x"), context());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("data 为空"), result.getContent());
  }

  @Test
  public void badBase64IsReportedWithEntryIndex() {
    FakePort port =
        new FakePort(
            ok(new JSONObject()
                .put("data", new org.json.JSONArray().put(new JSONObject().put("b64_json", "!!!not-base64!!!")))
                .toString()));
    ToolResult result = new ImageGenerationTool(port).execute(input("x"), context());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("第 1 张"), result.getContent());
    assertTrue(result.getContent().contains("base64"), result.getContent());
  }

  @Test
  public void itemWithoutAnyImageDataIsReported() {
    FakePort port =
        new FakePort(
            ok(new JSONObject()
                .put("data", new org.json.JSONArray().put(new JSONObject().put("revised_prompt", "x")))
                .toString()));
    ToolResult result = new ImageGenerationTool(port).execute(input("x"), context());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("未返回图片数据"), result.getContent());
  }

  @Test
  public void downloadFailureIsReportedPerEntry() {
    HttpPort failingDownloader =
        new HttpPort() {
          @Override
          public String getText(String url, Map<String, String> headers) {
            throw new UnsupportedOperationException();
          }

          @Override
          public BinaryResponse getBytes(String url, Map<String, String> headers) throws Exception {
            throw new java.io.IOException("cdn down");
          }

          @Override
          public TextResponse postJson(String url, String jsonBody, Map<String, String> headers) {
            throw new UnsupportedOperationException();
          }

          @Override
          public StreamHandle openStream(String url, Map<String, String> headers) {
            throw new UnsupportedOperationException();
          }
        };
    FakePort port =
        new FakePort(
            ok(new JSONObject()
                .put("data", new org.json.JSONArray()
                    .put(new JSONObject().put("url", "https://cdn/a.png")))
                .toString()));
    ToolResult result =
        new ImageGenerationTool(port, failingDownloader).execute(input("x"), context());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("下载结果图片失败"), result.getContent());
    assertTrue(result.getContent().contains("cdn down"), result.getContent());
  }

  @Test
  public void unwritableOutputDirectoryIsReported() throws Exception {
    // 保存目录指向一个已存在的文件：必须明确说「无法创建目录」，
    // 而不是把「Not a directory」当成落盘失败原因。
    Path occupied = tmpDir.resolve("occupied");
    Files.write(occupied, new byte[] {1});
    FakePort port =
        new FakePort(
            ok(new JSONObject()
                .put("data", new org.json.JSONArray().put(new JSONObject().put("b64_json", PNG_BASE64)))
                .toString()));
    ToolContext ctx =
        ToolContext.builder()
            .homePath(tmpRoot())
            .imageGeneration(
                new ImageGenerationEndpoint(
                    "https://host/v1", "k", "m", occupied.toString()))
            .build();

    ToolResult result = new ImageGenerationTool(port).execute(input("x"), ctx);

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("无法创建目录"), result.getContent());
  }

  @Test
  public void customOutputDirectoryIsUsed() throws Exception {
    Path custom = tmpDir.resolve("custom-images");
    FakePort port =
        new FakePort(
            ok(new JSONObject()
                .put("data", new org.json.JSONArray().put(new JSONObject().put("b64_json", PNG_BASE64)))
                .toString()));
    ToolContext ctx =
        ToolContext.builder()
            .homePath(tmpRoot())
            .imageGeneration(
                new ImageGenerationEndpoint("https://host/v1", "k", "m", custom.toString()))
            .build();

    ToolResult result = new ImageGenerationTool(port).execute(input("x"), ctx);

    assertFalse(result.isError(), result.getContent());
    assertTrue(Files.isDirectory(custom), "应使用自定义目录");
    try (java.util.stream.Stream<Path> files = Files.list(custom)) {
      assertEquals(1, files.count());
    }
  }

  @Test
  public void partialSuccessReportsBothSavedAndFailedEntries() throws Exception {
    // 一张成功一张坏：整体是成功，但失败原因也要出现在结果里。
    FakePort port =
        new FakePort(
            ok(new JSONObject()
                .put("data", new org.json.JSONArray()
                    .put(new JSONObject().put("b64_json", PNG_BASE64))
                    .put(new JSONObject().put("b64_json", "!!!bad!!!")))
                .toString()));
    ToolResult result = new ImageGenerationTool(port).execute(input("x"), context());

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("生成成功"), result.getContent());
    assertTrue(result.getContent().contains("1/2"), result.getContent());
    assertTrue(result.getContent().contains("第 2 张"), result.getContent());
  }
}
