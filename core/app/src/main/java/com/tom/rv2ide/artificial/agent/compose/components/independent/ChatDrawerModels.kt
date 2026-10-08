/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from Aharou (https://github.com/520huxiangli/Aharou),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

package com.tom.rv2ide.artificial.agent.compose.components.independent

import androidx.compose.runtime.Immutable
import com.tom.rv2ide.artificial.agent.compose.compat.AgentMode
import com.tom.rv2ide.artificial.agent.compose.compat.ReasoningEffort
import com.tom.rv2ide.artificial.agent.compose.compat.WorkspaceSearchEngine
import com.tom.rv2ide.artificial.agent.compose.compat.WorkspaceSearchHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/*
 * ChatDrawer 及其子组件用到的领域/展示模型，按 Aharou 原文照搬（保留全部中文 KDoc）：
 * - ChatSession ← feature/agent/domain/model/ChatSession.kt（AgentMode/ReasoningEffort 取自 compose.compat.AharouModelTypes，不重定义）
 * - FileEntry ← feature/workspace/domain/FileAccessProvider.kt（仅 data class，FileAccessProvider 接口本体在 compose.compat.AharouFileAccess 有最小投影）
 * - FileTreeNode/BrowseClipboard/WorkflowStatus/AgentUIState/FileBrowseState/ChatSearchHit/ChatSearchState ← feature/agent/presentation/AgentUiModels.kt
 *   （FileSearchState 不在此定义——同包 DrawerSearch.kt 已有）
 * - isValidFileEntryName ← FileAccessProvider.kt 顶层函数
 * - Workspace/WorkspaceType ← feature/workspace/domain/model/Workspace.kt（纯数据投影，ACS 接线时从 IProjectManager 投影）
 * - FileSearchViewModel ← feature/agent/presentation/FileSearchViewModel.kt（去 Hilt 版，见该类 KDoc）
 */

/**
 * 一次独立的聊天会话。消息通过 sessionId 归属到会话，切换会话即切换聊天历史。
 */
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val workspacePath: String = "",
    val mode: AgentMode = AgentMode.BUILD,
    /** 进入 PLAN 前的模式（如 AUTO）；退出 PLAN 时恢复，null 视为 BUILD。 */
    val modeBeforePlan: AgentMode? = null,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
    val providerId: String? = null,
    val model: String? = null,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val lastInputTokens: Int = 0,
    val isPinned: Boolean = false,
    /** 子代理会话：父会话 id；null 表示普通根会话。 */
    val parentId: String? = null,
    /** 子代理会话：派生子代理的类型（如 coder / researcher）；null 表示普通根会话。 */
    val subagentType: String? = null
)

/** 目录条目信息，供文件浏览/搜索列出目录条目（Aharou `FileAccessProvider.listFiles` 的返回元素）。 */
data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    /** 文件大小（字节）；目录可为 0。 */
    val size: Long,
    /** 最后修改时间（epoch 毫秒）。 */
    val lastModified: Long,
    /** 本地模式下可提供宿主 [java.io.File]，供需要本地路径的调用方（如 [android.graphics.BitmapFactory]）使用；远程模式为 null。 */
    val localFile: java.io.File? = null,
    /** 可读/可写/可执行权限位（rwx 字符串，如 "rwx"）；远程按 SFTP 权限解析，无法获取时给合理默认。 */
    val permissions: String = "---"
)

/**
 * 侧边栏文件树的一个可见节点（已按展开状态扁平化，携带 [depth] 供 UI 缩进）。
 * 从工作区根就地展开：只有 [FileBrowseState.Success] 里出现的节点才是当前可见的。
 */
@Immutable
data class FileTreeNode(
    val entry: FileEntry,
    /** 容器绝对路径（如 `~/workspace/app/src`）；[isRoot] 时为工作区根。 */
    val path: String,
    /** 缩进层级：根为 0，其直接子项为 1，依此类推。 */
    val depth: Int,
    /** 工作区根节点：不可重命名/删除，只能在其下新建。 */
    val isRoot: Boolean,
    /** 目录是否已展开；非目录恒为 false。 */
    val isExpanded: Boolean,
    /** 展开该目录时读取子项失败（如权限不足）；根读取失败走 [FileBrowseState.Error]。 */
    val hasError: Boolean = false,
    /** 被工作区根 .gitignore 命中：UI 以橙色弱化显示（类 VSCode）。 */
    val ignored: Boolean = false
)

/** 文件浏览剪切板：一次只能持有一项，复制或剪切后待粘贴。 */
@Immutable
data class BrowseClipboard(
    /** 源条目所在目录路径。 */
    val sourcePath: String,
    /** 源条目显示名（UI 提示用）。 */
    val sourceName: String,
    /** 是否剪切（粘贴成功后删除源）。 */
    val isCut: Boolean
)

/** 单轮工作流的最终结果状态（供 [AgentUIState.Result] 使用）。 */
enum class WorkflowStatus {
    SUCCESS, PARTIAL_SUCCESS, FAILED, CANCELLED
}

sealed class AgentUIState {
    object Idle : AgentUIState()
    object Loading : AgentUIState()
    object Streaming : AgentUIState()
    data class Result(val status: WorkflowStatus) : AgentUIState()
    data class Error(val message: String) : AgentUIState()
}

