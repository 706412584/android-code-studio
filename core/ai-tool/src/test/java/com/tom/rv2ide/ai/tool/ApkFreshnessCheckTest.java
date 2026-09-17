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

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * APK 产物核验的回归测试。
 *
 * <p><b>为什么需要它</b>：模型会谎报构建成功。真机实测：模型删掉编译错误后没有重新构建，
 * 却报告「构建成功，APK 已生成」并引用 9 小时前的旧 APK。若采信，自动化测试会在错误的
 * 产物上给出「通过」——这种失败是静默的，比直接报错更危险。
 *
 * <p>同时要防止<b>误报</b>：Gradle 增量构建判定 up-to-date 时不会重写 APK，
 * 此时 APK 时间早于构建开始时间是合法的。
 */
final class ApkFreshnessCheckTest {

  private static final long BUILD_START = 1_700_000_000_000L;

  @Test
  void blocksInstallWhenLastBuildFailed() {
    // 构建失败 + APK 是更早的产物 → 必须阻断
    long staleApk = BUILD_START - 9 * 3600_000L; // 9 小时前

    ApkFreshnessCheck.Result result =
        ApkFreshnessCheck.check(staleApk, false, BUILD_START);

    assertFalse(result.isInstallable(), "构建失败后不应允许安装旧产物");
    assertTrue(result.getMessage().contains("最近一次构建失败"), result.getMessage());
    assertTrue(result.getMessage().contains("旧产物"), result.getMessage());
  }

  @Test
  void allowsIncrementalBuildWhereApkWasNotRewritten() {
    // 构建成功但 APK 未重写（Gradle 判定 up-to-date）→ 放行，仅提示
    long unchangedApk = BUILD_START - 60_000L;

    ApkFreshnessCheck.Result result =
        ApkFreshnessCheck.check(unchangedApk, true, BUILD_START);

    assertTrue(result.isInstallable(), "增量构建未重写 APK 属正常，不应阻断");
    assertTrue(result.getMessage().contains("增量构建"), result.getMessage());
  }

  @Test
  void allowsWhenApkProducedByThisBuild() {
    long freshApk = BUILD_START + 30_000L;

    ApkFreshnessCheck.Result result = ApkFreshnessCheck.check(freshApk, true, BUILD_START);

    assertTrue(result.isInstallable());
    assertTrue(result.getMessage().isEmpty(), "正常情况不应产生噪声信息");
  }

  @Test
  void allowsWhenNoBuildRecord() {
    // 无记录（用户手动构建等）→ 不阻断，否则会挡住正常用法
    assertTrue(ApkFreshnessCheck.check(0L, null, null).isInstallable());
    assertTrue(ApkFreshnessCheck.check(0L, true, null).isInstallable());
    assertTrue(ApkFreshnessCheck.check(0L, null, BUILD_START).isInstallable());
  }

  @Test
  void messageIncludesBothTimestamps() {
    // 阻断信息要能让模型判断「APK 比构建早多久」，否则它无从理解为什么被拒
    long staleApk = BUILD_START - 9 * 3600_000L;

    String message = ApkFreshnessCheck.check(staleApk, false, BUILD_START).getMessage();

    assertTrue(message.contains("APK 文件时间"), message);
    assertTrue(message.contains("最近一次构建开始于"), message);
  }
}
