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

package com.tom.rv2ide.projects

import com.tom.rv2ide.tooling.api.messages.result.InitializeResult
import java.io.File

/**
 * 一个打开中的项目及其全部项目级状态。
 *
 * <p><b>为什么要把这些字段收拢到一个类</b>：它们此前散落在
 * [com.tom.rv2ide.projects.internal.ProjectManagerImpl] 的四个私有字段里，而那个类
 * 是进程单例——想同时打开两个项目就必须把这些状态按项目分开。抽成独立对象后，
 * 单项目行为完全不变（管理器持有一个 session），多项目只需换成「管理器持有多个」。
 *
 * <p><b>哪些状态属于这里</b>：只放**按项目变化**的东西。全局资源（事件总线、
 * 前台 Service、内存预算）不属于任何 session。
 *
 * <p><b>线程</b>：字段由编辑器主线程与索引协程共同访问，因此 [workspace] 与
 * [projectInitialized] 用 `@Volatile`。这不是完整同步——调用方仍需保证
 * 「setupProject 与 destroy 不并发」，与改动前的约束一致。
 */
class ProjectSession(val projectDir: File) {

  /** 项目模型（Workspace）；同步完成前为 null。 */
  @Volatile
  var workspace: IWorkspace? = null

  /** 是否已完成一次成功的同步。 */
  @Volatile
  var projectInitialized: Boolean = false

  /** 最近一次同步的结果；用于「同一实例重建时跳过重复初始化」。 */
  @Volatile
  var cachedInitResult: InitializeResult? = null

  /** 会话的稳定标识：规范化后的绝对路径。 */
  val id: String = projectDir.absolutePath

  /**
   * 最近一次被激活的逻辑时间戳（单调递增），仅用于会话 LRU 淘汰。
   *
   * <p>用注册表内的计数器而不是 `System.nanoTime()`：同一毫秒内的连续激活在
   * 时间戳上可能并列，导致「关闭活跃标签后该回到哪个」不确定；计数器保证严格全序。
   */
  @Volatile internal var lastAccessAt: Long = 0L

  /** 清空项目级状态（关闭项目时调用）。 */
  fun clear() {
    (workspace as? com.tom.rv2ide.projects.internal.WorkspaceImpl)
        ?.setVariantSelections(emptyMap())
    workspace = null
    projectInitialized = false
    cachedInitResult = null
  }

  override fun toString(): String = "ProjectSession($id)"
}
