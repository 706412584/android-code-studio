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

import java.util.Locale;

/**
 * 从 HTML 中提取可读文本。
 *
 * <p><b>为什么必须剥离标签</b>：把原始 HTML 直接回灌给模型有两重代价。一是体积——
 * 一个文档页面的标签往往比正文多好几倍，几万 token 的上下文被 {@code <div class="...">}
 * 占满；二是质量——标签噪声会干扰模型对内容的理解，实测中它会把导航栏文字当成正文。
 *
 * <p><b>为什么不用完整的 HTML 解析器</b>：工具模块要保持零依赖（除 org.json），
 * 而引入 jsoup 之类的解析器只为一件事——取文本。这里用一次线性扫描处理足够好的场景：
 * 去掉 {@code script}/{@code style} 的内容、把块级标签换成换行、解开常见实体、压缩空白。
 * 对结构极不规范的页面可能不如完整解析器，但不会更差到影响可用性，而依赖成本为零。
 *
 * <p>本类不引用 Android 类型，可单测。
 */
public final class HtmlTextExtractor {

  /** 需要整体丢弃内容的标签（内容不是给人读的正文）。 */
  private static final String[] DROPPED_TAGS = {"script", "style", "noscript", "svg", "head"};

  /** 视为块级、需要产生换行的标签。 */
  private static final String[] BLOCK_TAGS = {
    "p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6",
    "section", "article", "header", "footer", "blockquote", "pre", "table", "ul", "ol"
  };

  private HtmlTextExtractor() {}

  /** 提取纯文本；输入为 null 或空时返回空串。 */
  public static String extract(String html) {
    if (html == null || html.isEmpty()) {
      return "";
    }
    String text = stripDroppedSections(html);
    text = removeTagsKeepingText(text);
    text = decodeEntities(text);
    return collapseWhitespace(text);
  }

  /**
   * 丢掉整段不需要的标签内容。
   *
   * <p>循环处理嵌套同名标签（如 {@code <div><script>…</script></div>} 里的多层结构）：
   * 每次只找最近的一对闭合标签，找不到闭合就丢弃到结尾。
   */
  private static String stripDroppedSections(String html) {
    String result = html;
    for (String tag : DROPPED_TAGS) {
      result = dropTag(result, tag);
    }
    return result;
  }

  private static String dropTag(String html, String tag) {
    String lower = html.toLowerCase(Locale.ROOT);
    String open = "<" + tag;
    String close = "</" + tag;
    StringBuilder sb = new StringBuilder(html.length());
    int cursor = 0;
    while (true) {
      int start = lower.indexOf(open, cursor);
      if (start < 0) {
        sb.append(html, cursor, html.length());
        break;
      }
      sb.append(html, cursor, start);
      int end = lower.indexOf(close, start);
      if (end < 0) {
        // 没有闭合标签（截断的页面）→ 丢弃到结尾
        break;
      }
      int afterClose = lower.indexOf('>', end);
      cursor = afterClose < 0 ? html.length() : afterClose + 1;
      // 块级标签的位置需要保留一个换行，避免前后文粘连成一句
      sb.append('\n');
    }
    return sb.toString();
  }

  /**
   * 去掉标签、保留标签之间的文本，块级标签处插入换行。
   *
   * <p><b>裸 {@code '<'}</b>：真实正文里会出现「a &lt; b」这种字面小于号。若一律把它
   * 当成标签开头并丢弃到下一个 {@code '>'}，中间的正常文字会被整段吃掉——
   * 而 {@code '>'} 可能出现在很远的后文。因此先判断 {@code '<'} 后面是否像标签开头
   * （字母、{@code /}、{@code !}、{@code ?}）；不像就按字面保留这一个字符。
   */
  private static String removeTagsKeepingText(String html) {
    String lower = html.toLowerCase(Locale.ROOT);
    StringBuilder sb = new StringBuilder(html.length());
    int i = 0;
    while (i < html.length()) {
      int lt = lower.indexOf('<', i);
      if (lt < 0) {
        sb.append(html, i, html.length());
        break;
      }
      sb.append(html, i, lt);
      if (!looksLikeTagStart(lower, lt)) {
        // 裸 '<'：按字面保留，并继续扫描它之后的内容。
        sb.append('<');
        i = lt + 1;
        continue;
      }
      int gt = lower.indexOf('>', lt);
      if (gt < 0) {
        // 未闭合：按字面保留剩余内容
        sb.append(html, lt, html.length());
        break;
      }
      if (isBlockTag(tagNameOf(lower, lt, gt))) {
        sb.append('\n');
      }
      i = gt + 1;
    }
    return sb.toString();
  }

