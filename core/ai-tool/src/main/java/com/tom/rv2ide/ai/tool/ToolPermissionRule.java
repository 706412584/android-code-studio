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
 *   <li>shell 类——危险在**整条命令**。粒度取归一化后的命令全文，
 *       这样 {@code git status} 的授权不会顺带放行 {@code git push --force}。
 *       早先按「首词」授权是错的：首词相同的命令破坏力可以差到天上地下，
 *       {@code bash build.sh} 与 {@code bash -c '任意命令'} 会同键，
 *       等于一次放行就把整个解释器交出去。</li>
 *   <li>文件类——危险在**路径**。粒度取绝对路径，放行一次写入不等于放行任意路径。</li>
 *   <li>其余——没有可提取的判别字段时退化为**参数摘要**，
 *       即「只有参数完全相同的那一次调用」才复用规则。</li>
 * </ul>
 *
 * <p><b>为什么不产生「空 scope」规则</b>：空 scope 会拼出 {@code <tool>\0} 这样的键，
 * 它对该工具的**任意参数**都匹配。这对 {@code mcpx_*}、{@code install_apk}、
 * {@code gradle_build} 这类工具意味着「始终允许」被悄悄放大成无限放行。
 * 因此没有可判别字段时改取参数摘要，宁可让用户多确认几次。
 *
 * <p>规则以 {@code toolName + '\u0000' + scope} 的字符串形式持久化。
 * 用 NUL 作分隔符：工具名与 scope 都是受限字符集，NUL 不可能出现在其中，
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

  /** 参数摘要的前缀，用于把它与路径/命令区分开，也便于在设置页辨认。 */
  private static final String DIGEST_PREFIX = "args:";

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
   * <p>取不到判别字段时**不返回空串**，而是回落到参数摘要——空串会拼出对任意参数都生效
   * 的规则键，把「始终允许」放大成无限放行。见类注释。
   *
   * @param toolName 工具**规范名**
   */
  public static String scopeOf(String toolName, String arguments) {
    JSONObject json = parse(arguments);
    if (json == null) {
      // 参数不是合法 JSON（模型偶尔给出截断片段）→ 没有可靠的判别字段，
      // 只能让规则绑定到这段原始文本本身，即「同样的片段才复用」。
      return digest(arguments);
    }
    if (ToolNames.SHELL_EXECUTE.equals(toolName)) {
      return normalizeCommand(json.optString(ARG_COMMAND, ""));
    }
    if (ToolNames.FILE_WRITE.equals(toolName) || ToolNames.FILE_EDIT.equals(toolName)) {
      return fallbackIfEmpty(json.optString(ARG_FILE_PATH, "").trim(), arguments);
    }
    if (ToolNames.FILE_DELETE.equals(toolName)) {
      return fallbackIfEmpty(joinedPaths(json), arguments);
    }
    // 其余工具（mcpx_*、agent、install_apk、gradle_build 等）没有可提取的判别字段。
    return digest(arguments);
  }

  /**
   * 判别字段为空时回落到参数摘要。
   *
   * <p>文件类工具在缺路径时若返回空 scope，规则就变成「该工具的任何调用都放行」，
   * 而 {@code file_write} 并不需要确认、{@code file_delete} 又只对空路径拒绝，
   * 于是这条空白授权会一直躺在偏好里等待匹配。
   */
  private static String fallbackIfEmpty(String scope, String arguments) {
    return scope.isEmpty() ? digest(arguments) : scope;
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
    // 刻意复用 FileDeleteTool 自己的取路径方法：授权范围必须与**实际删除集合**逐字相同。
    // 在这里另写一份提取逻辑，就等于给两者留出漂移的空间——而漂移的方向永远是
    // 「授权比执行窄」，即一次点击放行了用户没看到的删除。
    java.util.List<String> paths = FileDeleteTool.collectPaths(json);
    StringBuilder sb = new StringBuilder();
    for (String path : paths) {
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
   * 命令的判别片段：归一化后的整条命令。
   *
   * <p><b>为什么不取首词</b>：首词相同并不代表破坏力相同。{@code git status} 与
   * {@code git push --force}、{@code bash build.sh} 与 {@code bash -c 'cat ~/.ssh/id_rsa'}、
   * {@code python3 x.py} 与 {@code python3 -c "import os;os.system('...')"} 都同键。
   * 一旦按首词授权，用户点一次「始终允许」就等于把整个解释器（或整个 git）交出去，
   * 这正是本类要防的提权路径。
   *
   * <p>归一化只做两件事：去掉首尾空白、把连续空白压成单个空格。这样
   * {@code git  status} 与 {@code git status} 仍视为同一条命令（避免无谓重问），
   * 而参数不同的命令必然不同键。大小写**不做**归一：shell 命令区分大小写。
   */
  private static String normalizeCommand(String command) {
    if (command == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(command.length());
    boolean pendingSpace = false;
    for (int i = 0; i < command.length(); i++) {
      char c = command.charAt(i);
      if (Character.isWhitespace(c)) {
        pendingSpace = sb.length() > 0;
        continue;
      }
      if (pendingSpace) {
        sb.append(' ');
        pendingSpace = false;
      }
      sb.append(c);
    }
    return sb.toString();
  }

  /**
   * 参数摘要：对原始参数文本取一个稳定的短标识。
   *
   * <p>用于没有可判别字段的工具。语义是「只有参数完全相同的那一次调用」才复用规则——
   * 这比空 scope 的「任意参数都复用」窄得多，代价是用户偶尔要多确认一次，
   * 而多确认一次的代价远小于一次静默的无限放行。
   *
   * <p>用哈希而非原文：参数可能很大（例如 {@code install_apk} 的路径加各种选项），
   * 直接塞进偏好键会让设置页显示不下，也会让规则集合无谓地膨胀。
   */
  private static String digest(String arguments) {
    if (arguments == null) {
      return DIGEST_PREFIX + "null";
    }
    String normalized = arguments.trim();
    if (normalized.isEmpty()) {
      return DIGEST_PREFIX + "empty";
    }
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(DIGEST_PREFIX);
      // 取前 8 字节（16 个十六进制位）足够区分同一次运行内的不同调用，
      // 且短到能在设置页一行显示。
      for (int i = 0; i < 8; i++) {
        sb.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
        sb.append(Character.forDigit(hash[i] & 0xF, 16));
      }
      return sb.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      // SHA-256 是 JDK 必备算法，走不到这里；真走到了就退回长度+前后缀，
      // 至少不返回空串（空串会退化成无限放行）。
      return DIGEST_PREFIX + normalized.length() + ":" + normalized.hashCode();
    }
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
