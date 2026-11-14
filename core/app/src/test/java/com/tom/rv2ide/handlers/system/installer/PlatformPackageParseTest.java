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

package com.tom.rv2ide.handlers.system.installer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * `sdkmanager --list` 输出里的平台版本解析。
 *
 * <p><b>为什么值得钉住</b>：这里的错误形态同样是**静默丢项 + 拼错包名**。早先的正则
 * `android-[0-9]+` 只截取整数部分，`46 / 37.0 / 37.1 / 37.2` 中带小数的全部被截成
 * `"37"`、`distinct()` 后只剩一个 chip；用户点它时 SDK 管理器拼出
 * `platforms;android-37`，而官方仓库里没有这个包——安装必然失败，且错误信息只说
 * 「找不到包」，看不出是解析把版本号截断了。
 *
 * <p>这些用例直接喂 sdkmanager 的真实输出片段，锁住「带 minor 的包必须保留原样」。
 */
public class PlatformPackageParseTest {

  /** `sdkmanager --list` 输出的真实形态（节选自官方仓库 stable 通道）。 */
  private static final String SDK_LIST_OUTPUT =
      "Available Packages:\n"
          + "  Path                 | Version | Description                | Location\n"
          + "  -------              | ------- | -------                    | -------\n"
          + "  build-tools;37.0.0   | 37.0.0  | Android SDK Build-Tools 37 | build-tools/37.0.0\n"
          + "  platforms;android-35 | 2       | Android SDK Platform 35    | platforms/android-35\n"
          + "  platforms;android-36 | 2       | Android SDK Platform 36    | platforms/android-36\n"
          + "  platforms;android-36.1 | 1     | Android SDK Platform 36.1  | platforms/android-36.1\n"
          + "  platforms;android-37.0 | 1     | Android SDK Platform 37.0  | platforms/android-37.0\n"
          + "  platforms;android-37.1 | 1     | Android SDK Platform 37.1  | platforms/android-37.1\n"
          + "  platforms;android-37.2 | 2     | Android SDK Platform 37.2  | platforms/android-37.2\n"
          + "  ndk;26.1.10909125    | 26.1.10909125 | NDK (Side by side)   | ndk/26.1.10909125\n"
          + "\nInstalled Packages:\n"
          + "  platforms;android-33 | 3       | Android SDK Platform 33    | platforms/android-33\n";

  @Test
  public void keepsMinorVersionsInsteadOfTruncating() {
    // 核心用例：37.0 / 37.1 / 37.2 必须是三个独立条目，不能被截成同一个 "37"。
    List<String> platforms = SdkManagerInstallerKt.parsePlatformPackages(SDK_LIST_OUTPUT);
    assertTrue("必须保留 android-37.2", platforms.contains("37.2"));
    assertTrue("必须保留 android-37.1", platforms.contains("37.1"));
    assertTrue("必须保留 android-37.0", platforms.contains("37.0"));
    assertTrue("必须保留 android-36.1", platforms.contains("36.1"));
  }

  @Test
  public void stripsAndroidPrefixSoPackageIdCanBeRebuilt() {
    // SDK 管理器的 packageId() 会补回 "platforms;android-"，因此这里不能带前缀，
    // 也不能带 minor 之外的任何修饰。
    List<String> platforms = SdkManagerInstallerKt.parsePlatformPackages(SDK_LIST_OUTPUT);
    for (String p : platforms) {
      assertFalse("不应带 android- 前缀：" + p, p.startsWith("android-"));
    }
  }

  @Test
  public void sortsAscendingByNumericSegments() {
    // 字符串排序会把 "37" 排在 "36" 前面（"3" == "3"，再比 "7" > "6" 是对的，
    // 但 "9" > "10" 才是经典错例）。这里确认按数字段升序，且 37.x 在 36.x 之后。
    List<String> platforms = SdkManagerInstallerKt.parsePlatformPackages(SDK_LIST_OUTPUT);
    assertEquals(
        Arrays.asList("33", "35", "36", "36.1", "37.0", "37.1", "37.2"), platforms);
  }

  @Test
  public void excludesRcPackages() {
    String withRc =
        "  platforms;android-37.3-rc01 | 1 | preview | platforms/android-37.3-rc01\n"
            + "  platforms;android-36 | 2 | stable | platforms/android-36\n";
    List<String> platforms = SdkManagerInstallerKt.parsePlatformPackages(withRc);
    assertFalse("rc 包应被过滤：" + platforms, platforms.toString().contains("rc"));
    assertTrue(platforms.contains("36"));
  }

