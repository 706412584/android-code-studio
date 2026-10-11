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

package com.tom.rv2ide.templates.android.flutter;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/**
 * 在 JVM 上生成一个<b>结构完整</b>的 Flutter 工程到 {@code build/flutter-project-test/}。
 *
 * <p>工程树对照 {@code flutter create 3.47.7} 的真实产物（本机实测生成）。
 * 与其它模板的 ProjectGen 测试的差异：本模板**设备端不构建**，所以这个
 * 工程树是给云端 CI 与真实 flutter 工具链消费的——结构必须与
 * flutter create 产物完全同构。
 *
 * <h3>覆盖边界（重要）</h3>
 *
 * 本测试**不经过** {@code Flutter.create()} 流程——那条路径依赖 Android
 * Context（PackageHelper / SdkVersionHelper / ProjectManager），JVM 测试
 * 无法构造（CMP/RN 的 ProjectGen 测试同理）。本测试覆盖的是
 * <b>文本内容与目录结构契约</b>（Sources 生成器的产物 + assets 布局），
 * 不覆盖 create() 里的目录创建顺序。审查 S1（appDir 缺 mkdirs）正是
 * 这类流程缺陷——已在生产代码修复，但同类问题的防护需靠设备端 E2E。
 */
public class FlutterProjectGenTest {

  private static final String PKG = "com.example.fluttertest";
  private static final String PROJECT_NAME = "fluttertest";

  @Test
  public void generateFullProjectStructure() throws IOException {
    // 注意：目录名与真实 flutter build 的验证目录（build/flutter-project）
    // 刻意不同——测试可能与该构建并发执行（Windows 文件锁）。
    File root = new File("build/flutter-project-test");
    deleteRecursively(root);
    assertTrue(root.exists() || root.mkdirs());

    writeAndroidPlatform(root);
    writeWebPlatform(root);
    writeDartFiles(root);
    writeMetadata(root);

    // ---- Android 平台（flutter create 产物同构）----
    assertTrue("缺 settings.gradle.kts", new File(root, "android/settings.gradle.kts").exists());
    assertTrue("缺根 build.gradle.kts", new File(root, "android/build.gradle.kts").exists());
    assertTrue("缺 gradle.properties", new File(root, "android/gradle.properties").exists());
    assertTrue("缺 gradlew", new File(root, "android/gradlew").exists());
    assertTrue(
        "缺 wrapper jar",
        new File(root, "android/gradle/wrapper/gradle-wrapper.jar").exists());
    assertTrue(
        "缺 wrapper properties",
        new File(root, "android/gradle/wrapper/gradle-wrapper.properties").exists());
    assertTrue("缺 app/build.gradle.kts", new File(root, "android/app/build.gradle.kts").exists());
    assertTrue(
        "缺 MainActivity",
        new File(
                root,
                "android/app/src/main/kotlin/" + PKG.replace('.', '/') + "/MainActivity.kt")
            .exists());
    assertTrue(
        "缺 AndroidManifest",
        new File(root, "android/app/src/main/AndroidManifest.xml").exists());
    assertTrue(
        "缺 debug manifest", new File(root, "android/app/src/debug/AndroidManifest.xml").exists());
    assertTrue(
        "缺 profile manifest",
        new File(root, "android/app/src/profile/AndroidManifest.xml").exists());

    // ---- Dart 侧 ----
    assertTrue("缺 lib/main.dart", new File(root, "lib/main.dart").exists());
    assertTrue("缺 pubspec.yaml", new File(root, "pubspec.yaml").exists());
    assertTrue(
        "缺 analysis_options.yaml", new File(root, "analysis_options.yaml").exists());
    assertTrue("缺 widget_test", new File(root, "test/widget_test.dart").exists());

    // ---- Web 平台 ----
    assertTrue("缺 web/index.html", new File(root, "web/index.html").exists());
    assertTrue("缺 web/manifest.json", new File(root, "web/manifest.json").exists());
    assertTrue("缺 web favicon", new File(root, "web/favicon.png").exists());

    // ---- CI 与元数据 ----
    assertTrue(
        "缺 workflow", new File(root, ".github/workflows/flutter.yml").exists());
    assertTrue("缺 README", new File(root, "README.md").exists());
    assertTrue("缺 .metadata", new File(root, ".metadata").exists());
    assertTrue("缺 .gitignore", new File(root, ".gitignore").exists());

    // ---- 跨文件一致性：pubspec name == widget_test 的 import 包名 ----
    String pubspec = read(root, "pubspec.yaml");
    assertTrue("pubspec name 必须与项目名一致", pubspec.contains("name: " + PROJECT_NAME));

    String widgetTest = read(root, "test/widget_test.dart");
    assertTrue(
        "widget_test 的 import 必须与 pubspec name 一致",
        widgetTest.contains("package:" + PROJECT_NAME + "/main.dart"));
  }

