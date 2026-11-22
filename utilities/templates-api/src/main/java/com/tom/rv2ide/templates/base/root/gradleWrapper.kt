/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.templates.base.root

import com.tom.rv2ide.templates.base.ProjectTemplateBuilder
import java.io.File

internal fun ProjectTemplateBuilder.gradleWrapperProps() {
  val name = "gradle-wrapper.properties"
  val wrapperProps = File(data.projectDir, "gradle/wrapper/${name}")
  wrapperProps.parentFile!!.mkdirs()

  executor.save(gradleWrapperPropsSrc(), wrapperProps)
}

internal fun ProjectTemplateBuilder.gradleWrapperPropsSrc(): String {
  // 与 App 模板一致：有官方哈希就钉住，避免 Gradle 在解压失败后去拉镜像的
  // `.sha256`（国内镜像不提供该文件，会把真实网络错误掩盖成「发行包不存在」）。
  val version = com.tom.rv2ide.templates.GRADLE_WRAPPER_DISTRIBUTION_VERSION
  val checksum =
      com.tom.rv2ide.utils.GradleDistributionChecksums.sha256For(version, allVariant = false)
  return buildString {
    appendLine("distributionBase=GRADLE_USER_HOME")
    appendLine("distributionPath=wrapper/dists")
    appendLine("distributionUrl=https\\://mirrors.aliyun.com/macports/distfiles/gradle/gradle-$version-bin.zip")
    if (checksum != null) appendLine("distributionSha256Sum=$checksum")
    appendLine("networkTimeout=10000")
    appendLine("zipStoreBase=GRADLE_USER_HOME")
    appendLine("zipStorePath=wrapper/dists")
  }
}
