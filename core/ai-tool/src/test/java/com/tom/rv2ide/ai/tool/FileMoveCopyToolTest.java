/*
 * This file is part of AndroidCodeStudio.
 *
 * This program is free software: you can redistribute it and/or modify
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

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link FileMoveTool} / {@link FileCopyTool} 的行为测试。
 *
 * <p>重点在**不丢数据**：移动会删除源，任何一条错误路径都不该让用户既丢了源、又没拿到目标。
 */
class FileMoveCopyToolTest {

  private static ToolContext context(String home) {
    return ToolContext.builder().homePath(home).build();
  }

  private static File write(File file, String content) throws Exception {
    file.getParentFile().mkdirs();
    Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    return file;
  }

  private static JSONObject args(String source, String target) throws Exception {
    return new JSONObject().put("source", source).put("target", target);
  }

  /* ───────────────────────── 权限元数据 ───────────────────────── */

  @Test
  void metadata() {
    FileMoveTool move = new FileMoveTool();
    assertEquals(ToolNames.FILE_MOVE, move.getName());
    assertEquals(ToolCategory.WRITE, move.getCategory());
    assertEquals(ToolDisplayCategory.WRITE, move.getDisplayCategory());
    // 移动会删除源，与 file_delete 同级
    assertTrue(move.needsConfirmation(), "移动必须确认（会删源文件）");

    FileCopyTool copy = new FileCopyTool();
    assertEquals(ToolNames.FILE_COPY, copy.getName());
    assertEquals(ToolCategory.WRITE, copy.getCategory());
    // 复制不删任何东西，不必打扰用户
    assertFalse(copy.needsConfirmation(), "复制不应要求确认");
  }

  /* ───────────────────────── 移动 ───────────────────────── */

