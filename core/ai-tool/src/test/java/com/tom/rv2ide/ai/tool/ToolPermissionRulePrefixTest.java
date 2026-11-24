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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolNames;
import org.junit.jupiter.api.Test;

/**
 * 「命令前缀」规则键的生成边界：只有「可执行名 + 子命令」的简单 shell 命令
 * 才生成前缀键，复合命令、解释器、flag 形态、ssh 后端一律不生成。
 *
 * <p>这些断言是安全边界：任何一条被放宽，都等于把「同类命令始终允许」放大成
 * 更大的授权面（整条复合命令、整个解释器、或 uid 0）。
 */
final class ToolPermissionRulePrefixTest {

  private static String key(String command) {
    return ToolPermissionRule.prefixKeyFor(
        ToolNames.SHELL_EXECUTE, "{\"command\":\"" + command + "\"}");
  }

  @Test
  void twoWordPrefixIsGenerated() {
    assertEquals("shell_execute\u0000prefix:git status", key("git status"));
    // 变体参数（flag、路径）不改变前缀
    assertEquals("shell_execute\u0000prefix:git status", key("git status -sb"));
    assertEquals("shell_execute\u0000prefix:git status", key("git  status   --porcelain"));
  }

  @Test
  void gradlewTaskNameKeepsColons() {
    assertEquals(
        "shell_execute\u0000prefix:./gradlew :core:app:assembleDebug",
        key("./gradlew :core:app:assembleDebug --offline"));
  }

  @Test
  void flagAsSecondTokenYieldsNoPrefix() {
    // 「git --version」若取首词就是「git」整包授权，连带放行 git push --force
    assertEquals("", key("git --version"));
    assertEquals("", key("./gradlew --offline"));
  }

  @Test
  void singleWordCommandYieldsNoPrefix() {
    // 单词命令的前缀与全文同义，不需要第二维度
    assertEquals("", key("ls"));
    assertEquals("", key(""));
  }

  @Test
  void compoundCommandsYieldNoPrefix() {
    assertEquals("", key("git status && rm -rf x"));
    assertEquals("", key("echo hi | cat"));
    assertEquals("", key("echo hi > /sdcard/f"));
    assertEquals("", key("echo `id`"));
    assertEquals("", key("echo $(id)"));
    assertEquals("", key("git status &"));
    // 引号内的元字符也拒绝：方向是保守（退回全文规则），不是误放行
    assertEquals("", key("git commit -m \"a > b\""));
  }

  @Test
  void multiLineCommandYieldsNoPrefix() {
    // 换行会在 normalize 时被压成空格，必须在归一之前拒绝多命令
    assertEquals(
        "", ToolPermissionRule.prefixKeyFor(ToolNames.SHELL_EXECUTE, "{\"command\":\"git status\\nrm -rf x\"}"));
  }

  @Test
  void interpretersAndWrappersYieldNoPrefix() {
    assertEquals("", key("bash build.sh"));
    assertEquals("", key("python3 x.py"));
    assertEquals("", key("sudo git status"));
    assertEquals("", key("env FOO=1 git status"));
    // basename 归一：带路径的解释器同样拒绝
    assertEquals("", key("/usr/bin/python3 x.py"));
  }

  @Test
  void unsafeWordsYieldNoPrefix() {
    // 引号 / 变量替换无法静态判定语义
    assertEquals("", key("git \"status\""));
    assertEquals("", key("git $CMD"));
  }

  @Test
  void backendIsPartOfTheKey() {
    String plain = ToolPermissionRule.prefixKeyFor(
        ToolNames.SHELL_EXECUTE, "{\"command\":\"git status\"}");
    String root = ToolPermissionRule.prefixKeyFor(
        ToolNames.SHELL_EXECUTE, "{\"command\":\"git status\",\"backend\":\"root\"}");
    assertNotEquals(plain, root, "Termux 与 root 必须是不同的授权对象（防提权）");
    assertTrue(root.endsWith("root"));
  }

  @Test
  void sshBackendYieldsNoPrefix() {
    assertEquals(
        "",
        ToolPermissionRule.prefixKeyFor(
            ToolNames.SHELL_EXECUTE,
            "{\"command\":\"git status\",\"backend\":\"ssh\",\"sshHost\":\"a:22\"}"));
  }

  @Test
  void nonShellToolsYieldNoPrefix() {
    assertEquals(
        "", ToolPermissionRule.prefixKeyFor(ToolNames.FILE_WRITE, "{\"file_path\":\"a\"}"));
    assertEquals("", ToolPermissionRule.prefixKeyFor("", "{}"));
    assertEquals("", ToolPermissionRule.prefixKeyFor(ToolNames.SHELL_EXECUTE, "not json"));
  }

  @Test
  void aliasIsCanonicalizedBeforeKeying() {
    // 模型写 bash 别名时，键仍以规范名 shell_execute 开头
    assertEquals(
        "shell_execute\u0000prefix:git status",
        ToolPermissionRule.prefixKeyFor("bash", "{\"command\":\"git status\"}"));
  }

  @Test
  void busyboxNiceAndCommandYieldNoPrefix() {
    // 独立验证代理发现的绕过：busybox/toybox 的子命令是解释器入口，
    // nice/command 不改变危险度——都必须拒生成前缀键。
    assertEquals("", key("busybox sh ok.sh"));
    assertEquals("", key("toybox sh ok.sh"));
    assertEquals("", key("nice git status"));
    assertEquals("", key("command git status"));
  }

  @Test
  void describeMarksPrefixRules() {
    String described = ToolPermissionRule.describe(key("git status"));
    assertTrue(described.contains("git status"), described);
    assertTrue(described.contains("同类前缀"), "前缀规则必须在设置页与逐字规则可区分: " + described);
  }
}
