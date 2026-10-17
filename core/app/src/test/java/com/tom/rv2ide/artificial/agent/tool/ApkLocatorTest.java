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

package com.tom.rv2ide.artificial.agent.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 按项目路径定位 APK。
 *
 * <p><b>回归背景（真机实测）</b>：后台项目构建时会话绑 ProjectB、编辑器开着
 * MyBasicActivity。构建本身打在了 ProjectB（APK 时间戳确实更新），但工具用无参
 * {@code findApkPath()}（走编辑器全局 workspace）回报了 MyBasicActivity 的路径，
 * 模型据此向用户报告「构建的其实是你打开的那个项目」——一个完全错误的结论。
 *
 * <p>这里钉住按路径重载的语义：只扫指定项目，且取最新产物。
 */
public class ApkLocatorTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private File project(String name) throws IOException {
    return tmp.newFolder(name);
  }

  private File apk(File project, String module, String variant, String name, long modified)
      throws IOException {
    File dir = new File(project, module + "/build/outputs/apk/" + variant);
    assertTrue("应能创建目录 " + dir, dir.mkdirs());
    File apk = new File(dir, name);
    Files.write(apk.toPath(), new byte[] {0x50, 0x4b, 0x03, 0x04});
    assertTrue(apk.setLastModified(modified));
    return apk;
  }

  @Test
  public void findsApkUnderTheGivenProjectOnly() throws IOException {
    File a = project("A");
    File b = project("B");
    apk(a, "app", "debug", "a-debug.apk", 1_000L);
    File expected = apk(b, "app", "debug", "b-debug.apk", 2_000L);

    assertEquals(expected.getAbsolutePath(), GradleBuildTool.ApkLocator.findApkPath(b.getAbsolutePath()));
    assertEquals(
        new File(a, "app/build/outputs/apk/debug/a-debug.apk").getAbsolutePath(),
        GradleBuildTool.ApkLocator.findApkPath(a.getAbsolutePath()));
  }

  @Test
  public void picksTheNewestApkInTheProject() throws IOException {
    File project = project("P");
    apk(project, "app", "debug", "old.apk", 1_000L);
    File newest = apk(project, "app", "release", "new.apk", 5_000L);

    assertEquals(newest.getAbsolutePath(), GradleBuildTool.ApkLocator.findApkPath(project.getAbsolutePath()));
  }

  @Test
  public void returnsNullWhenProjectHasNoApk() throws IOException {
    File project = project("Empty");
    assertTrue(new File(project, "app/src").mkdirs());

    assertNull(GradleBuildTool.ApkLocator.findApkPath(project.getAbsolutePath()));
  }

  @Test
  public void returnsNullForMissingDirectory() {
    assertNull(
        GradleBuildTool.ApkLocator.findApkPath(
            new File(tmp.getRoot(), "does-not-exist").getAbsolutePath()));
    assertNull(GradleBuildTool.ApkLocator.findApkPath(""));
  }

  @Test
  public void readsApplicationIdFromOutputMetadata() throws IOException {
    File project = project("Meta");
    File apk = apk(project, "app", "debug", "app-debug.apk", 3_000L);
    File meta = new File(apk.getParentFile(), "output-metadata.json");
    Files.write(
        meta.toPath(),
        ("{\"version\":3,\"elements\":[{\"outputFile\":\"app-debug.apk\","
                + "\"applicationId\":\"com.example.demo\"}]}")
            .getBytes(StandardCharsets.UTF_8));

    assertEquals(
        "com.example.demo",
        GradleBuildTool.ApkLocator.findApplicationId(project.getAbsolutePath()));
  }

  @Test
  public void returnsNullApplicationIdWhenMetadataIsMissing() throws IOException {
    File project = project("NoMeta");
    apk(project, "app", "debug", "app-debug.apk", 3_000L);

    assertNull(GradleBuildTool.ApkLocator.findApplicationId(project.getAbsolutePath()));
  }

  @Test
  public void skipsIntermediateDirectories() throws IOException {
    // intermediates/ 下也有 .apk（拆分产物、未对齐包），但输出目录才是最终产物。
    // 当前实现按 mtime 取最新，若 intermediates 里的更新会选错——这里钉住现状：
    // 只有 build/outputs/apk 会被扫描。
    File project = project("Skip");
    File intermediates = new File(project, "app/build/intermediates/apk/debug");
    assertTrue(intermediates.mkdirs());
    File stale = new File(intermediates, "stale.apk");
    Files.write(stale.toPath(), new byte[] {1});
    assertTrue(stale.setLastModified(9_000L));
    File expected = apk(project, "app", "debug", "real.apk", 1_000L);

    assertEquals(
        expected.getAbsolutePath(), GradleBuildTool.ApkLocator.findApkPath(project.getAbsolutePath()));
  }
}
