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
 * JVM 上把产物落盘并<b>真正用 javac 编译一遍</b>（{@link #generatedSourcesCompile()}），
 * 编译失败即测试失败，进 CI。
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
   * 把产物写到 {@code core/app/build/quickdevelop-out/}，供人工核对与外部编译。
   *
   * <p>目录刻意放在 build 下：不污染源码树，且会随 {@code clean} 清掉。
   */
  @Test
  public void writeSourcesForExternalCompilation() throws IOException {
    File base = dumpGeneratedSources();
    assertTrue("缺少落盘目录: " + base, base.isDirectory());
  }

  /** 落盘生成的全部 ui/ 与 tool/ 源码，返回落盘根目录（{@code build/quickdevelop-out/<pkg>}）。 */
  private File dumpGeneratedSources() throws IOException {
    File base = new File("build/quickdevelop-out/" + PKG.replace('.', '/'));
    if (base.exists()) {
      deleteRecursively(base);
    }
    File outDir = new File(base, "ui");
    assertTrue("无法创建输出目录: " + outDir, outDir.mkdirs());

    List<String> names = new ArrayList<>();
    for (Pair<String, String> c : components()) {
      File f = new File(outDir, c.getFirst() + ".java");
      Files.write(f.toPath(), c.getSecond().getBytes(StandardCharsets.UTF_8));
      names.add(c.getFirst());
    }
    assertEquals(BASE_COUNT + EXTRA_COUNT * 2, names.size());

    // 工具库同样落盘（它们依赖 Android API，比组件更需要编译验证）。
    File toolDir = new File(base, "tool");
    assertTrue("无法创建工具库目录: " + toolDir, toolDir.mkdirs());
    List<Pair<String, String>> toolkits = QuickDevelopToolkits.INSTANCE.all(PKG);
    for (Pair<String, String> t : toolkits) {
      File f = new File(toolDir, t.getFirst() + ".java");
      Files.write(f.toPath(), t.getSecond().getBytes(StandardCharsets.UTF_8));
    }
    assertEquals(5, toolkits.size());

    // README 也落盘：文档是从规格表产出的，得能肉眼核对它没写成空壳。
    Files.write(
        new File(base, "ui-README.md").toPath(),
        QuickDevelopSources.INSTANCE.uiReadme(PKG).getBytes(StandardCharsets.UTF_8));
    Files.write(
        new File(base, "tool-README.md").toPath(),
        QuickDevelopToolkits.INSTANCE.readme(PKG).getBytes(StandardCharsets.UTF_8));
    return base;
  }

  /**
   * **真正用 javac 编译生成的 ui/ + tool/ 源码**——这是本测试类存在的核心价值。
   *
   * <p>为什么必须真编译：生成的 Java 是<b>字符串</b>，编译器平时看不到它。改一个继承
   * 类名、漏一个 import、写错一个方法签名，本类里其它「断言字符串结构」的测试<b>全绿</b>，
   * 用户建工程跑到 gradle 时才炸。此前只有 {@code tools/build-qd-project.sh} 这个
   * <b>手工脚本</b>会编译，靠人记得跑。
   *
   * <p>为什么用外部 javac 进程而非 {@code javax.tools.JavaCompiler}：Android 单测的
   * 编译 bootclasspath 是 {@code android.jar}，<b>没有 {@code javax.tools}</b> 包，
   * 直接用会在编译测试自身时就报「程序包 javax.tools 不存在」。改为调用测试 JVM 所在的
   * JDK 自带的 {@code bin/javac}（{@code java.home}），既真实又与 AGP 配置解耦。
   *
   * <p>类路径用<b>测试 JVM 自己的 {@code java.class.path}</b>：Gradle 已把 AGP 的
   * mockable {@code android.jar} 与全部 androidx 依赖放上去。不要引用
   * {@code core/ticode/libs/*.jar}——那些 jar <b>未入库</b>（.gitignore），
   * 新克隆/CI 上没有。编译只需类型签名，mockable jar 足够。
   *
   * <p>参数写进 {@code @argfile}：测试 classpath 很长，Windows 命令行有长度上限。
   *
   * <p>只编译 ui/ + tool/：ticode 的 333 个文件已由 {@code tools/ticode-compile.sh}
   * 单独把关，放进这里会拖慢单测。
   */
  @Test
  public void generatedSourcesCompile() throws IOException, InterruptedException {
    File base = dumpGeneratedSources();

    List<String> sources = new ArrayList<>();
    collectJava(new File(base, "ui"), sources);
    collectJava(new File(base, "tool"), sources);
    assertEquals("落盘的 ui+tool 源码数量不符", BASE_COUNT + EXTRA_COUNT * 2 + 5, sources.size());

    String javacName = System.getProperty("os.name", "").toLowerCase().contains("win")
        ? "javac.exe" : "javac";
    File javac = new File(new File(System.getProperty("java.home"), "bin"), javacName);
    assertTrue("找不到 javac（需在 JDK 下跑测试）: " + javac, javac.canExecute());

    File outDir = new File("build/quickdevelop-classes");
    if (outDir.exists()) {
      deleteRecursively(outDir);
    }
    assertTrue("无法创建编译输出目录", outDir.mkdirs());

    // @argfile：-encoding / -classpath / -d / 源码清单。
    // 关键：javac 的 argfile 里**反斜杠是转义符**（`\a` 会被吃掉），Windows 路径
    // 必须写成正斜杠；含空格时整体加引号。
    StringBuilder args = new StringBuilder();
    args.append("-encoding UTF-8\n-nowarn\n");
    args.append("-classpath\n\"").append(slash(System.getProperty("java.class.path"))).append("\"\n");
    args.append("-d\n\"").append(slash(outDir.getAbsolutePath())).append("\"\n");
    for (String s : sources) {
      args.append('"').append(slash(s)).append("\"\n");
    }
    File argfile = new File("build/quickdevelop-javac-args.txt");
    Files.write(argfile.toPath(), args.toString().getBytes(StandardCharsets.UTF_8));

    Process p =
        new ProcessBuilder(javac.getAbsolutePath(), "@" + argfile.getAbsolutePath())
            .redirectErrorStream(true)
            .start();
    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int rc = p.waitFor();

    assertEquals("生成的 ui/tool 源码编译失败（javac 退出码 " + rc + "）:\n" + output, 0, rc);
  }

  /** javac 的 argfile 把 `\` 当转义符，Windows 路径要转正斜杠（classpath 的 `;` 不受影响）。 */
  private static String slash(String p) {
    return p.replace('\\', '/');
  }

  private static void collectJava(File dir, List<String> out) {
    File[] files = dir.listFiles();
    if (files == null) {
      return;
    }
    for (File f : files) {
      if (f.isDirectory()) {
        collectJava(f, out);
      } else if (f.getName().endsWith(".java")) {
        out.add(f.getAbsolutePath());
      }
    }
  }

  /**
   * README 必须列出全部控件，且带目录锚点。
   *
   * <p>钉住「文档从规格表产出」这件事：若有人改成手写清单，加控件时就会漏。
   */
  @Test
  public void uiReadmeCoversEveryWidgetAndHasNav() {
    String md = QuickDevelopSources.INSTANCE.uiReadme(PKG);

    assertTrue("缺少目录", md.contains("## 目录"));
    assertTrue("缺少容器分区", md.contains("## 容器类"));
    assertTrue("缺少扩展控件分区", md.contains("## 扩展控件"));
    assertTrue("缺少命名规则", md.contains("## 命名规则"));

    // 每个扩展控件的中文名都应出现在文档里
    for (Pair<String, String> c : components()) {
      String name = c.getFirst();
      // 英文别名不必逐一出现（文档按中文名列表 + 说明别名机制），中文名必须出现
      if (name.chars().anyMatch(cp -> cp > 0x2E80)) {
        assertTrue("README 未覆盖控件: " + name, md.contains("`" + name + "`"));
      }
    }
  }

  /** 工具库 README 必须覆盖 5 个类与权限表。 */
  @Test
  public void toolReadmeCoversAllKitsAndPermissions() {
    String md = QuickDevelopToolkits.INSTANCE.readme(PKG);

    for (String kit : new String[] {"字符", "文件", "数据", "工具", "系统"}) {
      assertTrue("README 未覆盖工具类: " + kit, md.contains("## " + kit));
    }
    assertTrue("缺少权限一览", md.contains("## 权限一览"));
    assertTrue("未说明 API 24 限制", md.contains("API 24"));
    // 权限表必须列出通知权限：模板 manifest 里已声明它，文档若漏写会让人以为不需要。
    assertTrue("权限表未列出 POST_NOTIFICATIONS", md.contains("POST_NOTIFICATIONS"));
  }

  /**
   * `卡片` 的背景与圆角必须走 CardView 自己的 API。
   *
   * <p>回归用例：`视图.设背景` 会 {@code View.setBackground} 掉 CardView 内部的
   * RoundRectDrawable，导致 `圆角` 静默失效（卡片变直角）。这条断言锁住「不许回退到
   * 背景式实现」。
   */
  @Test
  public void cardViewUsesOwnBackgroundAndRadiusApi() {
    String src = sourceOf("卡片");

    assertTrue("卡片.背景 未走 setCardBackgroundColor", src.contains("setCardBackgroundColor(color);"));
    assertTrue("卡片.圆角 未走 setRadius", src.contains("setRadius(视图.dp(radiusDp));"));
    assertFalse(
        "卡片 不应再走背景式 设背景（会顶掉 CardView 自身 drawable）",
        src.contains("视图.设背景(this"));
    assertFalse(
        "卡片 不应再走背景式 设圆角", src.contains("视图.设圆角(this"));
  }

  /** `侧滑窗体.侧栏` 必须显式给 LayoutParams 设 gravity，否则抽屉永远打不开。 */
  @Test
  public void drawerSetsGravityOnLayoutParams() {
    String src = sourceOf("侧滑窗体");

    assertTrue("侧栏 未设置 params.gravity", src.contains("params.gravity = Gravity.START;"));
    assertTrue("侧栏 未 import Gravity", src.contains("import android.view.Gravity;"));
  }

  /**
   * 生成的 manifest 必须声明工具类用到的权限。
   *
   * <p>这两条权限缺失时行为是**静默失效**（震动没反应、通知不显示，都不崩溃），
   * 所以最容易被忽略——必须由测试钉住。
   */
  @Test
  public void manifestDeclaresToolkitPermissions() {
    String xml = QuickDevelopSources.INSTANCE.manifestXml();

    assertTrue("缺 VIBRATE", xml.contains("android.permission.VIBRATE"));
    assertTrue("缺 POST_NOTIFICATIONS", xml.contains("android.permission.POST_NOTIFICATIONS"));
    assertTrue("缺 launcher activity", xml.contains("android.intent.action.MAIN"));
    assertTrue("theme 应为 AppTheme", xml.contains("@style/AppTheme"));
  }

  /**
   * README 的别名说明必须与实际生成的类一致。
   *
   * <p>回归用例：别名只对规格表里的 38 个扩展控件生成，7 个基础控件（视图/线性布局/
   * 约束布局/文本/按钮/输入框/页面）**没有**别名。文档曾把 `ViewBox` / `LinearBox` /
   * `ConstraintBox` 当作可用别名列进容器表，照抄会编译失败。
   */
  @Test
  public void uiReadmeAliasClaimsMatchGeneratedClasses() {
    String md = QuickDevelopSources.INSTANCE.uiReadme(PKG);

    Set<String> names = new HashSet<>();
    for (Pair<String, String> c : components()) {
      names.add(c.getFirst());
    }
    // 前提：基础控件确实没有别名文件（否则下面的文档断言就无意义了）
    for (String base : new String[] {"视图", "线性布局", "约束布局", "文本", "按钮", "输入框", "页面"}) {
      assertFalse("基础控件不应有别名文件: " + base + "Box", names.contains(base + "Box"));
    }
    // 文档不得把不存在的别名当成可用项
    assertFalse("README 不应宣称 ViewBox 可用", md.contains("`ViewBox`"));
    assertFalse("README 不应宣称 LinearBox 可用", md.contains("`LinearBox`"));
    assertFalse("README 不应宣称 ConstraintBox 可用", md.contains("`ConstraintBox`"));
    // 扩展控件的别名必须仍然列出
    assertTrue("README 应列出扩展控件别名 CardBox", md.contains("`CardBox`"));
  }

  /** 按中文类名取生成源码。 */
  private static String sourceOf(String className) {
    for (Pair<String, String> c : components()) {
      if (c.getFirst().equals(className)) {
        return c.getSecond();
      }
    }
    throw new AssertionError("未找到控件: " + className);
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
