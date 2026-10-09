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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.tom.rv2ide.artificial.agent.compose.components.independent.FileBrowseState
import com.tom.rv2ide.artificial.agent.compose.components.independent.FileEntry
import com.tom.rv2ide.artificial.agent.compose.components.independent.FileTreeNode
import com.tom.rv2ide.artificial.agent.compose.components.independent.isValidFileEntryName
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 会话抽屉「文件」Tab 的宿主状态（[FloatingAssistantView] 专用）。
 *
 * <p><b>为什么单独一个文件</b>：FloatingAssistantView 已 3600+ 行，文件树的读目录 /
 * 新建 / 重命名 / 删除 / 复制剪切粘贴是一整块自洽的状态机（展开集、剪切板、冲突确认、
 * 操作中路径集），塞进宿主只会让它再涨两三百行。这里持有全部状态并暴露操作方法，
 * 宿主只把 `browseState` / `expandedPaths` 等读出来喂给 [com.tom.rv2ide.artificial.agent.compose.components.independent.ChatDrawerContent]。
 *
 * <p>全部操作直接用 `java.io.File`（ACS 的工具层同样直接落宿主文件），容器路径与
 * 宿主路径一一对应（无远程模式）。所有变更后自动重拉当前展开的目录。
 *
 * <p>**线程**：方法内部自行切 IO；`browseState` 的写入都在主线程（mutableStateOf 要求）。
 * 读目录用懒展开（每次只读一层），与 Aharou 的 `FileBrowseState` 语义一致。
 */
class AssistantDrawerFileHost {

  /** 当前工作区根；null 时文件 Tab 呈 Loading 之外的空态由 UI 兜住（nodes 为空）。 */
  var workspaceRoot: File? = null

  /** 文件树三态。根目录不可读时进 [FileBrowseState.Error]。 */
  var browseState by mutableStateOf<FileBrowseState>(FileBrowseState.Loading)
    private set

  /** 已展开目录的容器路径集合（驱动子项可见性与箭头方向）。 */
  val expandedPaths = mutableSetOf<String>()

  /** 正在展开（读目录 IO 中）的路径；UI 据此显示加载圈。 */
  var expandingPath: String? = null
    private set

  /** 正在执行变更操作（新建/删除/粘贴…）的路径集合；UI 据此禁用行。 */
  val fileOpPaths: SnapshotStateList<String> = androidx.compose.runtime.mutableStateListOf()

  /** 剪切板（复制/剪切各一条目）；null 表示空。 */
  var clipboard: com.tom.rv2ide.artificial.agent.compose.components.independent.BrowseClipboard? = null
    private set

  /** 粘贴冲突待确认 (目标路径, 名字)；确认覆盖/取消后清空。 */
  var pasteConflict: Pair<String, String>? = null
    private set

  private var pendingPaste: Pair<File, File>? = null

  // ── 读取 ──

  /** 切换工作区并重置树状态。 */
  fun setWorkspace(root: File?) {
    workspaceRoot = root
    expandedPaths.clear()
    clipboard = null
    pasteConflict = null
    if (root != null) {
      expandedPaths.add(containerPath(root))
      refreshExpanded()
    } else {
      browseState = FileBrowseState.Success(emptyList())
    }
  }

  /** 展开或折叠一个目录；展开时读一层子项。 */
  fun toggleExpand(path: String) {
    if (!expandedPaths.remove(path)) {
      expandedPaths.add(path)
      refreshExpanded()
    } else {
      refreshExpanded()
    }
  }

  /** 重新读取当前展开的所有层级。 */
  fun refreshExpanded() {
    val root = workspaceRoot ?: return
    expandingPath = null
    io {
      val nodes = mutableListOf<FileTreeNode>()
      var firstError: String? = null
      fun readLevel(dir: File, depth: Int) {
        val children =
            runCatching {
              dir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            }.getOrNull()
        if (children == null) {
          if (firstError == null) firstError = dir.absolutePath
          return
        }
        for (child in children) {
          val rel = child.relativeToOrNull(root)?.invariantSeparatorsPath ?: continue
          nodes += FileTreeNode(
              entry =
                  FileEntry(
                      name = child.name,
                      isDirectory = child.isDirectory,
                      size = if (child.isFile) child.length() else 0L,
                      lastModified = child.lastModified(),
                      localFile = child,
                  ),
              path = containerPath(child),
              depth = depth,
              isRoot = false,
              isExpanded = child.isDirectory && containerPath(child) in expandedPaths,
          )
          if (child.isDirectory && containerPath(child) in expandedPaths) {
            readLevel(child, depth + 1)
          }
        }
      }
      readLevel(root, 0)
      withContext(Dispatchers.Main) {
        browseState =
            if (nodes.isEmpty() && firstError != null) FileBrowseState.Error(firstError)
            else FileBrowseState.Success(nodes)
      }
    }
  }

