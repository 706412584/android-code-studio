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

package com.tom.rv2ide.projects.internal

import androidx.annotation.RestrictTo
import com.android.builder.model.v2.models.ProjectSyncIssues
import com.google.auto.service.AutoService
import com.google.common.collect.ImmutableList
import com.tom.rv2ide.eventbus.events.EventReceiver
import com.tom.rv2ide.eventbus.events.editor.DocumentSaveEvent
import com.tom.rv2ide.eventbus.events.file.FileCreationEvent
import com.tom.rv2ide.eventbus.events.file.FileDeletionEvent
import com.tom.rv2ide.eventbus.events.file.FileEvent
import com.tom.rv2ide.eventbus.events.file.FileRenameEvent
import com.tom.rv2ide.eventbus.events.project.ProjectInitializedEvent
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.projects.CachingProject
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.ProjectSession
import com.tom.rv2ide.projects.ProjectSessionRegistry
import com.tom.rv2ide.projects.R
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.tasks.executeAsync
import com.tom.rv2ide.tooling.api.IAndroidProject
import com.tom.rv2ide.tooling.api.IProject
import com.tom.rv2ide.tooling.api.messages.result.InitializeResult
import com.tom.rv2ide.tooling.api.models.BuildVariantInfo
import com.tom.rv2ide.utils.DocumentUtils
import com.tom.rv2ide.utils.flashError
import com.tom.rv2ide.utils.withStopWatch
import com.tom.rv2ide.utils.GradleFileParser
import java.io.File
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.pathString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory

/**
 * Internal implementation of [IProjectManager].
 *
 * @author Akash Yadav
 */
@AutoService(IProjectManager::class)
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
class ProjectManagerImpl : IProjectManager, EventReceiver {

  /**
   * 会话表。S1 阶段上限为 1（与改动前「一次只能打开一个项目」一致）；
   * 多标签页在 S2/S3 提高上限，本类其余代码不需要再改。
   */
  private val sessions = ProjectSessionRegistry(maxSessions = 1)

  /** 当前活跃会话；未打开项目时为 null。 */
  private val session: ProjectSession?
    get() = sessions.active()

  override val projectDir: File
    get() =
        checkNotNull(session?.projectDir) {
          "Cannot get project directory. Path has not been set."
        }

  override val projectSyncIssues: ProjectSyncIssues?
    get() = getWorkspace()?.getProjectSyncIssues()

  override fun getWorkspace(): IWorkspace? {
    return session?.workspace
  }

  override fun openProject(directory: File) {
    // 路径归一化与去重在注册表里完成（canonical path）。
    sessions.activate(directory)
  }

  /**
   * 项目是否已完成同步。
   *
   * <p>保留为可写属性：编辑器 Activity 在同步成功/失败时会显式置位（见
   * `ProjectHandlerActivity.onProjectInitialized`），语义与改动前一致。
   */
  var projectInitialized: Boolean
    get() = session?.projectInitialized == true
    set(value) {
      session?.projectInitialized = value
    }

  /** 最近一次同步结果；用于同一实例重建时跳过重复初始化。 */
  var cachedInitResult: InitializeResult?
    get() = session?.cachedInitResult
    set(value) {
      session?.cachedInitResult = value
    }

  override suspend fun setupProject(project: IProject) {
    val current =
        session
            ?: throw IllegalStateException(
                "Cannot setup project. No project has been opened (call openProject first).")
    current.workspace =
        withStopWatch("Transform project proxy") {
          withContext(Dispatchers.IO) {
            WorkspaceModelBuilder.build(current.projectDir, CachingProject(project))
          }
        }

    val rootProject = this.getWorkspace() ?: return

    // build variants must be updated before the sources and classpaths are indexed
    updateBuildVariants { buildVariants ->
      (current.workspace as? WorkspaceImpl)?.setVariantSelections(buildVariants)
    }

    log.info(
        "Found {} project sync issues: {}",
        rootProject.getProjectSyncIssues().syncIssues.size,
        rootProject.getProjectSyncIssues().syncIssues,
    )

    withStopWatch("Setup project") {
      val indexerScope = CoroutineScope(Dispatchers.Default)
      val modulesFlow = flow {
        rootProject.getSubProjects().filterIsInstance<ModuleProject>().forEach { emit(it) }
      }

      val jobs =
          modulesFlow.map { module ->
            indexerScope.async {
              module.indexSourcesAndClasspaths()
              if (module is AndroidModule) {
                module.readResources()
              }
            }
          }

      // wait for the indexing to finish
      jobs.toList().awaitAll()
    }
  }

