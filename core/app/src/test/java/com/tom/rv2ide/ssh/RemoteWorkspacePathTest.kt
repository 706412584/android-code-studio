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

package com.tom.rv2ide.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 本机项目路径 → 远端工作区目录的映射。
 *
 * <p><b>为什么值得钉住</b>：`shell_execute` 传来的 cwd 是**本机**路径
 * （`/data/data/.../ACSProjects/MyApp`）。直接拿它去远端 `cd` 必然失败，而 ssh 的失败
 * 形态是 shell 以 exit-signal 中止，模型看到的报错是「SSH 连接失败: 未知错误」——
 * 完全指错方向。真机实测正是如此（2026-11-10，黑鲨 + 设备自带 sshd）。
 */
class RemoteWorkspacePathTest {

  @Test
  fun mapsProjectToRemoteWorkspaceWithSameDirectoryName() {
    assertEquals(
        "/srv/work/MyApp",
        RemoteSshConnection.mapToRemoteWorkspace("/srv/work", "/local/ACSProjects/MyApp"))
  }

  @Test
  fun trailingSlashOnEitherSideDoesNotProduceDoubleSlash() {
    // 项目路径与工作区根都可能带尾斜杠（编辑器里两种写法都有）。不处理会拼出
    // `/srv/work//MyApp`，多数情况下能工作，但会让人误判路径来源。
    assertEquals(
        "/srv/work/MyApp",
        RemoteSshConnection.mapToRemoteWorkspace("/srv/work/", "/local/MyApp/"))
  }

  @Test
  fun surroundingWhitespaceIsIgnored() {
    // 设置页输入框可能带前后空格。
    assertEquals(
        "/srv/work/MyApp",
        RemoteSshConnection.mapToRemoteWorkspace("  /srv/work  ", "  /local/MyApp  "))
  }

  @Test
  fun returnsNullWhenRemoteWorkspaceIsUnset() {
    // 未配置远端工作区时不该编出一个路径——调用方据此退回远端 $HOME。
    assertNull(RemoteSshConnection.mapToRemoteWorkspace(null, "/local/MyApp"))
    assertNull(RemoteSshConnection.mapToRemoteWorkspace("", "/local/MyApp"))
    assertNull(RemoteSshConnection.mapToRemoteWorkspace("   ", "/local/MyApp"))
  }

  @Test
  fun blankProjectPathMapsToWorkspaceRoot() {
    assertEquals("/srv/work", RemoteSshConnection.mapToRemoteWorkspace("/srv/work", ""))
  }

  @Test
  fun rootWorkspacePathStillConcatenates() {
    // 工作区根是 "/" 时，trimEnd('/') 会把它变成空串并误判为「未配置」。
    // 这是边界：宁可返回 "/MyApp" 也不要返回 null。
    assertEquals("/MyApp", RemoteSshConnection.mapToRemoteWorkspace("/", "/local/MyApp"))
  }
}