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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证工作区路径限制。
 *
 * <p>这是模型可控输入的安全边界：模型给出的路径不能逃出用户打开的工作区。
 * 用例覆盖相对路径穿越、符号链接逃逸、以及同前缀目录绕过。
 */
final class FileToolPathPolicyTest {

  @Test
  void resolvesRelativePathInsideWorkspace(@TempDir Path workspace) throws IOException {
    File resolved = FileToolPathPolicy.resolve(workspace.toString(), "src/Main.java");

    assertTrue(
        resolved.getPath().startsWith(workspace.toRealPath().toString()),
        "解析结果应位于工作区内: " + resolved);
  }

  @Test
  void resolvesEmptyPathToWorkspaceRoot(@TempDir Path workspace) throws IOException {
    File resolved = FileToolPathPolicy.resolve(workspace.toString(), "");

    assertEquals(workspace.toRealPath().toFile(), resolved);
  }

  @Test
  void rejectsParentTraversalEscape(@TempDir Path workspace) throws IOException {
    // 构造一个真实存在的父级，确保失败原因是策略而非路径不存在
    Path outside = workspace.getParent();

    IOException error =
        assertThrows(
            IOException.class,
            () -> FileToolPathPolicy.resolve(workspace.toString(), "../" + outside.getFileName()));

    assertTrue(
        error.getMessage().contains("outside the current workspace"),
        "错误信息应说明越界: " + error.getMessage());
  }

  @Test
  void rejectsAbsolutePathOutsideWorkspace(@TempDir Path workspace) throws IOException {
    Path outside = Files.createTempDirectory("acs-outside");

    assertThrows(
        IOException.class,
        () -> FileToolPathPolicy.resolve(workspace.toString(), outside.toString()));
  }

  @Test
  void rejectsSiblingDirectoryWithSharedPrefix(@TempDir Path parent) throws IOException {
    // workspace 与 workspace-evil 共享前缀；仅靠 startsWith 的朴素实现会误放行。
    Path workspace = Files.createDirectory(parent.resolve("workspace"));
    Path evil = Files.createDirectory(parent.resolve("workspace-evil"));

    assertThrows(
        IOException.class,
        () -> FileToolPathPolicy.resolve(workspace.toString(), evil.toString()));
  }

  @Test
  void rejectsSymlinkEscapingWorkspace(@TempDir Path workspace) throws IOException {
    Path outside = Files.createTempDirectory("acs-outside");
    Files.writeString(outside.resolve("secret.txt"), "secret");
    Path link = workspace.resolve("link");
    try {
      Files.createSymbolicLink(link, outside);
    } catch (UnsupportedOperationException | IOException e) {
      // 平台不支持符号链接（如无权限的 Windows），跳过该用例
      return;
    }

    assertThrows(
        IOException.class, () -> FileToolPathPolicy.resolve(workspace.toString(), "link/secret.txt"));
  }

  @Test
  void allowsExtraWriteRoot(@TempDir Path parent) throws IOException {
    Path workspace = Files.createDirectory(parent.resolve("ws"));
    Path skills = Files.createDirectory(parent.resolve("skills"));
    ToolContext context =
        ToolContext.builder()
            .homePath(workspace.toString())
            .extraWriteRoots(java.util.List.of(skills.toString()))
            .build();

    File resolved = FileToolPathPolicy.resolve(context, skills.resolve("a.txt").toString());

    assertTrue(resolved.getPath().startsWith(skills.toRealPath().toString()));
  }

  @Test
  void bypassFlagDisablesProtection(@TempDir Path parent) throws IOException {
    Path workspace = Files.createDirectory(parent.resolve("ws"));
    Path outside = Files.createDirectory(parent.resolve("outside"));
    ToolContext context =
        ToolContext.builder().homePath(workspace.toString()).bypassPathProtection(true).build();

    File resolved = FileToolPathPolicy.resolve(context, outside.toString());

    assertEquals(outside.toRealPath().toFile(), resolved);
  }

  @Test
  void rejectsEmptyWorkspacePath() {
    assertThrows(IOException.class, () -> FileToolPathPolicy.resolve("", "a.txt"));
  }

  @Test
  void isInsideRequiresSeparatorBoundary() {
    assertTrue(FileToolPathPolicy.isInside(new File("/w/ws"), new File("/w/ws/a")));
    assertTrue(FileToolPathPolicy.isInside(new File("/w/ws"), new File("/w/ws")));
    assertFalse(
        FileToolPathPolicy.isInside(new File("/w/ws"), new File("/w/ws-evil/a")),
        "同前缀目录不应被视为内部");
  }
}
