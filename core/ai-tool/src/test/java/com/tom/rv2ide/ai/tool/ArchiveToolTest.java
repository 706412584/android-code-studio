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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ArchiveTool}：压缩/解压往返，以及两条**安全**防线（Zip Slip / zip bomb）。
 *
 * <p>解压是典型的高危操作，安全断言比功能断言更重要。
 */
class ArchiveToolTest {

  private static ToolContext context(String home) {
    return ToolContext.builder().homePath(home).build();
  }

  private static ToolResult run(ToolContext ctx, String action, String path, String output)
      throws Exception {
    JSONObject input = new JSONObject().put("action", action).put("path", path);
    if (output != null) {
      input.put("output", output);
    }
    return new ArchiveTool().execute(input, ctx);
  }

  private static File write(File file, String content) throws Exception {
    file.getParentFile().mkdirs();
    Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    return file;
  }

  @Test
  void compressFileThenExtractRoundTrip(@TempDir File dir) throws Exception {
    write(new File(dir, "hello.txt"), "hi there");
    ToolContext ctx = context(dir.getAbsolutePath());

    ToolResult compressed = run(ctx, "compress", "hello.txt", "out.zip");
    assertFalse(compressed.isError(), compressed.getContent());
    assertTrue(new File(dir, "out.zip").isFile());
    assertTrue(compressed.getContent().contains("条目数: 1"), compressed.getContent());

    ToolResult extracted = run(ctx, "extract", "out.zip", "unpacked");
    assertFalse(extracted.isError(), extracted.getContent());
    File restored = new File(dir, "unpacked/hello.txt");
    assertTrue(restored.isFile());
    assertEquals("hi there", new String(Files.readAllBytes(restored.toPath()), StandardCharsets.UTF_8));
  }

  @Test
  void compressDirectoryPreservesStructure(@TempDir File dir) throws Exception {
    write(new File(dir, "proj/a.txt"), "A");
    write(new File(dir, "proj/sub/b.txt"), "B");
    ToolContext ctx = context(dir.getAbsolutePath());

    assertFalse(run(ctx, "compress", "proj", "p.zip").isError());

    assertFalse(run(ctx, "extract", "p.zip", "back").isError());
    assertTrue(new File(dir, "back/proj/a.txt").isFile());
    assertTrue(new File(dir, "back/proj/sub/b.txt").isFile());
  }

