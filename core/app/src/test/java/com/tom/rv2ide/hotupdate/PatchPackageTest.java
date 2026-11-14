/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
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

package com.tom.rv2ide.hotupdate;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

/**
 * 锁定 {@link PatchPackage} 的 dex 命名规范化与 base 版本读取。
 *
 * <p>这两个行为都对应**真实踩过的坑**，且失败是静默的：
 *
 * <ul>
 *   <li>dex 跳号（patch-cli 沿用 base APK 的 classes8/classes12 名）会让 ART 找不到补丁里
 *       任何类——补丁「应用成功」但代码毫无变化。真机上花了很多轮才定位，必须有回归保护。
 *   <li>base 版本读错会让补丁装到不匹配的版本上，静默注入错误 dex。
 * </ul>
 */
public class PatchPackageTest {

  private static File newTempZip(String name, List<String[]> entries) throws IOException {
    File f = File.createTempFile(name, ".zip");
    f.deleteOnExit();
    try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(f))) {
      for (String[] e : entries) {
        zout.putNextEntry(new ZipEntry(e[0]));
        zout.write(e[1].getBytes("UTF-8"));
        zout.closeEntry();
      }
    }
    return f;
  }

  private static List<String> namesOf(File zip) throws IOException {
    List<String> out = new ArrayList<>();
    try (ZipFile z = new ZipFile(zip)) {
      z.entries().asIterator().forEachRemaining(e -> out.add(e.getName()));
    }
    return out;
  }

  private static byte[] readEntry(File zip, String name) throws IOException {
    try (ZipFile z = new ZipFile(zip)) {
      return z.getInputStream(z.getEntry(name)).readAllBytes();
    }
  }

  // ---- dex 命名规范化 ----

  @Test
  public void gapsInDexNamesAreClosed() throws IOException {
    // patch-cli 对多 dex 应用的真实输出：没有 classes.dex，只有 8 和 12。
    File src =
        newTempZip(
            "patch",
            Arrays.asList(
                new String[] {"patch.json", "{}"},
                new String[] {"classes8.dex", "DEX-EIGHT"},
                new String[] {"classes12.dex", "DEX-TWELVE"}));

    File out = File.createTempFile("norm", ".zip");
    out.deleteOnExit();
    List<String> result = PatchPackage.INSTANCE.normalizeDexNames(src, out);

    assertEquals(Arrays.asList("classes.dex", "classes2.dex"), result);
    assertEquals(Arrays.asList("patch.json", "classes.dex", "classes2.dex"), namesOf(out));
  }

  @Test
  public void dexContentIsCopiedVerbatim() throws IOException {
    File src =
        newTempZip(
            "patch",
            Arrays.asList(
                new String[] {"patch.json", "{}"}, new String[] {"classes9.dex", "PAYLOAD-9"}));

    File out = File.createTempFile("norm", ".zip");
    out.deleteOnExit();
    PatchPackage.INSTANCE.normalizeDexNames(src, out);

    // 改名不碰内容：dex 内部不引用自身文件名，逐字节保留是安全的。
    assertArrayEquals("PAYLOAD-9".getBytes("UTF-8"), readEntry(out, "classes.dex"));
  }

  @Test
  public void nonDexEntriesArePreserved() throws IOException {
    File src =
        newTempZip(
            "patch",
            Arrays.asList(
                new String[] {"patch.json", "{}"},
                new String[] {"classes2.dex", "D2"},
                new String[] {"assets/foo.bin", "BIN"},
                new String[] {"lib/arm64-v8a/libx.so", "SO"}));

    File out = File.createTempFile("norm", ".zip");
    out.deleteOnExit();
    PatchPackage.INSTANCE.normalizeDexNames(src, out);

    List<String> names = namesOf(out);
    assertTrue("资源条目必须保留", names.contains("assets/foo.bin"));
    assertTrue("so 条目必须保留", names.contains("lib/arm64-v8a/libx.so"));
    assertArrayEquals("BIN".getBytes("UTF-8"), readEntry(out, "assets/foo.bin"));
  }

  @Test
  public void alreadyContiguousNamesAreUnchanged() throws IOException {
    File src =
        newTempZip(
            "patch",
            Arrays.asList(
                new String[] {"patch.json", "{}"},
                new String[] {"classes.dex", "D1"},
                new String[] {"classes2.dex", "D2"}));

    File out = File.createTempFile("norm", ".zip");
    out.deleteOnExit();
    List<String> result = PatchPackage.INSTANCE.normalizeDexNames(src, out);

    assertEquals(Arrays.asList("classes.dex", "classes2.dex"), result);
  }

  @Test
  public void manifestIsWrittenFirst() throws IOException {
    // 清单放最前，便于人工排查（SDK 按名查找，顺序无关）。
    File src =
        newTempZip(
            "patch",
            Arrays.asList(
                new String[] {"classes5.dex", "D"},
                new String[] {"patch.json", "{}"},
                new String[] {"zzz.txt", "x"}));

    File out = File.createTempFile("norm", ".zip");
    out.deleteOnExit();
    PatchPackage.INSTANCE.normalizeDexNames(src, out);

    assertEquals("patch.json", namesOf(out).get(0));
  }

  @Test(expected = IllegalArgumentException.class)
  public void zipWithoutDexIsRejected() throws IOException {
    // 没有 dex 的 zip 不是补丁包，应当明确报错而不是产出一个空补丁。
    File src = newTempZip("nodx", Collections.singletonList(new String[] {"patch.json", "{}"}));
    File out = File.createTempFile("norm", ".zip");
    out.deleteOnExit();
    PatchPackage.INSTANCE.normalizeDexNames(src, out);
  }

  // ---- base 版本读取 ----

  @Test
  public void baseVersionCodeIsRead() throws IOException {
    File src =
        newTempZip(
            "patch",
            Arrays.asList(
                new String[] {"patch.json", "{\"baseVersionCode\":1027,\"versionCode\":1028}"},
                new String[] {"classes.dex", "D"}));

    assertEquals(1027, PatchPackage.INSTANCE.readBaseVersionCode(src));
  }

  @Test
  public void missingBaseVersionCodeYieldsMinusOne() throws IOException {
    File src =
        newTempZip("patch", Collections.singletonList(new String[] {"patch.json", "{\"versionCode\":1028}"}));

    assertEquals(-1, PatchPackage.INSTANCE.readBaseVersionCode(src));
  }

  @Test
  public void malformedManifestYieldsMinusOne() throws IOException {
    File src = newTempZip("patch", Collections.singletonList(new String[] {"patch.json", "not json at all"}));

    assertEquals(-1, PatchPackage.INSTANCE.readBaseVersionCode(src));
  }

  @Test
  public void absentManifestYieldsMinusOne() throws IOException {
    File src = newTempZip("patch", Collections.singletonList(new String[] {"classes.dex", "D"}));

    assertEquals(-1, PatchPackage.INSTANCE.readBaseVersionCode(src));
  }

  // ---- 资源变更拒绝（r12 线上事故的回归保护）----

  /**
   * 含 {@code resources.arsc} 的补丁必须被拒绝。
   *
   * <p>回归背景（r12 线上事故）：补丁里的 resources.arsc 是整体替换，而资源 ID 跨版本
   * 会漂移（r11→r12 有 981 个 ID 变了）。补丁只带差分 dex，未被覆盖的代码仍引用旧 ID，
   * 在新表里指向别的资源——实测主页「打开项目」显示成「找不到未解析的类名」。
   */
  @Test
  public void patchWithArscIsRejected() throws IOException {
    File src =
        newTempZip(
            "patch",
            Arrays.asList(
                new String[] {"patch.json", "{}"},
                new String[] {"classes.dex", "D"},
                new String[] {"resources.arsc", "ARSC"}));

    assertTrue(PatchPackage.INSTANCE.containsResources(src));
    try {
      PatchPackage.INSTANCE.normalizeDexNames(src, File.createTempFile("out", ".zip"));
      fail("含 resources.arsc 的补丁应被拒绝");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("资源"));
    }
  }

  /** {@code res/} 下的资源文件同样触发拒绝。 */
  @Test
  public void patchWithResEntriesIsRejected() throws IOException {
    File src =
        newTempZip(
            "patch",
            Arrays.asList(
                new String[] {"patch.json", "{}"},
                new String[] {"classes.dex", "D"},
                new String[] {"res/layout/foo.xml", "<x/>"}));

    assertTrue(PatchPackage.INSTANCE.containsResources(src));
    try {
      PatchPackage.INSTANCE.normalizeDexNames(src, File.createTempFile("out", ".zip"));
      fail("含 res/ 条目的补丁应被拒绝");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("资源"));
    }
  }

  // ---- resourceIdsStable 标记（启用 aapt2 --stable-ids 后的放行路径）----

  /**
   * 含资源但带 {@code resourceIdsStable: true} 的补丁应放行。
   *
   * <p>这是启用资源 ID 稳定化（aapt2 --stable-ids）后的正常路径：CI 校验两个 APK
   * 的 ID 对齐后写入该标记，App 端据此放行。实测稳定化生效时 0 漂移
   * （不启用时 1147 个漂移）。
   */
  @Test
  public void patchWithResourcesAndStableIdsIsAccepted() throws IOException {
    File src =
        newTempZip(
            "patch",
            Arrays.asList(
                new String[] {"patch.json", "{\"resourceIdsStable\":true}"},
                new String[] {"classes.dex", "D"},
                new String[] {"resources.arsc", "ARSC"},
                new String[] {"res/layout/foo.xml", "<x/>"}));

    assertTrue(PatchPackage.INSTANCE.containsResources(src));
    assertTrue(PatchPackage.INSTANCE.hasStableResourceIds(src));
    // 不抛异常 = 放行
    List<String> out =
        PatchPackage.INSTANCE.normalizeDexNames(src, File.createTempFile("out", ".zip"));
    assertEquals(Collections.singletonList("classes.dex"), out);
  }

  /** 标记为 false 或缺失时仍拒绝——标记必须显式为 true。 */
  @Test
  public void patchWithResourcesAndFalseOrMissingFlagIsRejected() throws IOException {
    for (String manifest : new String[] {"{}", "{\"resourceIdsStable\":false}"}) {
      File src =
          newTempZip(
              "patch",
              Arrays.asList(
                  new String[] {"patch.json", manifest},
                  new String[] {"classes.dex", "D"},
                  new String[] {"resources.arsc", "ARSC"}));

      assertFalse(PatchPackage.INSTANCE.hasStableResourceIds(src));
      try {
        PatchPackage.INSTANCE.normalizeDexNames(src, File.createTempFile("out", ".zip"));
        fail("标记非 true 时含资源的补丁应被拒绝：" + manifest);
      } catch (IllegalArgumentException expected) {
        assertTrue(expected.getMessage().contains("资源"));
      }
    }
  }

  /** 纯 dex 补丁即使没有标记也放行——标记只对含资源的补丁有意义。 */
  @Test
  public void pureDexPatchNeedsNoStableIdsFlag() throws IOException {
    File src =
        newTempZip(
            "patch",
            Arrays.asList(
                new String[] {"patch.json", "{}"},
                new String[] {"classes.dex", "D"}));

    assertFalse(PatchPackage.INSTANCE.hasStableResourceIds(src));
    assertFalse(PatchPackage.INSTANCE.containsResources(src));
    PatchPackage.INSTANCE.normalizeDexNames(src, File.createTempFile("out", ".zip"));
  }

  /** 纯 dex 补丁（历史正常形态）必须照常通过——守卫不能误伤。 */
  @Test
  public void pureDexPatchStillAccepted() throws IOException {
    File src =
        newTempZip(
            "patch",
            Arrays.asList(
                new String[] {"patch.json", "{}"},
                new String[] {"classes8.dex", "D8"},
                new String[] {"classes12.dex", "D12"}));

    assertFalse(PatchPackage.INSTANCE.containsResources(src));
    List<String> out = PatchPackage.INSTANCE.normalizeDexNames(src, File.createTempFile("out", ".zip"));
    assertEquals(Arrays.asList("classes.dex", "classes2.dex"), out);
  }

  /**
   * 判据二：`patch.json` 声明了资源变更即拒绝——即使包内没有实际的资源文件。
   *
   * <p>这是生成侧的自我记录（patch-cli 实测在 r12 里写 `changes.resources.modified: 98`）。
   * 留着这个判据是为了防「打包方式变了但文件判据漏检」——两个信号互为补充。
   */
  @Test
  public void declaredResourceChangesAreRejected() throws IOException {
    String manifest =
        "{\"changes\":{\"resources\":{\"modified\":[\"res/a.xml\",\"res/b.xml\"],"
            + "\"added\":[],\"deleted\":[]}}}";
    File src =
        newTempZip(
            "patch",
            Arrays.asList(
                new String[] {"patch.json", manifest},
                new String[] {"classes.dex", "D"}));

    // 包内没有 resources.arsc / res/，文件判据为 false……
    assertFalse(PatchPackage.INSTANCE.containsResourceEntries(src));
    // ……但声明判据为 true，整体仍拒绝
    assertTrue(PatchPackage.INSTANCE.declaresResourceChanges(src));
    assertTrue(PatchPackage.INSTANCE.containsResources(src));
    try {
      PatchPackage.INSTANCE.normalizeDexNames(src, File.createTempFile("out", ".zip"));
      fail("声明了资源变更的补丁应被拒绝");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("资源"));
    }
  }

  /** 声明为空数组时不算资源变更（patch-cli 对无资源改动的补丁会这样写）。 */
  @Test
  public void emptyResourceDeclarationIsNotAChange() throws IOException {
    String manifest = "{\"changes\":{\"resources\":{\"modified\":[],\"added\":[],\"deleted\":[]}}}";
    File src =
        newTempZip(
            "patch",
            Arrays.asList(
                new String[] {"patch.json", manifest},
                new String[] {"classes.dex", "D"}));

    assertFalse(PatchPackage.INSTANCE.declaresResourceChanges(src));
    assertFalse(PatchPackage.INSTANCE.containsResources(src));
  }

  /** 清单缺失或损坏时不因判据二误判（文件判据仍会兜底）。 */
  @Test
  public void malformedManifestDoesNotFalselyTriggerDeclaration() throws IOException {
    File noManifest =
        newTempZip("patch", Collections.singletonList(new String[] {"classes.dex", "D"}));
    assertFalse(PatchPackage.INSTANCE.declaresResourceChanges(noManifest));

    File badJson =
        newTempZip(
            "patch",
            Arrays.asList(
                new String[] {"patch.json", "not json"},
                new String[] {"classes.dex", "D"}));
    assertFalse(PatchPackage.INSTANCE.declaresResourceChanges(badJson));
  }

  // ---- 资源路径风格指纹（2026-11-15 PJA110 无限重启事故的回归保护）----

  /**
   * release 构建的资源名被缩短（{@code res/hq.xml}），应判为 {@code SHORTENED}。
   *
   * <p>回归背景：CI 的补丁基于 release 包（缩短名），装到本地 debug 包（原名
   * {@code res/drawable/abc_vector_test.xml}）上，资源表指向不存在的文件 → 启动即崩。
   */
  @Test
  public void shortenedResourceNamesAreDetected() throws IOException {
    File apk =
        newTempZip(
            "release",
            Arrays.asList(
                new String[] {"classes.dex", "D"},
                new String[] {"res/hq.xml", "X"},
                new String[] {"res/y4.xml", "X"},
                new String[] {"res/Bd.xml", "X"},
                new String[] {"res/aM.xml", "X"},
                new String[] {"res/UX.xml", "X"}));

    assertEquals(PatchPackage.Style.SHORTENED, PatchPackage.INSTANCE.resourcePathStyle(apk));
  }

  /** debug 构建保留原始资源名（含下划线、长度足够），应判为 {@code FULL}。 */
  @Test
  public void originalResourceNamesAreDetected() throws IOException {
    File apk =
        newTempZip(
            "debug",
            Arrays.asList(
                new String[] {"classes.dex", "D"},
                new String[] {"res/drawable/abc_vector_test.xml", "X"},
                new String[] {"res/anim/abc_fade_in.xml", "X"},
                new String[] {"res/drawable/abc_vector_out.xml", "X"},
                new String[] {"res/mipmap-anydpi-v26/ic_launcher.xml", "X"}));

    assertEquals(PatchPackage.Style.FULL, PatchPackage.INSTANCE.resourcePathStyle(apk));
  }

  /**
   * **同一 versionCode 的 release 包与 debug 包必须判为不同风格**——这是整个
   * 加固的核心断言：versionCode 相同不足以说明构建一致，风格指纹才能区分。
   */
  @Test
  public void releaseAndDebugWithSameVersionCodeDifferInStyle() throws IOException {
    File release =
        newTempZip(
            "rel",
            Arrays.asList(
                new String[] {"res/hq.xml", "X"},
                new String[] {"res/y4.xml", "X"},
                new String[] {"res/Bd.xml", "X"},
                new String[] {"res/aM.xml", "X"}));
    File debug =
        newTempZip(
            "dbg",
            Arrays.asList(
                new String[] {"res/drawable/abc_vector_test.xml", "X"},
                new String[] {"res/anim/abc_fade_in.xml", "X"},
                new String[] {"res/drawable/abc_fade_out.xml", "X"},
                new String[] {"res/mipmap-anydpi-v26/ic_launcher.xml", "X"}));

    assertNotEquals(PatchPackage.INSTANCE.resourcePathStyle(release),
        PatchPackage.INSTANCE.resourcePathStyle(debug));
  }

  /** 没有 `res/` 条目（如纯 dex 补丁）时无法判定，返回 UNKNOWN 而不误判。 */
  @Test
  public void zipWithoutResEntriesIsUnknown() throws IOException {
    File patch =
        newTempZip(
            "nodex",
            Arrays.asList(
                new String[] {"patch.json", "{}"},
                new String[] {"classes.dex", "D"}));

    assertEquals(PatchPackage.Style.UNKNOWN, PatchPackage.INSTANCE.resourcePathStyle(patch));
  }

  /** 补丁与安装包风格不同 → 判定不兼容（本次事故的直接拦截条件）。 */
  @Test
  public void buildsAreIncompatibleWhenStylesDiffer() throws IOException {
    File releasePatch =
        newTempZip(
            "patch",
            Arrays.asList(
                new String[] {"res/hq.xml", "X"},
                new String[] {"res/y4.xml", "X"},
                new String[] {"res/Bd.xml", "X"},
                new String[] {"res/aM.xml", "X"}));
    File debugApk =
        newTempZip(
            "apk",
            Arrays.asList(
                new String[] {"res/drawable/abc_vector_test.xml", "X"},
                new String[] {"res/anim/abc_fade_in.xml", "X"},
                new String[] {"res/drawable/abc_fade_out.xml", "X"},
                new String[] {"res/mipmap-anydpi-v26/ic_launcher.xml", "X"}));

    assertFalse(PatchPackage.INSTANCE.buildsCompatible(releasePatch, debugApk));
  }

  /** 同风格判为兼容；纯 dex 补丁（UNKNOWN）不拦截，避免制造无法应用的死结。 */
  @Test
  public void buildsCompatibleWhenSameStyleOrUnknown() throws IOException {
    File releaseApk =
        newTempZip(
            "rel",
            Arrays.asList(
                new String[] {"res/hq.xml", "X"},
                new String[] {"res/y4.xml", "X"},
                new String[] {"res/Bd.xml", "X"},
                new String[] {"res/aM.xml", "X"}));
    File releasePatch =
        newTempZip(
            "patch",
            Arrays.asList(
                new String[] {"res/hq.xml", "X"},
                new String[] {"res/y4.xml", "X"}));

    assertTrue(PatchPackage.INSTANCE.buildsCompatible(releasePatch, releaseApk));

    // 纯 dex 补丁没有 res/ 条目 → UNKNOWN → 不拦截（上游 baseVersionCode 兜底）。
    File pureDex =
        newTempZip(
            "pure",
            Arrays.asList(
                new String[] {"patch.json", "{}"},
                new String[] {"classes.dex", "D"}));
    assertTrue(PatchPackage.INSTANCE.buildsCompatible(pureDex, releaseApk));
  }
}
