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

package com.tom.rv2ide.templates.android.composemultiplatform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 字符串级断言：生成的 Gradle 文本 / 源码里必须出现（或不出现）哪些关键内容。
 *
 * <p>这里只做「长什么样」的校验；「能否构建」由
 * {@code ComposeMultiplatformProjectGenTest} 产出完整工程树交给真实 Gradle。
 */
public class ComposeMultiplatformSourcesTest {

  private static final String PKG = "com.example.cmptest";
  private static final String PROJECT = "cmptest";

  // ------------------------------------------------------------------ 版本

  @Test
  public void kotlinVersion_matchesCmpRequirement() {
    // CMP 1.10.x 全平台要求 Kotlin ≥2.1.20（web/native 需 2.2）——锁 2.2.20。
    // 若改这里，先查 CMP 的 CHANGELOG 与 aar-metadata（见类注释）。
    // const val 在 Java 侧是静态字段（不走 INSTANCE.getter）。
    assertEquals("2.2.20", ComposeMultiplatformSources.KOTLIN_VERSION);
    assertEquals("1.10.3", ComposeMultiplatformSources.COMPOSE_MULTIPLATFORM_VERSION);
  }

  // ------------------------------------------------------------------ shared 模块

  @Test
  public void sharedModuleGradle_declaresAllFourPlugins() {
    String g = ComposeMultiplatformSources.INSTANCE.sharedModuleGradle(PKG, 36, 24);
    // 四个插件缺一不可：KGP、AGP library、org.jetbrains.compose（compose.* 访问器的
    // 来源）、compose compiler（与 Kotlin 同版本）。
    assertTrue(g.contains("alias(libs.plugins.kotlin.multiplatform)"));
    assertTrue(g.contains("alias(libs.plugins.android.library)"));
    assertTrue(g.contains("alias(libs.plugins.compose)"));
    assertTrue(g.contains("alias(libs.plugins.kotlin.compose)"));
  }

  @Test
  public void sharedModuleGradle_declaresThreeTargets() {
    String g = ComposeMultiplatformSources.INSTANCE.sharedModuleGradle(PKG, 36, 24);
    assertTrue("缺 androidTarget", g.contains("androidTarget"));
    assertTrue("缺 desktop(jvm) target", g.contains("jvm(\"desktop\")"));
    assertTrue("缺 wasmJs target", g.contains("wasmJs"));
    assertTrue("wasm 需要 binaries.executable()", g.contains("binaries.executable()"));
  }

  @Test
  public void sharedModuleGradle_declaresIosTargets() {
    // targets 改动后 iOS targets 已声明（macos runner 构建未签名 framework）。
    // 声明不影响本地构建：KMP 按平台惰性编译，设备端不会触发 iOS 任务。
    String g = ComposeMultiplatformSources.INSTANCE.sharedModuleGradle(PKG, 36, 24);
    assertTrue(g.contains("iosArm64"));
    assertTrue(g.contains("iosSimulatorArm64"));
  }

  @Test
  public void sharedModuleGradle_substitutesPlaceholders() {
    String g = ComposeMultiplatformSources.INSTANCE.sharedModuleGradle(PKG, 36, 24);
    // trimIndent 陷阱的回归断言：占位符必须全部被替换，不能残留
    assertFalse("残留 @PACKAGE_ID@", g.contains("@PACKAGE_ID@"));
    assertFalse("残留 @COMPILE_SDK@", g.contains("@COMPILE_SDK@"));
    assertFalse("残留 @MIN_SDK@", g.contains("@MIN_SDK@"));
    assertTrue(g.contains("namespace = \"" + PKG + ".shared\""));
    assertTrue(g.contains("compileSdk = 36"));
    assertTrue(g.contains("minSdk = 24"));
  }

  @Test
  public void sharedModuleGradle_usesComposeAccessorsNotBom() {
    // 不用 androidx.compose BOM：CMP 构件自带统一版本，混用 BOM 会拉进两套版本。
    String g = ComposeMultiplatformSources.INSTANCE.sharedModuleGradle(PKG, 36, 24);
    assertTrue(g.contains("implementation(compose.runtime)"));
    assertTrue(g.contains("implementation(compose.material3)"));
    assertFalse(g.contains("compose-bom"));
    assertFalse(g.contains("androidx.compose.ui:ui"));
  }