/** 侧边栏「文件」Tab 的文件树读取状态。远程模式走 SFTP/exec，读取可能失败或较慢，故区分三态。 */
sealed interface FileBrowseState {
    data object Loading : FileBrowseState
    data class Success(val nodes: List<FileTreeNode>) : FileBrowseState
    data class Error(val detail: String?) : FileBrowseState
}

/** 一条聊天记录搜索命中：会话标题 + 消息片段，供侧边栏结果行展示。 */
@Immutable
data class ChatSearchHit(
    val sessionId: String,
    val sessionTitle: String,
    val messageId: String,
    /** 命中词为中心的正文片段（已折叠换行空白，两端按需加省略号）。 */
    val snippet: String,
    val timestamp: Long
)

/** 侧边栏聊天记录搜索状态：当前关键词与命中的扁平结果（按时间倒序）。 */
@Immutable
data class ChatSearchState(
    val query: String = "",
    val loading: Boolean = false,
    val hits: List<ChatSearchHit> = emptyList()
)

/**
 * 单个目录条目名是否合法：非空、不含路径分隔符、不是 `.` 或 `..`。
 * 拦掉 `../` 这类会跳出当前目录的输入，供文件浏览的新建/重命名使用。
 */
fun isValidFileEntryName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() &&
        !trimmed.contains('/') &&
        !trimmed.contains('\\') &&
        trimmed != "." &&
        trimmed != ".."
}

/** 工作区类型：内部（App 私有 projects 根下）/ 外部本地目录（用户所选设备目录）/ 远程。 */
enum class WorkspaceType {
    INTERNAL,
    EXTERNAL_LOCAL,
    REMOTE
}

/**
 * 一个工作区 = AI 的操作根目录。
 *
 * - [WorkspaceType.INTERNAL]：App 私有项目根目录（filesDir/projects）下的一个子文件夹，物理上在私有 ext4。
 * - [WorkspaceType.EXTERNAL_LOCAL]：用户通过系统目录选择器选定的设备本地目录（如 /storage/emulated/0/xxx），
 *   双向直接读写该目录，不复制。删除工作区只解除关联、不删物理文件。
 * - [WorkspaceType.REMOTE]：远程 SSH 服务器上 remoteWorkspacePath 下的子文件夹。
 *
 * AI 的文件工具与命令执行都以 [path] 为根，切换工作区即切换 AI 的操作范围。
 *
 * ACS 移植注：纯数据投影（Aharou `feature/workspace/domain/model/Workspace.kt` 照搬），
 * ACS 接线时从 IProjectManager 投影当前工程根。
 */
data class Workspace(
    /** 文件夹名，作为唯一标识。 */
    val name: String,
    /** 绝对路径，可直接用于 java.io.File 与容器挂载。 */
    val path: String,
    /** 工作区类型，默认内部工作区，保持旧构造兼容。 */
    val type: WorkspaceType = WorkspaceType.INTERNAL,
    /** 工作区当前是否可用（外部本地目录被移动/删除时为 false），默认可用。 */
    val available: Boolean = true
)

/**
 * 侧边栏「文件」Tab 的工作区搜索：关键词防抖后交给 [WorkspaceSearchEngine]，
 * 结果按文件分组由 UI 完成（与「会话」Tab 的搜索同一套交互：输入即出结果、点击跳转）。
 *
 * 独立成一个状态持有者（而不是塞进会话 ViewModel）是为了不碰行数棘轮基线文件，
 * 搜索本身也不依赖会话状态：搜索根就是当前工作区。
 *
 * ACS 移植注（去 Hilt）：ACS 无 DI 框架，宿主用 remember 持有并在 dispose 时释放。
 * 原 androidx ViewModel 的 viewModelScope 由自建 [scope]（SupervisorJob + Dispatchers.Default）
 * 替代，onCleared 换成 [dispose]；debounce/flatMapLatest/stateIn 逻辑逐字保留。
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class FileSearchViewModel(private val engine: WorkspaceSearchEngine) {

    private companion object {
        /** 输入停顿多久才真正发起搜索：与 VS Code 的 searchOnTypeDebouncePeriod 同值（300ms）。 */
        const val DEBOUNCE_MS = 300L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _query = MutableStateFlow("")

    /** 输入框里的原始关键词（可能还没触发搜索）。 */
    val query: StateFlow<String> = _query.asStateFlow()

    val state: StateFlow<FileSearchState> = _query
        .debounce(DEBOUNCE_MS)
        .flatMapLatest { raw ->
            val keyword = raw.trim()
            if (keyword.isEmpty()) {
                flowOf(FileSearchState(query = raw))
            } else {
                flow {
                    emit(FileSearchState(query = raw, loading = true))
                    val result = engine.search(keyword)
                    emit(
                        FileSearchState(
                            query = raw,
                            hits = result.hits,
                            truncated = result.truncated
                        )
                    )
                }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, FileSearchState())

    fun updateQuery(value: String) {
        _query.value = value
    }

    fun clear() {
        _query.value = ""
    }

    /** 取消自建协程域（对应 androidx ViewModel 的 onCleared）。 */
    fun dispose() {
        scope.cancel()
    }
}
