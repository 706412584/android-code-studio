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

import com.tom.rv2ide.templates.android.QuickDevelop;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/**
 * 真正<b>执行</b> Quick Develop 的文档生成逻辑，并断言产物。
 *
 * <h3>为什么需要这个测试</h3>
 *
 * {@link QuickDevelop} 里的 {@code writeDocs(projectRoot, packageId)} 是 private，
 * 之前的 3 个测试全是「手写重实现」——它们自己拼 settings.gradle、自己写 build.gradle，
 * <b>不调用真实的 create()</b>，因此文档生成这条路径从未被执行过：路径对不对、
 * 会不会抛异常、内容是否过期，全是未知。实测生成产物里根本没有 README。
 *
 * <p>{@code writeDocs} 只依赖 {@code File projectRoot} + {@code String packageId}，
 * 不需要 {@code Context}，可以直接在纯 JVM 上跑（{@code writeTicodeModule} 才需要
 * Context 从 assets 拷源码，故本测试不覆盖它）。这里用反射调用真实方法，而不是
 * 为了测试去放宽生产代码的可见性——保持最小改动。
 *
 * <p><b>实测发现</b>：{@code writeDocs} 本身<b>不创建</b> {@code ui/} 与
 * {@code tool/} 目录，它依赖调用方先建好——生产中由 {@code writeComponents()} /
 * {@code writeToolkits()} 保证。因此本测试在调用前预建这两个目录，以复现真实的
 * 调用顺序；这也说明 {@code writeDocs} 不是完全独立的函数。
 *
 * <p>反射调用会连带执行 {@code Log.d}（android.util.Log）。若单测 classpath 未提供
 * android 桩，这里会抛 {@code NoClassDefFoundError}，由 {@link #invokeWriteDocs}
 * 显式报告，避免被误当成「文档内容不对」。
 */
public class QuickDevelopDocsGenTest {

  private static final String PKG = "com.example.qdtest";

  /**
   * 反射执行 {@code QuickDevelop.writeDocs(File, String)}。
   *
   * <p>不缓存 {@code Method}：它是可变的，若测试框架并行跑同一方法会有线程安全问题。
   *
   * <p><b>关于 Log.d</b>：单测 classpath 上是 AGP 的 mockable {@code android.jar}，
   * {@code android.util.Log.d} 会抛 {@code RuntimeException: Method d in
   * android.util.Log not mocked}。而 {@code writeDocs} 的<b>最后一条语句</b>正是
   * {@code Log.d("QuickDevelop", "Wrote dev docs")}，三份文档在此之前已全部落盘。
   * 这里只吞掉「Log 未 mock」这一个已知异常，其余异常照常失败——这样产物断言仍然
   * 有效，同时若有人把 Log.d 提前到写文件之前，文件断言会立刻暴露。
   */
  private static void invokeWriteDocs(File projectRoot, String packageId) {
    try {
      Method m = QuickDevelop.class.getDeclaredMethod("writeDocs", File.class, String.class);
      m.setAccessible(true);
      m.invoke(new QuickDevelop(), projectRoot, packageId);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (isAndroidLogNotMocked(cause)) {
        return;
      }
      throw new AssertionError(
          "writeDocs 抛异常: " + (cause == null ? e : cause.toString()), cause == null ? e : cause);
    } catch (NoClassDefFoundError e) {
      throw new AssertionError(
          "writeDocs 依赖的 Android 类在单测 classpath 上缺失: " + e.getMessage(), e);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("反射调用 writeDocs 失败: " + e, e);
    }
  }

  /** 是否为「android.util.Log 未 mock」这一已知的 mockable-jar 异常。 */
  private static boolean isAndroidLogNotMocked(Throwable t) {
    return t instanceof RuntimeException
        && t.getMessage() != null
        && t.getMessage().contains("android.util.Log")
        && t.getMessage().contains("not mocked");
  }

