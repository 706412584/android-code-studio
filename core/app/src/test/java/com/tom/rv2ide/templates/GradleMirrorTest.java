/*
 * This file is part of AndroidCodeStudio.
 *
 * This program is free software: you can redistribute it and/or modify
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

package com.tom.rv2ide.templates;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.tom.rv2ide.utils.GradleDistributionChecksums;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 镜像源选择与工程改写。
 *
 * <p><b>为什么这些值得钉住</b>：错误的形态都是「静默的」——
 * <ul>
 *   <li>选错镜像 → 构建时下载卡死几十秒才超时，用户只看到「卡住」，看不出是镜像问题；
 *   <li>改写 wrapper 时连带改坏其它属性 → 用户的 `networkTimeout` 等设置丢失；
 *   <li>镜像顺序反了（官方在前）→ 国内永远走不到镜像，等于没做加速。
 * </ul>
 */
public class GradleMirrorTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  // ---- 候选表与 URL 拼接 ----

  @Test
  public void candidatesAreOrderedWithDomesticFirst() {
    List<GradleMirror.Mirror> all = GradleMirror.ALL;
    assertTrue("候选不应为空", all.size() >= 2);
    // 官方源必须排在最后：国内实测不可达（307 → GitHub 超时），
    // 排前面会让预检白等一个超时。
    assertEquals(
        "官方源应排在候选末位", "official", all.get(all.size() - 1).getId());
    assertFalse("默认源不应是官方", "official".equals(GradleMirror.defaultMirrorId()));
    // 默认必须是阿里云：腾讯的 WAF 对 Java 客户端返回 200 + 反爬挑战页，
    // Gradle wrapper 会把它当 zip 写盘（实测 2026-11-23，983 字节 HTML）。
    assertEquals("默认源应是阿里云", "aliyun", GradleMirror.defaultMirrorId());
    assertEquals("阿里云应排在候选首位", "aliyun", all.get(0).getId());
  }

  @Test
  public void urlForBuildsBothVariantSuffixes() {
    GradleMirror.Mirror aliyun = GradleMirror.byId("aliyun");
    assertNotNull(aliyun);
    assertEquals(
        "https://mirrors.aliyun.com/macports/distfiles/gradle/gradle-9.6.1-bin.zip",
        GradleMirror.urlFor(aliyun, "9.6.1", false));
    assertEquals(
        "https://mirrors.aliyun.com/macports/distfiles/gradle/gradle-9.6.1-all.zip",
        GradleMirror.urlFor(aliyun, "9.6.1", true));
  }

  @Test
  public void byIdReturnsNullForUnknown() {
    assertNull(GradleMirror.byId("不存在的镜像"));
    assertNull(GradleMirror.byId(null));
  }

  // ---- Maven 镜像表 ----

  @Test
  public void mavenRepositoriesUseAliyunGoogleAndCentral() {
    List<List<String>> repos = GradleMirror.mavenRepositories();
    assertFalse("Maven 镜像不应为空", repos.isEmpty());
    // 顺序有意义：google 必须在 central 前——AGP/AndroidX 只在 google 下，
    // 而 aliyun 的 central 里没有它们，顺序反了会先白查一次 central。
    assertEquals("m-google", repos.get(0).get(0));
    assertEquals("https://maven.aliyun.com/repository/google", repos.get(0).get(1));
    assertEquals("m-central", repos.get(1).get(0));
  }

  // ---- init script 内容 ----

  @Test
  public void initScriptUsesSettingsEvaluatedNotAllprojects() {
    String script = GradleMirror.buildMavenInitScript();
    // allprojects { repositories {} } 会被 FAIL_ON_PROJECT_REPOS 直接拒绝
    // （实测：repository ... was added by initialization script），
    // 而多数工程用的正是那个模式。必须走 settings 级。
    assertTrue("必须用 settingsEvaluated", script.contains("settingsEvaluated"));
    assertFalse("不能用 allprojects 加仓库", script.contains("allprojects"));
    assertTrue("要同时改 pluginManagement", script.contains("pluginManagement.repositories"));
    assertTrue(
        "要同时改 dependencyResolutionManagement",
        script.contains("dependencyResolutionManagement.repositories"));
  }

  @Test
  public void initScriptPrependsMirrorsAndKeepsOfficialFallback() {
    String script = GradleMirror.buildMavenInitScript();
    // 前置而非追加：追加到末尾时官方仓库先命中，镜像形同虚设。
    assertTrue("必须用 addFirst 前置", script.contains("addFirst"));
    // 倒序插入才不颠倒顺序（实测逐个 addFirst 得到 m-central m-google）。
    assertTrue("必须倒序插入", script.contains("reverseEach"));
    // 已存在同名仓库时不能重复加（新建项目的 settings.gradle 已自带镜像）。
    assertTrue("要判重", script.contains("existing.contains"));
  }

  @Test
  public void initScriptContainsBothMirrorUrls() {
    String script = GradleMirror.buildMavenInitScript();
    assertTrue(script.contains("maven.aliyun.com/repository/google"));
    assertTrue(script.contains("maven.aliyun.com/repository/central"));
  }

  // ---- wrapper 改写 ----

  @Test
  public void rewriteOnlyReplacesDistributionUrlAndChecksumAndKeepsOtherLines() throws Exception {
    File root = tmp.newFolder("proj");
    File wrapperDir = new File(root, "gradle/wrapper");
    assertTrue(wrapperDir.mkdirs());
    File props = new File(wrapperDir, "gradle-wrapper.properties");
    // 真实的 wrapper properties 还有别的项，改写不该把它们弄丢。
    String original =
        "distributionBase=GRADLE_USER_HOME\n"
            + "distributionPath=wrapper/dists\n"
            + "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.6.1-bin.zip\n"
            + "networkTimeout=10000\n"
            + "validateDistributionUrl=true\n"
            + "zipStoreBase=GRADLE_USER_HOME\n";
    Files.write(props.toPath(), original.getBytes(StandardCharsets.UTF_8));

    boolean changed = GradleMirror.rewriteWrapper(root, GradleMirror.byId("aliyun"));

    assertTrue("应改写成功", changed);
    String rewritten = new String(Files.readAllBytes(props.toPath()), StandardCharsets.UTF_8);
    assertTrue("应指向阿里云镜像", rewritten.contains("mirrors.aliyun.com/macports/distfiles/gradle/"));
    assertFalse("不应残留官方地址", rewritten.contains("services.gradle.org"));
    // properties 里惯用转义形式；replaceFirst 的替换串会吃 `\`，必须 quoteReplacement。
    assertTrue("URL 的冒号/斜杠应保持转义", rewritten.contains("distributionUrl=https\\://"));
    // 版本与变体保持不变。
    assertTrue("版本要保留", rewritten.contains("gradle-9.6.1-bin.zip"));
    // 其它行一字不动。
    assertTrue("networkTimeout 不能被丢", rewritten.contains("networkTimeout=10000"));
    assertTrue("validateDistributionUrl 不能被丢", rewritten.contains("validateDistributionUrl=true"));
    assertTrue(
        "应写入 9.6.1 的官方哈希",
        rewritten.contains("distributionSha256Sum=" + GradleDistributionChecksums.sha256For("9.6.1", false)));
  }

  @Test
  public void rewriteCorrectsStaleChecksum() throws Exception {
    File root = tmp.newFolder("projStale");
    File wrapperDir = new File(root, "gradle/wrapper");
    assertTrue(wrapperDir.mkdirs());
    File props = new File(wrapperDir, "gradle-wrapper.properties");
    Files.write(
        props.toPath(),
        ("distributionUrl=https\\://services.gradle.org/distributions/gradle-9.6.1-bin.zip\n"
                + "distributionSha256Sum=deadbeef\n")
            .getBytes(StandardCharsets.UTF_8));

    assertTrue(GradleMirror.rewriteWrapper(root, GradleMirror.byId("aliyun")));
    String rewritten = new String(Files.readAllBytes(props.toPath()), StandardCharsets.UTF_8);
    assertFalse("错误哈希应被换掉", rewritten.contains("distributionSha256Sum=deadbeef"));
    assertTrue(
        "应校正为官方哈希",
        rewritten.contains("distributionSha256Sum=" + GradleDistributionChecksums.sha256For("9.6.1", false)));
    assertEquals("哈希行不应重复", 1, countOccurrences(rewritten, "distributionSha256Sum="));
  }

  @Test
  public void rewriteLeavesChecksumAloneForUnknownVersion() throws Exception {
    File root = tmp.newFolder("projUnknown");
    File wrapperDir = new File(root, "gradle/wrapper");
    assertTrue(wrapperDir.mkdirs());
    File props = new File(wrapperDir, "gradle-wrapper.properties");
    Files.write(
        props.toPath(),
        ("distributionUrl=https\\://services.gradle.org/distributions/gradle-10.0.0-bin.zip\n"
                + "distributionSha256Sum=custom-value\n")
            .getBytes(StandardCharsets.UTF_8));

    assertTrue(GradleMirror.rewriteWrapper(root, GradleMirror.byId("aliyun")));
    String rewritten = new String(Files.readAllBytes(props.toPath()), StandardCharsets.UTF_8);
    assertTrue("未知版本不得改写哈希", rewritten.contains("distributionSha256Sum=custom-value"));
    assertTrue("URL 仍应换镜像", rewritten.contains("mirrors.aliyun.com/macports/distfiles/gradle/"));
  }

  /** 模板 assets 的 wrapper 必须钉住与版本匹配的官方哈希（否则会重现 .sha256 404 故障）。 */
  @Test
  public void templateAssetWrappersPinMatchingChecksums() throws Exception {
    File assets = new File("src/main/assets");
    assertTrue("缺少模板 assets 目录: " + assets.getAbsolutePath(), assets.isDirectory());
    File[] templateDirs = assets.listFiles(File::isDirectory);
    assertNotNull(templateDirs);
    int checked = 0;
    for (File dir : templateDirs) {
      File props = new File(dir, "gradle/wrapper/gradle-wrapper.properties");
      if (!props.isFile()) continue;
      String text = new String(Files.readAllBytes(props.toPath()), StandardCharsets.UTF_8);
      Matcher version = Pattern.compile("gradle-([0-9][0-9.]*)-(bin|all)\\.zip").matcher(text);
      Matcher sha = Pattern.compile("distributionSha256Sum=([0-9a-f]{64})").matcher(text);
      assertTrue(dir.getName() + " 的 wrapper 认不出 Gradle 版本", version.find());
      assertTrue(dir.getName() + " 的 wrapper 缺少 distributionSha256Sum", sha.find());
      assertEquals(
          dir.getName() + " 的哈希与版本不匹配",
          GradleDistributionChecksums.sha256For(version.group(1), "all".equals(version.group(2))),
          sha.group(1));
      checked++;
    }
    assertEquals("应至少检查 13 份模板 wrapper", 13, checked);
  }

  private static int countOccurrences(String text, String needle) {
    int count = 0, from = 0;
    while ((from = text.indexOf(needle, from)) >= 0) {
      count++;
      from += needle.length();
    }
    return count;
  }

  @Test
  public void rewriteKeepsAllVariantSuffix() throws Exception {
    File root = tmp.newFolder("projAll");
    File wrapperDir = new File(root, "gradle/wrapper");
    assertTrue(wrapperDir.mkdirs());
    File props = new File(wrapperDir, "gradle-wrapper.properties");
    Files.write(
        props.toPath(),
        "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.0.0-all.zip\n"
            .getBytes(StandardCharsets.UTF_8));

    assertTrue(GradleMirror.rewriteWrapper(root, GradleMirror.byId("aliyun")));
    String rewritten = new String(Files.readAllBytes(props.toPath()), StandardCharsets.UTF_8);
    // `-all` 不能被悄悄换成 `-bin`：两者内容不同，用户可能故意选了 all。
    assertTrue("必须保留 -all 变体", rewritten.contains("gradle-9.0.0-all.zip"));
  }

  @Test
  public void rewriteFindsAndroidSubdirLayoutToo() throws Exception {
    // 官方 Flutter 形态：wrapper 在 android/ 子目录。
    File root = tmp.newFolder("flutterLike");
    File wrapperDir = new File(root, "android/gradle/wrapper");
    assertTrue(wrapperDir.mkdirs());
    File props = new File(wrapperDir, "gradle-wrapper.properties");
    Files.write(
        props.toPath(),
        "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-bin.zip\n"
            .getBytes(StandardCharsets.UTF_8));

    assertTrue("应能在 android/ 子目录找到", GradleMirror.rewriteWrapper(root, GradleMirror.byId("aliyun")));
    String rewritten = new String(Files.readAllBytes(props.toPath()), StandardCharsets.UTF_8);
    assertTrue(rewritten.contains("mirrors.aliyun.com/macports/distfiles/gradle/"));
    assertTrue(rewritten.contains("gradle-8.14.3-bin.zip"));
  }

  @Test
  public void rewriteReturnsFalseWhenWrapperMissingOrUnparseable() throws Exception {
    // 没有 wrapper 文件的工程（形态特殊）不该抛异常，也不该被当成失败。
    File empty = tmp.newFolder("noWrapper");
    assertFalse(GradleMirror.rewriteWrapper(empty, GradleMirror.byId("aliyun")));

    // distributionUrl 认不出 Gradle 版本时同样安静跳过。
    File root = tmp.newFolder("weird");
    File wrapperDir = new File(root, "gradle/wrapper");
    assertTrue(wrapperDir.mkdirs());
    Files.write(
        new File(wrapperDir, "gradle-wrapper.properties").toPath(),
        "distributionUrl=https\\://example.com/my-custom-gradle.zip\n"
            .getBytes(StandardCharsets.UTF_8));
    assertFalse(GradleMirror.rewriteWrapper(root, GradleMirror.byId("aliyun")));
  }

  @Test
  public void readGradleVersionExtractsVersion() throws Exception {
    File root = tmp.newFolder("ver");
    File wrapperDir = new File(root, "gradle/wrapper");
    assertTrue(wrapperDir.mkdirs());
    Files.write(
        new File(wrapperDir, "gradle-wrapper.properties").toPath(),
        "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.6.1-bin.zip\n"
            .getBytes(StandardCharsets.UTF_8));
    assertEquals("9.6.1", GradleMirror.readGradleVersion(root));
  }

  @Test
  public void initScriptMirrorListIsIndentedConsistently() {
    String script = GradleMirror.buildMavenInitScript();
    // 占位符必须被替换掉；残留说明 replace 失效（脚本里会出现字面量 __MIRRORS__）。
    assertFalse("占位符不应残留", script.contains("__MIRRORS__"));
    // mirrors 列表项要与列表容器同一层级缩进后的 4 空格——曾因 `$decl` 插值
    // 不受 trimIndent 管辖而错位。这里逐行检查：每个镜像行的缩进应比
    // `def mirrors = [` 那行多 4 个空格。
    String[] lines = script.split("\\n", -1);
    int listIndent = -1;
    for (int i = 0; i < lines.length; i++) {
      String l = lines[i];
      if (l.contains("def mirrors = [")) {
        listIndent = l.indexOf("def mirrors");
      }
      if (l.contains("m-google") || l.contains("m-central")) {
        int indent = l.indexOf("['");
        assertTrue("镜像项 " + l.trim() + " 缩进异常", listIndent >= 0 && indent == listIndent + 4);
      }
    }
  }
}
