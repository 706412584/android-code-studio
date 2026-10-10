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

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * 文生视频工具：cc-haha media-gen 插件的协议移植（Agnes 兼容 + Grok 两套，按模型名分流）。
 *
 * <p><b>与图片工具的关键差异</b>：视频生成是异步任务——提交后返回任务 id，
 * 轮询状态直到 completed/done 才拿到结果 URL。轮询在工具执行线程内 sleep 等待
 * （工具本来就跑在后台执行器上），间隔 3s、超时上限 30 分钟，与 cc-haha 一致。
 *
 * <p><b>结果形态</b>：视频是 URL 不是 base64，且体积大（数 MB~数十 MB），不内联
 * 回传模型——下载落盘到保存目录，结果文本给路径，模型/用户按需处理。
 *
 * <p>协议语义照 {@code media-gen-server.mjs}（generateVideo / generateGrokVideo /
 * pollGrokVideo）：Agnes 失败态含 error/cancelled/rejected，对 429/5xx 瞬时失败
 * 重试 ≤3 次；Grok 失败态为 failed/expired。纯 Java + {@link HttpRequestPort}，可 JVM 单测。
 */
public final class VideoGenerationTool extends BaseTool {

  /** 轮询间隔（ms）。cc-haha 固定 3s。 */
  static final long POLL_INTERVAL_MS = 3_000;

  /** 默认等待上限（秒）；cc-haha 600s，可传 timeout_seconds 调到最多 1800s。 */
  static final int DEFAULT_TIMEOUT_SECONDS = 600;
  static final int MAX_TIMEOUT_SECONDS = 1800;

  /** 瞬时失败（429/5xx/网络抖动）允许的连续重试次数。 */
  static final int MAX_TRANSIENT_FAILURES = 3;

  /** 视频时长默认与上限（秒）：Grok 1-15。 */
  static final int DEFAULT_DURATION_SECONDS = 8;
  static final int MAX_DURATION_SECONDS = 15;

  /** 生成结果在项目工作区下的落盘子目录（与图片工具一致）。 */
  private static final String OUTPUT_DIR = "ai-generated";

  private final HttpRequestPort http;

  public VideoGenerationTool(HttpRequestPort http) {
    this.http = http;
  }

  @Override
  public String getName() {
    return ToolNames.VIDEO_GENERATION;
  }

  @Override
  public String getDescription() {
    return "用文生视频模型生成短视频（异步任务，可能等待数分钟）。参数：prompt（必填，画面描述）、"
        + "duration（可选，秒数 1-15，默认 8）、aspect_ratio（可选，如 16:9）、resolution（可选，480p/720p）。"
        + "结果保存为视频文件并给出路径。未配置时提示去设置→能力→视频生成配置。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.GENERATE;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.IMAGE_GENERATION;
  }

  @Override
  public boolean isConcurrencySafe() {
    // 轮询占线程等待，不做并发——同会话同时只该有一个视频任务在跑。
    return false;
  }

