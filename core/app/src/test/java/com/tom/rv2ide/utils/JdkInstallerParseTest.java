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

package com.tom.rv2ide.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/**
 * JDK 管理器「可用版本」列表的解析。
 *
 * <p><b>为什么值得钉住</b>：列表直接决定用户能点哪张卡。写错有两种真实故障：
 * <ul>
 *   <li>把 `openjdk-17-source` / `openjdk-17-x` 当成 JDK 本体 → 界面上多出
 *       「JDK 17-source」这种点不动的卡；
 *   <li>顺序不按版本号排 → 列表显示成 17 / 21 / 11，用户以为缺版本。
 * </ul>
 *
 * <p>注意这里只测**解析**。真正「哪些版本装得上」由查询本身限定（只查 ACS
 * 自举源，见 {@code discoverAvailable} 的注释）——那是设备行为，JVM 单测覆盖不到。
 */
public class JdkInstallerParseTest {

  @Test
  public void keepsOnlyNumericSuffixes() {
    // 实测的 apt-cache 输出形态：既有本体，也有 source / x 变体。
    String out =
        "openjdk-17\n"
            + "openjdk-17-source\n"
            + "openjdk-17-x\n"
            + "openjdk-21\n"
            + "openjdk-21-source\n"
            + "openjdk-21-x\n";
    assertEquals(List.of("17", "21"), JdkInstaller.parsePackageNames(out));
  }

  @Test
  public void sortsNumericallyNotLexically() {
    // 字典序会把 "11" 排在 "9" 前面、"21" 排在 "17" 前——必须按数值排。
    String out = "openjdk-21\nopenjdk-9\nopenjdk-11\nopenjdk-17\n";
    assertEquals(List.of("9", "11", "17", "21"), JdkInstaller.parsePackageNames(out));
  }

  @Test
  public void deduplicatesAndIgnoresNoise() {
    // 多个源提供同一版本时会重复出现；空行与无关行不能污染结果。
    String out = "openjdk-21\n\n  openjdk-17  \nopenjdk-21\nlibfoo\nopenjdk-\n";
    assertEquals(List.of("17", "21"), JdkInstaller.parsePackageNames(out));
  }

  @Test
  public void emptyOutputYieldsEmptyList() {
    // 空结果由调用方兜底成 FALLBACK_VERSIONS；这里只保证解析本身不抛。
    assertTrue(JdkInstaller.parsePackageNames("").isEmpty());
    assertTrue(JdkInstaller.parsePackageNames("\n\n").isEmpty());
  }

  @Test
  public void fallbackMatchesBootstrapSource() {
    // 兜底列表是「查不到时的最后退路」，必须与自举源实测值一致（17/21）。
    assertEquals(List.of("17", "21"), JdkInstaller.FALLBACK_VERSIONS);
  }

  @Test
  public void availabilityQueryIsPinnedToBootstrapSource() {
    // 命门：必须带 `Dir::Etc::sourceparts=-`（只认 sources.list = ACS 自举源，
    // 忽略 sources.list.d/ 下用户手工加的官方 Termux 源）。删掉它，界面上就会
    // 重新出现装不上的 JDK 25/26，而列表本身照常显示、毫无报错。
    List<String> args = JdkInstaller.discoverQueryArgs();
    assertTrue("应带 -o 选项", args.contains("-o"));
    assertTrue(
        "应把 sourceparts 指成禁用（只认自举源）",
        args.contains("Dir::Etc::sourceparts=-"));
    assertTrue("应是 apt-cache pkgnames 查询", args.contains("apt-cache"));
    assertTrue("应查 openjdk- 前缀", args.contains("openjdk-"));
  }
}
