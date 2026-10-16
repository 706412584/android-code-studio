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
 * along with AndroidCodeStudio.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 相对路径显示（{@code displayPath}）的行为。
 *
 * <p>模型看到的路径形态直接决定它下一次能不能找到文件：绝对路径泄漏会让输出冗长、
 * 跨机器不可复现；相对路径归一（{@code ./}、末尾斜杠、{@code file://} 前缀）不做的话
 * 同一文件在输出里会以多种形态出现，模型会以为是不同文件。
 *
 * <p>该逻辑在 {@code FileReadTool} 与 {@code ListDirectoryTool} 里各有一份**完全相同**的
 * 拷贝。这里既测行为、也测两份实现的一致性——将来只改一份就是分叉 bug。
 */
final class DisplayPathTest {

  /** 通过反射调用私有 displayPath（两份实现签名相同）。 */
  private static String call(Object tool, String workspace, String path) throws Exception {
    Method m = tool.getClass().getDeclaredMethod("displayPath", String.class, String.class);
    m.setAccessible(true);
    return (String) m.invoke(null, workspace, path);
  }

  private static List<Object> implementations() {
    List<Object> tools = new ArrayList<>();
    tools.add(new FileReadTool());
    tools.add(new ListDirectoryTool());
    return tools;
  }

  private static final String WS = "/workspace";

  private static final String[][] CASES = {
    // {输入, 期望}
    {"", ""},
    {"   ", ""},
    {"./a.txt", "a.txt"},
    {"././b/c.txt", "b/c.txt"},
    {"a.txt", "a.txt"},
    {"sub/dir/", "sub/dir"},
    {"sub\\dir\\file.txt", "sub/dir/file.txt"},
    {"/workspace/src/Main.kt", "src/Main.kt"},
    {"/workspace", "."},
    {"/workspace/", "."},
    {"/elsewhere/x.txt", "/elsewhere/x.txt"},
    {"file:///workspace/a.txt", "a.txt"},
  };

  @Test
  void displayPathBehaviorIsIdenticalInBothTools() throws Exception {
    for (Object tool : implementations()) {
      String toolName = tool.getClass().getSimpleName();
      for (String[] c : CASES) {
        String actual = call(tool, WS, c[0]);
        assertEquals(c[1], actual,
            toolName + ".displayPath(" + c[0] + ") 期望 " + c[1] + " 实际 " + actual);
      }
    }
  }

  @Test
  void nullWorkspaceFallsBackToAbsolutePath() throws Exception {
    // 无工作区（后台调用）时绝对路径原样保留——此时没有可相对的基准。
    for (Object tool : implementations()) {
      assertEquals("/abs/x.txt", call(tool, null, "/abs/x.txt"));
    }
  }

  @Test
  void nonAbsoluteWorkspaceDoesNotStripAnything() throws Exception {
    // 工作区本身不是绝对路径 → 不做相对换算（避免误剥）。
    for (Object tool : implementations()) {
      assertEquals("/a/b.txt", call(tool, "relative/ws", "/a/b.txt"));
    }
  }

  @Test
  void readToolReportsRelativePathInOutput(@TempDir Path root) throws Exception {
    // 端到端确认：大文件走 KB 分段时，结果里出现的是相对路径（而非 /tmp/xxx
    // 这种机器相关路径）——绝对路径泄漏会让输出冗长且跨机器不可复现。
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 60000; i++) {
      sb.append("line-").append(i).append('\n');
    }
    Files.writeString(root.resolve("big.txt"), sb.toString());
    FileReadTool tool = new FileReadTool();
    ToolResult r =
        tool.execute(
            new JSONObject().put("file_path", "big.txt").put("start_kb", 0).put("end_kb", 1),
            ToolContext.builder().homePath(root.toString()).build());
    assertTrue(!r.isError(), r.getContent());
    assertTrue(!r.getContent().contains(root.toString()),
        "结果不应泄漏绝对路径：" + r.getContent().substring(0, Math.min(300, r.getContent().length())));
  }

  @Test
  void listDirectoryReportsRelativePaths(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("a.txt"), "x");
    Files.createDirectories(root.resolve("sub"));
    ListDirectoryTool tool = new ListDirectoryTool();
    ToolResult r =
        tool.execute(
            new JSONObject().put("path", "."),
            ToolContext.builder().homePath(root.toString()).build());
    assertTrue(!r.isError(), r.getContent());
    assertTrue(r.getContent().contains("a.txt"), r.getContent());
    assertTrue(r.getContent().contains("sub"), r.getContent());
    assertTrue(!r.getContent().contains(root.toString()),
        "目录列表不应泄漏绝对路径：" + r.getContent());
  }
}
