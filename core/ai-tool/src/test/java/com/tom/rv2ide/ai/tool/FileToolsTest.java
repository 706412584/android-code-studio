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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 文件工具的读写往返验证。
 *
 * <p>这些工具是 agent 的主要产出手段，因此除了「能工作」之外，还验证几个关键契约：
 * 参数缺失时不抛异常而是返回错误结果（保证 agent 循环不中断）、写入前自动建父目录、
 * 以及写入内容与磁盘内容逐字一致（中文与换行不能损坏）。
 */
final class FileToolsTest {

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

  @Test
  void writeThenReadRoundTripsExactContent(@TempDir Path workspace) throws IOException {
    ToolContext ctx = context(workspace);
    String content = "package com.example;\n\n// 中文注释不应损坏\nval x = 1\n";

    ToolResult write =
        new FileWriteTool().execute(args("file_path", "src/Main.kt", "content", content), ctx);
    assertFalse(write.isError(), "写入应成功: " + write.getContent());

    ToolResult read = new FileReadTool().execute(args("file_path", "src/Main.kt"), ctx);
    assertFalse(read.isError(), "读取应成功: " + read.getContent());
    assertTrue(
        read.getContent().contains("中文注释不应损坏"), "中文内容应完整保留: " + read.getContent());
    assertEquals(
        content, Files.readString(workspace.resolve("src/Main.kt")), "磁盘内容应与写入内容一致");
  }

  @Test
  void writeCreatesMissingParentDirectories(@TempDir Path workspace) throws IOException {
    ToolContext ctx = context(workspace);

    ToolResult result =
        new FileWriteTool()
            .execute(args("file_path", "a/b/c/deep.txt", "content", "hello"), ctx);

    assertFalse(result.isError(), "应自动创建父目录: " + result.getContent());
    assertTrue(Files.exists(workspace.resolve("a/b/c/deep.txt")));
  }

  @Test
  void editReplacesUniqueMatch(@TempDir Path workspace) throws IOException {
    ToolContext ctx = context(workspace);
    Files.writeString(workspace.resolve("f.txt"), "hello world\n");

    ToolResult result =
        new FileEditTool()
            .execute(
                args("file_path", "f.txt", "old_string", "world", "new_string", "there"), ctx);

    assertFalse(result.isError(), "编辑应成功: " + result.getContent());
    assertEquals("hello there\n", Files.readString(workspace.resolve("f.txt")));
  }

  @Test
  void editFailsWhenOldStringAbsentWithoutThrowing(@TempDir Path workspace) throws IOException {
    ToolContext ctx = context(workspace);
    Files.writeString(workspace.resolve("f.txt"), "hello\n");

    ToolResult result =
        new FileEditTool()
            .execute(args("file_path", "f.txt", "old_string", "nope", "new_string", "x"), ctx);

    assertTrue(result.isError(), "未匹配时应返回错误结果而非抛异常");
  }

  @Test
  void readMissingFileReturnsErrorResult(@TempDir Path workspace) {
    ToolResult result =
        new FileReadTool().execute(args("file_path", "does-not-exist.txt"), context(workspace));

    assertTrue(result.isError());
    assertFalse(result.getContent().isEmpty(), "错误结果应带有可读原因");
  }

  @Test
  void deleteRemovesFile(@TempDir Path workspace) throws IOException {
    ToolContext ctx = context(workspace);
    Files.writeString(workspace.resolve("gone.txt"), "bye");

    // 删除必须给出理由：这是上游的审计设计，强制模型说明删除意图。
    // paths 是 JSON 数组，支持一次删除多个目标。
    ToolResult result =
        new FileDeleteTool()
            .execute(
                args(
                    "paths",
                    new org.json.JSONArray().put("gone.txt"),
                    "reason",
                    "cleanup obsolete file"),
                ctx);

    assertFalse(result.isError(), "删除应成功: " + result.getContent());
    assertFalse(Files.exists(workspace.resolve("gone.txt")));
  }

  @Test
  void deleteWithoutReasonIsRejected(@TempDir Path workspace) throws IOException {
    ToolContext ctx = context(workspace);
    Files.writeString(workspace.resolve("keep.txt"), "important");

    ToolResult result =
        new FileDeleteTool()
            .execute(args("paths", new org.json.JSONArray().put("keep.txt")), ctx);

    assertTrue(result.isError(), "缺少理由时应拒绝删除");
    assertTrue(Files.exists(workspace.resolve("keep.txt")), "文件不应被删除");
  }

  @Test
  void listDirectoryReportsEntries(@TempDir Path workspace) throws IOException {
    ToolContext ctx = context(workspace);
    Files.writeString(workspace.resolve("one.txt"), "1");
    Files.writeString(workspace.resolve("two.txt"), "2");

    ToolResult result = new ListDirectoryTool().execute(args("path", "."), ctx);

    assertFalse(result.isError(), "列目录应成功: " + result.getContent());
    assertTrue(result.getContent().contains("one.txt"), result.getContent());
    assertTrue(result.getContent().contains("two.txt"), result.getContent());
  }

  @Test
  void globFindsMatchingFiles(@TempDir Path workspace) throws IOException {
    ToolContext ctx = context(workspace);
    Files.createDirectories(workspace.resolve("src"));
    Files.writeString(workspace.resolve("src/A.java"), "class A {}");
    Files.writeString(workspace.resolve("src/B.kt"), "class B");

    ToolResult result = new GlobTool().execute(args("pattern", "**/*.java", "path", "."), ctx);

    assertFalse(result.isError(), "glob 应成功: " + result.getContent());
    assertTrue(result.getContent().contains("A.java"), result.getContent());
    assertFalse(result.getContent().contains("B.kt"), "不应匹配非目标扩展名");
  }

  @Test
  void toolsDeclareExpectedCategories(@TempDir Path workspace) {
    assertEquals(ToolCategory.READ, new FileReadTool().getCategory());
    assertEquals(ToolCategory.WRITE, new FileWriteTool().getCategory());
    assertEquals(ToolCategory.WRITE, new FileEditTool().getCategory());
    assertEquals(ToolCategory.WRITE, new FileDeleteTool().getCategory());
  }

  @Test
  void writeIsBlockedOutsideWorkspace(@TempDir Path workspace) throws IOException {
    Path outside = Files.createTempDirectory("acs-outside");

    ToolResult result =
        new FileWriteTool()
            .execute(
                args("file_path", outside.resolve("x.txt").toString(), "content", "nope"),
                context(workspace));

    assertTrue(result.isError(), "工作区外的写入应被拒绝");
  }
}