  override fun destroy() {
    log.info("Destroying project manager")
    sessions.closeAll()
  }

  /**
   * 关闭当前活跃会话（编辑器 Activity 结束时调用）。
   *
   * <p>与 [destroy] 的区别：只清掉**本会话**的项目级状态，不影响（未来的）其它会话。
   * 单会话阶段两者等价；多标签页阶段编辑器关闭一个标签时**必须**走这里。
   */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
  fun closeSession() {
    val current = session ?: return
    log.info("Closing project session: {}", current.id)
    sessions.close(current.projectDir)
  }

  @JvmOverloads
  fun generateSources(
      builder: BuildService? = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
  ) {
    if (builder == null) {
      log.warn("Cannot generate sources. BuildService is null.")
      return
    }

    if (!builder.isToolingServerStarted()) {
      flashError(R.string.msg_tooling_server_unavailable)
      return
    }

    if (builder.isBuildInProgress) {
      return
    }

    val tasks =
        getWorkspace()
            ?.androidProjects()
            ?.flatMap { module ->
              val variant = module.getSelectedVariant()
              if (variant == null) {
                log.error("Selected build variant for project '{}' not found", module.path)
                return@flatMap emptyList()
              }

              val mainArtifact = variant.mainArtifact
              val variantNameCapitalized =
                  variant.name.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                  }

              return@flatMap listOf(
                      mainArtifact.resGenTaskName,
                      mainArtifact.sourceGenTaskName,
                      if (module.viewBindingOptions.isEnabled)
                          "dataBindingGenBaseClasses$variantNameCapitalized"
                      else null,
                      "process${variantNameCapitalized}Resources",
                  )
                  .mapNotNull { it?.let { "${module.path}:${it}" } }
            }
            ?.toList() ?: emptyList()