  /**
   * 测试输出的工程根：{@code core/app/build/qd-docs/}（不污染源码树，clean 会清掉）。
   *
   * <p>同时预建 {@code ui/} 与 {@code tool/} 目录：真实 {@code create()} 里
   * {@code writeComponents()}（先建 ui/ 再调 writeDocs）与 {@code writeToolkits()}
   * （先建 tool/）都在 writeDocs 之前跑，writeDocs 本身不建这两个目录——
   * 若这里不预建，writeDocs 会因父目录缺失抛 FileNotFoundException。
   */
  private static File newProjectRoot() {
    File root = new File("build/qd-docs/qdtest");
    deleteRecursively(root);
    File javaRoot = new File(root, "app/src/main/java/" + pkgPath());
    assertTrue("无法创建 ui/ 目录", new File(javaRoot, "ui").mkdirs());
    assertTrue("无法创建 tool/ 目录", new File(javaRoot, "tool").mkdirs());
    return root;
  }

  private static String pkgPath() {
    return PKG.replace('.', '/');
  }

  private static String read(File f) throws IOException {
    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
  }

  /**
   * writeDocs 必须落盘 3 份文档，且路径与 writeComponents/writeToolkits 一致。
   *
   * <p>回归用例：文档是「生成产物」的一部分，曾因测试 harness 不走这条路而完全缺失。
   */
  @Test
  public void writeDocsCreatesAllThreeDocuments() {
    File root = newProjectRoot();
    invokeWriteDocs(root, PKG);

    File projectReadme = new File(root, "README.md");
    File uiReadme = new File(root, "app/src/main/java/" + pkgPath() + "/ui/README.md");
    File toolReadme = new File(root, "app/src/main/java/" + pkgPath() + "/tool/README.md");

    assertTrue("缺工程根 README.md: " + projectReadme, projectReadme.isFile());
    assertTrue("缺 ui/README.md: " + uiReadme, uiReadme.isFile());
    assertTrue("缺 tool/README.md: " + toolReadme, toolReadme.isFile());

    assertTrue("工程 README 为空", projectReadme.length() > 0);
    assertTrue("ui/README 为空", uiReadme.length() > 0);
    assertTrue("tool/README 为空", toolReadme.length() > 0);
  }

  /**
   * 工程 README 必须含关键小节（目录 / 结构 / 上手 / 选型），且**标题必须顶格**。
   *
   * <p>为什么断言顶格：曾有一个真实缺陷——{@code projectReadme} 把多行、已去缩进的
   * {@code 选型指引()} 直接内嵌进模板，其第 1 列缩进被 {@code trimIndent()} 计入公共缩进，
   * 导致整份 README 每行都留 6 个前导空格，Markdown 会渲染成代码块。
   * 现改用单行占位符 + trimIndent 后 replace（{@code QuickDevelop.kt}），此断言锁住它。
   */
  @Test
  public void projectReadmeHasKeySections() throws IOException {
    File root = newProjectRoot();
    invokeWriteDocs(root, PKG);
    String md = read(new File(root, "README.md"));

    // 标题必须顶格（无前导空格）——否则整份文档会被 Markdown 当代码块。
    assertTrue("README 标题未顶格（有前导空格）: [" + md.split("\n")[0] + "]", md.startsWith("# " + PKG));
    assertTrue("缺「项目结构」", md.contains("\n## 项目结构"));
    assertTrue("缺「快速上手」", md.contains("\n## 快速上手"));
    assertTrue("缺「该用哪一套」选型指引", md.contains("该用哪一套"));
    assertTrue("缺「构建」", md.contains("\n## 构建"));
    // 文档里的相对链接（用点号包名，与 projectReadme 的写法一致）。
    assertTrue("缺 ui/README 链接", md.contains("[ui/README.md](app/src/main/java/" + PKG + "/ui/README.md)"));
    assertTrue("缺 tool/README 链接", md.contains("[tool/README.md](app/src/main/java/" + PKG + "/tool/README.md)"));
    // 选型指引的二级标题也必须顶格（它经 replace 插入，不能带缩进）。
    assertTrue("选型指引未顶格", md.contains("\n## 该用哪一套"));
  }