  @Test
  void extractRejectsZipSlipEntry(@TempDir File dir) throws Exception {
    // 构造一个含 ../ 逃逸条目的归档——这是 Zip Slip 的典型形态。
    File evil = new File(dir, "evil.zip");
    try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(evil))) {
      zip.putNextEntry(new ZipEntry("../escaped.txt"));
      zip.write("pwned".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    File sandbox = new File(dir, "sandbox");
    sandbox.mkdirs();

    ToolResult result = run(context(dir.getAbsolutePath()), "extract", "evil.zip", "sandbox/out");
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("Zip Slip"), result.getContent());
    // 逃逸文件绝不能出现（无论在工作区里还是父目录）。
    assertFalse(new File(dir, "escaped.txt").exists());
    assertFalse(new File(sandbox, "escaped.txt").exists());
  }

  @Test
  void safeResolveBlocksEscape() {
    String root = new File(".").getAbsolutePath();
    assertNull(ArchiveTool.safeResolve(root, "../x.txt"));
    assertNull(ArchiveTool.safeResolve(root, "a/../../x.txt"));
    assertNull(ArchiveTool.safeResolve(root, ""));
    // 正常相对路径应通过
    assertTrue(ArchiveTool.safeResolve(root, "a/b.txt") != null);
  }

  @Test
  void extractRejectsTooManyEntries(@TempDir File dir) throws Exception {
    File bomb = new File(dir, "bomb.zip");
    try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(bomb))) {
      for (int i = 0; i <= ArchiveTool.MAX_ENTRIES; i++) {
        zip.putNextEntry(new ZipEntry("f" + i + ".txt"));
        zip.write(new byte[] {1});
        zip.closeEntry();
      }
    }
    ToolResult result =
        run(context(dir.getAbsolutePath()), "extract", "bomb.zip", "out");
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("条目数超过上限"), result.getContent());
  }

  @Test
  void rejectsMissingPathAndBadAction(@TempDir File dir) throws Exception {
    ToolContext ctx = context(dir.getAbsolutePath());
    assertTrue(run(ctx, "compress", "nope.txt", null).isError());

    write(new File(dir, "a.txt"), "x");
    ToolResult bad = run(ctx, "explode", "a.txt", null);
    assertTrue(bad.isError());
    assertTrue(bad.getContent().contains("compress"), bad.getContent());
  }

  @Test
  void compressRequiresZipExtension(@TempDir File dir) throws Exception {
    write(new File(dir, "a.txt"), "x");
    ToolResult result = run(context(dir.getAbsolutePath()), "compress", "a.txt", "out.tar");
    assertTrue(result.isError());
    assertTrue(result.getContent().contains(".zip"), result.getContent());
  }

  @Test
  void rejectsOutputInsideCompressedDirectory(@TempDir File dir) throws Exception {
    write(new File(dir, "proj/a.txt"), "A");
    ToolResult result =
        run(context(dir.getAbsolutePath()), "compress", "proj", "proj/self.zip");
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("内部"), result.getContent());
  }

  @Test
  void extractDefaultsToArchiveSiblingDirectory(@TempDir File dir) throws Exception {
    write(new File(dir, "data.txt"), "D");
    ToolContext ctx = context(dir.getAbsolutePath());
    assertFalse(run(ctx, "compress", "data.txt", "bundle.zip").isError());

    ToolResult result = run(ctx, "extract", "bundle.zip", null);
    assertFalse(result.isError(), result.getContent());
    // 默认解到与 zip 同名的目录 bundle/
    assertTrue(new File(dir, "bundle/data.txt").isFile());
  }

  // ---- 此前未覆盖的分支 ----

  @Test
  void compressWithoutOutputUsesSourceNameWithZipExtension(@TempDir File dir) throws Exception {
    // 省略 output：默认归档名 = 源文件名（去扩展名）+ .zip。
    write(new File(dir, "notes.txt"), "N");
    ToolResult result = run(context(dir.getAbsolutePath()), "compress", "notes.txt", null);
    assertFalse(result.isError(), result.getContent());
    assertTrue(new File(dir, "notes.zip").isFile(), "默认应产出 notes.zip");
  }

  @Test
  void compressDirectoryWithoutOutputUsesDirectoryName(@TempDir File dir) throws Exception {
    // 目录：默认名保留目录名（不剥"扩展名"）。
    write(new File(dir, "proj/a.txt"), "A");
    ToolResult result = run(context(dir.getAbsolutePath()), "compress", "proj", null);
    assertFalse(result.isError(), result.getContent());
    assertTrue(new File(dir, "proj.zip").isFile(), "默认应产出 proj.zip");
  }

  @Test
  void compressEmptyDirectoryIsRejected(@TempDir File dir) throws Exception {
    File empty = new File(dir, "empty");
    assertTrue(empty.mkdirs());
    ToolResult result = run(context(dir.getAbsolutePath()), "compress", "empty", null);
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("空"), result.getContent());
  }

  @Test
  void emptySubdirectoryKeepsStructureInArchive(@TempDir File dir) throws Exception {
    // 空子目录也要在归档里留条目——否则解压后目录结构丢失。
    write(new File(dir, "proj/a.txt"), "A");
    assertTrue(new File(dir, "proj/sub-empty").mkdirs());

    ToolContext ctx = context(dir.getAbsolutePath());
    assertFalse(run(ctx, "compress", "proj", "out.zip").isError());
    assertFalse(run(ctx, "extract", "out.zip", "restored").isError());

    assertTrue(new File(dir, "restored/proj/sub-empty").isDirectory(),
        "空子目录应被还原");
  }

  @Test
  void extractNonFileIsRejected(@TempDir File dir) throws Exception {
    File sub = new File(dir, "subdir");
    assertTrue(sub.mkdirs());
    ToolResult result = run(context(dir.getAbsolutePath()), "extract", "subdir", null);
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("必须是文件"), result.getContent());
  }

  @Test
  void extractToExistingFileTargetIsRejected(@TempDir File dir) throws Exception {
    write(new File(dir, "a.txt"), "A");
    ToolContext ctx = context(dir.getAbsolutePath());
    assertFalse(run(ctx, "compress", "a.txt", "a.zip").isError());
    write(new File(dir, "occupied"), "not a dir");

    ToolResult result = run(ctx, "extract", "a.zip", "occupied");
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("不是目录"), result.getContent());
  }

  @Test
  void extractRoundTripReportsEntryCountAndSize(@TempDir File dir) throws Exception {
    // 结果元数据：条目数与解压后大小都要如实报出。
    write(new File(dir, "x.txt"), "hello");
    ToolContext ctx = context(dir.getAbsolutePath());
    assertFalse(run(ctx, "compress", "x.txt", "x.zip").isError());

    ToolResult result = run(ctx, "extract", "x.zip", "out");
    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("条目数: 1"), result.getContent());
    assertTrue(result.getContent().contains("解压后大小"), result.getContent());
  }

  @Test
  void toolMetadata() {
    ArchiveTool tool = new ArchiveTool();
    assertTrue(tool.needsConfirmation(), "解压/压缩是写操作，应确认");
    assertEquals(
        com.tom.rv2ide.ai.tool.api.ToolDisplayCategory.GENERIC, tool.getDisplayCategory());
  }
}
