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

package com.tom.rv2ide.ai.agent.export;

import com.tom.rv2ide.ai.agent.conversation.AssistantMessageEntry;
import com.tom.rv2ide.ai.agent.conversation.CompactionEntry;
import com.tom.rv2ide.ai.agent.conversation.ConversationEntry;
import com.tom.rv2ide.ai.agent.conversation.ConversationLog;
import com.tom.rv2ide.ai.agent.conversation.ToolResultEntry;
import com.tom.rv2ide.ai.agent.conversation.UserMessageEntry;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import java.util.ArrayList;
import java.util.List;

/**
 * 把一段会话导出为 Markdown。
 *
 * <p><b>为什么导出是必要的</b>：一次有价值的排查过程（「为什么构建失败 → 改了哪三个文件
 * → 结论」）只存在于应用内的消息列表里，无法带走、无法分享、关掉应用后也难回看。
 * 导出成 Markdown 让它可以进笔记、贴到 issue、发给同事。
 *
 * <p><b>为什么是 Markdown 而不是 PDF/PNG</b>：Markdown 是纯文本，可以在任何地方打开与
 * 编辑，且体积小。PDF/PNG 需要渲染管线与字体嵌入，收益（外观固定）远小于复杂度；
 * 需要 PDF 时用户可以用任何 Markdown 工具转换。
 *
 * <p><b>过程信息默认折叠为细节块</b>：一次运行可能产生几十次工具调用，全部展开会把
 * 真正的结论淹掉。默认只输出工具名与状态摘要，展开由阅读者决定。
 *
 * <p>本类不引用 Android 类型，可单测。
 */
public final class ConversationExporter {

  /** 工具输出在导出时的截断长度。 */
  private static final int TOOL_OUTPUT_PREVIEW_CHARS = 2000;

  /** 导出选项。 */
  public static final class Options {
    private boolean includeToolCalls = true;
    private boolean includeToolOutput = true;
    private boolean includeTimestamps = false;
    private boolean includeCompaction = true;

    /** 是否包含工具调用行。关掉后只剩用户与助手的对话。 */
    public Options includeToolCalls(boolean value) {
      this.includeToolCalls = value;
      return this;
    }

    /** 是否包含工具输出。 */
    public Options includeToolOutput(boolean value) {
      this.includeToolOutput = value;
      return this;
    }

    /** 是否在每条消息前加时间戳。 */
    public Options includeTimestamps(boolean value) {
      this.includeTimestamps = value;
      return this;
    }

    /** 是否包含上下文压缩记录。 */
    public Options includeCompaction(boolean value) {
      this.includeCompaction = value;
      return this;
    }

    /** 仅对话：不含工具调用与输出。 */
    public static Options conversationOnly() {
      return new Options()
          .includeToolCalls(false)
          .includeToolOutput(false)
          .includeCompaction(false);
    }

    /** 完整：含全部过程信息。 */
    public static Options full() {
      return new Options().includeTimestamps(true);
    }
  }

  private ConversationExporter() {}

  /**
   * 导出为 Markdown。
   *
   * <p>入参是**条目**而非 {@code ConversationLog.EntryLocation}：导出只需要条目内容，
   * 不需要字节偏移与序号。依赖更窄的类型让本类与日志的存储表示解耦，也更易测试；
   * 持有位置列表的调用方用 {@link #entriesOf} 转换。
   *
   * @param title 标题；为空时使用默认标题
   * @param entries 会话条目，按时间正序
   */
  public static String toMarkdown(String title, List<ConversationEntry> entries) {
    return toMarkdown(title, entries, new Options());
  }

  public static String toMarkdown(
      String title, List<ConversationEntry> entries, Options options) {
    Options opts = options == null ? new Options() : options;

    StringBuilder sb = new StringBuilder();
    sb.append("# ").append(title == null || title.trim().isEmpty() ? "AI 对话" : title.trim());
    sb.append("\n\n");

    if (entries == null || entries.isEmpty()) {
      sb.append("_（没有可导出的内容）_\n");
      return sb.toString();
    }

    for (ConversationEntry entry : entries) {
      if (entry == null) {
        continue;
      }
      appendEntry(sb, entry, opts);
    }

    return sb.toString().trim() + "\n";
  }

  /**
   * 从日志位置列表取出条目。
   *
   * <p>{@code ConversationStore.read()} 返回的是带偏移的位置列表；导出只关心内容，
   * 用这个转换把两者接起来。
   */
  public static List<ConversationEntry> entriesOf(List<ConversationLog.EntryLocation> locations) {
    List<ConversationEntry> entries = new ArrayList<>();
    if (locations == null) {
      return entries;
    }
    for (ConversationLog.EntryLocation location : locations) {
      if (location != null && location.getEntry() != null) {
        entries.add(location.getEntry());
      }
    }
    return entries;
  }

  /** 导出为纯文本（去掉 Markdown 标记）。 */
  public static String toPlainText(String title, List<ConversationEntry> entries) {
    return toMarkdown(title, entries, Options.conversationOnly());
  }

