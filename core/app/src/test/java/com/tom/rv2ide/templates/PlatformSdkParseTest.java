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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 平台目录名解析（`android-36` / `android-37.2`）。
 *
 * <p><b>为什么值得钉住</b>：这里的错误形态是**静默丢项**——早先用 `toIntOrNull()`
 * 解析 `"37.2"` 会得到 null，于是「用户装了 android-37.2 却在向导的构建 SDK 下拉里
 * 看不到它」，没有任何报错。这正是本轮修的问题，因此逐条锁死。
 *
 * <p>次要版本是 API 36 起才有的：平台目录名可能是 `android-37.2`，
 * 而生成的工程必须同时写 `compileSdkMinor`，否则 AGP 去找不存在的 `android-37`。
 */
public class PlatformSdkParseTest {

  @Test
  public void parsesPlainVersionWithoutMinor() {
    PlatformSdk sdk = AtcWizardDialogKt.parsePlatformDir("android-36");
    assertNotNull(sdk);
    assertEquals(36, sdk.getMajor());
    assertNull("不带 minor 的平台 minor 应为 null", sdk.getMinor());
  }

  @Test
  public void parsesVersionWithMinor() {
    // 核心用例：带 minor 的目录名不能被丢掉。
    PlatformSdk sdk = AtcWizardDialogKt.parsePlatformDir("android-37.2");
    assertNotNull("android-37.2 必须能解析出来", sdk);
    assertEquals(37, sdk.getMajor());
    assertEquals(Integer.valueOf(2), sdk.getMinor());
  }

  @Test
  public void parsesOtherMinorVariants() {
    // 36.1 也是真实存在的平台（API 36 的次要版本）。
    PlatformSdk sdk = AtcWizardDialogKt.parsePlatformDir("android-36.1");
    assertNotNull(sdk);
    assertEquals(36, sdk.getMajor());
    assertEquals(Integer.valueOf(1), sdk.getMinor());
  }

  @Test
  public void returnsNullForUnparseableNames() {
    // 不认识的形态跳过而不是当成 0 混进列表——否则下拉里会出现 "API 0: Android 0"。
    assertNull(AtcWizardDialogKt.parsePlatformDir("android-"));
    assertNull(AtcWizardDialogKt.parsePlatformDir("android-preview"));
    assertNull(AtcWizardDialogKt.parsePlatformDir("platforms"));
    assertNull(AtcWizardDialogKt.parsePlatformDir(""));
  }

  @Test
  public void labelUsesEnumDisplayNameForKnownApi() {
    // 已知 API 复用 Sdk 枚举的展示名，与「最低 SDK」下拉保持一致。
    String plain = new PlatformSdk(36, null).label();
    assertTrue("应含 API 36", plain.contains("API 36"));
    assertTrue("应含代号 BakLava", plain.contains("BakLava"));

    // API 37 无代号（Android 17 的主版本，Google 已停用甜点代号），
    // 展示名不应出现空括号 `()`。
    // 注意：37 是 Android 17，不是 Android 16 的次要版本——那个是 android-36.1。
    String withMinor = new PlatformSdk(37, 2).label();
    assertTrue("应含 API 37", withMinor.contains("API 37"));
    assertTrue("应补版本后缀 37.2", withMinor.contains("37.2"));
    assertTrue("API 37 应标为 Android 17", withMinor.contains("Android 17"));
    assertTrue("空代号不应留下空括号", !withMinor.contains("()"));
  }

  @Test
  public void labelFallsBackForUnknownApi() {
    // 枚举里没有的 API 不能崩，退到简单文案。
    String label = new PlatformSdk(99, null).label();
    assertTrue(label.contains("99"));
  }
}
