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

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** {@link AxmlParser} 用真实 AXML manifest（com.tom.rv2ide）验证解析正确性。 */
final class AxmlParserTest {

  private static byte[] readResource(String name) throws Exception {
    try (InputStream in = AxmlParserTest.class.getResourceAsStream(name)) {
      assertNotNull(in, "测试资源不存在: " + name);
      return in.readAllBytes();
    }
  }

  @Test
  void parsesRealAcsManifest() throws Exception {
    AxmlParser.Manifest m = AxmlParser.parse(readResource("/test-manifest.axml"));
    assertNotNull(m, "真实 AXML 应能解析");
    assertEquals("com.tom.rv2ide", m.packageName, "包名");
    assertNotNull(m.versionCode, "versionCode 应解析出来");
    assertTrue(m.versionCode > 0, "versionCode 应>0");
    assertFalse(m.permissions.isEmpty(), "权限列表不应为空");
    assertFalse(m.activities.isEmpty(), "activity 列表不应为空");
    // 主 activity 与关键组件应被提取。
    assertTrue(
        m.activities.toString().contains("MainActivity"),
        "应包含 MainActivity：" + m.activities);
  }

  @Test
  void parsesMtManagerManifest() throws Exception {
    AxmlParser.Manifest m = AxmlParser.parse(readResource("/mt-manifest.axml"));
    assertNotNull(m, "MT 管理器 AXML 应能解析");
    assertEquals("bin.mt.plus", m.packageName, "MT 管理器包名");
  }

  @Test
  void rejectsNonAxmlBytes() {
    assertNull(AxmlParser.parse("not an axml".getBytes()));
    assertNull(AxmlParser.parse(new byte[0]));
    assertNull(AxmlParser.parse(null));
  }

  @Test
  void parsesVersionAndSdks() throws Exception {
    AxmlParser.Manifest m = AxmlParser.parse(readResource("/test-manifest.axml"));
    assertNotNull(m);
    assertNotNull(m.versionName, "versionName 应非空");
    assertNotNull(m.minSdk, "minSdk 应解析出来");
    assertNotNull(m.targetSdk, "targetSdk 应解析出来");
  }
}
