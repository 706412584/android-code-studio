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

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 基于 RSS/Atom 搜索结果的 provider，**无需 API key**。
 *
 * <p><b>为什么默认给一个免密钥的实现</b>：搜索要 API key 意味着「装好应用 → 搜索不可用 →
 * 用户得先去某个网站注册、拿 key、再回来填」。多数人走到第二步就放弃了，于是
 * {@code web_search} 实际上等于不存在。免密钥的 RSS 端点虽然结果质量与配额不如商业 API，
 * 但让功能开箱可用；需要更好效果的用户仍可换成带 key 的 provider。
 *
 * <p>解析用简单的字符串扫描而非 XML 解析器：RSS 结构固定（{@code <item>} 内含
 * {@code <title>}/{@code <link>}/{@code <description>}），而引入 XML 解析器会给工具模块
 * 增加依赖。解析失败时返回空列表——搜索失败应当表现为「没找到」，由调用方决定怎么提示。
 *
 * <p><b>关于默认端点</b>：{@code DEFAULT_ENDPOINT} 指向 Bing 的 {@code format=rss} 输出。
 * 该端点**未经本项目实测验证**（无网络环境的开发机上无法验证），且第三方端点随时可能
 * 变更或限流。因此：端点通过构造参数可替换，解析器同时兼容 RSS 与 Atom 两种结构；
 * 若实际使用中发现默认端点不可用，换成任何返回 RSS/Atom 的搜索端点即可，
 * 无需改动本类。
 */
public final class RssSearchProvider implements WebSearchTool.Provider {

  /** 搜索端点。未经实测验证，可替换为任何返回 RSS/Atom 的搜索端点。 */
  public static final String DEFAULT_ENDPOINT = "https://www.bing.com/search";

  private final HttpPort http;
  private final String endpoint;
  private final String apiKey;

  public RssSearchProvider(HttpPort http) {
    this(http, DEFAULT_ENDPOINT, "");
  }

  public RssSearchProvider(HttpPort http, String endpoint, String apiKey) {
    this.http = http == null ? HttpPort.none() : http;
    this.endpoint = endpoint == null || endpoint.trim().isEmpty() ? DEFAULT_ENDPOINT : endpoint.trim();
    this.apiKey = apiKey == null ? "" : apiKey.trim();
  }

  @Override
  public boolean isConfigured() {
    // 免密钥：只要有网络端口就可用。
    return true;
  }

  @Override
  public String unavailableReason() {
    return "";
  }

  @Override
  public List<WebSearchTool.Result> search(String query, int limit) throws Exception {
    if (query == null || query.trim().isEmpty()) {
      return Collections.emptyList();
    }
    int count = limit <= 0 ? WebSearchTool.DEFAULT_RESULTS : limit;
    String url = buildUrl(query.trim(), count);

    String body = http.getText(url, Collections.<String, String>emptyMap());
    List<WebSearchTool.Result> results = parseItems(body);
    return results.size() > count ? new ArrayList<>(results.subList(0, count)) : results;
  }

  /**
   * 构造搜索 URL。
   *
   * <p>{@code format=rss} 让端点返回 RSS 而非 HTML：结构固定、无需从页面里猜选择器。
   * 查询词必须编码——不编码时含空格或 {@code &} 的查询会把 URL 拆坏，表现为
   * 「搜什么都返回同样结果」。
   */
  String buildUrl(String query, int limit) {
    StringBuilder sb = new StringBuilder(endpoint);
    sb.append(endpoint.contains("?") ? '&' : '?');
    sb.append("q=").append(encode(query));
    sb.append("&format=rss");
    sb.append("&count=").append(limit);
    if (!apiKey.isEmpty()) {
      sb.append("&key=").append(encode(apiKey));
    }
    return sb.toString();
  }

  private static String encode(String value) {
    try {
      return URLEncoder.encode(value, "UTF-8");
    } catch (java.io.UnsupportedEncodingException e) {
      // UTF-8 必然存在；此分支不可达。
      return value;
    }
  }