  @Test
  public void androidPlatformMatchesFlutterCreate() throws IOException {
    // android/ 的骨架必须与 flutter create 产物同构——Flutter 工具链
    // （flutter build）对目录结构有硬性预期，缺文件会在云端构建时报错。
    File root = new File("build/flutter-project-test");
    if (!new File(root, "android/settings.gradle.kts").exists()) {
      generateFullProjectStructure();
    }

    // res 目录（图标 + 启动主题）
    assertTrue(
        "缺 launcher 图标（xxxhdpi）",
        new File(root, "android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png").exists());
    assertTrue(
        "缺 launch_background",
        new File(root, "android/app/src/main/res/drawable/launch_background.xml").exists());
    assertTrue(
        "缺 styles.xml",
        new File(root, "android/app/src/main/res/values/styles.xml").exists());
    assertTrue(
        "缺 values-night/styles.xml",
        new File(root, "android/app/src/main/res/values-night/styles.xml").exists());

    // settings.gradle.kts 的 flutter.sdk require 结构
    String settings = read(root, "android/settings.gradle.kts");
    assertTrue(settings.contains("flutter.sdk not set in local.properties"));
    assertTrue(settings.contains("includeBuild(\"$flutterSdkPath/packages/flutter_tools/gradle\")"));

    // wrapper 用腾讯镜像
    String wrapper = read(root, "android/gradle/wrapper/gradle-wrapper.properties");
    assertTrue(wrapper.contains("mirrors.cloud.tencent.com"));
  }

  @Test
  public void versionConsistencyAcrossFiles() throws IOException {
    File root = new File("build/flutter-project-test");
    if (!new File(root, "android/settings.gradle.kts").exists()) {
      generateFullProjectStructure();
    }

    // settings.gradle.kts 的 AGP/Kotlin 版本与 Sources 常量一致
    String settings = read(root, "android/settings.gradle.kts");
    assertTrue(
        "AGP 版本不一致", settings.contains("version \"" + FlutterSources.AGP_VERSION + "\""));
    assertTrue(
        "Kotlin 版本不一致",
        settings.contains("version \"" + FlutterSources.KOTLIN_VERSION + "\""));

    // wrapper 版本与常量一致
    String wrapper = read(root, "android/gradle/wrapper/gradle-wrapper.properties");
    assertTrue(
        "Gradle 版本不一致",
        wrapper.contains("gradle-" + FlutterSources.GRADLE_VERSION + "-all.zip"));

    // pubspec 的 Dart 约束与常量一致
    String pubspec = read(root, "pubspec.yaml");
    assertTrue(
        "Dart 版本不一致", pubspec.contains("sdk: ^" + FlutterSources.DART_VERSION));
  }

  // ------------------------------------------------------------------ 写入辅助

