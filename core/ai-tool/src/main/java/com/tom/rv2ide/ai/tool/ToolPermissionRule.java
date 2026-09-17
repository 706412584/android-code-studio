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

import com.tom.rv2ide.ai.tool.api.ToolNames;
import org.json.JSONObject;

/**
 * 危险工具授权的一条粒度规则：匹配「哪个工具 + 什么样的参数」。
 *
 * <p><b>为什么需要它</b>：此前授权只有一个全局布尔位（{@code dangerous_confirmed}），
 * 用户对 {@code shell_execute "ls"} 点过一次「始终允许」，就等于放行
 * {@code shell_execute "rm -rf /"}。粒度太粗使「始终允许」这个选项实际上不可用——
 * 用户只能被迫每次都点「本次允许」。
 *
 * <p><b>授权粒度按工具分类决定</b>，因为「参数里什么才是危险的部分」因工具而异：
 * <ul>
 *   <li>shell 类——危险在**命令**。粒度取命令的**首词**（如 {@code git}、{@code ./gradlew}），
 *       这样 {@code git status} 的授权不会顺带放行 {@code git push --force}。
 *       取首词而非整条命令是刻意的折中：整条命令前缀匹配过窄（参数一变就要重问），
 *       只匹配工具名又过宽（等于回到全局布尔）。</li>
 *   <li>文件类——危险在**路径**。粒度取绝对路径，放行一次写入不等于放行任意路径。</li>
 *   <li>其余——没有可提取的判别字段，退化为按工具名授权。</li>
 * </ul>
 *
 * <p>规则以 {@code toolName + '\u0000' + scope} 的字符串形式持久化。
 * 用 NUL 作分隔符：工具名与命令首词都是受限字符集，NUL 不可能出现在其中，
 * 因此不存在拼接歧义（{@code "a" + "b\u0000c"} 与 {@code "a\u0000b" + "c"} 不会撞车）。
 *
 * <p>本类不引用 Android 类型，可单测。
 */
public final class ToolPermissionRule {

  /** shell 类工具的参数键：命令全文。 */
  public static final String ARG_COMMAND = "command";

  /** 文件写类工具的参数键：目标路径。 */
  public static final String ARG_FILE_PATH = "file_path";

  /** 删除类工具的参数键：路径数组。 */
  public static final String ARG_PATHS = "paths";

  /** 键与值之间的分隔符。见类注释说明为何选 NUL。 */
  private static final char SEPARATOR = '\u0000';

  /** 多路径 scope 内部的连接符。路径不可能含控制字符，故无歧义。 */
  private static final char PATH_JOINER = '\u0001';

  private ToolPermissionRule() {}

  /**
   * 构造一条规则键。
   *
   * <p>工具名会先归一为规范名：模型可能写别名（{@code read} 而非 {@code file_read}），
   * 授权时用别名、判定时用规范名会让用户点过的「始终允许」静默失效，表现为反复弹窗。
   *
   * @param toolName 工具名（可为别名）
   * @param arguments 工具参数原始 JSON；null 或无法解析时按无参数处理
   * @return 持久化用的规则键；toolName 为空时返回空串（调用方应视作不匹配）
   */
  public static String keyFor(String toolName, String arguments) {
    if (toolName == null || toolName.isEmpty()) {
      return "";
    }
    String canonical = ToolRegistry.canonicalName(toolName);
    return canonical + SEPARATOR + scopeOf(canonical, arguments);
  }

  /**
   * 提取参数中用于判别授权的片段。
   *
   * <p>无法提取时返回空串——此时规则退化为「该工具整体已授权」。
   *
   * @param toolName 工具**规范名**
   */
  public static String scopeOf(String toolName, String arguments) {
    JSONObject json = parse(arguments);
    if (json == null) {
      return "";
    }
    if (ToolNames.SHELL_EXECUTE.equals(toolName)) {
      return firstWord(json.optString(ARG_COMMAND, ""));
    }
    if (ToolNames.FILE_WRITE.equals(toolName) || ToolNames.FILE_EDIT.equals(toolName)) {
      return json.optString(ARG_FILE_PATH, "").trim();
    }
    if (ToolNames.FILE_DELETE.equals(toolName)) {
      return joinedPaths(json);
    }
    return "";
  }

  /**
   * 删除类工具的 scope：全部目标路径按固定顺序连接。
   *
   * <p><b>为什么必须按路径授权</b>：{@code file_delete} 是最具破坏力的工具，却最容易
   * 退化成工具级授权——它的参数是 {@code paths} 数组而不是 {@code file_path} 单值，
   * 只按单值键提取会拿到空 scope，于是「始终允许删除 A.kt」变成「始终允许删除任何文件」。
   *
   * <p>取全部路径而非首个：模型一次可以删多个文件，只按首个授权会让同一次调用里
   * 其余的删除被悄悄连带放行。顺序保持参数原序，使同一次调用产生稳定的键。
   */
  private static String joinedPaths(JSONObject json) {
    org.json.JSONArray paths = json.optJSONArray(ARG_PATHS);
    if (paths == null || paths.length() == 0) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < paths.length(); i++) {
      String path = paths.optString(i, "").trim();
      if (path.isEmpty()) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append(PATH_JOINER);
      }
      sb.append(path);
    }
    return sb.toString();
  }

  /**
   * 命令的判别片段：第一个词。
   *
   * <p>先 {@code trim()} 再按空白切分，因为命令可能以多个空格或制表符开头；
   * 首词本身不含空白，所以无需逐字符扫描。
   */
  private static String firstWord(String command) {
    if (command == null) {
      return "";
    }
    String trimmed = command.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    int end = 0;
    while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))) {
      end++;
    }
    return trimmed.substring(0, end);
  }

  /** 解析参数 JSON；空串或非法 JSON 返回 null。 */
  private static JSONObject parse(String arguments) {
    if (arguments == null) {
      return null;
    }
    String trimmed = arguments.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    try {
      return new JSONObject(trimmed);
    } catch (Exception e) {
      // 参数不是合法 JSON（模型偶尔给出截断片段）→ 无法判别，退化为按工具名授权。
      // 调用方拿到空 scope 后仍需用户确认，因此这里不会误放行。
      return null;
    }
  }

  /**
   * 供 UI 展示的规则描述，例如 {@code shell_execute: git}。
   *
   * <p>路径可能很长，超过 48 字符时截断中段——保留首尾才能同时看清挂载点与文件名。
   */
  public static String describe(String key) {
    if (key == null || key.isEmpty()) {
      return "";
    }
    int at = key.indexOf(SEPARATOR);
    if (at < 0) {
      return key;
    }
    String tool = key.substring(0, at);
    // 多路径 scope 内部用不可见字符连接，展示时换成可读分隔符。
    String scope = key.substring(at + 1).replace(PATH_JOINER, ',');
    if (scope.isEmpty()) {
      return tool;
    }
    if (scope.length() <= 48) {
      return tool + ": " + scope;
    }
    int half = 24;
    return tool + ": " + scope.substring(0, half) + "…" + scope.substring(scope.length() - half);
  }
}
