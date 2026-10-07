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
 *   <li>手机交互类（{@code phone_click}/{@code phone_swipe}/...）——危险在**工具本身**，
 *       与参数无关：每次调用的爆炸半径都是「在屏幕上做一次交互」，因此粒度取工具名，
 *       不含参数。详见 {@link #PHONE_TOOL_SCOPED}。</li>
 *   <li>{@code phone_clear_data}——危险在**目标包名**：粒度取包名，
 *       放行一次清数据不等于放行任意应用的清数据。</li>
 *   <li>{@code git_write}——危险在**动作及其作用对象**：粒度取「动作 + 分支/远端/路径」，
 *       放行一次 {@code stage a.kt} 不等于放行 {@code discard a.kt}，放行 {@code commit}
 *       也不等于放行 {@code push}（本地提交可回滚，推送不可撤回）。{@code commit} 例外地
 *       只取动作名：提交信息每次都不同，按信息建键会让「始终允许」完全失效，
 *       而单次提交的爆炸半径固定且可回滚。</li>
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

  /** 清数据类工具的参数键：目标包名。 */
  public static final String ARG_PACKAGE_NAME = "packageName";

  /** git_write 的参数键：动作名（stage / commit / push ...）。 */
  public static final String ARG_ACTION = "action";

  /** git_write 的参数键：分支名（checkout / branch_* / push / pull）。 */
  public static final String ARG_BRANCH = "branch";

  /** git_write 的参数键：远端名（fetch / push / pull / remote_*）。 */
  public static final String ARG_REMOTE = "remote";

  /** 键与值之间的分隔符。见类注释说明为何选 NUL。 */
  private static final char SEPARATOR = '\u0000';

  /** 多路径 scope 内部的连接符。路径不可能含控制字符，故无歧义。 */
  private static final char PATH_JOINER = '\u0001';

  /** 参数摘要的前缀，用于把它与路径/命令区分开，也便于在设置页辨认。 */
  private static final String DIGEST_PREFIX = "args:";

  /**
   * 按「工具名」授权的 phone_* 交互工具的 scope 值。
   *
   * <p>这些工具的爆炸半径与参数无关（都是「在屏幕上做一次交互」），因此 scope 取固定值，
   * 使用户点一次「始终允许」能对**该工具的任何参数**生效。见 {@link #PHONE_TOOL_SCOPED}。
   */
  private static final String TOOL_SCOPE = "tool";

  /**
   * scope 不含参数的 phone_* 交互工具：其规则键退化为「工具名」。
   *
   * <p><b>为什么必须按工具名而非参数摘要</b>：这些工具的参数是坐标/文本/手势距离，
   * 每次调用几乎都不同（{@code {"x":540,"y":1200}} vs {@code {"x":540,"y":1201}}）。
   * 若按参数摘要建键，用户点「始终允许」后**下一次点击照旧弹窗**，「始终允许」形同虚设
   * ——这正是本类要解决的原始问题。
   *
   * <p><b>为什么不担心被放大</b>：与 shell 不同（同一工具、命令全文不同则破坏力天差地别），
   * 这些工具的每一次调用在权限语义上等价——「允许 phone_click」就是「允许在屏幕上点一下」，
   * 不存在「允许 ls 却顺带放行 rm -rf /」那种提权路径。
   *
   * <p><b>{@code phone_clear_data} 刻意不在其中</b>：它带包名参数且破坏不可逆，
   * 按工具名授权会连别的应用的清数据一并放行，因此它的 scope 取包名。
   */
  private static final java.util.Set<String> PHONE_TOOL_SCOPED =
      java.util.Collections.unmodifiableSet(
          new java.util.LinkedHashSet<>(
              java.util.Arrays.asList(
                  ToolNames.PHONE_CLICK,
                  ToolNames.PHONE_CLICK_VIEW,
                  ToolNames.PHONE_SWIPE,
                  ToolNames.PHONE_LONG_PRESS,
                  ToolNames.PHONE_GLOBAL_ACTION,
                  ToolNames.PHONE_INPUT_TEXT)));

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
    // phone_* 交互工具在 parse 之前分流：它们的键要么与参数无关、要么只取单个字段，
    // 因此即便参数缺失/非法，也不该退化成参数摘要（那会让「始终允许」反复失效）。
    if (PHONE_TOOL_SCOPED.contains(toolName)) {
      return TOOL_SCOPE;
    }
    if (ToolNames.PHONE_CLEAR_DATA.equals(toolName)) {
      JSONObject clearJson = parse(arguments);
      String packageName =
          clearJson == null ? "" : clearJson.optString(ARG_PACKAGE_NAME, "").trim();
      // 包名缺失/非法时回落到参数摘要：空白 scope 会把「始终允许」放大成
      // 「允许清除任意应用的数据」，与 file_delete 空 scope 同类风险。见类注释。
      return fallbackIfEmpty(packageName, arguments);
    }

    JSONObject json = parse(arguments);
    if (json == null) {
      // 参数不是合法 JSON（模型偶尔给出截断片段）→ 没有可靠的判别字段，
      // 只能让规则绑定到这段原始文本本身，即「同样的片段才复用」。
      return digest(arguments);
    }
    if (ToolNames.SHELL_EXECUTE.equals(toolName)) {
      return normalizeCommand(json.optString(ARG_COMMAND, ""));
    }
    if (ToolNames.GIT_WRITE.equals(toolName)) {
      return gitWriteScope(json, arguments);
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
   * git_write 的 scope：动作名 + 判别字段（路径 / 分支 / 远端）。
   *
   * <p><b>为什么按动作区分</b>：同一个工具下不同动作的破坏力完全不同。
   * {@code stage} 只是挪动暂存区指针，{@code discard} 会**丢弃工作区未提交的修改**，
   * {@code push} 会把提交发出去且本地无法撤回。若按工具名授权，用户为省一次弹窗
   * 放行的是一次 {@code stage}，实际却连 {@code push --force} 一起放行了。
   *
   * <p><b>为什么 commit 只取动作名</b>：提交信息每次调用都不同（甚至带时间戳），
   * 按信息建键会让「始终允许」退化成每次照旧弹窗，等于该选项不可用。
   * 而单次提交的爆炸半径是固定的——只影响本地仓库且可用 {@code reset} 回滚——
   * 不存在「放行一次提交却顺带交出远端」的提权路径。
   *
   * <p>判别字段缺失（模型漏传）时回落到参数摘要：只取动作名会把「始终允许」放大成
   * 「允许该动作的任意参数」，而动作级放行恰恰是本方法要避免的。
   */
  private static String gitWriteScope(JSONObject json, String arguments) {
    String action = json.optString(ARG_ACTION, "").trim();
    if (action.isEmpty()) {
      return digest(arguments);
    }
    String discriminator;
    switch (action) {
      case "stage":
      case "unstage":
      case "discard":
        discriminator = json.optString(ARG_FILE_PATH, "").trim();
        break;
      case "checkout":
      case "branch_create":
      case "branch_delete":
        discriminator = json.optString(ARG_BRANCH, "").trim();
        break;
      case "remote_add":
      case "remote_remove":
      case "fetch":
        discriminator = json.optString(ARG_REMOTE, "").trim();
        break;
      case "push":
      case "pull":
        discriminator =
            joinNonEmpty(
                json.optString(ARG_REMOTE, "").trim(), json.optString(ARG_BRANCH, "").trim());
        break;
      case "commit":
        return action;
      default:
        // 未知动作（模型幻觉出的名字）没有可靠的作用对象概念，
        // 按参数摘要绑定到那一次具体调用，不放大。
        return digest(arguments);
    }
    if (discriminator.isEmpty()) {
      return digest(arguments);
    }
    return action + PATH_JOINER + discriminator;
  }

  /** 把非空片段用 {@link #PATH_JOINER} 连接；全空时返回空串。 */
  private static String joinNonEmpty(String... parts) {
    StringBuilder sb = new StringBuilder();
    for (String part : parts) {
      if (part == null || part.isEmpty()) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append(PATH_JOINER);
      }
      sb.append(part);
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