  @Test
  void moveRenamesFile(@TempDir File dir) throws Exception {
    File source = write(new File(dir, "a.txt"), "hello");
    ToolResult result = new FileMoveTool()
        .execute(args("a.txt", "b.txt"), context(dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    assertFalse(source.exists(), "源应已不存在");
    assertEquals("hello", FileIo.readUtf8(new File(dir, "b.txt")));
  }

  @Test
  void moveIntoExistingDirectoryKeepsName(@TempDir File dir) throws Exception {
    write(new File(dir, "a.txt"), "hello");
    new File(dir, "sub").mkdirs();

    ToolResult result = new FileMoveTool()
        .execute(args("a.txt", "sub"), context(dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    // `mv a.txt sub` 的语义是移到 sub/a.txt，而不是把 sub 目录整个替换掉
    assertEquals("hello", FileIo.readUtf8(new File(dir, "sub/a.txt")));
    assertTrue(new File(dir, "sub").isDirectory(), "目标目录本身必须还在");
  }

  @Test
  void moveCreatesMissingParentDirectories(@TempDir File dir) throws Exception {
    write(new File(dir, "a.txt"), "hello");
    ToolResult result = new FileMoveTool()
        .execute(args("a.txt", "deep/nested/b.txt"), context(dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    assertEquals("hello", FileIo.readUtf8(new File(dir, "deep/nested/b.txt")));
  }

  @Test
  void moveDirectoryRecursively(@TempDir File dir) throws Exception {
    write(new File(dir, "src/one.txt"), "1");
    write(new File(dir, "src/nested/two.txt"), "2");

    ToolResult result = new FileMoveTool()
        .execute(args("src", "dst"), context(dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    assertFalse(new File(dir, "src").exists());
    assertEquals("1", FileIo.readUtf8(new File(dir, "dst/one.txt")));
    assertEquals("2", FileIo.readUtf8(new File(dir, "dst/nested/two.txt")));
  }

  /* ───────────────────────── 失败路径（都不能丢数据） ───────────────────────── */

  @Test
  void moveMissingSourceFailsWithoutTouchingAnything(@TempDir File dir) throws Exception {
    ToolResult result = new FileMoveTool()
        .execute(args("nope.txt", "b.txt"), context(dir.getAbsolutePath()));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("Source not found"), result.getContent());
  }

  @Test
  void moveRefusesExistingTargetWithoutOverwrite(@TempDir File dir) throws Exception {
    File source = write(new File(dir, "a.txt"), "A");
    write(new File(dir, "b.txt"), "B");

    ToolResult result = new FileMoveTool()
        .execute(args("a.txt", "b.txt"), context(dir.getAbsolutePath()));

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("already exists"), result.getContent());
    // 关键：两边内容都不能动
    assertEquals("A", FileIo.readUtf8(source));
    assertEquals("B", FileIo.readUtf8(new File(dir, "b.txt")));
  }

  @Test
  void moveOverwritesWhenAsked(@TempDir File dir) throws Exception {
    write(new File(dir, "a.txt"), "A");
    write(new File(dir, "b.txt"), "B");

    JSONObject input = args("a.txt", "b.txt").put("overwrite", true);
    ToolResult result = new FileMoveTool().execute(input, context(dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    assertEquals("A", FileIo.readUtf8(new File(dir, "b.txt")));
  }

  @Test
  void moveRefusesSameSourceAndTarget(@TempDir File dir) throws Exception {
    File source = write(new File(dir, "a.txt"), "A");
    ToolResult result = new FileMoveTool()
        .execute(args("a.txt", "a.txt"), context(dir.getAbsolutePath()));

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("same path"), result.getContent());
    assertEquals("A", FileIo.readUtf8(source), "自移动失败后内容必须原样还在");
  }

  /** 把目录移进自己的子目录会无限递归（或直接失败），必须提前拦住。 */
  @Test
  void moveRefusesMovingDirectoryIntoItself(@TempDir File dir) throws Exception {
    write(new File(dir, "src/one.txt"), "1");
    ToolResult result = new FileMoveTool()
        .execute(args("src", "src/nested"), context(dir.getAbsolutePath()));

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("into itself"), result.getContent());
    assertEquals("1", FileIo.readUtf8(new File(dir, "src/one.txt")));
  }

  /**
   * 即使开了 overwrite，把文件移到已有目录也不该**清空那个目录**——
   * `mv a.txt target` 的语义是移进目录，不是替换目录。目录里原有的文件必须一个不少。
   */
  @Test
  void moveFileIntoDirectoryWithOverwriteKeepsExistingFiles(@TempDir File dir) throws Exception {
    write(new File(dir, "a.txt"), "A");
    write(new File(dir, "target/keep.txt"), "keep");

    JSONObject input = args("a.txt", "target").put("overwrite", true);
    ToolResult result = new FileMoveTool().execute(input, context(dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    assertEquals("keep", FileIo.readUtf8(new File(dir, "target/keep.txt")),
        "目录里原有的文件不能被删");
    assertEquals("A", FileIo.readUtf8(new File(dir, "target/a.txt")));
    assertFalse(new File(dir, "a.txt").exists());
  }

  @Test
  void moveOutsideWorkspaceIsRefused(@TempDir File dir) throws Exception {
    write(new File(dir, "a.txt"), "A");
    ToolResult result = new FileMoveTool()
        .execute(args("a.txt", "../../escape.txt"), context(dir.getAbsolutePath()));

    assertTrue(result.isError());
    assertEquals("A", FileIo.readUtf8(new File(dir, "a.txt")), "越界失败不该动源文件");
  }

  /* ───────────────────────── 复制 ───────────────────────── */

  @Test
  void copyKeepsSource(@TempDir File dir) throws Exception {
    File source = write(new File(dir, "a.txt"), "hello");
    ToolResult result = new FileCopyTool()
        .execute(args("a.txt", "b.txt"), context(dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    assertEquals("hello", FileIo.readUtf8(source), "复制的源必须原样保留");
    assertEquals("hello", FileIo.readUtf8(new File(dir, "b.txt")));
  }

  @Test
  void copyDirectoryRecursively(@TempDir File dir) throws Exception {
    write(new File(dir, "src/one.txt"), "1");
    write(new File(dir, "src/nested/two.txt"), "2");

    ToolResult result = new FileCopyTool()
        .execute(args("src", "dst"), context(dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    assertTrue(new File(dir, "src/one.txt").exists(), "源目录必须还在");
    assertEquals("1", FileIo.readUtf8(new File(dir, "dst/one.txt")));
    assertEquals("2", FileIo.readUtf8(new File(dir, "dst/nested/two.txt")));
  }

  @Test
  void copyRefusesExistingTargetWithoutOverwrite(@TempDir File dir) throws Exception {
    write(new File(dir, "a.txt"), "A");
    write(new File(dir, "b.txt"), "B");

    ToolResult result = new FileCopyTool()
        .execute(args("a.txt", "b.txt"), context(dir.getAbsolutePath()));

    assertTrue(result.isError());
    assertEquals("B", FileIo.readUtf8(new File(dir, "b.txt")), "拒绝时不能碰目标");
    assertEquals("A", FileIo.readUtf8(new File(dir, "a.txt")));
  }

  /** 大文件复制不能整块读进内存（工具要在手机上跑，堆很有限）。 */
  @Test
  void copyLargeFileStreams(@TempDir File dir) throws Exception {
    byte[] payload = new byte[3 * 1024 * 1024];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = (byte) (i % 251);
    }
    File source = new File(dir, "big.bin");
    Files.write(source.toPath(), payload);

    ToolResult result = new FileCopyTool()
        .execute(args("big.bin", "copy.bin"), context(dir.getAbsolutePath()));

    assertFalse(result.isError(), result.getContent());
    assertEquals(payload.length, new File(dir, "copy.bin").length());
  }
}
