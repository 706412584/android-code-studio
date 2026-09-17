/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from LineCode Pro (https://github.com/LangLang03/LineCodePro),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

package com.tom.rv2ide.ai.tool;

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.Collections;
import org.json.JSONObject;

/**
 * 抓取网页内容并转为可读文本。
 *
 * <p><b>为什么需要它</b>：模型的知识有截止时间，且无法自己查阅正在使用的库的最新文档。
 * 遇到「这个 API 在新版本里怎么用」这类问题，它只能凭记忆作答，而记忆可能已经过时——
 * 用户拿到的是看似合理却编译不过的代码。
 *
 * <p>HTML 会先被剥离为纯文本（见 {@link HtmlTextExtractor}）：原始标签既占满上下文，
 * 又干扰模型对正文的理解。
 *
 * <p>只支持 {@code http}/{@code https}：实现方（app 层）通过既有的 URL 策略做校验，
 * 因此这里不做协议白名单的重复实现——安全边界只有一处才不会漏。
 */
public final class WebFetchTool extends BaseTool {

  /** 回灌给模型的正文字符上限。超出后截断并说明。 */
  static final int MAX_CHARS = 60 * 1024;

  /** 单次抓取允许的最大响应体积（下载阶段就截断，避免大文件把内存吃掉）。 */
  static final int MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024;

  private final HttpPort http;

  public WebFetchTool(HttpPort http) {
    this.http = http == null ? HttpPort.none() : http;
  }

  @Override
  public String getName() {
    return ToolNames.WEB_FETCH;
  }

  @Override
  public String getDescription() {
    return "Fetch a web page or text resource and return its readable content. "
        + "HTML is converted to plain text. Use this to check current documentation, "
        + "release notes or API references instead of relying on memory.";
  }

  @Override
  public ToolCategory getCategory() {
    // 读远端内容，不改本地任何东西。
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.READ;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "url",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "Absolute http(s) URL to fetch"))
                .put(
                    "raw",
                    new JSONObject()
                        .put("type", "boolean")
                        .put(
                            "description",
                            "Return the body as-is instead of converting HTML to text. "
                                + "Use for plain text, JSON or source files.")))
        .put("required", new org.json.JSONArray().put("url"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String url = input.optString("url", "").trim();
    if (url.isEmpty()) {
      return error("url 不能为空。");
    }
    if (!isHttpUrl(url)) {
      return error("只支持 http/https 链接：" + url);
    }
    boolean raw = input.optBoolean("raw", false);

    if (context != null) {
      context.reportProgress("抓取 " + url);
    }

    try {
      String body = http.getText(url, Collections.<String, String>emptyMap());
      if (body == null) {
        body = "";
      }

      boolean looksHtml = !raw && looksLikeHtml(body);
      String content = looksHtml ? HtmlTextExtractor.extract(body) : body;

      if (content.trim().isEmpty()) {
        // 空结果要明确说出来。页面可能全靠 JS 渲染，模型据此换用别的手段，
        // 而不是以为「页面就是空的」。
        return ok("（" + url + " 没有可提取的文本内容。若该页面依赖 JavaScript 渲染，"
            + "抓取到的会是空壳 HTML。）");
      }

      StringBuilder sb = new StringBuilder();
      sb.append("[来源: ").append(url).append("]\n");
      if (looksHtml) {
        sb.append("[已从 HTML 提取正文]\n");
      }
      if (content.length() > MAX_CHARS) {
        sb.append(content, 0, MAX_CHARS);
        sb.append("\n\n... (内容过长，已截断，共 ").append(content.length()).append(" 字符) ...");
      } else {
        sb.append(content);
      }
      return ok(sb.toString());
    } catch (Exception e) {
      ExceptionUtils.restoreInterrupt(e);
      return error("抓取失败：" + ExceptionUtils.describeException(e));
    }
  }

  /**
   * 是否看起来像 HTML。
   *
   * <p>按开头若干字符判断而非全文搜索：HTML 文档的标记必然出现在开头（doctype 或首个
   * 标签），而正文里提到 {@code <div>} 的纯文本文章不该被当成 HTML 处理——
   * 那会把作者举例用的标签也剥掉。
   */
  private static boolean looksLikeHtml(String body) {
    int limit = Math.min(body.length(), 512);
    String head = body.substring(0, limit).toLowerCase(java.util.Locale.ROOT).trim();
    return head.startsWith("<!doctype html")
        || head.startsWith("<html")
        || head.contains("<head")
        || head.contains("<body")
        || head.contains("<div")
        || head.contains("<meta")
        || head.contains("<title");
  }

  private static boolean isHttpUrl(String url) {
    String lower = url.toLowerCase(java.util.Locale.ROOT);
    return lower.startsWith("http://") || lower.startsWith("https://");
  }
}
