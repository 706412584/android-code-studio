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

import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 钉住 {@link FileReadTool} 的分段读取语义：大文件拒绝整读、KB 范围按字节读且
 * 对齐到行边界、行号连续、目录树列出、以及行号/换行统计的边界处理。
 */
final class FileReadToolBehaviorTest {

  private static ToolContext context(Path workspace) {
    return ToolContext.builder().homePath(workspace.toString()).build();
  }

  private static JSONObject args(Object... kv) {
    JSONObject json = new JSONObject();
    for (int i = 0; i < kv.length; i += 2) {
      json.put((String) kv[i], kv[i + 1]);
    }
    return json;
  }

  private static FileReadTool tool() {
    return new FileReadTool();
  }

  @Test
  void smallFileIsReadWholeWithLineNumbers(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("a.txt"), "alpha\nbeta\n");
    ToolResult result = tool().execute(args("file_path", "a.txt"), context(workspace));
    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("1\talpha"), result.getContent());
    assertTrue(result.getContent().contains("2\tbeta"), result.getContent());
    // 结尾换行不应产生多余的空行号
    assertFalse(result.getContent().contains("3\t"), result.getContent());
  }

  @Test
  void largeFileWithoutRangeIsRefused(@TempDir Path workspace) throws Exception {
    String big = "x".repeat(60 * 1024);
    Files.writeString(workspace.resolve("big.txt"), big);
    ToolResult result = tool().execute(args("file_path", "big.txt"), context(workspace));
    assertTrue(result.isError(), "超过 50KB 且未给范围时应拒绝整读");
  }

  @Test
  void largeFileWithRangeIsReadByRequestedBytes(@TempDir Path workspace) throws Exception {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 4000; i++) {
      sb.append("line-").append(i).append('\n');
    }
    Files.writeString(workspace.resolve("big.txt"), sb.toString());

    ToolResult result = tool().execute(
        args("file_path", "big.txt", "start_kb", 0, "end_kb", 5), context(workspace));
    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("line-0"), result.getContent());
    // 范围信息应出现
    assertTrue(result.getContent().contains("KB") || result.getContent().contains("行"),
        result.getContent());
  }

  @Test
  void rangeStartBeyondEofIsReported(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("small.txt"), "short\n");
    ToolResult result = tool().execute(
        args("file_path", "small.txt", "start_kb", 999, "end_kb", 1000), context(workspace));
    assertTrue(result.isError(), "起点越界应报错");
  }

  @Test
  void rangeAlignedToLineBoundariesKeepsWholeLines(@TempDir Path workspace) throws Exception {
    // 每行等长，范围从行中间开始 → 首行应被跳过，返回完整行
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 3000; i++) {
      sb.append("0123456789abcdef\n"); // 17 字节/行
    }
    Files.writeString(workspace.resolve("grid.txt"), sb.toString());
    ToolResult result = tool().execute(
        args("file_path", "grid.txt", "start_kb", 1, "end_kb", 2), context(workspace));
    assertFalse(result.isError(), result.getContent());
    for (String line : result.getContent().split("\n")) {
      if (line.contains("\t")) {
        // 每行内容应是完整的 16 字符行，而不是半截
        String content = line.substring(line.indexOf('\t') + 1);
        assertEquals(16, content.length(), "返回内容必须对齐到完整行: " + line);
      }
    }
  }

  @Test
  void hugeKbRangeIsClamped(@TempDir Path workspace) throws Exception {
    // end_kb 极大不应一次性申请巨量内存：跨度被夹到 1024KB
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 40000; i++) {
      sb.append("row-").append(i).append('\n');
    }
    Files.writeString(workspace.resolve("wide.txt"), sb.toString());
    ToolResult result = tool().execute(
        args("file_path", "wide.txt", "start_kb", 0, "end_kb", 999999), context(workspace));
    assertFalse(result.isError(), result.getContent());
  }

  @Test
  void directoryIsListedAsTree(@TempDir Path workspace) throws Exception {
    Files.createDirectories(workspace.resolve("sub"));
    Files.writeString(workspace.resolve("sub/inner.txt"), "x");
    Files.writeString(workspace.resolve("top.txt"), "y");
    ToolResult result = tool().execute(args("file_path", "."), context(workspace));
    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("[DIR]"), result.getContent());
    assertTrue(result.getContent().contains("inner.txt"), result.getContent());
    assertTrue(result.getContent().contains("top.txt"), result.getContent());
  }

  @Test
  void emptyDirectoryIsReported(@TempDir Path workspace) throws Exception {
    Path empty = Files.createDirectory(workspace.resolve("empty"));
    ToolResult result = tool().execute(args("file_path", empty.toString()), context(workspace));
    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("empty") || result.getContent().contains("空"),
        result.getContent());
  }

  @Test
  void unsupportedImageExtensionIsRejectedNotReadAsText(@TempDir Path workspace) throws Exception {
    // gif/webp 当文本读会得到乱码；工具必须明确报错让模型转格式
    Files.write(workspace.resolve("anim.gif"), new byte[] {'G', 'I', 'F', '8', '9', 'a'});
    ToolResult result = tool().execute(args("file_path", "anim.gif"), context(workspace));
    assertTrue(result.isError(), "不支持的图片格式应报错而非返回二进制乱码");
  }

  @Test
  void metadataIsDeclared() {
    assertEquals(com.tom.rv2ide.ai.tool.api.ToolNames.FILE_READ, tool().getName());
    assertEquals(com.tom.rv2ide.ai.tool.api.ToolCategory.READ, tool().getCategory());
    assertEquals(ToolDisplayCategory.READ, tool().getDisplayCategory());
    assertTrue(tool().isConcurrencySafe());
    assertFalse(tool().getDescription().isEmpty());
  }

  @Test
  void parametersDeclareRequiredFilePath() throws Exception {
    JSONObject params = tool().getParameters();
    assertEquals("object", params.getString("type"));
    assertTrue(params.getJSONObject("properties").has("start_kb"));
    assertEquals("file_path", params.getJSONArray("required").getString(0));
  }

  @Test
  void missingPathIsResolvedToWorkspaceRoot(@TempDir Path workspace) throws Exception {
    // file_path 缺省 → 解析到工作区根目录，返回目录列表而非报错
    Files.writeString(workspace.resolve("root.txt"), "x");
    ToolResult result = tool().execute(new JSONObject(), context(workspace));
    assertFalse(result.isError(), result.getContent());
  }

  @Test
  void emptyFileReadsAsEmpty(@TempDir Path workspace) throws Exception {
    Files.write(workspace.resolve("zero.txt"), new byte[0]);
    ToolResult result = tool().execute(args("file_path", "zero.txt"), context(workspace));
    assertFalse(result.isError(), result.getContent());
    assertEquals("", result.getContent().trim());
  }

  @Test
  void utf8ContentSurvivesRangeRead(@TempDir Path workspace) throws Exception {
    String content = "中文字符串测试\n".repeat(200);
    Files.write(workspace.resolve("cjk.txt"), content.getBytes(StandardCharsets.UTF_8));
    ToolResult result = tool().execute(
        args("file_path", "cjk.txt", "start_kb", 0, "end_kb", 1), context(workspace));
    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("中文字符串测试"), result.getContent());
  }

  /* ───────────────────────── 按行号读取 ───────────────────────── */

  /**
   * 行号来自 grep / diagnostics / lsp_navigate，返回的都是 1 起的行号。
   * KB 与行号在含中文的文件里完全不成比例，靠 KB 估算经常读偏再重试。
   */
  @Test
  void lineRangeReadsExactLines(@TempDir Path workspace) throws Exception {
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= 100; i++) {
      sb.append("line").append(i).append('\n');
    }
    Files.writeString(workspace.resolve("a.txt"), sb.toString());

    ToolResult result = tool().execute(
        args("file_path", "a.txt", "start_line", 10, "end_line", 12), context(workspace));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("10\tline10"), result.getContent());
    assertTrue(result.getContent().contains("12\tline12"), result.getContent());
    assertFalse(result.getContent().contains("line9"), result.getContent());
    assertFalse(result.getContent().contains("line13"), result.getContent());
  }

  /** 行号必须与 grep 的口径一致（1 起、含首尾）。 */
  @Test
  void lineRangeIsOneBasedInclusive(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("a.txt"), "first\nsecond\nthird\n");

    ToolResult result = tool().execute(
        args("file_path", "a.txt", "start_line", 1, "end_line", 1), context(workspace));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("1\tfirst"), result.getContent());
    assertFalse(result.getContent().contains("second"), result.getContent());
  }

  /** 只给 start_line 时按默认跨度向后取，并给出续读位置。 */
  @Test
  void startLineAloneReturnsDefaultWindow(@TempDir Path workspace) throws Exception {
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= 50; i++) {
      sb.append("l").append(i).append('\n');
    }
    Files.writeString(workspace.resolve("a.txt"), sb.toString());
    // 写满 500 行才能触发默认窗口的截断信息；这里只验证能读到 start 之后的内容
    ToolResult result = tool().execute(
        args("file_path", "a.txt", "start_line", 45), context(workspace));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("45\tl45"), result.getContent());
    assertTrue(result.getContent().contains("50\tl50"), result.getContent());
  }

  @Test
  void lineRangeIsClampedToFileEnd(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("a.txt"), "a\nb\nc\n");
    ToolResult result = tool().execute(
        args("file_path", "a.txt", "start_line", 2, "end_line", 999), context(workspace));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("2\tb"), result.getContent());
    assertTrue(result.getContent().contains("3\tc"), result.getContent());
    // 越界的 end_line 应被夹到文件末，而不是报错
    assertTrue(result.getContent().contains("showing lines 2-3"), result.getContent());
  }

  @Test
  void startLineBeyondFileIsRejected(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("a.txt"), "a\nb\n");
    ToolResult result = tool().execute(
        args("file_path", "a.txt", "start_line", 50), context(workspace));

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("exceeds"), result.getContent());
  }

  @Test
  void zeroStartLineIsTreatedAsFirst(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("a.txt"), "a\nb\n");
    ToolResult result = tool().execute(
        args("file_path", "a.txt", "start_line", 0, "end_line", 1), context(workspace));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("1\ta"), result.getContent());
  }

  /** 行号范围必须优先于 KB 范围，否则模型传了两者会被静默按 KB 处理。 */
  @Test
  void lineRangeTakesPrecedenceOverKbRange(@TempDir Path workspace) throws Exception {
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= 200; i++) {
      sb.append("row").append(i).append('\n');
    }
    Files.writeString(workspace.resolve("a.txt"), sb.toString());

    ToolResult result = tool().execute(
        args("file_path", "a.txt", "start_line", 100, "end_line", 101, "start_kb", 0, "end_kb", 1),
        context(workspace));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("100\trow100"), result.getContent());
  }

  /**
   * 含中文的文件里 KB 与行号严重不成比例（一个中文字 3 字节）。
   * 行号读取必须按行扫描而不是按字节估算。
   */
  @Test
  void lineRangeIsAccurateForCjkContent(@TempDir Path workspace) throws Exception {
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= 60; i++) {
      sb.append("第").append(i).append("行中文内容测试字符串\n");
    }
    Files.write(workspace.resolve("cjk.txt"), sb.toString().getBytes(StandardCharsets.UTF_8));

    ToolResult result = tool().execute(
        args("file_path", "cjk.txt", "start_line", 30, "end_line", 30), context(workspace));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("30\t第30行中文内容测试字符串"), result.getContent());
  }

  /** 大文件按行读要能工作在 50KB 阈值之上（否则「定位到某行」对大文件仍然无解）。 */
  @Test
  void lineRangeWorksOnLargeFile(@TempDir Path workspace) throws Exception {
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= 20000; i++) {
      sb.append("padding line ").append(i).append(" ................\n");
    }
    Files.writeString(workspace.resolve("big.txt"), sb.toString());
    assertTrue(Files.size(workspace.resolve("big.txt")) > 50 * 1024);

    ToolResult result = tool().execute(
        args("file_path", "big.txt", "start_line", 19000, "end_line", 19002), context(workspace));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("19000\tpadding line 19000"), result.getContent());
    assertTrue(result.getContent().contains("19002\tpadding line 19002"), result.getContent());
  }

  @Test
  void lineRangeSchemaIsExposed() throws Exception {
    JSONObject params = tool().getParameters().getJSONObject("properties");
    assertTrue(params.has("start_line"));
    assertTrue(params.has("end_line"));
  }

  /** 末尾换行不该被算成额外一行（否则 end_line 会多读一行空内容）。 */
  @Test
  void trailingNewlineDoesNotAddALine(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("a.txt"), "only\n");
    ToolResult result = tool().execute(
        args("file_path", "a.txt", "start_line", 1, "end_line", 5), context(workspace));

    assertFalse(result.isError(), result.getContent());
    // 文件只有 1 行；end_line 被夹到 1
    assertTrue(result.getContent().contains("showing lines 1-1"), result.getContent());
  }
}
