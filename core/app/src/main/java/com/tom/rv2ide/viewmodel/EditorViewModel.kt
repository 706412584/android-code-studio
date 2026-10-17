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
package com.tom.rv2ide.viewmodel

import android.view.Gravity.CENTER
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blankj.utilcode.util.FileUtils
import com.google.gson.GsonBuilder
import com.tom.rv2ide.models.OpenedFilesCache
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.utils.ILogger
import java.io.File
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ViewModel for data used in [com.tom.rv2ide.activities.editor.EditorActivityKt] */
@Suppress("PropertyName")
class EditorViewModel : ViewModel() {

  internal val _isBuildInProgress = MutableLiveData(false)
  internal val _isInitializing = MutableLiveData(false)

  /**
   * 项目标签切换进行中。
   *
   * <p>与 [isInitializing] 分开：切标签时可能既不构建也不同步（目标项目已初始化过），
   * 但用户仍然需要看到「正在切换」的进度条——否则点击后界面毫无反馈，几秒后突然
   * 换了一屏内容，体感是「卡住」而不是「切换」。
   *
   * <p>切到**已同步**的项目时只做内存内的状态搬运（LSP 重新路由、文件视图重建），
   * 那些工作在 IO/后台线程完成，主线程只负责最后的 UI 提交。
   */
  internal val _isSwitchingProject = MutableLiveData(false)

  var isSwitchingProject: Boolean
    get() = _isSwitchingProject.value ?: false
    set(value) {
      _isSwitchingProject.value = value
    }
  internal val _statusText = MutableLiveData<Pair<CharSequence, Int>>("" to CENTER)

  internal val _displayedFile = MutableLiveData(-1)
  internal val _startDrawerOpened = MutableLiveData(false)
  internal val _isSyncNeeded = MutableLiveData(false)

  internal val _filesModified = MutableLiveData(false)
  internal val _filesSaving = MutableLiveData(false)

  /**
   * 本编辑器所属项目的根目录，由 Activity 在 `onCreate` 时捕获。
   *
   * <p>不再每次读写缓存都去问 `IProjectManager` 全局单例：`onPause` 里会话会先被关闭，
   * 之后 `saveOpenedFiles()` 才写缓存，那时全局单例已经没有项目了（旧代码会抛
   * `IllegalStateException` 并静默丢失缓存）。捕获一次后，关闭标签页的写入仍落到
   * 正确的项目目录，多标签页时也不会写到别的项目里。
   */
  @Volatile var projectDir: File? = null

  private val _openedFiles = MutableLiveData<OpenedFilesCache>()
  private val _isBoundToBuildService = MutableLiveData(false)
  private val _files = MutableLiveData<MutableList<File>>(ArrayList())

  /**
   * Holds information about the currently selected editor fragment. First value in the pair is the
   * index of the editor opened. Second value is the file that is opened.
   */
  private val mCurrentFile = MutableLiveData<Pair<Int, File?>?>(null)

  var areFilesModified: Boolean
    get() = _filesModified.value ?: false
    set(value) {
      _filesModified.value = value
    }

  var areFilesSaving: Boolean
    get() = _filesSaving.value ?: false
    set(value) {
      _filesSaving.value = value
    }

  var openedFilesCache: OpenedFilesCache?
    get() = _openedFiles.value
    set(value) {
      this._openedFiles.value = value
    }

  var isBoundToBuildSerice: Boolean
    get() = _isBoundToBuildService.value ?: false
    set(value) {
      _isBoundToBuildService.value = value
    }

  var isBuildInProgress: Boolean
    get() = _isBuildInProgress.value ?: false
    set(value) {
      _isBuildInProgress.value = value
    }

  var isInitializing: Boolean
    get() = _isInitializing.value ?: false
    set(value) {
      _isInitializing.value = value
    }

  var statusText: CharSequence
    get() = this._statusText.value?.first ?: ""
    set(value) {
      _statusText.value = value to (_statusText.value?.second ?: 0)
    }