  @Override
  public org.json.JSONObject getParameters() throws org.json.JSONException {
    return new org.json.JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new org.json.JSONObject()
                .put(
                    "prompt",
                    new org.json.JSONObject()
                        .put("type", "string")
                        .put("description", "画面描述，写清主体、运动、镜头"))
                .put(
                    "duration",
                    new org.json.JSONObject()
                        .put("type", "integer")
                        .put("description", "时长秒数 1-15，默认 8"))
                .put(
                    "aspect_ratio",
                    new org.json.JSONObject()
                        .put("type", "string")
                        .put("description", "宽高比如 16:9 / 9:16"))
                .put(
                    "resolution",
                    new org.json.JSONObject()
                        .put("type", "string")
                        .put("description", "分辨率 480p 或 720p"))
                .put(
                    "timeout_seconds",
                    new org.json.JSONObject()
                        .put("type", "integer")
                        .put("description", "等待上限秒数，默认 600，最大 1800")))
        .put("required", new org.json.JSONArray().put("prompt"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    if (http == null) {
      return error("当前环境未配置网络访问，无法生成视频。");
    }
    VideoGenerationEndpoint endpoint =
        context == null ? null : context.getVideoGeneration();
    if (endpoint == null || !endpoint.isConfigured()) {
      return error("未配置视频生成服务商。请到 设置 → AI 助手 → 能力 → 视频生成 选择服务商与模型。");
    }

    String prompt = input.optString("prompt", "").trim();
    if (prompt.isEmpty()) {
      return error("prompt 不能为空：请描述要生成的画面。");
    }
    int timeoutSeconds =
        clamp(input.optInt("timeout_seconds", DEFAULT_TIMEOUT_SECONDS), 1, MAX_TIMEOUT_SECONDS);

    if (context != null) {
      context.reportProgress(
          "生成视频（" + endpoint.getModel() + "）: " + abbreviate(prompt, 60));
    }

    // 按模型名分流协议（cc-haha generateVideo 的正则语义原样）。
    Result result;
    try {
      result =
          endpoint.isGrokProtocol()
              ? generateGrok(prompt, endpoint, input, timeoutSeconds)
              : generateAgnes(prompt, endpoint, input, timeoutSeconds);
    } catch (VideoTaskException e) {
      return error(e.getMessage());
    } catch (Exception e) {
      return error("视频生成请求失败：" + describeException(e));
    }

    // 下载落盘：视频体积大（数 MB 起），不内联给模型，路径回传。
    File file;
    try {
      file = downloadToOutput(context, endpoint, result.url);
    } catch (IOException e) {
      // 下载失败也要把 URL 带回去：任务已成功，模型可告知用户链接或稍后重试下载。
      return ok(
          "视频已生成但保存失败（"
              + e.getMessage()
              + "）。视频链接: "
              + result.url
              + "\n模型: "
              + endpoint.getModel()
              + "，任务 ID: "
              + result.videoId);
    }

    return ok(
        "视频生成成功（"
            + endpoint.getModel()
            + "）。\n文件路径:\n"
            + file.getAbsolutePath()
            + "\n大小: "
            + (file.length() / 1024)
            + " KB\n任务 ID: "
            + result.videoId);
  }

  // ─── Agnes 兼容协议（POST {base}/videos → 轮询 /agnesapi） ───────────────────

  private Result generateAgnes(
      String prompt, VideoGenerationEndpoint endpoint, JSONObject input, int timeoutSeconds)
      throws Exception {
    JSONObject body = new JSONObject().put("model", endpoint.getModel()).put("prompt", prompt);
    // 可选生成参数按 cc-haha 的白名单透传。
    for (String key :
        new String[] {
          "image", "mode", "height", "width", "num_frames", "frame_rate",
          "num_inference_steps", "seed", "negative_prompt"
        }) {
      if (input.has(key) && !input.isNull(key)) {
        body.put(key, input.get(key));
      }
    }

    JSONObject created = postJson(endpoint.agnesCreateUrl(), endpoint.getApiKey(), body);
    String videoId = firstNonEmpty(created, "video_id", "id", "task_id");
    if (videoId == null) {
      throw new VideoTaskException("视频接口未返回 video_id、id 或 task_id。");
    }

    String pollUrl = endpoint.agnesPollUrl(videoId);
    long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
    int transientFailures = 0;
    String lastState = "unknown";

    while (System.currentTimeMillis() < deadline) {
      JSONObject status;
      try {
        status = getJson(pollUrl, endpoint.getApiKey());
        // 状态查询成功即重置瞬时失败计数（cc-haha 同语义）。
        transientFailures = 0;
        String state = status.optString("status", "").toLowerCase(java.util.Locale.ROOT);
        lastState = state.isEmpty() ? "unknown" : state;
        if (isAgnesFailureState(lastState)) {
          throw new VideoTaskException(
              "视频生成失败（" + lastState + "）：" + pickError(status, lastState));
        }
        if ("completed".equals(lastState) || (lastState.equals("unknown") && status.has("url"))) {
          String url = status.optString("url", "");
          if (url.isEmpty()) {
            throw new VideoTaskException("视频任务已完成但未返回 URL。");
          }
          return new Result(url, videoId);
        }
      } catch (VideoTaskException e) {
        throw e;
      } catch (Exception e) {
        // 网络/HTTP 异常按瞬时失败处理，退避重试。
        transientFailures++;
        if (transientFailures > MAX_TRANSIENT_FAILURES) {
          throw new VideoTaskException(
              "视频状态查询连续失败，任务 ID: " + videoId + "：" + describeException(e));
        }
      }
      sleep(POLL_INTERVAL_MS * transientFailures);
    }
    throw new VideoTaskException(
        "视频生成等待超时，任务 ID: " + videoId + "，最后状态: " + lastState);
  }

  private static boolean isAgnesFailureState(String state) {
    switch (state) {
      case "failed":
      case "error":
      case "cancelled":
      case "canceled":
      case "rejected":
        return true;
      default:
        return false;
    }
  }

  // ─── Grok 协议（POST /videos/generations → 轮询 GET /videos/{id}） ──────────

  private Result generateGrok(
      String prompt, VideoGenerationEndpoint endpoint, JSONObject input, int timeoutSeconds)
      throws Exception {
    int duration =
        clamp(
            input.optInt("duration", DEFAULT_DURATION_SECONDS), 1, MAX_DURATION_SECONDS);
    JSONObject body =
        new JSONObject()
            .put("model", endpoint.getModel())
            .put("prompt", prompt)
            .put("duration", duration);
    if (input.has("aspect_ratio") && !input.isNull("aspect_ratio")) {
      body.put("aspect_ratio", input.getString("aspect_ratio"));
    }
    if (input.has("resolution") && !input.isNull("resolution")) {
      body.put("resolution", input.getString("resolution"));
    }
    if (input.has("image_url") && !input.isNull("image_url")) {
      body.put("image_url", input.getString("image_url"));
    }

    JSONObject created = postJson(endpoint.grokCreateUrl(), endpoint.getApiKey(), body);
    String requestId = created.optString("request_id", "");
    if (requestId.isEmpty()) {
      throw new VideoTaskException("Grok 视频接口未返回 request_id。");
    }

    String pollUrl = endpoint.grokPollUrl(requestId);
    long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
    String lastState = "unknown";

    while (System.currentTimeMillis() < deadline) {
      JSONObject status = getJson(pollUrl, endpoint.getApiKey());
      String state = status.optString("status", "").toLowerCase(java.util.Locale.ROOT);
      lastState = state.isEmpty() ? "unknown" : state;
      if ("failed".equals(lastState) || "expired".equals(lastState)) {
        throw new VideoTaskException(
            "Grok 视频任务失败（" + lastState + "）：" + pickError(status, lastState));
      }
      if ("done".equals(lastState)) {
        String url = status.optJSONObject("video") == null
            ? ""
            : status.optJSONObject("video").optString("url", "");
        if (url.isEmpty()) {
          throw new VideoTaskException("Grok 视频任务已完成但未返回 video.url。");
        }
        return new Result(url, requestId);
      }
      sleep(POLL_INTERVAL_MS);
    }
    throw new VideoTaskException(
        "Grok 视频任务等待超时，任务 ID: " + requestId + "，最后状态: " + lastState);
  }

  // ─── HTTP 原语 ──────────────────────────────────────────────────────────────

  private JSONObject postJson(String url, String apiKey, JSONObject body) throws Exception {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Content-Type", "application/json");
    if (!apiKey.isEmpty()) {
      headers.put("Authorization", "Bearer " + apiKey);
    }
    HttpRequestPort.Response response =
        http.request("POST", url, headers, body.toString(), 300_000);
    if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
      throw new VideoTaskException(
          "提交视频任务失败（HTTP " + response.getStatusCode() + "）：" + abbreviate(response.getBody(), 400));
    }
    return new JSONObject(response.getBody());
  }