  // ------------------------------------------------------------------ desktop 模块

  @Test
  public void desktopModuleGradle_configuresUberJarPath() {
    String g = ComposeMultiplatformSources.INSTANCE.desktopModuleGradle(PKG, PROJECT);
    // fat jar 任务是 packageUberJarForCurrentOS（不是 packageDistributionForCurrentOS，
    // 那是聚合任务，Linux 上出 deb/rpm）——这个任务名只出现在 workflow 注释里。
    assertTrue(g.contains("compose.desktop"));
    assertTrue(g.contains("mainClass = \"" + PKG + ".MainKt\""));
    assertTrue(g.contains("implementation(compose.desktop.currentOs)"));
    assertTrue(g.contains("implementation(project(\":composeApp\"))"));
    assertFalse("残留 @PROJECT_NAME@", g.contains("@PROJECT_NAME@"));
  }

  // ------------------------------------------------------------------ app 壳

  @Test
  public void appManifest_usesSystemTheme() {
    String m = ComposeMultiplatformSources.INSTANCE.appManifest();
    assertTrue(m.contains("android:theme=\"@style/Theme.CmpApp\""));
    // 不依赖 appcompat/Material3 XML 主题（Compose 自绘全部内容）
    assertFalse(m.contains("AppCompat"));
    assertTrue(m.contains("android:name=\".MainActivity\""));
  }

  @Test
  public void mainActivity_pointsAtSharedApp() {
    String k = ComposeMultiplatformSources.INSTANCE.mainActivity(PKG);
    assertTrue(k.contains("package " + PKG));
    assertTrue(k.contains("import " + PKG + ".shared.App"));
    assertTrue(k.contains("setContent"));
  }

  @Test
  public void sharedApp_isKotlinAndCounterDemo() {
    String k = ComposeMultiplatformSources.INSTANCE.sharedApp(PKG);
    assertTrue(k.contains("package " + PKG + ".shared"));
    assertTrue("@Composable 缺失", k.contains("@Composable"));
    assertTrue("material3 组件缺失", k.contains("androidx.compose.material3"));
    // Kotlin 字符串模板 $count 必须原样出现在生成物里（生成器侧用了 ${'$'} 转义）
    assertTrue(k.contains("点击了 $count 次"));
  }

  @Test
  public void desktopMain_startsComposeWindow() {
    String k = ComposeMultiplatformSources.INSTANCE.desktopMain(PKG);
    assertTrue(k.contains("package " + PKG));
    assertTrue(k.contains("fun main() = application"));
    assertTrue(k.contains(PKG + ".shared.App"));
  }

  // ------------------------------------------------------------------ workflow / README

  @Test
  public void ciWorkflow_coversThreePlatforms() {
    String w = ComposeMultiplatformSources.INSTANCE.ciWorkflow(PROJECT);
    assertTrue("缺 android job", w.contains(":app:assembleDebug"));
    assertTrue("缺 desktop fat jar job", w.contains(":desktop:packageUberJarForCurrentOS"));
    assertTrue("缺 web wasm job", w.contains(":composeApp:wasmJsBrowserDistribution"));
    // iOS 已启用（targets 改动）：macos runner 出未签名 framework
    assertTrue("缺 ios job", w.contains(":composeApp:linkDebugFrameworkIosArm64"));
    assertTrue("缺 ios runner", w.contains("runs-on: macos-latest"));
    // 每个 job 都受 targets input 控制
    assertTrue(w.contains("github.event.inputs.targets == 'all'"));
    assertTrue(w.contains("contains(github.event.inputs.targets, 'android')"));
  }

  @Test
  public void readme_mentionsAllModules() {
    String r = ComposeMultiplatformSources.INSTANCE.projectReadme(PKG, PROJECT);
    assertTrue(r.contains("composeApp"));
    assertTrue(r.contains("desktop"));
    assertTrue(r.contains(".github/workflows"));
    assertFalse("残留占位符", r.contains("@"));
  }
}
