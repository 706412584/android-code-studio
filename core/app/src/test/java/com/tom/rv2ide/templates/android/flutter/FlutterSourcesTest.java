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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 字符串级断言：Flutter 模板生成的文本里必须出现（或不出现）哪些关键内容。
 *
 * <p>这里只做「长什么样」的校验；「工程结构齐备」由
 * {@code FlutterProjectGenTest} 断言。
 */
public class FlutterSourcesTest {

  private static final String PKG = "com.example.fluttertest";
  private static final String PROJECT = "fluttertest";

  // ------------------------------------------------------------------ 版本

  @Test
  public void versionMatrix_matchesFlutterCreateOutput() {
    // 这些值来自 flutter create 3.47.7 的真实产物（templateDefault* 常量），
    // 升级 Flutter 时全套一起换。
    assertEquals("3.47.7", FlutterSources.FLUTTER_VERSION);
    assertEquals("3.13.5", FlutterSources.DART_VERSION);
    assertEquals("9.3.1", FlutterSources.GRADLE_VERSION);
    assertEquals("9.1.0", FlutterSources.AGP_VERSION);
    assertEquals("2.4.0", FlutterSources.KOTLIN_VERSION);
  }

  // ------------------------------------------------------------------ settings.gradle.kts

  @Test
  public void settingsGradle_requiresFlutterSdk() {
    // flutter.sdk 的 require 是 Flutter 工具链的硬约束——也是本模板
    // 设备端不可构建的根本原因（README 说明）。这个结构必须与
    // flutter create 产物一字不差。
    String s = FlutterSources.INSTANCE.androidSettingsGradleKts();
    assertTrue(s.contains("flutter.sdk not set in local.properties"));
    assertTrue(s.contains("includeBuild(\"$flutterSdkPath/packages/flutter_tools/gradle\")"));
    assertTrue(s.contains("id(\"dev.flutter.flutter-plugin-loader\") version \"1.0.0\""));
    assertTrue(s.contains("id(\"com.android.application\") version \"" + FlutterSources.AGP_VERSION + "\""));
    assertTrue(s.contains("id(\"org.jetbrains.kotlin.android\") version \"" + FlutterSources.KOTLIN_VERSION + "\""));
    assertTrue(s.contains("include(\":app\")"));
  }

  // ------------------------------------------------------------------ Gradle 配置

  @Test
  public void gradleProperties_hasFlutterFlags() {
    String p = FlutterSources.INSTANCE.androidGradleProperties();
    assertTrue(p.contains("android.useAndroidX=true"));
    assertTrue(p.contains("android.newDsl=false"));
    assertTrue(p.contains("android.builtInKotlin=false"));
    // 内存调低（云端 runner 7G 内存，官方 8G 堆会 OOM）
    assertTrue(p.contains("-Xmx4G"));
  }

  @Test
  public void gradleWrapper_usesTencentMirror() {
    // 云端 CI 无所谓，但用户若有桌面 Flutter 环境构建，镜像更快
    String w = FlutterSources.INSTANCE.gradleWrapperProperties();
    assertTrue(w.contains("mirrors.cloud.tencent.com/gradle/gradle-" + FlutterSources.GRADLE_VERSION + "-all.zip"));
    assertTrue("Flutter 模板默认 all 分发（IDE 需要源码）", w.contains("-all.zip"));
  }

  @Test
  public void appBuildGradle_usesFlutterPluginAndPackage() {
    String g = FlutterSources.INSTANCE.appBuildGradleKts(PKG);
    assertTrue(g.contains("id(\"com.android.application\")"));
    assertTrue(g.contains("id(\"dev.flutter.flutter-gradle-plugin\")"));
    assertTrue(g.contains("namespace = \"" + PKG + "\""));
    assertTrue(g.contains("applicationId = \"" + PKG + "\""));
    // 版本全部来自 Flutter 插件（不在模板写死）
    assertTrue(g.contains("compileSdk = flutter.compileSdkVersion"));
    assertTrue(g.contains("minSdk = flutter.minSdkVersion"));
    assertTrue(g.contains("flutter {"));
    assertTrue(g.contains("source = \"../..\""));
  }

  // ------------------------------------------------------------------ Android 壳

  @Test
  public void mainActivity_isMinimalFlutterActivity() {
    String s = FlutterSources.INSTANCE.mainActivityKt(PKG);
    assertTrue(s.contains("package " + PKG));
    assertTrue(s.contains("import io.flutter.embedding.android.FlutterActivity"));
    assertTrue(s.contains("class MainActivity : FlutterActivity()"));
  }

  @Test
  public void appManifest_hasFlutterEmbedding() {
    String m = FlutterSources.INSTANCE.appManifest(PROJECT);
    assertTrue(m.contains("android:label=\"" + PROJECT + "\""));
    assertTrue(m.contains("flutterEmbedding"));
    assertTrue(m.contains("io.flutter.embedding.android.NormalTheme"));
    assertTrue(m.contains("@style/LaunchTheme"));
    assertTrue(m.contains(".MainActivity"));
    // Flutter 的固定配置（不手改）
    assertTrue(m.contains("android:launchMode=\"singleTop\""));
    assertTrue(m.contains("android:taskAffinity=\"\""));
  }