  // ── 变更 ──

  fun createEntry(parentPath: String, name: String, isFolder: Boolean) {
    if (!isValidFileEntryName(name)) return
    val parent = hostFile(parentPath) ?: return
    io {
      runCatching {
        val target = File(parent, name)
        if (isFolder) target.mkdirs() else target.createNewFile()
      }
      refreshExpanded()
    }
  }

  fun renameEntry(path: String, newName: String) {
    if (!isValidFileEntryName(newName)) return
    val file = hostFile(path) ?: return
    io {
      runCatching { file.renameTo(File(file.parentFile, newName)) }
      refreshExpanded()
    }
  }

  fun deleteEntry(path: String) {
    val file = hostFile(path) ?: return
    io {
      runCatching { file.deleteRecursively() }
      withContext(Dispatchers.Main) { expandedPaths.remove(path) }
      refreshExpanded()
    }
  }

  /** 复制或剪切一个条目到剪切板（同一目录内重名延后到粘贴时决定）。 */
  fun copyEntry(sourcePath: String, targetDirPath: String, cut: Boolean) {
    val src = hostFile(sourcePath) ?: return
    clipboard =
        com.tom.rv2ide.artificial.agent.compose.components.independent.BrowseClipboard(
            sourcePath = sourcePath,
            sourceName = src.name,
            isCut = cut,
        )
  }

  /**
   * 粘贴剪切板内容到 [targetDirPath]。重名时**不覆盖**，置 [pasteConflict] 等用户确认
   * （回调 [onConflict] 传 true 表示覆盖）。
   */
  fun paste(targetDirPath: String, onConflict: (Boolean) -> Unit) {
    val clip = clipboard ?: return
    val src = hostFile(clip.sourcePath) ?: return
    val dstDir = hostFile(targetDirPath) ?: return
    val dst = File(dstDir, src.name)
    if (dst.exists()) {
      pendingPaste = src to dst
      pasteConflict = targetDirPath to src.name
      onConflict(true) // 通知 UI 弹确认框（ChatDrawerContent 内部据此显示覆盖确认）
      return
    }
    doPaste(src, dst, clip.isCut)
  }

  /** 用户在冲突确认框里选「覆盖」。 */
  fun pasteOverwrite() {
    val (src, dst) = pendingPaste ?: return clearConflict()
    doPaste(src, dst, clipboard?.isCut ?: false)
  }

  /** 用户取消覆盖确认。 */
  fun cancelPasteOverwrite() = clearConflict()

  fun clearClipboard() {
    clipboard = null
  }

  // ── 内部 ──

  private fun doPaste(src: File, dst: File, cut: Boolean) {
    io {
      runCatching {
        if (src.isDirectory) {
          src.copyRecursively(dst, overwrite = true)
          if (cut) src.deleteRecursively()
        } else {
          src.copyTo(dst, overwrite = true)
          if (cut) src.delete()
        }
      }
      withContext(Dispatchers.Main) {
        if (cut) clipboard = null
        clearConflict()
      }
      refreshExpanded()
    }
  }

  private fun clearConflict() {
    pendingPaste = null
    pasteConflict = null
  }

  /** 容器路径（`~/workspace/...`）→ 宿主 [File]；只认当前工作区内的路径。 */
  private fun hostFile(containerPath: String): File? {
    val root = workspaceRoot ?: return null
    val prefix = CONTAINER_PREFIX
    val rel =
        when {
          containerPath == "$prefix" -> ""
          containerPath.startsWith("$prefix/") -> containerPath.removePrefix("$prefix/")
          // 也接受直接的宿主绝对路径（搜索命中透传等场景）
          else -> {
            val f = File(containerPath)
            return if (f.absolutePath.startsWith(root.absolutePath)) f else null
          }
        }
    return if (rel.isEmpty()) root else File(root, rel)
  }

  private fun containerPath(file: File): String {
    val root = workspaceRoot ?: return file.absolutePath
    val rel = file.relativeToOrNull(root)?.invariantSeparatorsPath
    return if (rel == null || rel == "." || rel.isEmpty()) CONTAINER_PREFIX
    else "$CONTAINER_PREFIX/$rel"
  }

  private fun io(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch(block = block)
  }

  companion object {
    /** 与 [com.tom.rv2ide.artificial.agent.compose.compat.WorkspacePathMapper.CONTAINER_ROOT] 同值，避免引 compat 依赖环。 */
    private const val CONTAINER_PREFIX = "~/workspace"
  }
}
