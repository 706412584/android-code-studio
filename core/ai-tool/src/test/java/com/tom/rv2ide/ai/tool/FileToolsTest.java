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

  // ---- 行尾归一化（回归：CRLF 文件曾导致 file_edit 恒失败）----

  @Test
  void editMatchesLfOldStringAgainstCrlfFile(@TempDir Path workspace) throws IOException {
    // 实测场景：模型给 LF 的 old_string，而文件是 CRLF。
    // 修复前 content.contains(oldString) 恒 false，工具报「No matching text found」，
    // 模型无法据此推断原因是行尾差异，只会反复重试或改用 file_write 整文件覆盖。
    ToolContext ctx = context(workspace);
    Files.writeString(workspace.resolve("f.txt"), "line1\r\nline2\r\nline3\r\n");

    ToolResult result =
        new FileEditTool()
            .execute(
                args("file_path", "f.txt", "old_string", "line2\n", "new_string", "LINE2\n"),
                ctx);

    assertFalse(result.isError(), "LF 的 old_string 应能匹配 CRLF 文件: " + result.getContent());
    // 行尾风格必须保持不变——一次编辑不该把整个文件转成 LF。
    assertEquals("line1\r\nLINE2\r\nline3\r\n", Files.readString(workspace.resolve("f.txt")));
  }

  @Test
  void editPreservesCrlfWhenOldStringIsCrlf(@TempDir Path workspace) throws IOException {
    ToolContext ctx = context(workspace);
    Files.writeString(workspace.resolve("f.txt"), "a\r\nb\r\n");

    ToolResult result =
        new FileEditTool()
            .execute(
                args("file_path", "f.txt", "old_string", "a\r\n", "new_string", "A\r\n"), ctx);

    assertFalse(result.isError(), "CRLF 的 old_string 也应正常: " + result.getContent());
    assertEquals("A\r\nb\r\n", Files.readString(workspace.resolve("f.txt")));
  }

  @Test
  void editKeepsLfFileAsLf(@TempDir Path workspace) throws IOException {
    // 反向保护：LF 文件不能被写成 CRLF。
    ToolContext ctx = context(workspace);
    Files.writeString(workspace.resolve("f.txt"), "a\nb\n");

    ToolResult result =
        new FileEditTool()
            .execute(args("file_path", "f.txt", "old_string", "a\n", "new_string", "A\n"), ctx);

    assertFalse(result.isError(), "LF 文件应正常编辑: " + result.getContent());
    assertEquals("A\nb\n", Files.readString(workspace.resolve("f.txt")));
  }

  @Test
  void editMultiLineCrlfBlockWithLfOldString(@TempDir Path workspace) throws IOException {
    // 多行块——这是实际失败的三次调用的形态（old_string 是连续多行）。
    ToolContext ctx = context(workspace);
    Files.writeString(
        workspace.resolve("f.txt"), "#include <EGL/egl.h>\r\n#include <memory>\r\n\r\n#include \"Model.h\"\r\n");

    ToolResult result =
        new FileEditTool()
            .execute(
                args(
                    "file_path", "f.txt",
                    "old_string", "#include <EGL/egl.h>\n#include <memory>\n\n#include \"Model.h\"\n",
                    "new_string", "#include <EGL/egl.h>\n#include <memory>\n\n#include \"Game.h\"\n"),
                ctx);

    assertFalse(result.isError(), "多行 LF 块应能匹配 CRLF 文件: " + result.getContent());
    assertEquals(
        "#include <EGL/egl.h>\r\n#include <memory>\r\n\r\n#include \"Game.h\"\r\n",
        Files.readString(workspace.resolve("f.txt")));
  }

  @Test
  void editPreservesMixedLineEndings(@TempDir Path workspace) throws IOException {
    // 混合行尾：同一文件里既有 LF 又有 CRLF 行。
    // 这是真实会发生的——模型用 file_write 把 LF 内容写进 CRLF 文件就产生了这种文件。
    // 修复前用「全文是否含 \r」判定风格，会把原本的 LF 行也改成 CRLF，
    // git 视角下整个文件都变了（实际只改了一行）。
    ToolContext ctx = context(workspace);
    Files.writeString(workspace.resolve("f.txt"), "a\nb\r\nc\n");

    ToolResult result =
        new FileEditTool()
            .execute(args("file_path", "f.txt", "old_string", "b\n", "new_string", "B\n"), ctx);

    assertFalse(result.isError(), "混合行尾文件应能编辑: " + result.getContent());
    // a 与 c 保持 LF，b 保持 CRLF —— 只改内容，不动任何行的行尾。
    assertEquals("a\nB\r\nc\n", Files.readString(workspace.resolve("f.txt")));
  }

  @Test
  void editDoesNotAccumulateCarriageReturns(@TempDir Path workspace) throws IOException {
    // 含裸 CR 的 LF 文件：修复前 replace("\n","\r\n") 会让 \r 越加越多，
    // 每次编辑都污染一轮。
    ToolContext ctx = context(workspace);
    Files.writeString(workspace.resolve("f.txt"), "a\rb\n");

    ToolResult result =
        new FileEditTool()
            .execute(args("file_path", "f.txt", "old_string", "b\n", "new_string", "B\n"), ctx);

    assertFalse(result.isError(), "应能编辑: " + result.getContent());
    assertEquals("a\rB\n", Files.readString(workspace.resolve("f.txt")));
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
