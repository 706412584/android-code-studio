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

import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * grep 工具的内容搜索语义。
 *
 * <p><b>为什么需要这组测试</b>：设备上只有 BSD grep 2.5.1（不支持 {@code |} 交替），
 * 本工具是为了补上这个短板而写的进程内实现。搜索的边界条件——二进制文件、超大文件、
 * 分页、glob 过滤——都是「写错了不会报错、只是悄悄少给结果」的那类问题，必须钉住。
 */
final class GrepToolTest {

  private static ToolContext context(Path root) {
    return ToolContext.builder().homePath(root.toString()).build();
  }

  private static ToolResult run(Path root, JSONObject args) {
    return new GrepTool().execute(args, context(root));
  }

  private static void write(Path dir, String name, String content) throws IOException {
    Path file = dir.resolve(name);
    Files.createDirectories(file.getParent() == null ? dir : file.getParent());
    Files.write(file, content.getBytes(StandardCharsets.UTF_8));
  }

  // ---- 基本搜索 ----

  @Test
  void findsFilesContainingThePattern(@TempDir Path root) throws IOException {
    write(root, "a.kt", "val x = 1\nfun alpha() {}\n");
    write(root, "b.kt", "fun beta() {}\n");

    ToolResult result = run(root, new JSONObject().put("pattern", "alpha"));
    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("a.kt"), "应找到 a.kt: " + result.getContent());
    assertFalse(result.getContent().contains("b.kt"), "b.kt 不含该模式");
  }

  /**
   * 正则交替是设备自带 grep 缺的能力，也是本工具存在的理由。
   *
   * <p>实测设备上的 BSD grep 2.5.1 对 {@code grep -E 'a|b'} 无输出，模型会误以为
   * 「没有匹配」而不是「这个 grep 不支持」。
   */
  @Test
  void supportsRegexAlternation(@TempDir Path root) throws IOException {
    write(root, "a.txt", "alpha\n");
    write(root, "b.txt", "beta\n");
    write(root, "c.txt", "gamma\n");

    ToolResult result = run(root, new JSONObject().put("pattern", "alpha|beta"));
    assertTrue(result.getContent().contains("a.txt"), result.getContent());
    assertTrue(result.getContent().contains("b.txt"), result.getContent());
    assertFalse(result.getContent().contains("c.txt"), "gamma 不该匹配");
  }

  @Test
  void contentModeShowsLineNumbersAndText(@TempDir Path root) throws IOException {
    write(root, "a.kt", "line one\nval target = 42\nline three\n");

    ToolResult result =
        run(root, new JSONObject().put("pattern", "target").put("output_mode", "content"));
    assertTrue(result.getContent().contains("a.kt:2:"), "应含 文件:行号: " + result.getContent());
    assertTrue(result.getContent().contains("val target = 42"), result.getContent());
  }

  @Test
  void countModeReportsPerFileCounts(@TempDir Path root) throws IOException {
    write(root, "a.txt", "hit\nhit\nmiss\nhit\n");

    ToolResult result =
        run(root, new JSONObject().put("pattern", "hit").put("output_mode", "count"));
    assertTrue(result.getContent().contains("a.txt:3"), "应报 3 处: " + result.getContent());
    assertTrue(result.getContent().contains("3 occurrence"), result.getContent());
  }

  @Test
  void caseInsensitiveFlagWorks(@TempDir Path root) throws IOException {
    write(root, "a.txt", "Hello World\n");

    ToolResult sensitive = run(root, new JSONObject().put("pattern", "hello"));
    assertTrue(sensitive.getContent().contains("No files found"), sensitive.getContent());

    ToolResult insensitive =
        run(root, new JSONObject().put("pattern", "hello").put("-i", true));
    assertTrue(insensitive.getContent().contains("a.txt"), insensitive.getContent());
  }

  @Test
  void noMatchesIsNotAnError(@TempDir Path root) throws IOException {
    write(root, "a.txt", "nothing here\n");
    ToolResult result = run(root, new JSONObject().put("pattern", "zzzz"));
    assertFalse(result.isError(), "无匹配不是错误: " + result.getContent());
    assertTrue(result.getContent().contains("No files found"), result.getContent());
  }

  // ---- 错误引导 ----

  /**
   * 把 glob 当正则用是高频失误，必须给出可操作的提示。
   *
   * <p>{@code *.kt} 在正则里是「量词 * 作用于行首」，Java 会直接抛语法错误。
   * 若只回一句「搜索失败」，模型会反复重试同一条表达式。
   */
  @Test
  void globLookingPatternGetsAnActionableHint(@TempDir Path root) {
    ToolResult result = run(root, new JSONObject().put("pattern", "*.kt"));
    assertTrue(result.isError(), "非法正则应报错");
    assertTrue(result.getContent().contains("正则"), "应说明用的是正则: " + result.getContent());
    assertTrue(result.getContent().contains("glob"), "应点明与 glob 的区别: " + result.getContent());
  }

  @Test
  void emptyPatternIsRejected(@TempDir Path root) {
    ToolResult result = run(root, new JSONObject().put("pattern", "   "));
    assertTrue(result.isError());
  }

  @Test
  void unknownOutputModeListsValidOnes(@TempDir Path root) {
    ToolResult result =
        run(root, new JSONObject().put("pattern", "x").put("output_mode", "bogus"));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("content"), result.getContent());
    assertTrue(result.getContent().contains("count"), result.getContent());
  }

  // ---- 过滤与排除 ----

  @Test
  void globFilterLimitsWhichFilesAreSearched(@TempDir Path root) throws IOException {
    write(root, "a.kt", "needle\n");
    write(root, "b.txt", "needle\n");

    ToolResult result =
        run(root, new JSONObject().put("pattern", "needle").put("glob", "*.kt"));
    assertTrue(result.getContent().contains("a.kt"), result.getContent());
    assertFalse(result.getContent().contains("b.txt"), "txt 应被 glob 过滤掉");
  }

  @Test
  void braceGlobExpandsToMultiplePatterns(@TempDir Path root) throws IOException {
    write(root, "a.kt", "needle\n");
    write(root, "b.java", "needle\n");
    write(root, "c.txt", "needle\n");

    ToolResult result =
        run(root, new JSONObject().put("pattern", "needle").put("glob", "*.{kt,java}"));
    assertTrue(result.getContent().contains("a.kt"), result.getContent());
    assertTrue(result.getContent().contains("b.java"), result.getContent());
    assertFalse(result.getContent().contains("c.txt"), result.getContent());
  }

  /**
   * 构建产物与 VCS 目录必须自动排除。
   *
   * <p>手机上 {@code build/} 动辄上千个 class/dex，不排除会把真实结果淹掉——
   * 而模型只会看到「一堆 build/ 下的路径」，据此判断代码结构必然出错。
   */
  @Test
  void buildAndVcsDirectoriesAreExcluded(@TempDir Path root) throws IOException {
    write(root, "src/Main.kt", "needle\n");
    write(root, "build/Generated.kt", "needle\n");
    write(root, ".git/config", "needle\n");
    write(root, "node_modules/pkg/index.js", "needle\n");

    ToolResult result = run(root, new JSONObject().put("pattern", "needle"));
    assertTrue(result.getContent().contains("Main.kt"), result.getContent());
    assertFalse(result.getContent().contains("Generated.kt"), "build/ 应被排除");
    assertFalse(result.getContent().contains(".git"), ".git 应被排除");
    assertFalse(result.getContent().contains("node_modules"), "node_modules 应被排除");
  }

  /** 二进制文件（含 NUL）跳过，并在结尾如实说明跳过了多少。 */
  @Test
  void binaryFilesAreSkippedAndReported(@TempDir Path root) throws IOException {
    write(root, "text.txt", "needle\n");
    Files.write(root.resolve("bin.dat"), new byte[] {'n', 'e', 0, 'x'});

    ToolResult result = run(root, new JSONObject().put("pattern", "needle"));
    assertTrue(result.getContent().contains("text.txt"), result.getContent());
    assertTrue(result.getContent().contains("跳过"), "应报告跳过的文件数: " + result.getContent());
  }

  // ---- 分页 ----

  @Test
  void headLimitTruncatesAndSaysSo(@TempDir Path root) throws IOException {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 20; i++) {
      sb.append("needle ").append(i).append('\n');
    }
    write(root, "a.txt", sb.toString());

    ToolResult result =
        run(
            root,
            new JSONObject()
                .put("pattern", "needle")
                .put("output_mode", "content")
                .put("head_limit", 5));
    String content = result.getContent();
    assertTrue(content.contains("showing 5 of 20"), "应说明被截断: " + content);
    assertTrue(content.contains("offset"), "应提示可用 offset 翻页: " + content);
  }

  @Test
  void offsetSkipsEarlierResults(@TempDir Path root) throws IOException {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 10; i++) {
      sb.append("needle ").append(i).append('\n');
    }
    write(root, "a.txt", sb.toString());

    ToolResult result =
        run(
            root,
            new JSONObject()
                .put("pattern", "needle")
                .put("output_mode", "content")
                .put("head_limit", 2)
                .put("offset", 5));
    String content = result.getContent();
    assertTrue(content.contains("needle 5"), "应从第 6 条开始: " + content);
    assertFalse(content.contains("needle 0"), "前面的应被跳过: " + content);
  }

  @Test
  void zeroHeadLimitMeansUnlimited(@TempDir Path root) throws IOException {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 300; i++) {
      sb.append("needle\n");
    }
    write(root, "a.txt", sb.toString());

    ToolResult result =
        run(
            root,
            new JSONObject()
                .put("pattern", "needle")
                .put("output_mode", "content")
                .put("head_limit", 0));
    assertFalse(result.getContent().contains("showing"), "0 表示不限，不该报截断");
  }

  // ---- 路径与默认值 ----

  @Test
  void defaultsToProjectRootWhenPathOmitted(@TempDir Path root) throws IOException {
    write(root, "nested/deep/a.txt", "needle\n");
    ToolResult result = run(root, new JSONObject().put("pattern", "needle"));
    assertTrue(result.getContent().contains("a.txt"), "应递归子目录: " + result.getContent());
  }

  @Test
  void canSearchASingleFile(@TempDir Path root) throws IOException {
    write(root, "a.txt", "needle\n");
    write(root, "b.txt", "needle\n");

    ToolResult result =
        run(root, new JSONObject().put("pattern", "needle").put("path", "a.txt"));
    assertTrue(result.getContent().contains("a.txt"), result.getContent());
    assertFalse(result.getContent().contains("b.txt"), "只应搜指定文件: " + result.getContent());
  }

  @Test
  void missingPathIsReported(@TempDir Path root) {
    ToolResult result =
        run(root, new JSONObject().put("pattern", "x").put("path", "does-not-exist"));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("不存在"), result.getContent());
  }

  /** 越界路径必须被拒绝——与其它文件工具同一条边界。 */
  @Test
  void pathOutsideWorkspaceIsRejected(@TempDir Path root) {
    ToolResult result =
        run(root, new JSONObject().put("pattern", "x").put("path", "../../etc"));
    assertTrue(result.isError(), "越界路径应被拒绝");
  }

  // ---- 工具元数据 ----

  @Test
  void isReadOnlyAndConcurrencySafe() {
    GrepTool tool = new GrepTool();
    assertEquals("grep", tool.getName());
    assertTrue(tool.isAllowedInReadonlyMode(), "只读模式应放行");
    assertTrue(tool.isConcurrencySafe(), "只读遍历可并发");
  }

  @Test
  void parameterSchemaDocumentsRegexAndOutputModes() throws Exception {
    JSONObject params = new GrepTool().getParameters();
    JSONObject props = params.getJSONObject("properties");

    assertTrue(
        props.getJSONObject("pattern").getString("description").contains("正则"),
        "必须说明 pattern 是正则而非 glob");
    String modeDesc = props.getJSONObject("output_mode").getString("description");
    assertTrue(modeDesc.contains("content"), modeDesc);
    assertTrue(modeDesc.contains("count"), modeDesc);
    assertTrue(modeDesc.contains("files_with_matches"), modeDesc);
    assertEquals("pattern", params.getJSONArray("required").getString(0));
  }
}
