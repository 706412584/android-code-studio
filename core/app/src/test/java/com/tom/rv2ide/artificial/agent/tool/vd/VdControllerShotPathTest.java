/*
 * This file is part of AndroidCodeStudio.
 *
 * Ported from Aharou (https://github.com/520huxiangli/Aharou),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

package com.tom.rv2ide.artificial.agent.tool.vd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * {@code ps} 命令行解析：复用别的实例留下的 runner 时，靠它把 runner 实际的截图输出
 * 路径读回来。路径是命令行里唯一带引号的参数（见 {@link VdController} 启动时拼的命令）。
 *
 * <p>照搬 Aharou 的同名测试（包名换成 ACS 的）。
 *
 * <p>另含 {@code dumpsys display} / SurfaceFlinger 的解析用例，输入是**实机抓的原始输出**
 * （Android 10 黑鲨 SKW-A0；Android 14 格式取自 Aharou 验证过的正则形态）——这两个
 * 解析在 Android 10 上曾各有一处不兼容（见 {@link VdController} 类注释）。
 */
public class VdControllerShotPathTest {

  @Test
  public void parse_takesQuotedPngPath() {
    String args =
        "app_process / com.tom.rv2ide.vd.VdMain 1080 1920 440 "
            + "\"/storage/emulated/0/Android/data/com.tom.rv2ide/files/vd/frame-ce3c299e.png\" "
            + "/data/local/tmp/acs-vd.shot";
    assertEquals(
        "/storage/emulated/0/Android/data/com.tom.rv2ide/files/vd/frame-ce3c299e.png",
        VdController.parseRunnerShotPath(args));
  }

  @Test
  public void parse_toleratesExtraSpacesAndOtherArgs() {
    String args =
        "  app_process   /   com.tom.rv2ide.vd.VdMain 1080 1920 440 "
            + "  \"/storage/emulated/0/Android/data/com.tom.rv2ide.debug/files/vd/frame-1a2b3c4d.png\"  "
            + "/data/local/tmp/acs-vd.shot";
    assertEquals(
        "/storage/emulated/0/Android/data/com.tom.rv2ide.debug/files/vd/frame-1a2b3c4d.png",
        VdController.parseRunnerShotPath(args));
  }

  @Test
  public void parse_returnsNullWhenNoRunnerLine() {
    assertNull(VdController.parseRunnerShotPath(""));
    assertNull(VdController.parseRunnerShotPath("com[.]tom[.]rv2ide[.]vd[.]VdMain"));
    assertNull(VdController.parseRunnerShotPath(null));
  }

  @Test
  public void parse_ignoresQuotedArgsThatAreNotPng() {
    assertNull(
        VdController.parseRunnerShotPath(
            "app_process / com.tom.rv2ide.vd.VdMain 1080 1920 440 /data/local/tmp/acs-vd.shot"));
  }

  // ---- dumpsys display 解析（Android 10 实机输出）----

  /** 黑鲨 SKW-A0 / Android 10 的原始输出（mViewports 与 DisplayDeviceInfo 两行）。 */
  private static final String DUMP_ANDROID10 =
      "  mViewports=[DisplayViewport{type=INTERNAL, valid=true, displayId=0,"
          + " uniqueId='local:19260422491175297', physicalPort=-127, orientation=0,"
          + " logicalFrame=Rect(0, 0 - 1080, 2340), physicalFrame=Rect(0, 0 - 1080, 2340),"
          + " deviceWidth=1080, deviceHeight=2340}, DisplayViewport{type=VIRTUAL, valid=true,"
          + " displayId=3, uniqueId='virtual:com.android.shell,2000,acs-vd,0', physicalPort=null,"
          + " orientation=0, logicalFrame=Rect(0, 0 - 1080, 1920), physicalFrame=Rect(0, 0 - 1080, 1920),"
          + " deviceWidth=1080, deviceHeight=1920}]\n"
          + "  DisplayDeviceInfo{\"acs-vd\": uniqueId=\"virtual:com.android.shell,2000,acs-vd,0\","
          + " 1080 x 1920, modeId 4, defaultModeId 4, supportedModes [{id=4, width=1080, height=1920,"
          + " fps=60.0}], colorMode 0, supportedColorModes [0], HdrCapabilities null, density 440,"
          + " 440.0 x 440.0 dpi, appVsyncOff 0, presDeadline 16666666, touch VIRTUAL, rotation 0,"
          + " type VIRTUAL, state ON, owner com.android.shell (uid 2000), FLAG_ROTATES_WITH_CONTENT,"
          + " FLAG_PRESENTATION, FLAG_OWN_CONTENT_ONLY}\n";

