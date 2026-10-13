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

package com.tom.rv2ide.cloudbuild;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 仓库名派生的回归测试。
 *
 * <p>仓库名会被 GitHub 做合法性校验（非法字符 → 422），而这个函数是「一键建仓」
 * 的第一环——它产出非法名时用户看到的是 GitHub 的原始报错，很难自己联想到
 * 「项目名里有特殊字符」。边界因此值得钉住。
 */
public class DeriveRepoNameTest {

  @Test
  public void camelCaseBecomesLowercaseHyphen() {
    assertEquals("my-flutter", CloudBuildService.deriveRepoName("MyFlutter"));
    assertEquals("compose-multiplatform", CloudBuildService.deriveRepoName("ComposeMultiplatform"));
  }

  @Test
  public void alreadyLowercaseStays() {
    assertEquals("myapp", CloudBuildService.deriveRepoName("myapp"));
  }

  @Test
  public void spacesAndSpecialCharsBecomeHyphen() {
    assertEquals("my-app", CloudBuildService.deriveRepoName("My App"));
    assertEquals("my-app", CloudBuildService.deriveRepoName("My@App!"));
    assertEquals("a-b", CloudBuildService.deriveRepoName("a---b"));
  }

  @Test
  public void leadingAndTrailingDotsAndHyphensStripped() {
    // GitHub 不允许名字以点/连字符开头；首尾的点与连字符都剥掉。
    // 注意 `-MyApp-` → `-my-app-` → 剥首尾得 `my-app`：**中间的连字符保留**，
    // 它是合法字符（驼峰转换的产物），吞掉会让名字与项目名对不上。
    assertEquals("my-app", CloudBuildService.deriveRepoName(".MyApp"));
    assertEquals("my-app", CloudBuildService.deriveRepoName("MyApp."));
    assertEquals("my-app", CloudBuildService.deriveRepoName("-MyApp-"));
  }

  @Test
  public void emptyOrAllInvalidFallsBack() {
    assertEquals("acs-project", CloudBuildService.deriveRepoName(""));
    assertEquals("acs-project", CloudBuildService.deriveRepoName("   "));
    assertEquals("acs-project", CloudBuildService.deriveRepoName("中文名"));
    assertEquals("acs-project", CloudBuildService.deriveRepoName(null));
  }

  @Test
  public void resultIsAlwaysGitHubLegal() {
    // GitHub 规则：字母数字与 . _ -，且不以点/连字符开头。
    String[] inputs = {"MyFlutter", "我的项目", "a b c", "___", "App.v2", "X"};
    for (String input : inputs) {
      String name = CloudBuildService.deriveRepoName(input);
      assertFalse("结果不能为空：" + input, name.isEmpty());
      assertTrue("只允许 [a-z0-9._-]：" + name, name.matches("[a-z0-9._-]+"));
      assertFalse("不能以点或连字符开头：" + name, name.startsWith(".") || name.startsWith("-"));
    }
  }
}
