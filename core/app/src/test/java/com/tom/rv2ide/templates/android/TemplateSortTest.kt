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

package com.tom.rv2ide.templates.android

import android.content.Context
import com.tom.rv2ide.templates.AtcInterface
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 向导列表排序（跨平台置顶）的回归测试。
 *
 * 排序是**用户可见的产品决策**（用户明确要求跨平台模板优先展示），不是实现细节
 * ——它值得被测试钉住，否则将来重构注册表时很容易被顺手改掉而没人发现。
 *
 * 用最小假实现驱动纯函数 [sortForDisplay]，不依赖 Android Context（真实模板都要
 * Context 构造，JVM 上拿不到）。用 Kotlin 写是因为 [Template.create] 是 suspend
 * 函数，Java 实现要手写 Continuation。
 */
class TemplateSortTest {

  /** 最小模板桩：只关心排序用到的 isCrossPlatform。 */
  private class StubTemplate(
      private val name: String,
      private val crossPlatform: Boolean,
  ) : Template {
    override val displayName: String = name
    override val isCrossPlatform: Boolean = crossPlatform
    override val templateType = Template.TemplateType.OTHER

    override suspend fun create(
        context: Context,
        listener: AtcInterface.TemplateCreationListener?,
        options: TemplateOptions,
    ) {
      // 排序测试不创建项目。
    }
  }

  @Test
  fun `跨平台模板排最前`() {
    val sorted =
        sortForDisplay(
            listOf(
                StubTemplate("A", false),
                StubTemplate("Cross1", true),
                StubTemplate("B", false),
                StubTemplate("Cross2", true),
            )
        )

    assertEquals("Cross1", sorted[0].displayName)
    assertEquals("Cross2", sorted[1].displayName)
    assertEquals("A", sorted[2].displayName)
    assertEquals("B", sorted[3].displayName)
  }

  @Test
  fun `组内保持原相对顺序（稳定排序）`() {
    // 组内必须保持原相对顺序——否则每次注册表微调都会让卡片乱跳。
    val sorted =
        sortForDisplay(
            listOf(
                StubTemplate("A", false),
                StubTemplate("B", false),
                StubTemplate("C", false),
                StubTemplate("X", true),
                StubTemplate("Y", true),
            )
        )

    assertEquals(listOf("X", "Y", "A", "B", "C"), sorted.map { it.displayName })
  }

  @Test
  fun `全部非跨平台时保持原序`() {
    val sorted = sortForDisplay(listOf(StubTemplate("A", false), StubTemplate("B", false)))
    assertEquals(listOf("A", "B"), sorted.map { it.displayName })
  }

  @Test
  fun `全部跨平台时保持原序`() {
    val sorted = sortForDisplay(listOf(StubTemplate("X", true), StubTemplate("Y", true)))
    assertEquals(listOf("X", "Y"), sorted.map { it.displayName })
  }

  @Test
  fun `空列表安全`() {
    assertTrue(sortForDisplay(emptyList()).isEmpty())
  }

  /**
   * 三个真实跨平台模板的 isCrossPlatform 必须为 true、描述以「跨平台：」开头。
   *
   * 不构造真实模板（要 Context），改为读源码断言：这类「标记位忘了设 / 文案改漏」
   * 的错误编译能过、默认值也能过，只有真机看界面才发现。
   */
  @Test
  fun `三个跨平台模板都已正确标记`() {
    val files =
        listOf(
            "core/app/src/main/java/com/tom/rv2ide/templates/android/ComposeMultiplatform.kt",
            "core/app/src/main/java/com/tom/rv2ide/templates/android/ReactNative.kt",
            "core/app/src/main/java/com/tom/rv2ide/templates/android/Flutter.kt",
        )
    val repoRoot = findRepoRoot()
    for (path in files) {
      val content = File(repoRoot, path).readText(Charsets.UTF_8)
      assertTrue("$path 必须声明 isCrossPlatform = true", content.contains("override val isCrossPlatform = true"))
      assertTrue("$path 的 description 应以「跨平台：」开头（用户要求的统一文案）", content.contains("\"跨平台："))
    }
  }

  /** 测试的工作目录可能不是仓库根（Gradle 从模块目录跑）：向上找 settings.gradle.kts。 */
  private fun findRepoRoot(): File {
    var dir: File? = File(".").absoluteFile
    while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
      dir = dir.parentFile
    }
    return dir ?: File(".").absoluteFile
  }
}