  @Test
  public void ignoresUnrelatedPackages() {
    // build-tools / ndk 里有 "android-" 之外的字符串，不应被这条正则吃进来。
    List<String> platforms = SdkManagerInstallerKt.parsePlatformPackages(SDK_LIST_OUTPUT);
    for (String p : platforms) {
      assertTrue("只应出现纯版本号：" + p, p.matches("[0-9]+(\\.[0-9]+)?"));
    }
  }

  @Test
  public void returnsEmptyForNoPlatforms() {
    // 空输入不能崩（sdkmanager 输出为空或只有表头）。
    assertTrue(SdkManagerInstallerKt.parsePlatformPackages("").isEmpty());
    assertTrue(SdkManagerInstallerKt.parsePlatformPackages("Available Packages:\n").isEmpty());
  }

  // ---------------------------------------------------------------------------
  // 以下锁住「旧版 sdkmanager 列不出 37.x 时，直接读 Google 仓库索引」这条回退路径。
  // ---------------------------------------------------------------------------

  /** Google repository2-3.xml 的真实形态（节选，属性换行/缩进照抄）。 */
  private static final String REPOSITORY_XML =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          + "<sdk:sdk-repository xmlns:sdk=\"http://schemas.android.com/sdk/android/repo/repository2/03\">\n"
          + "  <remotePackage path=\"build-tools;37.0.0\">\n"
          + "    <revision><major>37</major><minor>0</minor><micro>0</micro></revision>\n"
          + "  </remotePackage>\n"
          + "  <remotePackage path=\"platforms;android-35\">\n"
          + "    <type-details xsi:type=\"sdk:platformDetailsType\"><api-level>35</api-level></type-details>\n"
          + "  </remotePackage>\n"
          + "  <remotePackage path=\"platforms;android-36\">\n"
          + "    <type-details xsi:type=\"sdk:platformDetailsType\"><api-level>36</api-level></type-details>\n"
          + "  </remotePackage>\n"
          + "  <remotePackage path=\"platforms;android-36.1\">\n"
          + "    <type-details xsi:type=\"sdk:platformDetailsType\"><api-level>36.1</api-level></type-details>\n"
          + "  </remotePackage>\n"
          + "  <remotePackage path=\"platforms;android-37.0\">\n"
          + "    <type-details xsi:type=\"sdk:platformDetailsType\"><api-level>37.0</api-level></type-details>\n"
          + "  </remotePackage>\n"
          + "  <remotePackage path=\"platforms;android-37.1\">\n"
          + "    <type-details xsi:type=\"sdk:platformDetailsType\"><api-level>37.1</api-level></type-details>\n"
          + "  </remotePackage>\n"
          + "  <remotePackage path=\"platforms;android-37.2\">\n"
          + "    <type-details xsi:type=\"sdk:platformDetailsType\"><api-level>37.2</api-level></type-details>\n"
          + "    <archives><archive><complete><url>platform-37.2_r02.zip</url></complete></archive></archives>\n"
          + "  </remotePackage>\n"
          + "  <remotePackage path=\"ndk;26.1.10909125\" />\n"
          + "</sdk:sdk-repository>\n";

  @Test
  public void repositoryXmlListsMinorPlatforms() {
    // 旧版 sdkmanager 只给到 36，仓库索引必须补齐 36.1 / 37.0 / 37.1 / 37.2。
    List<String> platforms = SdkManagerInstallerKt.parseRepositoryXml(REPOSITORY_XML);
    assertEquals(
        Arrays.asList("35", "36", "36.1", "37.0", "37.1", "37.2"), platforms);
  }

  @Test
  public void repositoryXmlIgnoresNonPlatformPackages() {
    // build-tools / ndk 的 path 也被 `path="` 前缀匹配，但不会以 platforms; 开头。
    List<String> platforms = SdkManagerInstallerKt.parseRepositoryXml(REPOSITORY_XML);
    for (String p : platforms) {
      assertTrue("只应出现平台版本号：" + p, p.matches("[0-9]+(\\.[0-9]+)?"));
      assertFalse("不应含 build-tools 片段：" + p, p.startsWith("build"));
    }
  }

  @Test
  public void repositoryXmlReturnsEmptyForGarbage() {
    // 响应是错误页 / 空串时不崩，返回空列表（调用方据此回退）。
    assertTrue(SdkManagerInstallerKt.parseRepositoryXml("").isEmpty());
    assertTrue(SdkManagerInstallerKt.parseRepositoryXml("<html>502 Bad Gateway</html>").isEmpty());
  }
}