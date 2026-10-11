/*
 * This file is part of AndroidCodeStudio.
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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool;

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.List;
import org.json.JSONObject;

/**
 * 读取内置终端（Termux）的会话屏幕内容。
 *
 * <p><b>与 {@code shell_execute} 的分工</b>：那个每次起一个独立进程、返回本次命令的输出；
 * 这个读的是**用户在终端里手动跑的东西**——AI 看不到那部分，除非通过本工具。典型用法：
 * 用户在终端跑了个报错的构建，让 AI「看看我的终端」，AI 直接读到屏幕内容，不必让用户复制粘贴。
 *
 * <p><b>只读</b>：不提供写入/注入命令。往用户终端打字有副作用（可能打断他正在跑的进程），
 * 且心理预期差；本期不做。
 *
 * <p>无参数调用时列出所有会话（序号 + 标题 + 是否运行中）；给定 {@code session} 时返回
 * 该会话的屏幕内容（尾部片段，超出上限时中间省略）。
 */
public final class TerminalReadTool extends BaseTool {

  /** 返回给模型的屏幕内容上限：24KB。 */
  private static final int MAX_OUTPUT_CHARS = 24 * 1024;

  @Override
  public String getName() {
    return ToolNames.TERMINAL_READ;
  }

  @Override
  public String getDescription() {
    return "读取内置终端（Termux）会话的屏幕内容。"
        + "用于查看用户在终端里手动执行的命令及其输出——shell_execute 看不见这些。"
        + "省略 session 参数时列出所有会话；给定 session 序号时返回该会话的屏幕内容。只读。";
  }

  @Override
  public ToolCategory getCategory() {
    // 只读屏幕内容，无副作用，只读模式下也放行。
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.SHELL;
  }

  @Override
  public boolean isConcurrencySafe() {
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
                    "session",
                    new JSONObject()
                        .put("type", "number")
                        .put(
                            "description",
                            "会话序号（0 起，见省略参数时的列表）。"
                                + "省略则只列出会话，不返回屏幕内容。")))
        .put("required", new org.json.JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    TerminalPort port = context == null ? null : context.getTerminal();
    if (port == null) {
      return error("终端读取未接入。");
    }
    if (!port.isAvailable()) {
      return error("终端不可用：" + port.unavailableReason());
    }

    List<TerminalPort.Session> sessions = port.sessions();
    if (sessions == null || sessions.isEmpty()) {
      return ok("当前没有终端会话。");
    }

    // 无 session 参数：只列会话清单。
    if (!input.has("session") || input.isNull("session")) {
      StringBuilder sb = new StringBuilder();
      sb.append("终端会话 ").append(sessions.size()).append(" 个：\n");
      for (TerminalPort.Session session : sessions) {
        sb.append("- [").append(session.getIndex()).append("] ");
        sb.append(session.getTitle().isEmpty() ? "(未命名)" : session.getTitle());
        sb.append(session.isRunning() ? " (运行中)" : " (已结束)");
        if (!session.getCwd().isEmpty()) {
          sb.append("  cwd=").append(session.getCwd());
        }
        sb.append('\n');
      }
      sb.append("用 session 参数指定序号可读取其屏幕内容。");
      return ok(sb.toString());
    }

    int index = input.optInt("session", -1);
    TerminalPort.Session target = null;
    for (TerminalPort.Session session : sessions) {
      if (session.getIndex() == index) {
        target = session;
        break;
      }
    }
    if (target == null) {
      return error("没有序号为 " + index + " 的会话（共 " + sessions.size() + " 个）。");
    }

    StringBuilder sb = new StringBuilder();
    sb.append("会话 [").append(target.getIndex()).append("] ");
    sb.append(target.getTitle().isEmpty() ? "(未命名)" : target.getTitle());
    sb.append(target.isRunning() ? " (运行中)" : " (已结束)").append('\n');
    String transcript = target.getTranscript();
    if (transcript.trim().isEmpty()) {
      sb.append("(屏幕为空)");
    } else {
      sb.append("--- 屏幕内容 ---\n").append(truncate(transcript));
    }
    return ok(sb.toString());
  }

  /** 中间截断：保留首尾，中间标注省略量（终端尾部才是最新输出，故保留尾部尤其重要）。 */
  static String truncate(String text) {
    if (text == null || text.length() <= MAX_OUTPUT_CHARS) {
      return text == null ? "" : text;
    }
    int half = MAX_OUTPUT_CHARS / 2;
    int omitted = text.length() - MAX_OUTPUT_CHARS;
    return text.substring(0, half)
        + "\n…（省略 " + omitted + " 字符）…\n"
        + text.substring(text.length() - half);
  }
}
