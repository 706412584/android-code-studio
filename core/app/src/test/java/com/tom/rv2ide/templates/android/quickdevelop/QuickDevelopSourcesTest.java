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

package com.tom.rv2ide.templates.android.quickdevelop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kotlin.Pair;
import org.junit.Test;

/**
 * 锁定 Quick Develop 生成的控件集。
 *
 * <p>为什么需要这个测试：生成的 Java 是<b>字符串</b>，编译器不会检查它——写错一个
 * 继承类名或漏掉 import，要等用户新建工程、跑到 gradle 构建时才会暴露。这里在
 * JVM 上把产物落盘，既能断言结构（数量/重名），也让外部可以用 javac 真正编译一遍。
 */
public class QuickDevelopSourcesTest {

  private static final String PKG = "com.example.qdtest";

  /** 现有手写的 7 个类（视图/线性布局/约束布局/文本/按钮/输入框/页面）。 */
  private static final int BASE_COUNT = 7;

  /** 规格表新增的控件数。 */
  private static final int EXTRA_COUNT = 38;

  private static List<Pair<String, String>> components() {
    return QuickDevelopSources.INSTANCE.components(PKG);
  }

  /** 总数 = 7 个手写类 + 38 个新增控件的「中文类 + 英文别名」两两。 */
  @Test
  public void generatesExpectedNumberOfComponents() {
    assertEquals(BASE_COUNT + EXTRA_COUNT * 2, components().size());
  }

  /** 重名会让生成的工程直接编译失败（同包下两个同名类）。 */
  @Test
  public void classNamesAreUnique() {
    Set<String> seen = new HashSet<>();
    for (Pair<String, String> c : components()) {
      assertTrue("类名重复: " + c.getFirst(), seen.add(c.getFirst()));
    }
  }

  /** 每个类的源码必须与文件名同名的类声明，否则 javac 报 "class X is public, should be in X.java"。 */
  @Test
  public void eachSourceDeclaresItsOwnClass() {
    for (Pair<String, String> c : components()) {
      String name = c.getFirst();
      String src = c.getSecond();
      boolean declared =
          src.contains("class " + name + " extends")
              || src.contains("class " + name + " {")
              || src.contains("abstract class " + name + " extends");
      assertTrue("源码未声明类 " + name, declared);
      assertTrue("源码缺少包声明: " + name, src.contains("package " + PKG + ".ui;"));
    }
  }

  /** 中文类名合法（Java 标识符允许 Unicode 字母），但要防住混入空白/标点。 */
  @Test
  public void chineseClassNamesAreValidIdentifiers() {
    for (Pair<String, String> c : components()) {
      String name = c.getFirst();
      assertFalse("类名含空白: " + name, name.contains(" "));
      assertFalse("类名含斜杠: " + name, name.contains("/"));
      assertTrue("类名为空", name.length() > 0);
    }
  }

  /**
   * 把产物写到 {@code core/app/build/quickdevelop-out/}，供外部 javac 编译验证。
   *
   * <p>目录刻意放在 build 下：不污染源码树，且会随 {@code clean} 清掉。
   */
  @Test
  public void writeSourcesForExternalCompilation() throws IOException {
    File outDir = new File("build/quickdevelop-out/" + PKG.replace('.', '/') + "/ui");
    if (outDir.exists()) {
      deleteRecursively(outDir);
    }
    assertTrue("无法创建输出目录: " + outDir, outDir.mkdirs());

    List<String> names = new ArrayList<>();
    for (Pair<String, String> c : components()) {
      File f = new File(outDir, c.getFirst() + ".java");
      Files.write(f.toPath(), c.getSecond().getBytes(StandardCharsets.UTF_8));
      names.add(c.getFirst());
    }
    assertEquals(BASE_COUNT + EXTRA_COUNT * 2, names.size());
  }

  private static void deleteRecursively(File file) {
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        deleteRecursively(child);
      }
    }
    file.delete();
  }
}