  var statusGravity: Int
    get() = this._statusText.value?.second ?: CENTER
    set(value) {
      _statusText.value = (_statusText.value?.first ?: "") to value
    }

  var displayedFileIndex: Int
    get() = _displayedFile.value!!
    set(value) {
      _displayedFile.value = value
    }

  var startDrawerOpened: Boolean
    get() = _startDrawerOpened.value ?: false
    set(value) {
      _startDrawerOpened.value = value
    }

  var isSyncNeeded: Boolean
    get() = _isSyncNeeded.value ?: false
    set(value) {
      _isSyncNeeded.value = value
    }

  internal var files: MutableList<File>
    get() = this._files.value ?: Collections.emptyList()
    set(value) {
      this._files.value = value
    }

  private inline fun updateFiles(crossinline action: (files: MutableList<File>) -> Unit) {
    val files = this.files
    action(files)
    this.files = files
  }

  /**
   * Add the given file to the list of opened files.
   *
   * @param file The file that has been opened.
   */
  fun addFile(file: File) = updateFiles { files -> files.add(file) }

  /**
   * Remove the file at the given index from the list of opened files.
   *
   * @param index The index of the closed file.
   */
  fun removeFile(index: Int) = updateFiles { files ->
    files.removeAt(index)

    if (files.isEmpty()) {
      mCurrentFile.value = null
    }
  }

  fun removeAllFiles() = updateFiles { files ->
    files.clear()
    setCurrentFile(-1, null)
  }

  fun setCurrentFile(index: Int, file: File?) {
    displayedFileIndex = index
    mCurrentFile.value = index to file
  }

  fun updateFile(index: Int, newFile: File) = updateFiles { files -> files[index] = newFile }

  /**
   * Get the opened file at the given index.
   *
   * @param index The index of the file.
   * @return The file at the given index.
   */
  fun getOpenedFile(index: Int): File {
    return files[index]
  }

  /**
   * Get the number of files opened.
   *
   * @return The number of files opened.
   */
  fun getOpenedFileCount(): Int {
    return files.size
  }

  /**
   * Get the list of currently opened files.
   *
   * @return The list of opened files.
   */
  fun getOpenedFiles(): List<File> {
    return Collections.unmodifiableList(files)
  }

  /**
   * Add an observer to the list of opened files.
   *
   * @param lifecycleOwner The lifecycle owner.
   * @param observer The observer.
   */
  fun observeFiles(lifecycleOwner: LifecycleOwner?, observer: Observer<MutableList<File>?>?) {
    _files.observe(lifecycleOwner!!, observer!!)
  }

  fun getCurrentFileIndex(): Int {
    return mCurrentFile.value?.first ?: -1
  }

  fun getCurrentFile(): File? {
    return mCurrentFile.value?.second
  }

  /**
   * Get the [OpenedFilesCache] if it is already loaded, otherwise read the cache from the file
   * system and invoke the given callback.
   *
   * If the cache is already loaded, [result] is called on the same thread. Otherwise, it is always
   * called on the main/UI thread.
   */
  inline fun getOrReadOpenedFilesCache(crossinline result: (OpenedFilesCache?) -> Unit) {
    return openedFilesCache?.let(result)
        ?: run {
          viewModelScope
              .launch(Dispatchers.IO) {
                val cache =
                    try {
                      val cacheFile = getOpenedFilesCache(false)
                      if (cacheFile.exists() && cacheFile.length() > 0L) {
                        cacheFile.bufferedReader().use(OpenedFilesCache::parse)
                      } else null
                    } catch (err: IOException) {
                      // ignore exception
                      null
                    } catch (err: IllegalStateException) {
                      // 没有打开项目时 projectDir 会抛 IllegalStateException（"Path has not
                      // been set."）。此时本就没有 openedFiles 缓存可读，按「无缓存」处理。
                      // 不吞掉它会让 onStart 的协程崩掉整个进程（外层 try/catch 抓不到协程内
                      // 的异常）——无项目状态下启动编辑器 Activity 就会闪退。
                      null
                    }

                withContext(Dispatchers.Main) { result(cache) }
              }
              .also { job -> handleOpenedFilesCacheJobCompletion(job, "read") }
          Unit
        }
  }