  @Test
  public void parseDisplayDump_readsAndroid10Format() {
    VdController.VdInfo info = VdController.parseDisplayDump(DUMP_ANDROID10);
    assertEquals(3, info.displayId);
    assertEquals(1080, info.width);
    assertEquals(1920, info.height);
    assertEquals(440, info.dpi);
  }

  @Test
  public void parseDisplayDump_returnsNullWhenAbsent() {
    assertNull(VdController.parseDisplayDump(null));
    assertNull(VdController.parseDisplayDump(""));
    assertNull(
        VdController.parseDisplayDump(
            "  mViewports=[DisplayViewport{type=INTERNAL, valid=true, displayId=0,"
                + " uniqueId='local:1', deviceWidth=1080, deviceHeight=2340}]"));
  }

  /** 虚拟屏用别的名字（Aharou 同机共存）时不能误认。 */
  @Test
  public void parseDisplayDump_ignoresOtherDisplayNames() {
    assertNull(
        VdController.parseDisplayDump(
            DUMP_ANDROID10.replace("acs-vd", "aharou-vd")));
  }

  // ---- SurfaceFlinger display token 解析 ----

  /** Android 10：SF 里虚拟屏是 {@code + DisplayDevice{virtual, "acs-vd"}}，没有数字 id。 */
  @Test
  public void parseSfDisplayId_returnsEmptyOnAndroid10() {
    assertEquals(
        "",
        VdController.parseSfDisplayId(
            "Display 19260422491175297 (HWC display 0): port=129 pnpId=QCM"
                + " displayName=\"ss_amoled_fhd\"\n"
                + "+ DisplayDevice{virtual, \"acs-vd\"}\n"
                + "   isVirtual=true no hwcId,"));
  }

  /** Android 13：从 DisplayDevice 行捞（--display-id 只列物理屏）。 */
  @Test
  public void parseSfDisplayId_readsAndroid13Format() {
    assertEquals(
        "4",
        VdController.parseSfDisplayId(
            "Display 19260422491175297 (HWC display 0): port=129\n"
                + "Display 4 (virtual, \"acs-vd\")\n"));
  }

  /** Android 14+：--display-id 直接列出虚拟屏。 */
  @Test
  public void parseSfDisplayId_readsAndroid14Format() {
    assertEquals(
        "5",
        VdController.parseSfDisplayId(
            "Display 19260422491175297 (HWC display 0): port=129\n"
                + "Display 5 (Virtual display): displayName=\"acs-vd\"\n"));
  }

  @Test
  public void parseSfDisplayId_returnsEmptyWhenAbsent() {
    assertEquals("", VdController.parseSfDisplayId(null));
    assertEquals("", VdController.parseSfDisplayId(""));
  }

  // ---- resumed 行选择（Android 10 实机取样）----

  /**
   * Android 10 的 section 里 {@code mResumedActivity}（栈级，4 空格缩进）在
   * {@code ResumedActivity}（屏级，1 空格缩进）之前——裸子串匹配会先命中栈级记录，
   * 取到可能滞后的值。这里钉住必须选中屏级记录。
   */
  @Test
  public void pickResumedLine_prefersScreenLevelOverStackLevel() {
    String section =
        "    mResumedActivity: ActivityRecord{ad3cbfc u0 com.android.settings/.MiuiSettings t1894}\n"
            + " ResumedActivity:ActivityRecord{6aea874 u0 com.miui.home/.launcher.Launcher t1}\n";
    assertEquals(
        "ResumedActivity:ActivityRecord{6aea874 u0 com.miui.home/.launcher.Launcher t1}",
        VdController.pickResumedLine(section));
  }

  /** 栈级记录单独出现时不得被误选（它是 m 开头，不匹配屏级前缀）。 */
  @Test
  public void pickResumedLine_ignoresStackLevelAlone() {
    assertNull(
        VdController.pickResumedLine(
            "    mResumedActivity: ActivityRecord{ad3cbfc u0 com.android.settings/.MiuiSettings t1894}\n"));
  }

  /** Android 13+ 的 {@code topResumedActivity=} 优先于 10 的形态（同段混排时取前者）。 */
  @Test
  public void pickResumedLine_prefersTopResumedActivity() {
    String section =
        " ResumedActivity:ActivityRecord{aaa u0 com.a/.B t1}\n"
            + "  topResumedActivity=ActivityRecord{bbb u0 com.c/.D t2}\n";
    assertEquals(
        "topResumedActivity=ActivityRecord{bbb u0 com.c/.D t2}",
        VdController.pickResumedLine(section));
  }

  @Test
  public void pickResumedLine_returnsNullForEmptyOrMissing() {
    assertNull(VdController.pickResumedLine(null));
    assertNull(VdController.pickResumedLine(""));
    assertNull(VdController.pickResumedLine("  Stack #193: type=standard mode=fullscreen\n"));
  }
}
