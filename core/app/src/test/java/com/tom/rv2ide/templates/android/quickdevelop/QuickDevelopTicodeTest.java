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
import java.util.List;
import org.junit.Test;

/**
 * 锁定 Quick Develop 模板生成的 <b>{@code :ticode} 模块</b>。
 *
 * <h3>为什么需要这个测试</h3>
 *
 * {@code :ticode} 的 build.gradle 是<b>字符串</b>，编译器不会检查它；生成的 333 个
 * .java 也只有在用户新建工程、跑到 gradle 构建时才会暴露问题。这里在 JVM 上：
 * <ol>
 *   <li>断言 build.gradle 的关键内容（插件别名 / namespace / flexbox 依赖）；
 *   <li>断言 assets 里的 ticode 源码**存在且完整**（333 个 .java，包结构正确）——
 *       这是「模板真能产出可用模块」的前提，之前从无任何断言覆盖；
 *   <li>把源码落盘到 {@code build/ticode-out/}，供外部 javac 编译验证。
 * </ol>
 */
public class QuickDevelopTicodeTest {

  /** assets 里 ticode 源码的根（相对 core/app 的工作目录）。 */
  private static final File ASSETS_TICODE =
      new File("src/main/assets/" + QuickDevelopTicode.ASSETS_PATH);

  // ---------------------------------------------------------------------------------------
  // build.gradle 生成
  // ---------------------------------------------------------------------------------------

  /** KTS：库插件 + namespace + flexbox 依赖缺一不可。 */
  @Test
  public void ktsModuleGradleHasLibraryPluginNamespaceAndFlexbox() {
    String g = QuickDevelopTicode.INSTANCE.moduleGradle(true, 36, 26);
    assertTrue("缺库插件别名", g.contains("alias(libs.plugins.android.library)"));
    assertTrue("缺 namespace", g.contains("namespace = \"ticode.zh\""));
    assertTrue("缺 compileSdk", g.contains("compileSdk = 36"));
    assertTrue("缺 minSdk", g.contains("minSdk = 26"));
    assertTrue("缺 flexbox 依赖", g.contains("implementation(libs.androidx.flexbox)"));
    assertTrue("KTS 的依赖应是调用式", g.contains("implementation(libs.androidx.appcompat)"));
    assertFalse("库模块不应有 applicationId", g.contains("applicationId"));
    // 依赖块缩进：`dependencies {` 顶格，每个 implementation 缩进 4 空格，`}` 顶格。
    // 曾有此 bug：多行 $deps 内嵌进模板后参与 trimIndent 的公共缩进计算，
    // 导致只有首行对齐、其余顶格。
    assertTrue(
        "dependencies 块缩进错位:\n" + g,
        g.contains("\ndependencies {\n    implementation(libs.androidx.core)\n")
            && g.contains("\n    implementation(libs.androidx.flexbox)\n}"));
  }

  /** Groovy：依赖是空格式（无外层括号），namespace 用单引号。 */
  @Test
  public void groovyModuleGradleUsesGroovySyntax() {
    String g = QuickDevelopTicode.INSTANCE.moduleGradle(false, 34, 24);
    assertTrue("缺库插件别名", g.contains("alias(libs.plugins.android.library)"));
    assertTrue("Groovy 的 namespace 用单引号", g.contains("namespace 'ticode.zh'"));
    assertTrue("Groovy 的依赖应无外层括号", g.contains("implementation libs.androidx.flexbox"));
    assertFalse("Groovy 不应出现 KTS 式调用", g.contains("implementation(libs.androidx.flexbox)"));
  }

  /** 两种 DSL 都必须带上 ticode 依赖的全部 5 个库。 */
  @Test
  public void bothDslsDeclareAllFiveDependencies() {
    for (boolean kts : new boolean[] {true, false}) {
      String g = QuickDevelopTicode.INSTANCE.moduleGradle(kts, 36, 26);
      for (String lib :
          new String[] {"core", "appcompat", "recyclerview", "constraintlayout", "flexbox"}) {
        assertTrue("kts=" + kts + " 缺 libs.androidx." + lib, g.contains("androidx." + lib));
      }
    }
  }

  /** README 必须说明这是结绳移植、给出用法，否则用户不知道这模块干嘛的。 */
  @Test
  public void readmeExplainsModule() {
    String r = QuickDevelopTicode.INSTANCE.readme();
    assertTrue("README 未说明来源", r.contains("结绳"));
    assertTrue("README 未给包名", r.contains("ticode.zh"));
    assertTrue("README 未给用法示例", r.contains("加解密操作"));
  }

  /**
   * 工程 README 必须给出「三套 API 用哪一套」的选型指引。
   *
   * <p>工程里同时有 ui/ tool/ ticode 三套中文 API，没有指引用户必然困惑。
   * 这条断言钉住指引存在且覆盖三个关键决策点（界面用 ui/、工具用 tool/、
   * 结绳兼容用 ticode），防止以后被删。
   */
  @Test
  public void 选型指引覆盖三套API() {
    String g = QuickDevelopTicode.INSTANCE.选型指引();
    assertTrue("未指引界面用 ui/", g.contains("ui/"));
    assertTrue("未指引工具用 tool/", g.contains("tool/"));
    assertTrue("未指引 ticode 的用途", g.contains("ticode"));
    assertTrue("未说明 ticode 与 ui 的取舍", g.contains("getView()"));
    assertTrue("未给出移除 ticode 的方法", g.contains("include(\":ticode\")"));
  }