  fun handleOpenedFilesCacheJobCompletion(it: Job, operation: String) {
    it.invokeOnCompletion { err ->
      if (err != null) {
        ILogger.ROOT.error("[EditorViewModel] Failed to {} opened files cache", operation, err)
      }
    }
  }

  fun writeOpenedFiles(cache: OpenedFilesCache?) {
    // 在**启动协程之前**捕获项目目录：切项目标签时先 saveOpenedFiles() 再改 projectDir，
    // 若把目录解析放进协程里，调度延迟会让旧项目的文件写进新项目的缓存。
    // 只捕获目录本身（纯内存读取），路径拼接与 .bak 重命名仍留在 IO 线程上。
    val dir = projectDir ?: runCatching { IProjectManager.getInstance().projectDir }.getOrNull()
    if (dir == null) {
      ILogger.ROOT.warn("[EditorViewModel] No project directory; skipping opened files cache write")
      return
    }

    viewModelScope
        .launch(Dispatchers.IO) {
          try {
            val file = openedFilesCacheFile(dir, true)

            if (cache == null) {
              // Only delete if file exists
              if (file.exists()) {
                file.delete()
              }
              return@launch
            }

            // Ensure parent directory exists before writing
            file.parentFile?.let { parentDir ->
              if (!parentDir.exists()) {
                val created = parentDir.mkdirs()
                if (!created && !parentDir.exists()) {
                  ILogger.ROOT.error("Failed to create parent directory: ${parentDir.absolutePath}")
                  return@launch
                }
              }
            }

            // Create the file if it doesn't exist
            if (!file.exists()) {
              val created = file.createNewFile()
              if (!created) {
                ILogger.ROOT.error("Failed to create cache file: ${file.absolutePath}")
                return@launch
              }
            }

            val gson = GsonBuilder().setPrettyPrinting().create()
            val string = gson.toJson(cache)

            // Use try-catch for the actual file writing
            try {
              file.writeText(string)
              ILogger.ROOT.debug("Successfully wrote opened files cache to: ${file.absolutePath}")
            } catch (e: Exception) {
              ILogger.ROOT.error("Failed to write opened files cache", e)
              // If writing fails, clean up the empty file
              if (file.exists() && file.length() == 0L) {
                file.delete()
              }
            }
          } catch (e: Exception) {
            ILogger.ROOT.error("Error in writeOpenedFiles", e)
          }
        }
        .also { job -> handleOpenedFilesCacheJobCompletion(job, "write") }
  }

  @PublishedApi
  internal fun getOpenedFilesCache(forWrite: Boolean = false): File =
      openedFilesCacheFile(projectDir ?: IProjectManager.getInstance().projectDir, forWrite)

  /** 按显式项目目录解析缓存文件；[writeOpenedFiles] 用它把目录固定为写入时的项目。 */
  private fun openedFilesCacheFile(projectDir: File, forWrite: Boolean): File {
    var file = Environment.getProjectCacheDir(projectDir)
    file = File(file, "editor/openedFiles.json")

    if (file.exists() && forWrite) {
      try {
        FileUtils.rename(file, "${file.name}.bak")
      } catch (e: Exception) {
        ILogger.ROOT.warn("Failed to create backup of cache file", e)
      }
    }

    // Ensure parent directory exists
    file.parentFile?.let { parentDir ->
      if (!parentDir.exists()) {
        val created = parentDir.mkdirs()
        if (!created && !parentDir.exists()) {
          ILogger.ROOT.error("Failed to create parent directory: ${parentDir.absolutePath}")
        }
      }
    }

    return file
  }
}
