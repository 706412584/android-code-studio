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

package com.tom.rv2ide.ai.agent.command;

import com.tom.rv2ide.ai.agent.prompt.ChatMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 斜杠命令的解析与目录。
 *
 * <p><b>为什么需要斜杠命令</b>：切模式、换模型这类操作要去设置界面点好几层。在输入框里
 * 打 {@code /mode plan} 直接切换，不必中断思路。这是纯本地操作——命令不会发给模型，
 * 也不消耗额度。
 *
 * <p><b>解析必须区分「命令」与「以斜杠开头的普通消息」</b>。用户完全可能输入
 * {@code /etc/hosts 这个文件是干什么的}。因此只有**已知命令名**才被当作命令；
 * 未知的 {@code /xxx} 一律按普通消息处理，由模型去理解——这比弹一个「未知命令」错误
 * 更有用，因为用户本来就没打算用命令。
 *
 * <p>本类不引用 Android 类型，可单测。
 */
public final class SlashCommandCatalog {

  /** 命令种类。 */
  public enum Kind {
    /** 切换对话模式。 */
    MODE,
    /** 切换模型。 */
    MODEL,
    /** 新建会话。 */
    NEW_CONVERSATION,
    /** 清空当前会话。 */
    CLEAR,
    /** 列出可用命令。 */
    HELP,
    /** 是命令但参数不合法。 */
    INVALID,
    /** 不是命令（按普通消息处理）。 */
    NOT_A_COMMAND
  }

  /** 一条命令的定义。 */
  public static final class Definition {
    private final String name;
    private final String usage;
    private final String description;

    Definition(String name, String usage, String description) {
      this.name = name;
      this.usage = usage;
      this.description = description;
    }

    /** 命令名，不含斜杠。 */
    public String getName() {
      return name;
    }

    /** 用法示例。 */
    public String getUsage() {
      return usage;
    }

    public String getDescription() {
      return description;
    }
  }

  /** 解析结果。 */
  public static final class Parsed {
    private final Kind kind;
    private final String argument;
    private final String error;
    private final String raw;

    Parsed(Kind kind, String argument, String error, String raw) {
      this.kind = kind;
      this.argument = argument == null ? "" : argument;
      this.error = error == null ? "" : error;
      this.raw = raw == null ? "" : raw;
    }

    public Kind getKind() {
      return kind;
    }

    /** 命令参数（如 {@code /mode plan} 的 {@code plan}）。 */
    public String getArgument() {
      return argument;
    }

    /** 参数不合法时的说明；否则为空串。 */
    public String getError() {
      return error;
    }

    /** 原始输入。 */
    public String getRaw() {
      return raw;
    }

    /** 是否是命令（含参数非法的情况）。 */
    public boolean isCommand() {
      return kind != Kind.NOT_A_COMMAND;
    }

    /** 是否是可执行的命令（不是普通消息、参数也合法）。 */
    public boolean isExecutable() {
      return kind != Kind.NOT_A_COMMAND && kind != Kind.INVALID;
    }

    /**
     * 解析出的模式（仅 {@link Kind#MODE} 且参数合法时有意义）。
     *
     * <p>返回 null 表示不适用。
     */
    public ChatMode modeOrNull() {
      if (kind != Kind.MODE || argument.isEmpty()) {
        return null;
      }
      return ChatMode.fromId(argument);
    }
  }

  private static final List<Definition> DEFINITIONS =
      Collections.unmodifiableList(
          Arrays.asList(
              new Definition("mode", "/mode <chat|plan|agent|control>", "切换对话模式"),
              new Definition("model", "/model <模型名>", "切换当前服务商的模型"),
              new Definition("new", "/new", "开始一个新会话"),
              new Definition("clear", "/clear", "清空当前会话的消息"),
              new Definition("help", "/help", "列出可用命令")));

  private SlashCommandCatalog() {}

  /** 全部命令定义，供帮助与自动补全使用。 */
  public static List<Definition> definitions() {
    return DEFINITIONS;
  }

