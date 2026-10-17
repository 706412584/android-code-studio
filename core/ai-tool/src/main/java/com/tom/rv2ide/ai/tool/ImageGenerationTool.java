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
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 文生图工具：调用 OpenAI 兼容的 {@code POST {baseUrl}/images/generations} 端点。
 *
 * <p><b>为什么走 ToolContext 注入而不是工具参数</b>：服务商 baseUrl / 密钥 / 模型名
 * 是用户在「能力」页配置的全局偏好，不该让模型每轮猜，也不该出现在模型可见的
 * 参数 schema 里（密钥泄露面）。模型只给 prompt / size / n。
 *
 * <p><b>结果回传</b>：图片落盘到工作区（或能力页指定的目录），结果文本里给出路径——
 * 模型可用 {@code file_read} 或告知用户位置。**图片字节不进上下文**（见
 * {@code renderResults} 内的说明）。
 *
 * <p><b>结果形态三种都要认</b>：请求里强制 {@code response_format=b64_json}，
 * 但不少网关忽略它——实测 loomy2api 只回普通 https 外链，返回 data URL 的也有。
 * 只认 b64_json 会让这些网关的生成被报成「服务商未返回图片数据」。三种形态的
 * 处理见 {@link #renderResults}。
 *
 * <p>纯 Java + {@link HttpRequestPort}，可在 JVM 上单测。
 */
public final class ImageGenerationTool extends BaseTool {

  /** 图片尺寸上限（边长 px）。超过部分网关会直接 400，钳到常见最大值。 */
  static final int MAX_SIZE_PX = 4096;

  /** 单次生成张数上限。多图会成倍放大 base64 回传体积。 */
  static final int MAX_COUNT = 4;

  /** 生成结果在项目工作区下的落盘子目录。 */
  private static final String OUTPUT_DIR = "ai-generated";

  /** 生成请求超时——图片生成常见 10~60s，给足余量。 */
  private static final int TIMEOUT_MS = 120_000;

  private final HttpRequestPort http;

  /**
   * 结果图片 URL 的下载口子。
   *
   * <p>与 {@link #http} 分开：那个的响应体是 String，二进制经字符集转换会损坏，
   * 因此下载走返回原始字节的 {@link HttpPort#getBytes}（实现见 app 层
   * {@code AppHttpPort}，内部仍走 {@code SimpleHttpClient} 的 UrlPolicy 与代理）。
   * 可为 null（部分装配场景只注入请求口），此时 URL 形式的结果只能报告不可用。
   */
  private final HttpPort binaryHttp;

  public ImageGenerationTool(HttpRequestPort http) {
    this(http, null);
  }

  public ImageGenerationTool(HttpRequestPort http, HttpPort binaryHttp) {
    this.http = http;
    this.binaryHttp = binaryHttp;
  }

  @Override
  public String getName() {
    return ToolNames.IMAGE_GENERATION;
  }

  @Override
  public String getDescription() {
    return "用文生图模型生成图片。参数：prompt（必填，画面描述）、size（可选，宽x高，"
        + "默认 1024x1024，需为 64 的倍数）、n（可选，张数 1-4，默认 1）。"
        + "生成结果以图片直接回传并保存到文件，结果里给出保存路径。"
        + "未配置图片服务商时报错并提示去设置→能力→图片生成配置。";
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
    // 网络请求且无共享可变状态，可并发。
    return true;
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
                        .put("description", "画面描述，写清主体、风格、构图"))
                .put(
                    "size",
                    new org.json.JSONObject()
                        .put("type", "string")
                        .put("description", "图片尺寸，如 1024x1024 / 1024x1792，默认 1024x1024"))
                .put(
                    "n",
                    new org.json.JSONObject()
                        .put("type", "integer")
                        .put("description", "生成张数 1-4，默认 1")))
        .put("required", new JSONArray().put("prompt"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    if (http == null) {
      return error("当前环境未配置网络访问，无法生成图片。");
    }
    ImageGenerationEndpoint endpoint =
        context == null ? null : context.getImageGeneration();
    if (endpoint == null || !endpoint.isConfigured()) {
      return error("未配置图片生成服务商。请到 设置 → AI 助手 → 能力 → 图片生成 选择服务商与模型。");
    }

    String prompt = input.optString("prompt", "").trim();
    if (prompt.isEmpty()) {
      return error("prompt 不能为空：请描述要生成的画面。");
    }

    String size = normalizeSize(input.optString("size", "1024x1024").trim());
    if (size == null) {
      return error("size 格式无效：应为 宽x高（如 1024x1024），每边 16-4096。");
    }

    int n = (int) input.optDouble("n", 1);
    if (n < 1) {
      n = 1;
    }
    if (n > MAX_COUNT) {
      n = MAX_COUNT;
    }

    if (context != null) {
      context.reportProgress("生成图片（" + endpoint.getModel() + "）: " + abbreviate(prompt, 60));
    }

    // 请求体。response_format 强制 b64_json：url 形式返回的临时外链模型访问不了，
    // 而且很快过期——base64 直接进结果链路。
    JSONObject body =
        new JSONObject()
            .put("model", endpoint.getModel())
            .put("prompt", prompt)
            .put("n", n)
            .put("size", size)
            .put("response_format", "b64_json");

    java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
    headers.put("Content-Type", "application/json");
    if (!endpoint.getApiKey().isEmpty()) {
      headers.put("Authorization", "Bearer " + endpoint.getApiKey());
    }

    HttpRequestPort.Response response;
    try {
      response = http.request("POST", endpoint.imagesUrl(), headers, body.toString(), TIMEOUT_MS);
    } catch (Exception e) {
      return error("图片生成请求失败：" + describeException(e));
    }

    if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
      String detail = response.getBody();
      if (detail.length() > 800) {
        detail = detail.substring(0, 800) + "…";
      }
      return error(
          "图片生成失败（HTTP "
              + response.getStatusCode()
              + "）。服务商返回: "
              + (detail.isEmpty() ? "（空）" : detail));
    }

    return renderResults(context, endpoint, response.getBody());
  }

  /**
   * 解析响应并把图片落盘、回传。
   *
   * <p>兼容三种返回形态（按出现频率）：
   * <ol>
   *   <li>标准 OpenAI 的 {@code data[].b64_json}；
   *   <li>{@code data[].url} 为 data URL（内嵌 base64）；
   *   <li>{@code data[].url} 为**普通 http(s) URL**——不少网关（实测 loomy2api）
   *       无视 {@code response_format=b64_json}，只回外链。此时下载该 URL 取字节
   *       （见 {@link #downloadResultImage}）。此前只认前两种，第三种会被报成
   *       「服务商未返回图片数据」，用户看到的是生图失败。
   * </ol>
   */
  private ToolResult renderResults(
      ToolContext context, ImageGenerationEndpoint endpoint, String responseBody) {
    JSONArray data;
    try {
      data = new JSONObject(responseBody).optJSONArray("data");
    } catch (Exception e) {
      return error("图片生成响应不是 JSON: " + abbreviate(responseBody, 200));
    }
    if (data == null || data.length() == 0) {
      return error("图片生成响应里没有图片数据（data 为空）。");
    }

    StringBuilder note = new StringBuilder();
    int saved = 0;

    for (int i = 0; i < data.length(); i++) {
      JSONObject item = data.optJSONObject(i);
      if (item == null) {
        continue;
      }
      String base64 = item.optString("b64_json", "");
      String mime = "image/png";
      String remoteUrl = "";
      if (base64.isEmpty()) {
        // 部分网关无视 response_format，返回 data URL（data:image/png;base64,....）
        // 或普通外链。
        String url = item.optString("url", "");
        if (url.startsWith("data:")) {
          int comma = url.indexOf(',');
          if (comma > 0) {
            mime = url.substring(5, comma).split(";")[0];
            base64 = url.substring(comma + 1);
          }
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
          remoteUrl = url;
        }
      }

      byte[] bytes = null;
      if (!base64.isEmpty()) {
        try {
          bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
          note.append("第 ").append(i + 1).append(" 张：base64 解码失败\n");
          continue;
        }
      } else if (!remoteUrl.isEmpty()) {
        try {
          HttpPort.BinaryResponse downloaded = downloadResultImage(remoteUrl);
          bytes = downloaded.getBytes();
          if (downloaded.getMimeType() != null && downloaded.getMimeType().startsWith("image/")) {
            mime = downloaded.getMimeType();
          }
        } catch (Exception e) {
          note.append("第 ").append(i + 1).append(" 张：下载结果图片失败（")
              .append(describeException(e)).append("）\n");
          continue;
        }
      }

      if (bytes == null || bytes.length == 0) {
        note.append("第 ").append(i + 1).append(" 张：服务商未返回图片数据\n");
        continue;
      }

      File file;
      try {
        file = writeOutput(context, endpoint, bytes, mime, i + 1);
      } catch (IOException e) {        note.append("第 ").append(i + 1).append(" 张：保存失败（").append(e.getMessage()).append("）\n");
        continue;
      }

      saved++;
      // **图片不进上下文**（用户实测决策）：1024px 全图的 base64 ≈700KB ≈175K
      // token，塞进 ToolResult 会同时引发两个实测问题——①上下文被单次工具结果
      // 撑满，后续轮次模型"看不到"自己刚生成的结果，误判失败而反复重新生成；
      // ②大 base64 随会话历史反复搬运，低端机上 GC 风暴、主线程掉帧上百。
      // 现在结果只给纯文本（成功确认 + 落盘路径），模型靠文本确认成功。
      note.append("图片 ").append(i + 1).append('/').append(data.length())
          .append(": ").append(file.getAbsolutePath()).append('\n');
    }

    if (saved == 0) {
      // 把逐条原因带上：否则「下载失败」「base64 解码失败」这类真正有用的信息
      // 会被丢掉，用户只看到「无法解析出有效图片」，无从判断该改什么。
      return error(
          "图片生成响应无法解析出有效图片。\n"
              + note
              + "原始响应: "
              + abbreviate(responseBody, 400));
    }

    note.insert(0, "生成成功（" + endpoint.getModel() + "）。\n");
    note.append("共 ").append(saved).append('/').append(data.length()).append(" 张已保存。\n");
    note.append("图片文件已在上方路径，任务完成，无需再次生成。\n");
    return ok(note.toString());
  }

  /**
   * 下载 URL 形式的结果图片。
   *
   * <p>走 {@link HttpPort#getBytes} 而不是自己开 {@code HttpURLConnection}：
   * 那条路径由 app 层 {@code AppHttpPort} 委托给 {@code SimpleHttpClient}，
   * 其中内建逐跳的 {@code UrlPolicy} 校验与代理——自己再建一条连接就等于复制出
   * 第二处安全边界（与 {@code VideoGenerationTool} 直连的做法不同，那条是历史遗留）。
   *
   * <p>未注入下载口（{@code binaryHttp == null}，如单测只给了请求口）时明确失败，
   * 不静默降级——否则用户会看到「未返回图片数据」而不知道是装配缺了口子。
   */
  private HttpPort.BinaryResponse downloadResultImage(String url) throws Exception {
    if (binaryHttp == null) {
      throw new IOException("未配置二进制下载通道，无法获取 URL 形式的结果图片");
    }
    return binaryHttp.getBytes(url, null);
  }

  /** 尺寸规范化：宽x高，每边 16-4096；无效返回 null。 */
  private static String normalizeSize(String raw) {
    if (raw.isEmpty()) {
      return "1024x1024";
    }
    int x = raw.indexOf('x');
    if (x <= 0 || x == raw.length() - 1) {
      return null;
    }
    int w;
    int h;
    try {
      w = Integer.parseInt(raw.substring(0, x).trim());
      h = Integer.parseInt(raw.substring(x + 1).trim());
    } catch (NumberFormatException e) {
      return null;
    }
    if (w < 16 || h < 16 || w > MAX_SIZE_PX || h > MAX_SIZE_PX) {
      return null;
    }
    return w + "x" + h;
  }

  /**
   * 落盘：用户在能力页指定了目录就用它，否则落项目工作区下的 {@value #OUTPUT_DIR}。
   *
   * <p>默认放工作区而不是 app 私有目录：生成的图属于当前项目的产物，路径直接可见、
   * 可被 {@code file_read} 引用、可进版本库；core/ai-tool 零 Android 依赖，
   * 也拿不到 {@code getExternalFilesDir}。
   */
  private static File writeOutput(
      ToolContext context, ImageGenerationEndpoint endpoint, byte[] bytes, String mime, int index)
      throws IOException {
    String custom = endpoint.getOutputDir();
    File base;
    if (!custom.isEmpty()) {
      base = new File(custom);
    } else {
      base = new File(context.getHomePath(), OUTPUT_DIR);
    }
    // 用 isDirectory 而非 exists 判定：路径存在但**是文件**时，mkdirs 不会执行，
    // 后续落盘只会得到一个「Not a directory」，与真实原因（保存目录配置错了）无关。
    if (!base.isDirectory() && !base.mkdirs() && !base.isDirectory()) {
      throw new IOException("无法创建目录: " + base.getAbsolutePath());
    }
    String ext = mime.endsWith("/jpeg") ? ".jpg" : mime.endsWith("/webp") ? ".webp" : ".png";
    File target = new File(base, "img-" + System.currentTimeMillis() + "-" + index + ext);
    try (FileOutputStream out = new FileOutputStream(target)) {
      out.write(bytes);
    }
    return target;
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
