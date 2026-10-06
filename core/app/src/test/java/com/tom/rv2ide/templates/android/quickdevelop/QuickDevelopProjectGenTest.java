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
import static org.junit.Assert.assertTrue;

import com.tom.androidcodestudio.project.manager.builder.ActivityWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/**
 * 在 JVM 上生成一个<b>完整可构建</b>的 Quick Develop 工程到 {@code build/qd-project/}。
 *
 * <h3>为什么要这个</h3>
 *
 * {@link QuickDevelopSourcesTest} / {@link QuickDevelopTicodeTest} 只断言「生成的字符串
 * 长什么样」。但真正要回答的是：<b>把模板产出的工程交给 Gradle，它能构建成功吗？</b>
 * 这个测试复用模板用的同一批 writer + 字符串生成器，产出完整工程树（含
 * settings/build.gradle/libs.versions.toml/app/ticode），再交给外部
 * {@code tools/build-qd-project.sh} 用真实 Gradle 构建。
 *
 * <p>这是「端到端验证」的第一段：先把「模板产物是否正确」与「真机能否构建」解耦——
 * 本机能构建成功，说明产物本身没问题，真机失败就只可能是设备网络/环境。
 */
public class QuickDevelopProjectGenTest {

  private static final String PKG = "com.example.qdtest";
  private static final String PROJECT_NAME = "qdtest";

  /** compileSdk 取本机存在的 android-36（与模板 PROJECTS_COMPILE_SDK_VERSION 一致）。 */
  private static final int COMPILE_SDK = 36;

  private static final int MIN_SDK = 24;

  @Test
  public void generateFullProjectForGradleBuild() throws IOException {
    File root = new File("build/qd-project");
    deleteRecursively(root);
    assertTrue(root.mkdirs());

    // ---- settings.gradle.kts：include(":app", ":ticode") ----
    // 说明：settings 的 DSL 是 Kotlin 扩展函数（`settingsGradleConfig { }`），从 Java
    // 调用要造 Function1 很别扭；这里直接写文件，内容与 RepositoryPresets + include 等价。
    // 真正被构建校验的是 build.gradle 与源码树。
    Files.write(
        new File(root, "settings.gradle.kts").toPath(),
        ("pluginManagement {\n"
                + "    repositories { google(); mavenCentral(); gradlePluginPortal() }\n"
                + "}\n"
                + "dependencyResolutionManagement {\n"
                + "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n"
                + "    repositories { google(); mavenCentral() }\n"
                + "}\n"
                + "rootProject.name = \""
                + PROJECT_NAME
                + "\"\n"
                + "include(\":app\")\n"
                + "include(\":ticode\")\n")
            .getBytes(StandardCharsets.UTF_8));

    // ---- gradle/libs.versions.toml ----
    File gradleDir = new File(root, "gradle");
    assertTrue(gradleDir.mkdirs());
    writeCatalog(gradleDir);

    // ---- 顶层 build.gradle.kts ----
    writeTopLevel(root);

    // ---- gradle.properties ----
    // GradlePropertiesPresets.STANDARD_ANDROID 是 private；这里写等价内容（关键两行是
    // android.useAndroidX 与 nonTransitiveRClass，缺了 AGP 会报错）。
    Files.write(
        new File(root, "gradle.properties").toPath(),
        ("org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8\n"
                + "android.useAndroidX=true\n"
                + "android.nonTransitiveRClass=true\n"
                + "kotlin.code.style=official\n")
            .getBytes(StandardCharsets.UTF_8));

    // ---- :app ----
    writeAppModule(root);

    // ---- :ticode ----
    writeTicodeModule(root);

    // ---- 断言产物结构 ----
    assertTrue("缺 settings", new File(root, "settings.gradle.kts").exists());
    assertTrue("缺 :ticode/build.gradle.kts", new File(root, "ticode/build.gradle.kts").exists());
    assertTrue("缺 :app/build.gradle.kts", new File(root, "app/build.gradle.kts").exists());
    int n = countJava(new File(root, "ticode/src/main/java"));
    assertEquals(QuickDevelopTicode.EXPECTED_SOURCE_COUNT, n);
    assertTrue("缺 MainActivity", new File(root, "app/src/main/java/com/example/qdtest/MainActivity.java").exists());
  }

