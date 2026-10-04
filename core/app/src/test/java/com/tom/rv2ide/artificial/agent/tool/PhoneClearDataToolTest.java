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

package com.tom.rv2ide.artificial.agent.tool;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.tom.rv2ide.BuildConfig;
import org.junit.Test;

/**
 * 锁定 {@code pm clear} 的包名防线。
 *
 * <p>这些断言值钱的原因：{@code pm clear} 破坏力等同卸载且不可逆，而它的入参来自模型。
 * 一旦防线被放松（例如有人为了「方便」去掉自保规则），agent 就可能清掉本应用的私有目录
 * ——那里装着 Android SDK、Termux rootfs 与 JDK，丢了无法从网络重建。
 */
public class PhoneClearDataToolTest {

  @Test
  public void acceptsOrdinaryThirdPartyPackages() {
    assertNull(PhoneClearDataTool.validatePackage("com.example.myapp"));
    assertNull(PhoneClearDataTool.validatePackage("org.fdroid.fdroid"));
    assertNull(PhoneClearDataTool.validatePackage("com.example"));
  }

  @Test
  public void rejectsSystemPackages() {
    for (String pkg :
        new String[] {
          "android",
          "android.media",
          "com.android.settings",
          "com.google.android.gms",
          "com.google.android.apps.maps",
        }) {
      assertNotNull("应拒绝系统包: " + pkg, PhoneClearDataTool.validatePackage(pkg));
    }
  }

  @Test
  public void rejectsMalformedPackages() {
    for (String pkg : new String[] {"a", "com", "com.1abc.x", "com..x", "com.example.", "com.a-b.c"}) {
      assertNotNull("应拒绝非法包名: " + pkg, PhoneClearDataTool.validatePackage(pkg));
    }
  }

  /**
   * 自保规则：绝不能清除本应用自身。
   *
   * <p>用 {@code BuildConfig.APPLICATION_ID} 而不是硬编码，因此这里也按同一常量断言——
   * 改包名（含 debug 后缀）后测试仍然有效。
   */
  @Test
  public void refusesToClearItself() {
    String self = BuildConfig.APPLICATION_ID;
    String rejection = PhoneClearDataTool.validatePackage(self);
    assertNotNull("必须拒绝清除本应用自身: " + self, rejection);
    assertTrue(rejection.contains(self));
  }
}