  private JSONObject getJson(String url, String apiKey) throws Exception {
    Map<String, String> headers = new LinkedHashMap<>();
    if (!apiKey.isEmpty()) {
      headers.put("Authorization", "Bearer " + apiKey);
    }
    HttpRequestPort.Response response = http.request("GET", url, headers, "", 60_000);
    // 202 = 任务进行中（部分网关语义），视作继续轮询。
    int code = response.getStatusCode();
    if ((code < 200 || code >= 300) && code != 202) {
      throw new VideoTaskException("HTTP " + code + "：" + abbreviate(response.getBody(), 400));
    }
    String body = response.getBody();
    return body.isEmpty() ? new JSONObject() : new JSONObject(body);
  }

  // ─── 下载落盘 ───────────────────────────────────────────────────────────────

  /**
   * 把结果 URL 下载到保存目录（用户指定或工作区 ai-generated/）。
   *
   * <p>走 HttpURLConnection 直连而不是 {@link HttpRequestPort}：后者以 String 承载
   * 响应体，二进制会被字符集转换损坏；视频必须按字节流落盘。
   */
  private static File downloadToOutput(
      ToolContext context, VideoGenerationEndpoint endpoint, String url) throws IOException {
    File base;
    String custom = endpoint.getOutputDir();
    if (!custom.isEmpty()) {
      base = new File(custom);
    } else {
      base = new File(context.getHomePath(), OUTPUT_DIR);
    }
    if (!base.exists() && !base.mkdirs() && !base.isDirectory()) {
      throw new IOException("无法创建目录: " + base.getAbsolutePath());
    }

    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setConnectTimeout(15_000);
    connection.setReadTimeout(120_000);
    connection.setInstanceFollowRedirects(true);
    int code = connection.getResponseCode();
    if (code < 200 || code >= 300) {
      throw new IOException("HTTP " + code);
    }

    File target = new File(base, "video-" + System.currentTimeMillis() + ".mp4");
    try (InputStream in = connection.getInputStream();
        FileOutputStream out = new FileOutputStream(target)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
    } finally {
      connection.disconnect();
    }
    return target;
  }

  // ─── 杂项 ───────────────────────────────────────────────────────────────────

  /** 任务失败（区别于网络异常）：消息已成型，直接作为工具错误结果。 */
  private static final class VideoTaskException extends Exception {
    VideoTaskException(String message) {
      super(message);
    }
  }

  private static final class Result {
    final String url;
    final String videoId;

    Result(String url, String videoId) {
      this.url = url;
      this.videoId = videoId;
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      // 被打断视为取消：抛出使轮询退出。
      throw new RuntimeException("视频生成等待被取消", e);
    }
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private static String firstNonEmpty(JSONObject json, String... keys) {
    for (String key : keys) {
      String value = json.optString(key, "");
      if (!value.isEmpty()) {
        return value;
      }
    }
    return null;
  }

  /** 从错误响应里挑人类可读的信息（cc-haha pickError 语义）。 */
  private static String pickError(JSONObject json, String fallback) {
    for (String key : new String[] {"message", "error", "detail"}) {
      String value = json.optString(key, "");
      if (!value.isEmpty()) {
        return value;
      }
    }
    return fallback;
  }

  private static String abbreviate(String value, int max) {
    if (value == null || value.length() <= max) {
      return value == null ? "" : value;
    }
    return value.substring(0, max) + "…";
  }

  private static String describeException(Exception e) {
    String message = e.getMessage();
    return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
  }
}
