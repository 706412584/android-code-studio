/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具大输出的统一处置：**全文落盘 + 首尾摘要 + 路径引用**，替代一刀截断。
 *
 * <p><b>为什么</b>：此前各工具各自截断（shell 32K 中段、WebFetch 60K 头部、
 * TaskOutput 16K 中段……），被丢掉的部分（构建日志中间的错误堆栈、网页末尾的
 * 关键段落）模型永远看不到。落盘后返回「首尾摘要 + 文件路径」，模型需要细节时
 * 自己用 file_read 分段翻页——信息不再永久丢失，上下文占用却与原来相同。
 *
 * <p><b>落盘位置</b>：{@code <工作区>/.acs/tool-output/}。选工作区而不是应用私有
 * 目录，是为了让 file_read（以工作区为根做路径校验）能直接读到。
 *
 * <p><b>回落</b>：上下文没有 homePath（无工作区/测试桩）或写入失败时，退回原来的
 * 中间截断，行为与改造前一致——落盘是增强，不是新的失败点。
 */
public final class ToolOutputSpill {

  /** 落盘子目录（相对工作区根）。 */
  static final String SPILL_DIR = ".acs/tool-output";

  private static final AtomicInteger SEQUENCE = new AtomicInteger();

  private ToolOutputSpill() {}

  /**
   * 文本超阈值时尝试落盘并返回「首尾摘要 + 路径」；否则原样返回。
   *
   * @param text 工具原始输出
   * @param maxChars 摘要预算（字符）；{@code <= 0} 时视为不截断（原样返回）
   * @param context 工具上下文；homePath 为空时无法落盘，退回中间截断
   */
  public static String maybeSpill(String text, int maxChars, ToolContext context) {
    if (text == null) {
      return "";
    }
    if (maxChars <= 0 || text.length() <= maxChars) {
      return text;
    }
    String home = context == null ? "" : nullToEmpty(context.getHomePath()).trim();
    if (!home.isEmpty()) {
      File spilled = spill(text, home);
      if (spilled != null) {
        int half = maxChars / 2;
        int omitted = text.length() - maxChars;
        return text.substring(0, half)
            + "\n…（省略 "
            + omitted
            + " 字符；完整输出已保存到 "
            + spilled.getAbsolutePath()
            + "，可用 file_read 分段查看）…\n"
            + text.substring(text.length() - half);
      }
    }
    return middleTruncate(text, maxChars);
  }

  /** 与改造前一致的中间截断：保留首尾，中间标注省略量。 */
  public static String middleTruncate(String text, int maxChars) {
    if (text == null) {
      return "";
    }
    if (maxChars <= 0 || text.length() <= maxChars) {
      return text;
    }
    int half = maxChars / 2;
    int omitted = text.length() - maxChars;
    return text.substring(0, half)
        + "\n…（省略 " + omitted + " 字符）…\n"
        + text.substring(text.length() - half);
  }

  /** 全文写入 {@code <home>/.acs/tool-output/}；失败返回 null（调用方走截断回落）。 */
  private static File spill(String text, String home) {
    try {
      File dir = new File(home, SPILL_DIR);
      if (!dir.isDirectory() && !dir.mkdirs()) {
        return null;
      }
      File target =
          new File(dir, "spill-" + System.currentTimeMillis() + "-" + SEQUENCE.incrementAndGet() + ".log");
      try (Writer writer =
          new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8)) {
        writer.write(text);
      }
      return target;
    } catch (IOException | RuntimeException e) {
      // 落盘失败不该让工具失败：退回截断即可。
      return null;
    }
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
