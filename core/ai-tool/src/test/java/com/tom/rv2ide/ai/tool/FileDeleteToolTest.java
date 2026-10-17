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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link FileDeleteTool}：批量删除、部分失败、递归目录与路径收集口径。 */
class FileDeleteToolTest {

  private static ToolContext context(String home) {
    return ToolContext.builder().homePath(home).build();
  }

  private static JSONObject input(String reason, String... paths) {
    org.json.JSONArray array = new org.json.JSONArray();
    for (String path : paths) {
      array.put(path);
    }
    return new JSONObject().put("reason", reason).put("paths", array);
  }

  private static File write(File file, String content) throws Exception {
    file.getParentFile().mkdirs();
    Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    return file;
  }

  @Test
  void metadata() {
    FileDeleteTool tool = new FileDeleteTool();
    assertEquals("file_delete", tool.getName());
    assertEquals(com.tom.rv2ide.ai.tool.api.ToolCategory.WRITE, tool.getCategory());
    assertEquals(com.tom.rv2ide.ai.tool.api.ToolDisplayCategory.DELETE, tool.getDisplayCategory());
    assertTrue(tool.needsConfirmation(), "删除必须确认");
    JSONObject params = tool.getParameters();
    assertTrue(params.getJSONArray("required").toString().contains("reason"));
  }

  @Test
  void deletesFileAndReportsIt(@TempDir File dir) throws Exception {
    write(new File(dir, "a.txt"), "A");
    ToolResult result =
        new FileDeleteTool().execute(input("cleanup", "a.txt"), context(dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    assertFalse(new File(dir, "a.txt").exists());
    assertTrue(result.getContent().contains("a.txt"), result.getContent());
  }

  @Test
  void deletesDirectoryRecursively(@TempDir File dir) throws Exception {
    write(new File(dir, "proj/a.txt"), "A");
    write(new File(dir, "proj/sub/b.txt"), "B");
    ToolResult result =
        new FileDeleteTool().execute(input("cleanup", "proj"), context(dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    assertFalse(new File(dir, "proj").exists(), "目录应被整体删除");
  }

  @Test
  void missingPathIsReportedAsError(@TempDir File dir) {
    ToolResult result =
        new FileDeleteTool().execute(input("cleanup", "nope.txt"), context(dir.getAbsolutePath()));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("nope.txt"), result.getContent());
  }

  @Test
  void partialFailureIsReportedButStillOk(@TempDir File dir) throws Exception {
    // 一个成功一个不存在：整体是成功（有删除发生），但失败项必须列出来。
    write(new File(dir, "a.txt"), "A");
    ToolResult result =
        new FileDeleteTool()
            .execute(input("cleanup", "a.txt", "missing.txt"), context(dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("a.txt"), result.getContent());
    assertTrue(result.getContent().contains("missing.txt"), result.getContent());
  }

  @Test
  void allFailuresMakeTheResultAnError(@TempDir File dir) {
    ToolResult result =
        new FileDeleteTool()
            .execute(input("cleanup", "x", "y"), context(dir.getAbsolutePath()));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("x"), result.getContent());
    assertTrue(result.getContent().contains("y"), result.getContent());
  }

  @Test
  void emptyReasonIsRejected(@TempDir File dir) throws Exception {
    write(new File(dir, "a.txt"), "A");
    ToolResult result =
        new FileDeleteTool().execute(input("  ", "a.txt"), context(dir.getAbsolutePath()));
    assertTrue(result.isError());
    assertTrue(new File(dir, "a.txt").exists(), "校验失败不应删除任何东西");
  }

  @Test
  void emptyPathsIsRejected(@TempDir File dir) {
    ToolResult result = new FileDeleteTool().execute(input("cleanup"), context(dir.getAbsolutePath()));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("path"), result.getContent());
  }

  @Test
  void escapeAttemptIsRejected(@TempDir File dir) throws Exception {
    File outside = new File(dir.getParentFile(), "outside-" + System.nanoTime() + ".txt");
    Files.write(outside.toPath(), new byte[] {1});
    try {
      ToolResult result =
          new FileDeleteTool()
              .execute(
                  input("cleanup", "../" + outside.getName()), context(dir.getAbsolutePath()));
      assertTrue(result.isError(), "越界路径必须被拒绝");
      assertTrue(outside.exists(), "工作区外的文件绝不能被删除");
    } finally {
      outside.delete();
    }
  }

  @Test
  void collectPathsOrderAndAliases() {
    // 授权与执行必须用同一口径：paths → file_path → path，且跳过空值。
    JSONObject input =
        new JSONObject()
            .put("paths", new org.json.JSONArray().put("a").put("  ").put("b"))
            .put("file_path", "c")
            .put("path", "d");

    assertEquals(
        java.util.Arrays.asList("a", "b", "c", "d"), FileDeleteTool.collectPaths(input));
  }

  @Test
  void collectPathsWithoutPathsArray() {
    JSONObject input = new JSONObject().put("path", "only");
    assertEquals(java.util.Collections.singletonList("only"), FileDeleteTool.collectPaths(input));
  }

  @Test
  void emptyDirectoryIsDeleted(@TempDir File dir) throws Exception {
    File empty = new File(dir, "empty");
    assertTrue(empty.mkdirs());
    ToolResult result =
        new FileDeleteTool().execute(input("cleanup", "empty"), context(dir.getAbsolutePath()));
    assertFalse(result.isError(), result.getContent());
    assertFalse(empty.exists());
  }
}