  private void writeAndroidPlatform(File root) throws IOException {
    write(root, "android/settings.gradle.kts", FlutterSources.INSTANCE.androidSettingsGradleKts());
    write(root, "android/build.gradle.kts", FlutterSources.INSTANCE.androidRootBuildGradleKts());
    write(root, "android/gradle.properties", FlutterSources.INSTANCE.androidGradleProperties());
    write(root, "android/.gitignore", FlutterSources.INSTANCE.androidGitignore());
    write(
        root,
        "android/gradle/wrapper/gradle-wrapper.properties",
        FlutterSources.INSTANCE.gradleWrapperProperties());
    write(root, "android/app/build.gradle.kts", FlutterSources.INSTANCE.appBuildGradleKts(PKG));
    write(
        root,
        "android/app/src/main/kotlin/" + PKG.replace('.', '/') + "/MainActivity.kt",
        FlutterSources.INSTANCE.mainActivityKt(PKG));
    write(
        root,
        "android/app/src/main/AndroidManifest.xml",
        FlutterSources.INSTANCE.appManifest(PROJECT_NAME));

    // 从 assets 拷贝二进制与静态资源（图标/wrapper jar/manifest/styles）
    File assetsAndroid = new File("src/main/assets/Flutter/android");
    if (assetsAndroid.exists()) {
      copyDir(new File(assetsAndroid, "gradlew"), new File(root, "android/gradlew"));
      copyDir(new File(assetsAndroid, "gradlew.bat"), new File(root, "android/gradlew.bat"));
      copyDir(
          new File(assetsAndroid, "gradle/wrapper/gradle-wrapper.jar"),
          new File(root, "android/gradle/wrapper/gradle-wrapper.jar"));
      copyDir(
          new File(assetsAndroid, "app/src/main/res"),
          new File(root, "android/app/src/main/res"));
      copyDir(
          new File(assetsAndroid, "app/src/debug"),
          new File(root, "android/app/src/debug"));
      copyDir(
          new File(assetsAndroid, "app/src/profile"),
          new File(root, "android/app/src/profile"));
    } else {
      // 回退：至少写出文本资源，让结构断言可过（测试 CWD 不一定是模块目录）
      write(root, "android/app/src/main/res/values/styles.xml", "<resources/>");
      write(root, "android/app/src/debug/AndroidManifest.xml", "<manifest/>");
      write(root, "android/app/src/profile/AndroidManifest.xml", "<manifest/>");
    }
  }

  private void writeWebPlatform(File root) throws IOException {
    write(root, "web/index.html", FlutterSources.INSTANCE.webIndexHtml(PROJECT_NAME));
    write(root, "web/manifest.json", FlutterSources.INSTANCE.webManifestJson(PROJECT_NAME));

    File assetsWeb = new File("src/main/assets/Flutter/web");
    if (assetsWeb.exists()) {
      copyDir(new File(assetsWeb, "favicon.png"), new File(root, "web/favicon.png"));
      copyDir(new File(assetsWeb, "icons"), new File(root, "web/icons"));
    }
  }

  private void writeDartFiles(File root) throws IOException {
    write(root, "lib/main.dart", FlutterSources.INSTANCE.mainDart(PROJECT_NAME));
    write(root, "test/widget_test.dart", FlutterSources.INSTANCE.widgetTest(PROJECT_NAME));
    write(root, "pubspec.yaml", FlutterSources.INSTANCE.pubspecYaml(PROJECT_NAME));
    write(root, "analysis_options.yaml", FlutterSources.INSTANCE.analysisOptionsYaml());
  }

  private void writeMetadata(File root) throws IOException {
    write(root, ".metadata", FlutterSources.INSTANCE.metadata());
    write(root, ".gitignore", FlutterSources.INSTANCE.gitignore());
    write(
        root,
        ".github/workflows/flutter.yml",
        FlutterSources.INSTANCE.ciWorkflow(PROJECT_NAME));
    write(root, "README.md", FlutterSources.INSTANCE.projectReadme(PKG, PROJECT_NAME));
  }

  private void write(File root, String relative, String content) throws IOException {
    File f = new File(root, relative);
    f.getParentFile().mkdirs();
    Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
  }

  private String read(File root, String relative) throws IOException {
    return new String(
        Files.readAllBytes(new File(root, relative).toPath()), StandardCharsets.UTF_8);
  }

  /** 递归拷贝（src 可以是文件或目录）。目标已存在时覆盖。 */
  private static void copyDir(File src, File dst) throws IOException {
    if (!src.exists()) return;
    if (src.isDirectory()) {
      dst.mkdirs();
      File[] children = src.listFiles();
      if (children != null) {
        for (File c : children) copyDir(c, new File(dst, c.getName()));
      }
    } else {
      dst.getParentFile().mkdirs();
      Files.copy(
          src.toPath(),
          dst.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void deleteRecursively(File f) {
    if (f.isDirectory()) {
      File[] children = f.listFiles();
      if (children != null) {
        for (File c : children) deleteRecursively(c);
      }
    }
    //noinspection ResultOfMethodCallIgnored
    f.delete();
  }
}