  // ---------------------------------------------------------------------------------------
  // assets 里的源码：这是模板能产出 :ticode 的前提
  // ---------------------------------------------------------------------------------------

  /** assets 里必须真有 ticode 源码——否则生成的模块是空的。 */
  @Test
  public void assetsContainTicodeSources() {
    assertTrue("assets 里没有 ticode 目录: " + ASSETS_TICODE.getAbsolutePath(), ASSETS_TICODE.isDirectory());
    List<File> java = collectJava(ASSETS_TICODE);
    assertEquals(
        "assets 里的 ticode 源码数不对（改过 ticode 后需跑 tools/sync-ticode-assets.sh）",
        QuickDevelopTicode.EXPECTED_SOURCE_COUNT,
        java.size());
  }

  /** 四个子包都要有，且数量与仓库版一致（防止同步时漏拷某个子目录）。 */
  @Test
  public void assetsHaveAllFourSubpackages() {
    File zh = new File(ASSETS_TICODE, "ticode/zh");
    for (String sub : new String[] {"base", "jvm", "android", "meng"}) {
      File d = new File(zh, sub);
      assertTrue("缺子包 ticode.zh." + sub, d.isDirectory());
      assertFalse("子包为空: " + sub, collectJava(d).isEmpty());
    }
  }

  /**
   * 每个源码的 package 声明必须与其目录路径一致。
   *
   * <p>这正是 javac 会检查的（"class X is public, should be in X.java" 的同类）——
   * 目录结构一旦与 package 对不上，整个模块编译失败。
   */
  @Test
  public void everySourcePackageMatchesItsDirectory() throws IOException {
    List<String> bad = new ArrayList<>();
    for (File f : collectJava(ASSETS_TICODE)) {
      String rel = ASSETS_TICODE.toPath().relativize(f.toPath()).toString().replace('\\', '/');
      // rel 形如 ticode/zh/jvm/Foo.java -> 期望 package ticode.zh.jvm
      String expectedPkg = rel.substring(0, rel.lastIndexOf('/')).replace('/', '.');
      String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
      if (!src.contains("package " + expectedPkg + ";")) {
        bad.add(rel + " (期望 package " + expectedPkg + ")");
      }
    }
    assertTrue("package 与目录不符:\n" + String.join("\n", bad), bad.isEmpty());
  }

  /** 源码必须是 UTF-8 且含中文标识符——GBK 读会读坏（中文 Windows 的经典坑）。 */
  @Test
  public void sourcesAreUtf8WithChineseIdentifiers() throws IOException {
    int withChinese = 0;
    for (File f : collectJava(ASSETS_TICODE)) {
      String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
      for (int i = 0; i < src.length(); i++) {
        if (Character.UnicodeScript.of(src.charAt(i)) == Character.UnicodeScript.HAN) {
          withChinese++;
          break;
        }
      }
    }
    // 绝大多数类是中文命名的；即便有几个纯英文，也应有数百个含中文
    assertTrue("含中文标识符的源码过少: " + withChinese, withChinese > 300);
  }

  /** 把 assets 源码落盘，供外部 javac 编译验证（与 QuickDevelopSourcesTest 同模式）。 */
  @Test
  public void writeSourcesForExternalCompilation() throws IOException {
    File out = new File("build/ticode-out");
    if (out.exists()) {
      deleteRecursively(out);
    }
    assertTrue("无法创建输出目录: " + out, out.mkdirs());
    int n = 0;
    for (File f : collectJava(ASSETS_TICODE)) {
      String rel = ASSETS_TICODE.toPath().relativize(f.toPath()).toString();
      File dest = new File(out, rel);
      dest.getParentFile().mkdirs();
      Files.copy(f.toPath(), dest.toPath());
      n++;
    }
    assertEquals(QuickDevelopTicode.EXPECTED_SOURCE_COUNT, n);
  }

  // ---------------------------------------------------------------------------------------

  private static List<File> collectJava(File dir) {
    List<File> out = new ArrayList<>();
    if (!dir.isDirectory()) {
      return out;
    }
    File[] children = dir.listFiles();
    if (children == null) {
      return out;
    }
    for (File c : children) {
      if (c.isDirectory()) {
        out.addAll(collectJava(c));
      } else if (c.getName().endsWith(".java")) {
        out.add(c);
      }
    }
    return out;
  }

  private static void deleteRecursively(File f) {
    File[] children = f.listFiles();
    if (children != null) {
      for (File c : children) {
        deleteRecursively(c);
      }
    }
    //noinspection ResultOfMethodCallIgnored
    f.delete();
  }
}
