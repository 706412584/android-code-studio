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

import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link GlobTool} 的文件匹配：通配符语义、隐藏目录跳过、无匹配与错误文案。
 *
 * <p>glob 的失败模式是静默的：`*` 与 `**` 写错时结果只是「少了几个文件」，
 * 模型据此判断「这个文件不存在」——比报错更糟。这里把两种通配符的语义钉住，
 * 并确认 `.git`/`node_modules` 这类目录不会被递归进去（否则一个 glob 能扫出十万文件）。
 */
final class GlobToolTest {

  private static ToolContext ctx(Path home) {
    return ToolContext.builder().homePath(home.toString()).build();
  }

  private static void touch(Path file) throws Exception {
    Files.createDirectories(file.getParent());
    Files.writeString(file, "x");
  }

  @Test
  void singleStarMatchesBasenameAtAnyDepth(@TempDir Path home) throws Exception {
    // 无 `/` 的 pattern 按**文件名**匹配（递归全树）——`*.java` 会命中子目录里的
    // c.java。这是刻意的：模型写 *.java 时意图是「找所有 java 文件」而非只看根层。
    touch(home.resolve("a.java"));
    touch(home.resolve("b.txt"));
    touch(home.resolve("sub/c.java"));

    ToolResult result = new GlobTool()
        .execute(new JSONObject().put("pattern", "*.java"), ctx(home));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("a.java"), result.getContent());
    assertTrue(result.getContent().contains("sub/c.java"), result.getContent());
    assertFalse(result.getContent().contains("b.txt"), result.getContent());
  }

  @Test
  void doubleStarMatchesInSubdirectoriesButNotRootLevel(@TempDir Path home) throws Exception {
    // 实现语义：`**/*.java` 的 `/` 是必需的——命中子目录，根层文件不命中
    // （正则 ^.*/[^/]*\.java$ 要求至少一级目录）。要找根层+全树用 *.java。
    touch(home.resolve("sub/deep/c.java"));
    touch(home.resolve("top.java"));

    ToolResult result = new GlobTool()
        .execute(new JSONObject().put("pattern", "**/*.java"), ctx(home));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("sub/deep/c.java"), result.getContent());
    assertFalse(result.getContent().contains("top.java"), "**/ 前缀要求至少一级目录");
  }

  @Test
  void questionMarkMatchesSingleChar(@TempDir Path home) throws Exception {
    touch(home.resolve("a1.txt"));
    touch(home.resolve("a12.txt"));

    ToolResult result = new GlobTool()
        .execute(new JSONObject().put("pattern", "a?.txt"), ctx(home));

    assertTrue(result.getContent().contains("a1.txt"), result.getContent());
    assertFalse(result.getContent().contains("a12.txt"), result.getContent());
  }

  @Test
  void hiddenAndNodeModulesDirectoriesAreSkipped(@TempDir Path home) throws Exception {
    touch(home.resolve(".git/config"));
    touch(home.resolve("node_modules/dep/index.js"));
    touch(home.resolve("src/real.java"));

    ToolResult result = new GlobTool()
        .execute(new JSONObject().put("pattern", "**/*"), ctx(home));

    assertFalse(result.getContent().contains(".git"), result.getContent());
    assertFalse(result.getContent().contains("node_modules"), result.getContent());
    assertTrue(result.getContent().contains("src/real.java"), result.getContent());
  }

  @Test
  void noMatchIsNotAnError(@TempDir Path home) throws Exception {
    touch(home.resolve("a.txt"));

    ToolResult result = new GlobTool()
        .execute(new JSONObject().put("pattern", "*.zzz"), ctx(home));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("zzz"), result.getContent());
  }

  @Test
  void missingRootIsReported(@TempDir Path home) {
    ToolResult result = new GlobTool()
        .execute(
            new JSONObject().put("pattern", "*.java").put("path", "no/such/dir"),
            ctx(home));
    assertTrue(result.isError(), result.getContent());
  }

  @Test
  void blankPatternIsRejected(@TempDir Path home) {
    ToolResult result = new GlobTool().execute(new JSONObject().put("pattern", ""), ctx(home));
    assertTrue(result.isError());
  }

  @Test
  void metadataAndConcurrency() {
    GlobTool tool = new GlobTool();
    assertEquals("glob", tool.getName());
    assertTrue(tool.isConcurrencySafe(), "只读搜索可并发");
    assertEquals(
        com.tom.rv2ide.ai.tool.api.ToolDisplayCategory.READ, tool.getDisplayCategory());
  }

  @Test
  void regexMetacharactersInPatternAreEscaped(@TempDir Path home) throws Exception {
    // 文件名里的点/括号必须按字面量匹配，而不是正则元字符。
    touch(home.resolve("a.b.java"));
    touch(home.resolve("axb.java"));

    ToolResult result = new GlobTool()
        .execute(new JSONObject().put("pattern", "a.b.java"), ctx(home));

    assertTrue(result.getContent().contains("a.b.java"), result.getContent());
    assertFalse(result.getContent().contains("axb.java"), "点必须按字面量匹配");
  }
}