  /** ui/README 必须含「通用样式方法」与目录锚点。 */
  @Test
  public void uiReadmeHasStyleSection() throws IOException {
    File root = newProjectRoot();
    invokeWriteDocs(root, PKG);
    String md = read(new File(root, "app/src/main/java/" + pkgPath() + "/ui/README.md"));

    assertTrue("缺「通用样式方法」", md.contains("## 通用样式方法"));
    assertTrue("缺目录", md.contains("## 目录"));
    assertTrue("缺「容器类」分区", md.contains("## 容器类"));
  }

  /** tool/README 必须非空且覆盖 5 个工具类。 */
  @Test
  public void toolReadmeIsNotEmptyAndCoversKits() throws IOException {
    File root = newProjectRoot();
    invokeWriteDocs(root, PKG);
    String md = read(new File(root, "app/src/main/java/" + pkgPath() + "/tool/README.md"));

    assertFalse("tool/README 为空", md.trim().isEmpty());
    for (String kit : new String[] {"字符", "文件", "数据", "工具", "系统"}) {
      assertTrue("tool/README 未覆盖工具类: " + kit, md.contains("## " + kit));
    }
  }

  /**
   * 文档里宣称的中文组件数量必须等于真实计数。
   *
   * <p><b>这是本测试最重要的断言</b>：工程 README 曾硬编码「44 个中文组件」，
   * 而真实已是 45（{@code BASE_CN_NAMES}(7) + {@code EXTRA_WIDGETS}(38)）。
   * 文档说 44、代码有 45，用户按文档找第 45 个控件会找不到。
   */
  @Test
  public void componentCountInDocsMatchesRealCount() throws IOException {
    int real = QuickDevelopSources.INSTANCE.getCHINESE_COMPONENT_COUNT();
    assertEquals("真实中文组件数应是 7 + 38", 45, real);

    File root = newProjectRoot();
    invokeWriteDocs(root, PKG);
    String projectReadme = read(new File(root, "README.md"));
    String uiReadme = read(new File(root, "app/src/main/java/" + pkgPath() + "/ui/README.md"));

    assertTrue(
        "工程 README 未出现真实数量 " + real + "，可能仍硬编码 44",
        projectReadme.contains(real + " 个中文组件"));
    assertTrue(
        "ui/README 未出现真实数量 " + real,
        uiReadme.contains(real + " 个中文组件"));
    assertFalse("工程 README 残留旧数量 44", projectReadme.contains("44 个中文组件"));
    assertFalse("ui/README 残留旧数量 44", uiReadme.contains("44 个中文组件"));
  }

  /**
   * 工程 README 里「该用哪一套」的数量声明也必须与真实一致（回归 44 vs 45）。
   *
   * <p>选型指引由 {@code QuickDevelopTicode.选型指引()} 产出、拼进工程 README，
   * 曾经写死 `ui/ 44/44`。
   */
  @Test
  public void selectionGuideCountMatchesRealCount() throws IOException {
    int real = QuickDevelopSources.INSTANCE.getCHINESE_COMPONENT_COUNT();

    File root = newProjectRoot();
    invokeWriteDocs(root, PKG);
    String md = read(new File(root, "README.md"));

    // 选型指引里的写法是 `ui/` N/N（中文名带反引号）。
    assertTrue("选型指引未用真实数量: `ui/` " + real + "/" + real, md.contains("`ui/` " + real + "/" + real));
    assertFalse("选型指引残留旧数量 `ui/` 44/44", md.contains("`ui/` 44/44"));
  }

  /**
   * ticode 的 README 内容直接断言（{@code readme()} 无参数，不需要 Context）。
   *
   * <p>写文件那一步在 {@code writeTicodeModule(context, …)} 里，需要 Context；
   * 这里锁住内容本身，避免它被改成空壳。
   */
  @Test
  public void ticodeReadmeContentIsComplete() {
    String md = QuickDevelopTicode.INSTANCE.readme();

    assertTrue("ticode README 未说明来源", md.contains("结绳"));
    assertTrue("ticode README 未给包名", md.contains("ticode.zh"));
    assertTrue("ticode README 未给用法", md.contains("加解密操作"));
    // 四个子包都应列出
    for (String sub : new String[] {"ticode.zh.base", "ticode.zh.jvm", "ticode.zh.android", "ticode.zh.meng"}) {
      assertTrue("ticode README 未列出子包: " + sub, md.contains(sub));
    }
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