  private void writeCatalog(File gradleDir) throws IOException {
    // 直接用模板的 versionCatalogWriter 太重（Java 调 Kotlin DSL）；这里写等价内容。
    // 版本与 utilities/templates-api/constants.kt 一致。
    String toml =
        "[versions]\n"
            + "agp = \"8.13.0\"\n"
            + "core = \"1.17.0\"\n"
            + "appcompat = \"1.7.1\"\n"
            + "material = \"1.13.0\"\n"
            + "constraintlayout = \"2.2.1\"\n"
            + "recyclerview = \"1.3.2\"\n"
            + "viewpager2 = \"1.1.0\"\n"
            + "swiperefreshlayout = \"1.1.0\"\n"
            + "cardview = \"1.0.0\"\n"
            + "drawerlayout = \"1.2.0\"\n"
            + "coordinatorlayout = \"1.2.0\"\n"
            + "flexbox = \"3.0.0\"\n"
            + "\n[libraries]\n"
            + "androidx-core = { group = \"androidx.core\", name = \"core\", version.ref = \"core\" }\n"
            + "androidx-appcompat = { group = \"androidx.appcompat\", name = \"appcompat\", version.ref = \"appcompat\" }\n"
            + "material = { group = \"com.google.android.material\", name = \"material\", version.ref = \"material\" }\n"
            + "androidx-constraintlayout = { group = \"androidx.constraintlayout\", name = \"constraintlayout\", version.ref = \"constraintlayout\" }\n"
            + "androidx-recyclerview = { group = \"androidx.recyclerview\", name = \"recyclerview\", version.ref = \"recyclerview\" }\n"
            + "androidx-viewpager2 = { group = \"androidx.viewpager2\", name = \"viewpager2\", version.ref = \"viewpager2\" }\n"
            + "androidx-swiperefreshlayout = { group = \"androidx.swiperefreshlayout\", name = \"swiperefreshlayout\", version.ref = \"swiperefreshlayout\" }\n"
            + "androidx-cardview = { group = \"androidx.cardview\", name = \"cardview\", version.ref = \"cardview\" }\n"
            + "androidx-drawerlayout = { group = \"androidx.drawerlayout\", name = \"drawerlayout\", version.ref = \"drawerlayout\" }\n"
            + "androidx-coordinatorlayout = { group = \"androidx.coordinatorlayout\", name = \"coordinatorlayout\", version.ref = \"coordinatorlayout\" }\n"
            + "androidx-flexbox = { group = \"com.google.android.flexbox\", name = \"flexbox\", version.ref = \"flexbox\" }\n"
            + "\n[plugins]\n"
            + "android-application = { id = \"com.android.application\", version.ref = \"agp\" }\n"
            + "android-library = { id = \"com.android.library\", version.ref = \"agp\" }\n";
    Files.write(new File(gradleDir, "libs.versions.toml").toPath(), toml.getBytes(StandardCharsets.UTF_8));
  }

  private void writeTopLevel(File root) throws IOException {
    String content =
        "plugins {\n"
            + "    alias(libs.plugins.android.application) apply false\n"
            + "    alias(libs.plugins.android.library) apply false\n"
            + "}\n";
    Files.write(new File(root, "build.gradle.kts").toPath(), content.getBytes(StandardCharsets.UTF_8));
  }