  /**
   * 命令名前缀匹配，供输入时的提示。
   *
   * <p>一旦出现空白就停止提示——命令名已确定，此时用户要打的是参数，
   * 继续弹命令列表会遮住参数输入。
   *
   * <p>注意判断空白要用**未 trim 的原文**：{@code "/mode "} 的末尾空格是有意义的信号
   * （用户已打完命令名），trim 会把它抹掉，于是参数阶段仍在提示命令名。
   */
  public static List<Definition> matching(String input) {
    List<Definition> result = new ArrayList<>();
    if (input == null || !input.startsWith("/")) {
      return result;
    }
    String body = input.substring(1);
    if (indexOfWhitespace(body) >= 0) {
      return result;
    }
    String typed = body.trim().toLowerCase(Locale.ROOT);
    for (Definition definition : DEFINITIONS) {
      if (definition.getName().startsWith(typed)) {
        result.add(definition);
      }
    }
    return result;
  }

  /**
   * 解析输入。
   *
   * <p>核心规则：只有已知命令名才算命令。{@code /etc/hosts 是干什么的} 不是命令——
   * 用户没打算用命令，弹一个「未知命令」只会碍事。
   */
  public static Parsed parse(String input) {
    String raw = input == null ? "" : input;
    String trimmed = raw.trim();

    if (!trimmed.startsWith("/")) {
      return new Parsed(Kind.NOT_A_COMMAND, "", "", raw);
    }

    // 去掉斜杠，按第一个空白切成「命令名 + 其余」
    String body = trimmed.substring(1);
    String name;
    String rest;
    int space = indexOfWhitespace(body);
    if (space < 0) {
      name = body;
      rest = "";
    } else {
      name = body.substring(0, space);
      rest = body.substring(space + 1).trim();
    }
    String normalized = name.toLowerCase(Locale.ROOT);

    if (normalized.isEmpty()) {
      return new Parsed(Kind.NOT_A_COMMAND, "", "", raw);
    }

    // 未知命令名 → 按普通消息处理（见类注释）。
    if (!isKnown(normalized)) {
      return new Parsed(Kind.NOT_A_COMMAND, "", "", raw);
    }

    switch (normalized) {
      case "mode":
        return parseMode(rest, raw);
      case "model":
        if (rest.isEmpty()) {
          return new Parsed(Kind.INVALID, "", "用法：/model <模型名>", raw);
        }
        return new Parsed(Kind.MODEL, rest, "", raw);
      case "new":
        return new Parsed(Kind.NEW_CONVERSATION, "", "", raw);
      case "clear":
        return new Parsed(Kind.CLEAR, "", "", raw);
      case "help":
        return new Parsed(Kind.HELP, "", "", raw);
      default:
        return new Parsed(Kind.NOT_A_COMMAND, "", "", raw);
    }
  }

  private static Parsed parseMode(String argument, String raw) {
    if (argument.isEmpty()) {
      // 不带参数时列出可选值：比只说「参数错误」更能让用户立刻用起来。
      return new Parsed(
          Kind.INVALID, "", "用法：/mode <" + String.join("|", ChatMode.allIds()) + ">", raw);
    }
    String candidate = argument.toLowerCase(Locale.ROOT);
    for (ChatMode mode : ChatMode.values()) {
      if (mode.getId().equals(candidate)) {
        return new Parsed(Kind.MODE, mode.getId(), "", raw);
      }
    }
    return new Parsed(
        Kind.INVALID, "", "未知模式：" + argument + "。可用：" + String.join("|", ChatMode.allIds()), raw);
  }

  private static boolean isKnown(String name) {
    for (Definition definition : DEFINITIONS) {
      if (definition.getName().equals(name)) {
        return true;
      }
    }
    return false;
  }

  private static int indexOfWhitespace(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (Character.isWhitespace(value.charAt(i))) {
        return i;
      }
    }
    return -1;
  }

  /** 生成帮助文本。 */
  public static String helpText() {
    StringBuilder sb = new StringBuilder();
    sb.append("可用命令：\n");
    for (Definition definition : DEFINITIONS) {
      sb.append(definition.getUsage()).append(" — ").append(definition.getDescription()).append('\n');
    }
    sb.append("\n命令不会发给模型，也不消耗额度。");
    return sb.toString().trim();
  }
}