  /**
   * 从 RSS/Atom 文本中取出条目。
   *
   * <p>同时兼容 {@code <item>}（RSS）与 {@code <entry>}（Atom）：不同端点返回的格式不同，
   * 只认一种会让换端点后静默返回空结果。
   */
  static List<WebSearchTool.Result> parseItems(String xml) {
    List<WebSearchTool.Result> results = new ArrayList<>();
    if (xml == null || xml.isEmpty()) {
      return results;
    }
    String lower = xml.toLowerCase(Locale.ROOT);

    int cursor = 0;
    while (true) {
      int itemStart = indexOfAny(lower, cursor, "<item", "<entry");
      if (itemStart < 0) {
        break;
      }
      int itemEnd = indexOfAny(lower, itemStart, "</item>", "</entry>");
      if (itemEnd < 0) {
        break;
      }
      String block = xml.substring(itemStart, itemEnd);
      String title = firstTag(block, "title");
      String link = firstTag(block, "link");
      if (link.isEmpty()) {
        // Atom 把地址放在属性里：<link href="..."/>
        link = attribute(block, "link", "href");
      }
      String snippet = firstTag(block, "description");
      if (snippet.isEmpty()) {
        snippet = firstTag(block, "summary");
      }
      if (!link.isEmpty() || !title.isEmpty()) {
        results.add(
            new WebSearchTool.Result(
                HtmlTextExtractor.extract(title),
                link.trim(),
                HtmlTextExtractor.extract(stripCdata(snippet))));
      }
      cursor = itemEnd + 1;
    }
    return results;
  }

  private static int indexOfAny(String haystack, int from, String a, String b) {
    int ia = haystack.indexOf(a, from);
    int ib = haystack.indexOf(b, from);
    if (ia < 0) {
      return ib;
    }
    if (ib < 0) {
      return ia;
    }
    return Math.min(ia, ib);
  }

  /** 取首个 {@code <tag>…</tag>} 的内容；找不到返回空串。 */
  private static String firstTag(String block, String tag) {
    String lower = block.toLowerCase(Locale.ROOT);
    int open = lower.indexOf("<" + tag);
    if (open < 0) {
      return "";
    }
    int contentStart = lower.indexOf('>', open);
    if (contentStart < 0) {
      return "";
    }
    int close = lower.indexOf("</" + tag, contentStart);
    if (close < 0) {
      return "";
    }
    return block.substring(contentStart + 1, close).trim();
  }

  /** 取形如 {@code <tag attr="value"/>} 的属性值；找不到返回空串。 */
  private static String attribute(String block, String tag, String attr) {
    String lower = block.toLowerCase(Locale.ROOT);
    int open = lower.indexOf("<" + tag);
    if (open < 0) {
      return "";
    }
    int close = lower.indexOf('>', open);
    if (close < 0) {
      return "";
    }
    String head = block.substring(open, close);
    String key = attr.toLowerCase(Locale.ROOT) + "=";
    int at = head.toLowerCase(Locale.ROOT).indexOf(key);
    if (at < 0) {
      return "";
    }
    int valueStart = at + key.length();
    if (valueStart >= head.length()) {
      return "";
    }
    char quote = head.charAt(valueStart);
    if (quote == '"' || quote == '\'') {
      int end = head.indexOf(quote, valueStart + 1);
      return end < 0 ? "" : head.substring(valueStart + 1, end).trim();
    }
    int end = valueStart;
    while (end < head.length() && !Character.isWhitespace(head.charAt(end))) {
      end++;
    }
    return head.substring(valueStart, end).trim();
  }

  /** 去掉 {@code <![CDATA[…]]>} 包装。 */
  private static String stripCdata(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    if (trimmed.startsWith("<![CDATA[") && trimmed.endsWith("]]>")) {
      return trimmed.substring(9, trimmed.length() - 3);
    }
    return trimmed;
  }
}