  private static void appendEntry(StringBuilder sb, ConversationEntry entry, Options options) {
    if (entry instanceof UserMessageEntry) {
      appendHeading(sb, "用户", entry, options);
      sb.append(((UserMessageEntry) entry).getContent()).append("\n\n");
      return;
    }

    if (entry instanceof AssistantMessageEntry) {
      AssistantMessageEntry assistant = (AssistantMessageEntry) entry;
      String content = assistant.getContent();
      if (content != null && !content.trim().isEmpty()) {
        appendHeading(sb, "助手", entry, options);
        sb.append(content.trim()).append("\n\n");
      }
      // 工具调用不单独成段：它的执行结果由 ToolResultEntry 给出，
      // 这里只列名字，让读者知道这一轮请求了哪些工具。
      if (options.includeToolCalls && !assistant.getToolCalls().isEmpty()) {
        sb.append("> 请求工具：");
        List<String> names = new ArrayList<>();
        for (ToolCall call : assistant.getToolCalls()) {
          names.add(call.getName());
        }
        sb.append(String.join(", ", names)).append("\n\n");
      }
      return;
    }

    if (entry instanceof ToolResultEntry) {
      if (!options.includeToolCalls) {
        return;
      }
      ToolResultEntry result = (ToolResultEntry) entry;
      String marker = result.isError() ? "✗" : "✓";
      sb.append("- ").append(marker).append(' ').append(result.getToolName());
      if (options.includeToolOutput) {
        String content = result.getContent();
        if (content != null && !content.trim().isEmpty()) {
          sb.append("：").append(preview(content.trim()));
        }
      }
      sb.append('\n');
      // 工具结果连续出现时共用同一个列表；遇到下一条非工具条目时空行由该条目补上。
      return;
    }

    if (entry instanceof CompactionEntry) {
      if (!options.includeCompaction) {
        return;
      }
      sb.append("> _（早期对话已压缩为摘要：")
          .append(preview(((CompactionEntry) entry).getSummary()))
          .append("）_\n\n");
      return;
    }

    // 其它条目类型（会话元信息、标题）不导出：它们是元数据，不是对话内容。
  }

  private static void appendHeading(
      StringBuilder sb, String role, ConversationEntry entry, Options options) {
    // 连续的工具结果行之后需要一个空行来断开列表，否则 Markdown 会把下面的标题吞进列表。
    ensureBlankLineBeforeHeading(sb);
    sb.append("**").append(role).append("**");
    if (options.includeTimestamps && entry.getTimestamp() > 0) {
      sb.append(" _(").append(formatTimestamp(entry.getTimestamp())).append(")_");
    }
    sb.append("\n\n");
  }

  private static void ensureBlankLineBeforeHeading(StringBuilder sb) {
    if (sb.length() < 2) {
      return;
    }
    char last = sb.charAt(sb.length() - 1);
    if (last != '\n') {
      sb.append("\n\n");
      return;
    }
    if (sb.charAt(sb.length() - 2) != '\n') {
      sb.append('\n');
    }
  }

  /**
   * 截断过长的内容。
   *
   * <p>工具输出可能非常大（读文件、构建日志）。导出文件应当是给人读的，
   * 保留前若干字符并注明被截断——完整内容仍在应用内可查。
   */
  private static String preview(String content) {
    String singleLine = content.replace("\r", "");
    if (singleLine.length() <= TOOL_OUTPUT_PREVIEW_CHARS) {
      return singleLine;
    }
    return singleLine.substring(0, TOOL_OUTPUT_PREVIEW_CHARS)
        + "…（已截断，共 "
        + singleLine.length()
        + " 字符）";
  }

  /**
   * 格式化时间戳。
   *
   * <p>用手写格式化而非 {@code SimpleDateFormat}：后者依赖默认时区与 Locale，
   * 在不同设备上会产出不同结果，而导出文件应当是稳定的。
   * 这里输出 UTC 的 {@code YYYY-MM-DD HH:MM}。
   */
  static String formatTimestamp(long millis) {
    long seconds = millis / 1000L;
    long days = seconds / 86400L;
    long secondsOfDay = seconds % 86400L;
    long hours = secondsOfDay / 3600L;
    long minutes = (secondsOfDay % 3600L) / 60L;

    long[] ymd = civilFromDays(days);
    return String.format(
        java.util.Locale.ROOT,
        "%04d-%02d-%02d %02d:%02d",
        ymd[0],
        ymd[1],
        ymd[2],
        hours,
        minutes);
  }

  /**
   * 从 1970-01-01 起的天数换算为年月日。
   *
   * <p>用 Howard Hinnant 的 civil_from_days 算法：纯整数运算，不依赖时区数据库，
   * 对任何输入都给出确定结果。
   */
  private static long[] civilFromDays(long daysSinceEpoch) {
    long z = daysSinceEpoch + 719468L;
    long era = (z >= 0 ? z : z - 146096L) / 146097L;
    long doe = z - era * 146097L;
    long yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L;
    long y = yoe + era * 400L;
    long doy = doe - (365L * yoe + yoe / 4L - yoe / 100L);
    long mp = (5L * doy + 2L) / 153L;
    long d = doy - (153L * mp + 2L) / 5L + 1L;
    long m = mp + (mp < 10L ? 3L : -9L);
    return new long[] {y + (m <= 2L ? 1L : 0L), m, d};
  }

  /**
   * 建议的导出文件名。
   *
   * <p>去掉路径分隔符与控制字符：标题可能含用户输入的任意字符，直接当文件名会失败或
   * 写到意外位置。
   */
  public static String suggestedFileName(String title) {
    String base = title == null || title.trim().isEmpty() ? "conversation" : title.trim();
    StringBuilder sb = new StringBuilder(base.length());
    for (int i = 0; i < base.length(); i++) {
      char c = base.charAt(i);
      if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<'
          || c == '>' || c == '|' || c < 0x20) {
        sb.append('_');
      } else {
        sb.append(c);
      }
    }
    String name = sb.toString().trim();
    if (name.isEmpty()) {
      name = "conversation";
    }
    // 文件名过长在多数文件系统上会失败；截断到 80 字符留出扩展名空间。
    if (name.length() > 80) {
      name = name.substring(0, 80);
    }
    return name + ".md";
  }
}
