/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.tooling.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 旧工程里 `buildToolsVersion ''` 的清理。
 *
 * <p><b>为什么值得钉住</b>：这一行会让 AGP 在配置阶段抛 `Invalid revision:`，
 * **整个项目初始化失败**（不是构建失败）。清理必须**只删空值行、保留有值行**——
 * 删错（把用户显式指定的版本也删了，或删出语法错误）会造成新的故障。
 *
 * <p>2026-11-10 PJA110 实测背景：某版本模板生成的 `sjjz` 工程第 10 行是
 * `buildToolsVersion ''`，daemon 日志 `FAILURE ... > Invalid revision:`。
 */
class StripEmptyBuildToolsVersionTest {

  @Test
  fun removesEmptyGroovyLine() {
    val src =
        """
        android {
            namespace 'com.example.app'
            compileSdk 36
            buildToolsVersion ''

            defaultConfig {
                applicationId 'com.example.app'
            }
        }
        """
            .trimIndent()
    val out = stripEmptyBuildToolsVersion(src)
    assertFalse(out.contains("buildToolsVersion"), "空值行应被删除")
    // 其余内容一字不动。
    assertTrue(out.contains("namespace 'com.example.app'"))
    assertTrue(out.contains("compileSdk 36"))
    assertTrue(out.contains("applicationId 'com.example.app'"))
    // 不留空行：compileSdk 行后直接是空行 + defaultConfig。
    assertTrue(out.contains("compileSdk 36\n\n    defaultConfig {"))
  }

  @Test
  fun removesEmptyKtsLineWithEquals() {
    val src =
        """
        android {
            namespace = "com.example.app"
            compileSdk = 36
            buildToolsVersion = ""
        }
        """
            .trimIndent()
    val out = stripEmptyBuildToolsVersion(src)
    assertFalse(out.contains("buildToolsVersion"), "KTS 空值行也应被删除")
    assertTrue(out.contains("compileSdk = 36"))
    assertTrue(out.contains("}"))
  }

  @Test
  fun keepsNonEmptyLineUntouched() {
    // 关键：用户显式指定的版本必须原样保留，不能一并删掉。
    val src =
        """
        android {
            compileSdk 36
            buildToolsVersion '36.0.0'
        }
        """
            .trimIndent()
    assertEquals(src, stripEmptyBuildToolsVersion(src))
  }

  @Test
  fun handlesNoTrailingNewlineAtEof() {
    // 空值行恰好在文件末尾（无换行）时也要能删掉。
    // 删掉该行后，它**前面**那个换行仍在（compileSdk 36 与它之间的分隔符），
    // 所以结果以 "compileSdk 36\n" 结尾——用 trimEnd 比较才不依赖这个细节。
    val src = "android {\n    compileSdk 36\n    buildToolsVersion ''"
    val out = stripEmptyBuildToolsVersion(src)
    assertFalse(out.contains("buildToolsVersion"))
    assertTrue(out.trimEnd().endsWith("compileSdk 36"))
  }

  @Test
  fun doesNotTouchOtherProperties() {
    // 只动这一行：同名前缀的其它属性（如 ndkVersion）不受影响。
    val src =
        """
        android {
            compileSdk 36
            buildToolsVersion ''
            ndkVersion '29.0.14033849'
        }
        """
            .trimIndent()
    val out = stripEmptyBuildToolsVersion(src)
    assertFalse(out.contains("buildToolsVersion"))
    assertTrue(out.contains("ndkVersion '29.0.14033849'"))
  }
}
