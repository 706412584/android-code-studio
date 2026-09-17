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

package com.tom.rv2ide.projects.builder

import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.lookup.Lookup.Key
import com.tom.rv2ide.tooling.api.IProject
import com.tom.rv2ide.tooling.api.messages.InitializeProjectParams
import com.tom.rv2ide.tooling.api.messages.result.BuildCancellationRequestResult
import com.tom.rv2ide.tooling.api.messages.result.InitializeResult
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult
import com.tom.rv2ide.tooling.api.models.ToolingServerMetadata
import java.util.concurrent.CompletableFuture

/**
 * A build service provides API to initialize project, execute builds, query a build, cancel running
 * builds, etc.
 *
 * @author Akash Yadav
 */
interface BuildService {

  companion object {

    /** Key that can be used to retrieve the [BuildService] instance using the [Lookup] API. */
    @JvmField val KEY_BUILD_SERVICE = Key<BuildService>()

    /**
     * Key that can be used to retrieve the instance of Tooling API's [IProject] model using the
     * [Lookup] API.
     */
    @JvmField val KEY_PROJECT_PROXY = Key<IProject>()
  }

  /** Whether a build is in progress or not. */
  val isBuildInProgress: Boolean

  /** Returns `true` if and only if the tooling API server has been started, `false` otherwise. */
  fun isToolingServerStarted(): Boolean

  /** Returns the [ToolingServerMetadata] of the tooling API server. */
  fun metadata(): CompletableFuture<ToolingServerMetadata>

  /**
   * Initialize the project.
   *
   * @param params Parameters for the project initialization.
   * @return A [CompletableFuture] which returns an [InitializeResult] when the project
   *   initialization process finishes.
   */
  fun initializeProject(params: InitializeProjectParams): CompletableFuture<InitializeResult>

  /**
   * Execute the given tasks.
   *
   * @param tasks The tasks to execute. If the fully qualified path of the task is not specified,
   *   then it will be executed in the root project directory.
   * @return A [CompletableFuture] which returns a list of [TaskExecutionResult]. The result
   *   contains a list of tasks that were executed and the result of the whole execution.
   */
  fun executeTasks(vararg tasks: String): CompletableFuture<TaskExecutionResult>

  /**
   * 最近一次构建的输出缓冲。
   *
   * <p>供 AI agent 在 [executeTasks] 失败后读取真实错误。`TaskExecutionResult` 只带
   * `Failure` 枚举（如 `BUILD_FAILED`），不含任何错误文本；模型据此无法自我修正，
   * 只能靠反复试错去猜。
   *
   * <p>与 `EventListener` 的区别：后者是单槽位、由 UI 构建面板占用；这里是旁路缓冲，
   * 两者互不影响。实现方需保证线程安全（构建输出来自 tooling 线程）。
   */
  val buildOutput: BuildOutputBuffer

  /** Cancel any running build. */
  fun cancelCurrentBuild(): CompletableFuture<BuildCancellationRequestResult>
}