  /** {@code '<'} 之后是否像标签名（字母 / '/' / '!' / '?'）。 */
  private static boolean looksLikeTagStart(String lower, int lt) {
    int next = lt + 1;
    if (next >= lower.length()) {
      return false;
    }
    char c = lower.charAt(next);
    if (c == '/' || c == '!' || c == '?') {
      // "</"、"<!--"、"<?" 都是标签/注释的开头
      int after = next + 1;
      return after < lower.length() && (Character.isLetter(lower.charAt(after)) || c != '?');
    }
    return Character.isLetter(c);
  }

  /** 取 {@code <name ...} 中的 name；取不到返回空串。 */
  private static String tagNameOf(String lower, int lt, int gt) {
    int start = lt + 1;
    if (start >= gt) {
      return "";
    }
    char first = lower.charAt(start);
    if (first == '/' || first == '!' || first == '?') {
      start++;
    }
    int end = start;
    while (end < gt) {
      char c = lower.charAt(end);
      if (!Character.isLetterOrDigit(c)) {
        break;
      }
      end++;
    }
    return lower.substring(start, Math.min(end, gt));
  }

  private static boolean isBlockTag(String name) {
    if (name.isEmpty()) {
      return false;
    }
    for (String tag : BLOCK_TAGS) {
      if (tag.equals(name)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 解开常见 HTML 实体。
   *
   * <p>先替换 {@code &amp;} 之外的长实体再处理 {@code &amp;}：反过来的话
   * {@code &amp;lt;} 会被先解成 {@code &lt;}，再解一次就成了 {@code <}——
   * 双重解码。因此 {@code &amp;} 必须最后处理。
   */
  private static String decodeEntities(String text) {
    String result = text;
    result = result.replace("&nbsp;", " ");
    result = result.replace("&lt;", "<");
    result = result.replace("&gt;", ">");
    result = result.replace("&quot;", "\"");
    result = result.replace("&#39;", "'");
    result = result.replace("&apos;", "'");
    result = result.replace("&mdash;", "—");
    result = result.replace("&ndash;", "–");
    result = result.replace("&hellip;", "…");
    result = result.replace("&amp;", "&");
    return result;
  }

  /**
   * 压缩空白：行内连续空白折成一个空格，连续空行折成一个换行。
   *
   * <p>保留换行是刻意的——段落结构是正文可读性的一部分，全压成一行会让模型难以区分
   * 标题与正文。
   */
  private static String collapseWhitespace(String text) {
    StringBuilder sb = new StringBuilder(text.length());
    boolean lastWasSpace = false;
    int pendingNewlines = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n' || c == '\r') {
        pendingNewlines++;
        lastWasSpace = false;
        continue;
      }
      if (c == ' ' || c == '\t' || c == '\u00a0') {
        lastWasSpace = true;
        continue;
      }
      if (pendingNewlines > 0) {
        if (sb.length() > 0) {
          sb.append('\n');
        }
        pendingNewlines = 0;
        lastWasSpace = false;
      } else if (lastWasSpace && sb.length() > 0) {
        sb.append(' ');
        lastWasSpace = false;
      }
      sb.append(c);
    }
    return sb.toString().trim();
  }
}
