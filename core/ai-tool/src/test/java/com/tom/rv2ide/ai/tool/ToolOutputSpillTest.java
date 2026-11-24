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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 大输出「全文落盘 + 首尾摘要 + 路径引用」的行为钉住：
 * 信息不永久丢失（文件内容完整）、上下文占用有预算（摘要长度受控）、
 * 无工作区或写入失败时退回改造前的中间截断。
 */
final class ToolOutputSpillTest {

  private static ToolContext contextAt(Path home) {
    return ToolContext.builder().homePath(home.toString()).build();
  }

  private static String longText(int length) {
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append((char) ('a' + (i % 26)));
    }
    return sb.toString();
  }

  @Test
  void shortTextPassesThrough(@TempDir Path home) {
    assertEquals("hello", ToolOutputSpill.maybeSpill("hello", 100, contextAt(home)));
    assertEquals("", ToolOutputSpill.maybeSpill(null, 100, contextAt(home)));
  }

  @Test
  void longTextSpillsToFileAndKeepsFullContent(@TempDir Path home) throws Exception {
    String text = longText(10_000);

    String result = ToolOutputSpill.maybeSpill(text, 1_000, contextAt(home));

    assertTrue(result.contains("完整输出已保存到"), "应包含落盘路径说明: " + result.substring(0, 200));
    assertTrue(result.length() < 1_200, "摘要长度必须受预算控制（含路径说明）: " + result.length());
    // 首尾内容保留
    assertTrue(result.startsWith(text.substring(0, 100)), "头部应保留原文开头");
    assertTrue(result.endsWith(text.substring(text.length() - 100)), "尾部应保留原文结尾");

    File dir = home.resolve(ToolOutputSpill.SPILL_DIR).toFile();
    File[] files = dir.listFiles();
    assertTrue(files != null && files.length == 1, "工作区下应生成一个落盘文件");
    String saved = new String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8);
    assertEquals(text, saved, "落盘文件必须保留完整原文（中间内容不再丢失）");
  }

  @Test
  void emptyHomeFallsBackToMiddleTruncate() {
    String text = longText(10_000);
    ToolContext noHome = ToolContext.builder().homePath("").build();

    String result = ToolOutputSpill.maybeSpill(text, 1_000, noHome);

    assertFalse(result.contains("完整输出已保存到"), "无工作区时不应落盘");
    assertTrue(result.contains("省略"), "应退回中间截断并标注省略量");
    assertEquals(1_000 + result.length() - 1_000, result.length()); // 长度 sanity
  }

  @Test
  void nullContextFallsBackToMiddleTruncate() {
    String result = ToolOutputSpill.maybeSpill(longText(10_000), 1_000, null);
    assertFalse(result.contains("完整输出已保存到"));
    assertTrue(result.contains("省略"));
  }

  @Test
  void unwritableHomeFallsBackToMiddleTruncate(@TempDir Path home) throws Exception {
    // homePath 指向一个**文件**而非目录：mkdirs 必然失败 → 回落而不是抛异常
    Path fileHome = home.resolve("not-a-dir");
    Files.writeString(fileHome, "x");
    String result = ToolOutputSpill.maybeSpill(longText(10_000), 1_000, contextAt(fileHome));

    assertFalse(result.contains("完整输出已保存到"), "落盘失败必须退回截断而非失败");
    assertTrue(result.contains("省略"));
  }

  @Test
  void nonPositiveBudgetPassesThrough(@TempDir Path home) {
    String text = longText(10_000);
    assertEquals(text, ToolOutputSpill.maybeSpill(text, 0, contextAt(home)));
  }
}
