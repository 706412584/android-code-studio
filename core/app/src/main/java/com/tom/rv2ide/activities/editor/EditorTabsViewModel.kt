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

package com.tom.rv2ide.activities.editor

import androidx.lifecycle.ViewModel
import com.tom.rv2ide.projects.ProjectSession
import com.tom.rv2ide.projects.ProjectSessionRegistry
import com.tom.rv2ide.projects.internal.ProjectManagerImpl
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 项目标签页的纯数据模型。
 *
 * @property id 规范化后的项目根目录路径（与 [ProjectSession.id] 相同），作为稳定标识。
 * @property displayName 标签上显示的名字（目录名）。
 */
data class ProjectTab(val id: String, val displayName: String) {

  /** 项目根目录。 */
  val projectDir: File
    get() = File(id)
}

/**
 * 编辑器 Activity 作用域的项目标签数据层。
 *
 * <p><b>为什么是 ViewModel</b>：标签列表与活跃标签必须在配置变更（旋转、分屏）后保留，
 * 且不能放在任何进程级 object 里（会随 Activity 泄漏）。
 *
 * <p><b>数据源</b>：直接读写 [ProjectManagerImpl.sessionRegistry]，**不维护第二份会话表**。
 * 会话（Workspace、同步状态）归 `core/projects` 管；本类只把它投影成 UI 需要的
 * `List<ProjectTab>` + `activeId`。因此任何走 `ProjectManagerImpl.openSession()` 的调用
 * （例如从 `MainActivity` 新建标签）在 [refresh] 后都能被看到。
 *
 * <p><b>线程</b>：与 `IProjectManager` 一致，按主线程调用设计；StateFlow 本身线程安全。
 */
class EditorTabsViewModel(
    private val registry: ProjectSessionRegistry =
        ProjectManagerImpl.getInstance().sessionRegistry
) : ViewModel() {

  private val _tabs = MutableStateFlow<List<ProjectTab>>(emptyList())

  /** 当前打开的项目标签，按首次打开顺序排列。 */
  val tabs: StateFlow<List<ProjectTab>> = _tabs.asStateFlow()

  private val _activeId = MutableStateFlow<String?>(null)

  /** 活跃标签的 id（规范化路径）；没有打开项目时为 null。 */
  val activeId: StateFlow<String?> = _activeId.asStateFlow()

  init {
    refresh()
  }

  /** 当前活跃标签；没有时为 null。 */
  val activeTab: ProjectTab?
    get() = _activeId.value?.let(::tab)

  /** 打开（或切到）项目并返回其标签。 */
  fun open(dir: File): ProjectTab {
    val session = registry.activate(dir)
    refresh()
    return ProjectTab(session.id, displayNameOf(session))
  }

  fun open(path: String): ProjectTab = open(File(path))

  /**
   * 切到已打开的标签。
   *
   * @return 目标项目已打开并成为活跃标签时返回 true；未打开时返回 false（不新建会话）。
   */
  fun switchTo(dir: File): Boolean {
    val session = registry.switchTo(dir) ?: return false
    refresh()
    return _activeId.value == session.id
  }

  fun switchTo(path: String): Boolean = switchTo(File(path))

  /**
   * 关闭标签。
   *
   * <p>关闭活跃标签时，活跃指针由注册表切到最近使用的剩余标签（没有剩余时置空），
   * 因此 UI 侧不需要自己决定「关掉当前标签后该显示谁」。
   *
   * @return 该标签原本存在并被关闭时返回 true。
   */
  fun close(dir: File): Boolean {
    if (registry.close(dir) == null) {
      return false
    }
    refresh()
    return true
  }

  fun close(path: String): Boolean = close(File(path))

  /** 关闭当前活跃标签；没有活跃标签时返回 false。 */
  fun closeActive(): Boolean {
    val active = registry.active() ?: return false
    return close(active.projectDir)
  }

  /** 按 id（规范化路径）查找标签。 */
  fun tab(id: String): ProjectTab? =
      _tabs.value.firstOrNull { it.id == id }

  /**
   * 从注册表重新同步标签列表与活跃 id。
   *
   * <p>用于外部直接改动了注册表的场景（例如 `MainActivity` 在 Activity 启动前调用了
   * `ProjectManagerImpl.openSession()`），以及 `onNewIntent` 之后。
   */
  fun refresh() {
    val sessions = registry.all()
    _tabs.value = sessions.map { ProjectTab(it.id, displayNameOf(it)) }
    _activeId.value = registry.activeId
  }

  private fun displayNameOf(session: ProjectSession): String {
    val name = session.projectDir.name
    return name.ifBlank { session.id }
  }
}
