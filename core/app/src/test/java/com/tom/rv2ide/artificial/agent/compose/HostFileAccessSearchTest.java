/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from Aharou (https://github.com/520huxiangli/Aharou),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

package com.tom.rv2ide.artificial.agent.compose;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.tom.rv2ide.artificial.agent.compose.compat.HostFileAccessProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@link HostFileAccessProvider} 扩展三件套（listFilesRecursive / fileSize / readLines）
 * 的回归测试。这是波次 3 WorkspaceSearchEngine 的数据面——语义错了搜索结果会静默错序
 * 或漏文件。
 */
public class HostFileAccessSearchTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private static void write(File f, String content) throws Exception {
    Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void listFilesRecursive_returnsRelativePaths_skipsGit() throws Exception {
    File root = tmp.newFolder("ws");
    new File(root, "app/src/main").mkdirs();
    write(new File(root, "app/src/main/A.kt"), "class A");
    write(new File(root, "README.md"), "hi");
    new File(root, ".git").mkdirs();
    write(new File(root, ".git/HEAD"), "ref"); // 必须被跳过

    HostFileAccessProvider provider = new HostFileAccessProvider(root);
    List<String> files = provider.listFilesRecursive("~/workspace", 8);

    assertTrue(files.contains("app/src/main"));
    assertTrue(files.contains("app/src/main/A.kt"));
    assertTrue(files.contains("README.md"));
    for (String f : files) {
      assertTrue("must not include .git: " + f, !f.startsWith(".git") && !f.contains("/.git/"));
    }
  }

  @Test
  public void listFilesRecursive_respectsMaxDepth() throws Exception {
    File root = tmp.newFolder("ws2");
    new File(root, "a/b/c/d").mkdirs();
    write(new File(root, "a/b/c/d/deep.txt"), "x");

    HostFileAccessProvider provider = new HostFileAccessProvider(root);
    // 深度 2：只展开到 a/b，d 下的文件不出现
    List<String> shallow = provider.listFilesRecursive("~/workspace", 2);
    assertTrue(!shallow.contains("a/b/c/d/deep.txt"));
    List<String> deep = provider.listFilesRecursive("~/workspace", 8);
    assertTrue(deep.contains("a/b/c/d/deep.txt"));
  }

  @Test
  public void fileSize_zeroForMissingOrDirectory() throws Exception {
    File root = tmp.newFolder("ws3");
    write(new File(root, "f.txt"), "12345");
    new File(root, "dir").mkdirs();
    HostFileAccessProvider provider = new HostFileAccessProvider(root);
    assertEquals(5L, provider.fileSize("~/workspace/f.txt"));
    assertEquals(0L, provider.fileSize("~/workspace/dir"));
    assertEquals(0L, provider.fileSize("~/workspace/nope.txt"));
  }

  @Test
  public void readLines_nullForMissing_contentForExisting() throws Exception {
    File root = tmp.newFolder("ws4");
    write(new File(root, "a.txt"), "line1\nline2");
    HostFileAccessProvider provider = new HostFileAccessProvider(root);
    assertEquals(Arrays.asList("line1", "line2"), provider.readLines("~/workspace/a.txt"));
    assertNull(provider.readLines("~/workspace/missing.txt"));
  }

  @Test
  public void absoluteHostPathsStillWork() throws Exception {
    // ACS 的工具产出宿主绝对路径；同一 provider 必须两种路径都能处理
    File root = tmp.newFolder("ws5");
    File f = new File(root, "abs.txt");
    write(f, "abs");
    HostFileAccessProvider provider = new HostFileAccessProvider(root);
    assertEquals(3L, provider.fileSize(f.getAbsolutePath()));
    assertEquals(Arrays.asList("abs"), provider.readLines(f.getAbsolutePath()));
  }
}
