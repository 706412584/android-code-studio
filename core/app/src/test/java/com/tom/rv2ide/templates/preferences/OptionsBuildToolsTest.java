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

package com.tom.rv2ide.templates.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * build-tools 值的归一化。
 *
 * <p><b>为什么值得钉住</b>：这里的错误形态是**新建项目直接坏掉**，且报错完全不指向
 * 原因——空串落盘后 AGP 报的是 `Invalid revision:`（`app/build.gradle` 的那一行），
 * 用户看到的是「项目初始化失败」而不是「build-tools 值不对」。
 *
 * <p>2026-11-10 在 PJA110 上实测：`sjjz` 工程的 `app/build.gradle` 第 10 行是
 * `buildToolsVersion ''`，daemon 日志 `FAILURE ... > Invalid revision:`，
 * 项目模型根本读不出来。删掉那一行后立刻初始化成功。
 */
public class OptionsBuildToolsTest {

  @Test
  public void blankBecomesNullSoTheLineIsOmitted() {
    // 核心用例：偏好里「不指定」存空串，必须变 null（= 不写该行），不能变 ""。
    assertNull(Options.normalizeBuildToolsVersion(""));
    assertNull(Options.normalizeBuildToolsVersion(null));
    // 只有空白的串同样按「未指定」处理。
    assertNull(Options.normalizeBuildToolsVersion("   "));
  }

  @Test
  public void explicitVersionIsPreserved() {
    // 用户显式选过的版本原样保留，不能被归一化吃掉。
    assertEquals("36.0.0", Options.normalizeBuildToolsVersion("36.0.0"));
    assertEquals("35.0.1", Options.normalizeBuildToolsVersion("35.0.1"));
    // 前后空白应当去掉（偏好值理论上不会有，但 truncate 更稳）。
    assertEquals("36.0.0", Options.normalizeBuildToolsVersion(" 36.0.0 ").trim());
  }
}