  // ------------------------------------------------------------------ Dart 侧

  @Test
  public void pubspec_hasCorrectNameAndSdk() {
    String p = FlutterSources.INSTANCE.pubspecYaml(PROJECT);
    assertTrue(p.contains("name: " + PROJECT));
    assertTrue(p.contains("sdk: ^" + FlutterSources.DART_VERSION));
    assertTrue(p.contains("flutter_lints: ^6.0.0"));
    assertTrue(p.contains("uses-material-design: true"));
    // cupertino_icons 是 flutter create 默认依赖
    assertTrue(p.contains("cupertino_icons"));
  }

  @Test
  public void mainDart_hasCounterExample() {
    String d = FlutterSources.INSTANCE.mainDart(PROJECT);
    assertTrue(d.contains("void main()"));
    assertTrue(d.contains("runApp(const MyApp())"));
    assertTrue(d.contains("StatefulWidget"));
    assertTrue(d.contains("setState"));
    assertTrue(d.contains("_counter"));
    assertTrue(d.contains(PROJECT));
    // 只依赖 Flutter SDK 自身的 material 包（无任何第三方依赖）
    assertTrue(d.contains("import 'package:flutter/material.dart';"));
    assertFalse("示例不应引入第三方包", d.contains("package:http"));
    assertFalse("示例不应引入第三方包", d.contains("package:provider"));
  }

  @Test
  public void widgetTest_matchesMainDart() {
    String t = FlutterSources.INSTANCE.widgetTest(PROJECT);
    // 测试 import 的包名必须与 pubspec 的 name 一致
    assertTrue(t.contains("package:" + PROJECT + "/main.dart"));
    assertTrue(t.contains("MyApp"));
    assertTrue(t.contains("Icons.add"));
    assertTrue(t.contains("findsOneWidget"));
  }

  // ------------------------------------------------------------------ web

  @Test
  public void webIndex_hasBootstrap() {
    String h = FlutterSources.INSTANCE.webIndexHtml(PROJECT);
    assertTrue(h.contains("flutter_bootstrap.js"));
    assertTrue(h.contains("$FLUTTER_BASE_HREF"));
    assertTrue(h.contains("<title>" + PROJECT + "</title>"));
  }

  @Test
  public void webManifest_isValidPwa() {
    String m = FlutterSources.INSTANCE.webManifestJson(PROJECT);
    assertTrue(m.contains("\"name\": \"" + PROJECT + "\""));
    assertTrue(m.contains("Icon-192.png"));
    assertTrue(m.contains("maskable"));
    assertTrue(m.contains("standalone"));
  }

  // ------------------------------------------------------------------ CI 与 README

  @Test
  public void ciWorkflow_buildsApkAndWeb() {
    String w = FlutterSources.INSTANCE.ciWorkflow(PROJECT);
    assertTrue(w.contains("subosito/flutter-action@v2"));
    assertTrue(w.contains("flutter-version: '" + FlutterSources.FLUTTER_VERSION + "'"));
    assertTrue(w.contains("flutter build apk --release"));
    assertTrue(w.contains("flutter build web --release"));
    assertTrue(w.contains("flutter pub get"));
    // artifact 上传
    assertTrue(w.contains("actions/upload-artifact@v4"));
    assertTrue(w.contains("build/app/outputs/flutter-apk/*.apk"));
  }

  @Test
  public void readme_explainsNoDeviceBuild() {
    // README 最重要的信息：手机上不构建、云端出包
    String r = FlutterSources.INSTANCE.projectReadme(PKG, PROJECT);
    assertTrue(r.contains("不构建"));
    assertTrue(r.contains("GitHub Actions"));
    assertTrue(r.contains("flutter.sdk"));
    assertTrue(r.contains("lib/main.dart"));
    assertTrue(r.contains(FlutterSources.FLUTTER_VERSION));
  }

  @Test
  public void metadata_hasStableChannel() {
    String m = FlutterSources.INSTANCE.metadata();
    assertTrue(m.contains("channel: \"stable\""));
    assertTrue(m.contains("project_type: app"));
  }

  @Test
  public void gitignore_coversFlutterArtifacts() {
    String g = FlutterSources.INSTANCE.gitignore();
    assertTrue(g.contains(".dart_tool/"));
    assertTrue(g.contains("build/"));
    assertTrue(g.contains("local.properties"));
    assertTrue(g.contains("*.jks"));

    String a = FlutterSources.INSTANCE.androidGitignore();
    assertTrue(a.contains("gradle-wrapper.jar"));
    assertTrue(a.contains("key.properties"));
  }
}
