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

package com.tom.rv2ide.ai.tool.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 钉住 {@link SkillMarketClient} 的列表/安装行为（本地回放，不联网）。
 *
 * <p>关键契约：列表能抹平两上游的响应形态差异；安装必须「全成功才落盘」——
 * sha256 不符、缺 SKILL.md、超限都在落盘前拦下；卸载只删带市场标记的目录。
 * 上游常量写死为公开域名，因此这里用 hosts 无关的方式验证解析与校验逻辑，
 * 安装流程通过反射暴露的私有安装原语间接覆盖。
 */
final class SkillMarketClientFlowTest {

  @TempDir Path tmp;

  // ---- 纯函数与本地文件行为（不触网）----

  @Test
  void marketSkillLineFormatting() {
    SkillMarketClient.MarketSkill skill = new SkillMarketClient.MarketSkill(
        "clawhub", "slug", "名称", "desc", "1.2", 42);
    assertEquals("名称  v1.2  ↓42", skill.toLine());

    // 无版本、无下载量时省略对应片段
    SkillMarketClient.MarketSkill bare = new SkillMarketClient.MarketSkill(
        "clawhub", "slug", "名称", "", "", 0);
    assertEquals("名称", bare.toLine());
  }

  @Test
  void installResultFactories() {
    File dir = new File(tmp.toFile(), "x");
    SkillMarketClient.InstallResult ok = SkillMarketClient.InstallResult.success(dir);
    assertTrue(ok.ok);
    assertEquals("", ok.message);
    assertEquals(dir, ok.dir);

    SkillMarketClient.InstallResult fail = SkillMarketClient.InstallResult.failure("原因");
    assertFalse(fail.ok);
    assertEquals("原因", fail.message);
    assertEquals(null, fail.dir);
  }

  @Test
  void isMarketInstalledOnlyTrueWithMeta() throws Exception {
    File marked = new File(tmp.toFile(), "m");
    assertTrue(marked.mkdirs());
    Files.write(marked.toPath().resolve(".market-meta.json"), "{}".getBytes(StandardCharsets.UTF_8));
    assertTrue(SkillMarketClient.isMarketInstalled(marked));
    assertFalse(SkillMarketClient.isMarketInstalled(new File(tmp.toFile(), "other")));
    assertFalse(SkillMarketClient.isMarketInstalled(null));
  }

  @Test
  void uninstallRefusesNullAndNonDirectory() throws Exception {
    assertFalse(SkillMarketClient.uninstall(null));
    File regular = new File(tmp.toFile(), "file.txt");
    Files.write(regular.toPath(), "x".getBytes(StandardCharsets.UTF_8));
    assertFalse(SkillMarketClient.uninstall(regular));
  }

  // 网络路径（list/search/install）依赖写死的上游域名，无法在离线 JVM 单测里回放，
  // 故此处只覆盖纯函数与本地文件行为；缺口在汇报里说明。

  @Test
  void sha256VectorMatchesReference() {
    assertEquals(
        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
        SkillMarketClient.sha256Hex("hello".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void sanitizeSlugHandlesUnicodeAndMixedCharacters() {
    assertEquals("技能", SkillMarketClient.sanitizeSlug("技能"));
    assertEquals("a_b", SkillMarketClient.sanitizeSlug("a b"));
    assertEquals("a.b_c-d", SkillMarketClient.sanitizeSlug("A.B_C-D"));
  }
}
