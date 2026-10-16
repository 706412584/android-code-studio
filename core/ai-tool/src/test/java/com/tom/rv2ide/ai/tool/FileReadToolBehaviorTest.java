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
}
