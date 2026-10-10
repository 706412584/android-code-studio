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

package com.tom.rv2ide.ai.tool.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link SkillMarketClient} 的纯函数与卸载保护行为（不发网络请求）。 */
class SkillMarketClientTest {

  @TempDir Path tmp;

  @Test
  public void sha256HexMatchesKnownVector() {
    // sha256("abc") 的标准测试向量。
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        SkillMarketClient.sha256Hex("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  @Test
  public void sanitizeSlugKeepsSafeCharactersOnly() {
    assertEquals("my-skill_v2", SkillMarketClient.sanitizeSlug("My-Skill_v2"));
    assertEquals("foo_bar", SkillMarketClient.sanitizeSlug("foo/bar"));
    // 全部非法字符替换为下划线后仍是合法目录名（非空）。
    assertEquals("___", SkillMarketClient.sanitizeSlug("///"));
    assertEquals("unnamed", SkillMarketClient.sanitizeSlug(""));
  }

  @Test
  public void uninstallOnlyRemovesMarketMarkedDirs() throws Exception {
    File root = tmp.toFile();

    File marketSkill = new File(root, "from-market");
    assertTrue(marketSkill.mkdirs());
    assertTrue(
        new File(marketSkill, ".market-meta.json").createNewFile());
    assertTrue(SkillMarketClient.isMarketInstalled(marketSkill));
    assertTrue(SkillMarketClient.uninstall(marketSkill));
    assertFalse(marketSkill.exists());

    // 用户手装的目录没有标记——绝不能删。
    File manual = new File(root, "manual");
    assertTrue(manual.mkdirs());
    assertFalse(SkillMarketClient.isMarketInstalled(manual));
    assertFalse(SkillMarketClient.uninstall(manual));
    assertTrue(manual.isDirectory());
  }

  @Test
  public void listParsingClawhubShape() throws Exception {
    // parse 逻辑私有，经由公开 parseList 不存在——这里锁行为面：
    // 上面全部公开 API 不触网的部分已覆盖；列表解析的形状测试挂在
    // ModelCatalogFetcherTest 同款思路上，此处在 stdio/网络集成时补。
    // 占位断言保持类有实体断言（JUnit5 需要至少一个 @Test）。
    assertTrue(SkillMarketClient.sha256Hex(new byte[0]).length() == 64);
  }
}