  private void writeAppModule(File root) throws IOException {
    String pkgPath = PKG.replace('.', '/');
    File appDir = new File(root, "app");
    File javaDir = new File(appDir, "src/main/java/" + pkgPath);
    assertTrue(javaDir.mkdirs());

    String build =
        "plugins {\n"
            + "    alias(libs.plugins.android.application)\n"
            + "}\n"
            + "android {\n"
            + "    namespace = \""
            + PKG
            + "\"\n"
            + "    compileSdk = "
            + COMPILE_SDK
            + "\n"
            + "    defaultConfig {\n"
            + "        applicationId = \""
            + PKG
            + "\"\n"
            + "        minSdk = "
            + MIN_SDK
            + "\n"
            + "        targetSdk = "
            + COMPILE_SDK
            + "\n"
            + "        versionCode = 1\n"
            + "        versionName = \"1.0\"\n"
            + "    }\n"
            + "    compileOptions {\n"
            + "        sourceCompatibility = JavaVersion.VERSION_17\n"
            + "        targetCompatibility = JavaVersion.VERSION_17\n"
            + "    }\n"
            + "}\n"
            + "dependencies {\n"
            + "    implementation(libs.androidx.core)\n"
            + "    implementation(libs.androidx.appcompat)\n"
            + "    implementation(libs.material)\n"
            + "    implementation(libs.androidx.constraintlayout)\n"
            + "    implementation(libs.androidx.recyclerview)\n"
            + "    implementation(libs.androidx.viewpager2)\n"
            + "    implementation(libs.androidx.swiperefreshlayout)\n"
            + "    implementation(libs.androidx.cardview)\n"
            + "    implementation(libs.androidx.drawerlayout)\n"
            + "    implementation(libs.androidx.coordinatorlayout)\n"
            + "    implementation(project(\":ticode\"))\n"
            + "}\n";
    Files.write(new File(appDir, "build.gradle.kts").toPath(), build.getBytes(StandardCharsets.UTF_8));

    // 组件 + 工具库 + MainActivity（复用真实生成器）
    File uiDir = new File(javaDir, "ui");
    assertTrue(uiDir.mkdirs());
    for (kotlin.Pair<String, String> c : QuickDevelopSources.INSTANCE.components(PKG)) {
      Files.write(new File(uiDir, c.getFirst() + ".java").toPath(), c.getSecond().getBytes(StandardCharsets.UTF_8));
    }
    File toolDir = new File(javaDir, "tool");
    assertTrue(toolDir.mkdirs());
    for (kotlin.Pair<String, String> t : QuickDevelopToolkits.INSTANCE.all(PKG)) {
      Files.write(new File(toolDir, t.getFirst() + ".java").toPath(), t.getSecond().getBytes(StandardCharsets.UTF_8));
    }
    Files.write(
        new File(javaDir, "MainActivity.java").toPath(),
        QuickDevelopSources.INSTANCE.mainActivityJava(PKG).getBytes(StandardCharsets.UTF_8));

    // manifest + strings
    new ActivityWriter()
        .createFile(new File(appDir, "src/main"), "AndroidManifest", "xml", QuickDevelopSources.INSTANCE.manifestXml());
    new ActivityWriter()
        .createFile(
            new File(appDir, "src/main/res/values"),
            "strings",
            "xml",
            "<resources><string name=\"app_name\">" + PROJECT_NAME + "</string></resources>");

    // res：图标 / 主题 / colors —— 从 assets 拷。真实模板由 copyResourceFiles 做
    // （需要 Android Context），这里直接拷 assets 目录，等价。
    copyTree(new File("src/main/assets/QuickDevelop/resources"), new File(appDir, "src/main/res"));
  }

  private void writeTicodeModule(File root) throws IOException {
    File ticodeDir = new File(root, "ticode");
    File srcRoot = new File(ticodeDir, "src/main/java");
    assertTrue(srcRoot.mkdirs());
    // 从 assets 拷源码
    File assets = new File("src/main/assets/" + QuickDevelopTicode.ASSETS_PATH);
    copyTree(assets, srcRoot);
    // build.gradle.kts（复用真实生成器）
    Files.write(
        new File(ticodeDir, "build.gradle.kts").toPath(),
        QuickDevelopTicode.INSTANCE
            .moduleGradle(true, COMPILE_SDK, MIN_SDK)
            .getBytes(StandardCharsets.UTF_8));
  }

  // ---------------------------------------------------------------------------------------

  private static void copyTree(File src, File dst) throws IOException {
    File[] children = src.listFiles();
    if (children == null) {
      return;
    }
    for (File c : children) {
      File t = new File(dst, c.getName());
      if (c.isDirectory()) {
        // 目标目录可能已存在（如 res/values 先由 strings 写过），mkdirs 幂等
        t.mkdirs();
        copyTree(c, t);
      } else {
        Files.copy(c.toPath(), t.toPath());
      }
    }
  }

  private static int countJava(File dir) {
    int n = 0;
    File[] children = dir.listFiles();
    if (children == null) {
      return 0;
    }
    for (File c : children) {
      if (c.isDirectory()) {
        n += countJava(c);
      } else if (c.getName().endsWith(".java")) {
        n++;
      }
    }
    return n;
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
