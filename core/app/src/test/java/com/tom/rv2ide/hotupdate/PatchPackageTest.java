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
import static org.junit.Assert.assertTrue;

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
}