    builder.executeTasks(*tasks.toTypedArray()).whenComplete { result, taskErr ->
      if (result == null || !result.isSuccessful || taskErr != null) {
        log.warn("Execution for tasks failed: {} {}", tasks, taskErr ?: "")
      } else {
        notifyProjectUpdate()
      }
    }
  }

  fun notifyProjectUpdate() {

    executeAsync {
      getWorkspace()?.apply {
        getSubProjects().forEach { subproject ->
          if (subproject is ModuleProject) {
            subproject.indexSources()
          }
        }
      }

      val event = ProjectInitializedEvent()
      event.put(IWorkspace::class.java, getWorkspace())
      EventBus.getDefault().post(event)
    }
  }

  private fun updateBuildVariants(onUpdated: (Map<String, BuildVariantInfo>) -> Unit = {}) {
    val rootProject =
        checkNotNull(this.getWorkspace()) {
          "Cannot update build variants. Root project model is null."
        }

    val buildVariants = mutableMapOf<String, BuildVariantInfo>()
    rootProject.getSubProjects().forEach { subproject ->
      if (subproject is AndroidModule) {

        val variantNames =
            ImmutableList.builder<String>()
                .addAll(subproject.variants.map { variant -> variant.name })
                .build()

        val variantName = subproject.configuredVariant?.name ?: IAndroidProject.DEFAULT_VARIANT

        val moduleDir = File(projectDir, subproject.path.replace(":", File.separator))
        val gradleInfo = GradleFileParser.parseModuleBuildGradle(moduleDir)

        buildVariants[subproject.path] =
            BuildVariantInfo(
                projectPath = subproject.path,
                buildVariants = variantNames,
                selectedVariant = variantName,
                versionName = gradleInfo?.versionName,
                versionCode = gradleInfo?.versionCode,
                minSdk = gradleInfo?.minSdk,
                targetSdk = gradleInfo?.targetSdk,
                compileSdk = gradleInfo?.compileSdk
            )
      }
    }

    onUpdated(buildVariants)
  }

  private fun generateSourcesIfNecessary(event: FileEvent) {
    val builder = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) ?: return
    val file = event.file
    if (getWorkspace()?.isAndroidResource(file) != true) {
      return
    }

    generateSources(builder)
  }

  @Suppress("unused")
  @Subscribe(threadMode = ThreadMode.ASYNC)
  fun onFileSaved(event: DocumentSaveEvent) {
    event.file.apply {
      if (isDirectory()) {
        return@apply
      }

      if (extension != "xml") {
        return@apply
      }

      val module = getWorkspace()?.findModuleForFile(this, false) ?: return@apply
      if (module !is AndroidModule) {
        return@apply
      }

      val isResource =
          module.mainSourceSet?.sourceProvider?.resDirectories?.any {
            this.pathString.contains(it.path)
          } ?: false

      if (isResource) {
        module.updateResourceTable()
      }
    }
  }

  override fun notifyFileCreated(file: File) {
    onFileCreated(FileCreationEvent(file))
  }

  override fun notifyFileDeleted(file: File) {
    onFileDeleted(FileDeletionEvent(file))
  }

  override fun notifyFileRenamed(from: File, to: File) {
    onFileRenamed(FileRenameEvent(from, to))
  }

  @Suppress("unused")
  @Subscribe(threadMode = ThreadMode.BACKGROUND)
  fun onFileCreated(event: FileCreationEvent) {
    generateSourcesIfNecessary(event)

    if (DocumentUtils.isJavaFile(event.file.toPath())) {
      getWorkspace()?.findModuleForFile(event.file, false)?.let {
        val sourceRoot = it.findSourceRoot(event.file) ?: return@let

        // add the source node entry
        it.compileJavaSourceClasses.append(event.file.toPath(), sourceRoot)
      }
    }
  }

  @Suppress("unused")
  @Subscribe(threadMode = ThreadMode.BACKGROUND)
  fun onFileDeleted(event: FileDeletionEvent) {
    generateSourcesIfNecessary(event)

    // Remove the source node entry
    // Do not check for Java file DocumentUtils.isJavaFile(...) as it checks for file existence as
    // well. As the file is already deleted, it will always return false
    if (event.file.extension == "java") {
      getWorkspace()
          ?.findModuleForFile(event.file, false)
          ?.compileJavaSourceClasses
          ?.findSource(event.file.toPath())
          ?.let { it.parent?.removeChild(it) }
    }
  }

  @Suppress("unused")
  @Subscribe(threadMode = ThreadMode.BACKGROUND)
  fun onFileRenamed(event: FileRenameEvent) {
    generateSourcesIfNecessary(event)

    // Do not check for Java file DocumentUtils.isJavaFile(...) as it checks for file existence as
    // well. As the file is already renamed to another filename, it will always return false
    if (event.file.extension == "java") {
      // remove the source node entry
      getWorkspace()
          ?.findModuleForFile(event.file, false)
          ?.compileJavaSourceClasses
          ?.findSource(event.file.toPath())
          ?.let { it.parent?.removeChild(it) }
    }

    if (DocumentUtils.isJavaFile(event.newFile.toPath())) {
      getWorkspace()?.findModuleForFile(event.newFile, false)?.let {
        val sourceRoot = it.findSourceRoot(event.newFile) ?: return@let
        // add the new source node entry
        it.compileJavaSourceClasses.append(event.newFile.toPath(), sourceRoot)
      }
    }
  }

  companion object {
    private val log = LoggerFactory.getLogger(ProjectManagerImpl::class.java)

    @JvmStatic
    fun getInstance(): ProjectManagerImpl = IProjectManager.getInstance() as ProjectManagerImpl
  }
}
