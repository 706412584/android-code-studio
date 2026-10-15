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
}
