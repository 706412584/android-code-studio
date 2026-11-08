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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.templates;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 模板创建失败的提示文案选择。
 *
 * <p><b>为什么这条值得钉住</b>：它的错误形态是「静默」——用户点了创建、界面回到首页、
 * 什么都没发生，完全不知道出了什么事。这正是「RN / Flutter / LibGDX 模板创建静默失败」
 * 那份报告的来源：失败回调那条路上，`message` 被整个丢掉（`TemplateListFragment` 的空
 * 实现），或者压根没有接收方（`AtcInterface().create(this)` 不传 listener）。
 *
 * <p>所以两件事必须锁死：
 * <ul>
 *   <li>有具体原因时**必须**展示具体原因——只给「创建失败」等于把排查线索扔掉
 *       （实测里那个原因就是 `FileNotFoundException: /path/...`，直接指到问题所在）；
 *   <li>没有原因时要有通用兜底——不能弹一个空对话框。
 * </ul>
 */
public class TemplateCreateFailureTest {

  private static final String FALLBACK = "项目创建失败。";

  @Test
  public void keepsConcreteReasonWhenPresent() {
    String reason =
        "Error: /data/user/0/com.tom.rv2ide/files/home/ACSProjects/MyApp/android/"
            + "settings.gradle.kts: open failed: ENOENT (No such file or directory)";
    assertEquals(reason, TemplateCreateFailure.resolveMessage(reason, FALLBACK));
  }

  @Test
  public void fallsBackWhenMessageIsNullOrBlank() {
    assertEquals(FALLBACK, TemplateCreateFailure.resolveMessage(null, FALLBACK));
    assertEquals(FALLBACK, TemplateCreateFailure.resolveMessage("", FALLBACK));
    // 只有空白的消息同样没有信息量，不能拿它当正文（会弹出一个几乎空白的对话框）。
    assertEquals(FALLBACK, TemplateCreateFailure.resolveMessage("   \n\t ", FALLBACK));
  }
}
