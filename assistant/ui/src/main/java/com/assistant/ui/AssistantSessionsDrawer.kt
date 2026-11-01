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
    /**
     * 导出某个会话。
     *
     * <p>**不接会露馅**：组件长按菜单里有「导出」项（`ChatDrawer.kt:1449`），
     * 点了会关闭菜单但什么都不发生——用户以为导出成功（在别处找文件）。
     * 要么实现，要么让组件不显示该项。
     */
    onExport: (SessionRow) -> Unit = {},
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    /** 文件树里点文件：宿主决定怎么处理（本 App 把内容追加到输入框）。 */
    onOpenFile: (path: String) -> Unit,
    onAddToInput: (path: String) -> Unit,
    /**
     * 会话搜索词。
     *
     * <p><b>按标题过滤，不做全文检索</b>：全文要顺序扫每个会话的 JSONL（几十 MB），
     * 是另一个量级的开销，需要索引或后台任务。标题过滤是用户最常见的用途
     * （「上次那个叫 X 的会话在哪」），且零成本。
     */
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    /** 会话置顶状态（宿主持久化）；默认全不置顶。 */
    pinnedSessionIds: Set<String> = emptySet(),
    /**
     * 搜索结果（由宿主扫会话记录得到）。
     *
     * <p>宿主负责搜：渲染层不依赖内核的 `ConversationStore`，
     * 拿不到会话正文（见 engine 与 ui 的分层约定）。
     */
    searchHits: List<ChatSearchHit> = emptyList(),
    modifier: Modifier = Modifier,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
  AIEditorTheme(darkTheme = darkTheme, followHostFrame = false) {
    val workspacePath = workspace?.absolutePath.orEmpty()

    ChatDrawerContent(
        // ---- 会话 ----
        // 置顶状态由宿主给（组件自己的 isPinned 是数据字段，这里把它填上），
        // 这样组件的排序逻辑（置顶优先）才能生效。
        sessions = sessions.map { it.toChatSession(workspacePath, it.id in pinnedSessionIds) },
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
        // 导出：宿主把会话转成文本并走分享 Intent（渲染层拿不到会话正文）。
        onExport = { onExport(it.toRow()) },
        // 批量删除：传 null 表示「没有批量删除接口」。
        //
        // 注意这**不会**隐藏「批量选择」入口——那个在 ChatDrawer 的长按菜单里无条件出现
        // （ChatDrawer.kt:481），选中后逐条走 onDelete，功能正常。传 null 只是让组件
        // 不用批量接口而已（此前这里的注释说「不显示入口」，是错的）。
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
        // 组件在 searchQuery 非空时切到**全文结果视图**（ChatSearchResults），
        // 读的是 searchState.hits。因此这里必须给出真实结果，不能传空
        // ——传空会表现为「输入后列表消失且没有任何结果」。
        searchQuery = searchQuery,
        searchState = ChatSearchState(query = searchQuery, hits = searchHits),
        onSearchQueryChange = onSearchQueryChange,
        onClearSearch = { onSearchQueryChange("") },
        onOpenSearchHit = { hit ->
          // 点搜索结果 = 打开那个会话。这里只做「切过去」，
          // 不定位到具体消息——渲染层没有「滚动到某条消息」的能力，
          // 假装能定位会让用户以为跳错了地方。
          sessions.firstOrNull { it.id == hit.sessionId }?.let(onSelect)
        },

        // ---- 文件搜索 ----
        // null 时组件内部用一个不存在的根，文件搜索结果恒空（行为安全）。
        fileSearchEngine = null,

        modifier = modifier,
    )
  }
}

/** 会话投影 → 抽屉组件认的完整模型。 */
private fun SessionRow.toChatSession(workspacePath: String, pinned: Boolean): ChatSession =
    ChatSession(
        id = id,
        title = title,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        workspacePath = workspacePath,
        // 置顶来自宿主的持久化集合；组件据此把它排在未置顶之前。
        isPinned = pinned,
    )

/** 反向投影。 */
private fun ChatSession.toRow(): SessionRow =
    SessionRow(id = id, title = title, updatedAt = updatedAt)
