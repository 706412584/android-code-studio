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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.assistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.assistant.ui.components.independent.AgentUIState
import com.assistant.ui.components.independent.ChatDrawerContent
import com.assistant.ui.components.independent.ChatSearchHit
import com.assistant.ui.components.independent.ChatSearchState
import com.assistant.ui.components.independent.ChatSession
import com.assistant.ui.components.independent.FileBrowseState
import com.assistant.ui.components.independent.Workspace
import com.assistant.ui.components.independent.WorkspaceType
import com.assistant.ui.theme.AIEditorTheme
import java.io.File

/**
 * 侧边栏的一行会话（渲染层投影，避免宿主直接构造 15 个字段的 [ChatSession]）。
 */
data class SessionRow(
    val id: String,
    val title: String,
    /** 最后修改时间（epoch 毫秒）。 */
    val updatedAt: Long,
    val messageCount: Int = 0,
)

/**
 * 侧边栏：**会话 + 文件** 两个 Tab。
 *
 * <p><b>为什么直接用 [ChatDrawerContent] 而不是自写一个</b>：它本来就是从参考项目
 * Aharou 移植过来的完整实现（会话列表 / 文件树 / 两个搜索 / 批量选择 / 长按菜单 /
 * 剪切板）。之前自写简化版是因为它参数多（40 个），但那样等于把「项目分类」和
 * 「查看文件」这两个能力丢了——用户明确要求要。
 *
 * <p>本文件只做三件事：把宿主的窄接口翻译成它的参数、把 [WorkspaceBrowser] 的状态
 * 接上、给未实现的能力传安全的空实现。
 *
 * @param workspace 模型的工作区根目录（文件 Tab 的树根）
 * @param browser 文件树状态持有者；null 时文件 Tab 显示「读取失败」
 */
@Composable
fun AssistantSessionsDrawer(
    sessions: List<SessionRow>,
    currentSessionId: String?,
    workspace: File?,
    browser: WorkspaceBrowser?,
    onSelect: (SessionRow) -> Unit,
    onNewSession: () -> Unit,
    onDelete: (SessionRow) -> Unit,
    onRename: (SessionRow, String) -> Unit,
    onTogglePin: (SessionRow) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    /** 文件树里点文件：宿主决定怎么处理（本 App 把内容追加到输入框）。 */
    onOpenFile: (path: String) -> Unit,
    onAddToInput: (path: String) -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
  AIEditorTheme(darkTheme = darkTheme, followHostFrame = false) {
    val workspacePath = workspace?.absolutePath.orEmpty()

    ChatDrawerContent(
        // ---- 会话 ----
        sessions = sessions.map { it.toChatSession(workspacePath) },
        workspaces =
            if (workspace != null) {
              listOf(
                  Workspace(
                      name = workspace.name.ifBlank { "workspace" },
                      path = workspacePath,
                      type = WorkspaceType.INTERNAL,
                  ))
            } else {
              emptyList()
            },
        currentWorkspacePath = workspacePath,
        currentSessionId = currentSessionId,
        // 运行态：本 App 没有多会话并行，恒为 Idle。
        agentStates = emptyMap<String, AgentUIState>(),
        awaitingPermissionSessionIds = emptySet(),
        onSelect = { onSelect(it.toRow()) },
        onDelete = { onDelete(it.toRow()) },
        onRename = { session, title -> onRename(session.toRow(), title) },
        onTogglePin = { onTogglePin(it.toRow()) },
        // 导出：本 App 还没做导出（需要分享 Intent + FileProvider），传空实现。
        onExport = {},
        // 批量删除：同样未接入，传 null 时组件不显示批量选择入口。
        onDeleteSessions = null,

        // ---- 文件树 ----
        browseState = browser?.state ?: FileBrowseState.Error(null),
        expandedPaths = browser?.expandedPaths ?: emptySet(),
        expandingPath = browser?.expandingPath,
        fileOpPaths = emptySet(),
        clipboard = browser?.clipboard,
        onToggleExpand = { browser?.toggleExpand(it) },
        onOpenFile = { onOpenFile(it) },
        onRefreshBrowse = { browser?.refresh() },
        onCreateFile = { dir, name -> browser?.createFile(dir, name) },
        onCreateFolder = { dir, name -> browser?.createFolder(dir, name) },
        onRenameEntry = { path, name -> browser?.rename(path, name) },
        onDeleteEntry = { path -> browser?.delete(path) },
        onCopyEntry = { src, dst -> browser?.copy(src, dst, cut = false) },
        onCutEntry = { src, dst -> browser?.copy(src, dst, cut = true) },
        onAddToInput = { onAddToInput(it) },
        onPasteEntry = { dir, _ ->
          // 本 App 的 copy 遇到同名直接拒绝（见 WorkspaceBrowser.copy），
          // 不弹覆盖确认，因此冲突回调永不触发。
          browser?.clipboard?.let { clip -> browser?.copy(clip.sourcePath, dir, cut = clip.isCut) }
        },
        onPasteOverwrite = {},
        onCancelPasteOverwrite = {},
        onClearClipboard = { browser?.clearClipboard() },

        // ---- 底部入口 ----
        // 本 App 没有内置浏览器，传 null 时该行不渲染。
        onNavigateToBrowser = null,
        onNavigateToSettings = onOpenSettings,

        // ---- 会话搜索 ----
        // 全文搜索需要扫 JSONL，属于后续步骤；空状态时搜索框仍显示但无结果。
        searchQuery = "",
        searchState = ChatSearchState(),
        onSearchQueryChange = {},
        onClearSearch = {},
        onOpenSearchHit = { _: ChatSearchHit -> },

        // ---- 文件搜索 ----
        // null 时组件内部用一个不存在的根，文件搜索结果恒空（行为安全）。
        fileSearchEngine = null,

        modifier = modifier,
    )
  }
}

/** 会话投影 → 抽屉组件认的完整模型。 */
private fun SessionRow.toChatSession(workspacePath: String): ChatSession =
    ChatSession(
        id = id,
        title = title,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        workspacePath = workspacePath,
    )

/** 反向投影。 */
private fun ChatSession.toRow(): SessionRow =
    SessionRow(id = id, title = title, updatedAt = updatedAt)
