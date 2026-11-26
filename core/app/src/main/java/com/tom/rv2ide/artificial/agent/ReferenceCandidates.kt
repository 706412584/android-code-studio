package com.tom.rv2ide.artificial.agent

import android.content.Context
import com.tom.rv2ide.ai.agent.conversation.ConversationSummary
import com.tom.rv2ide.artificial.agent.compose.compat.FileAccessProvider
import com.tom.rv2ide.artificial.agent.compose.compat.HostFileAccessProvider
import com.tom.rv2ide.artificial.agent.compose.components.independent.ComposerReference
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ReferenceCandidate(
    val reference: ComposerReference,
    val detail: String,
)

internal fun recentReferenceSessions(sessions: List<ConversationSummary>, cwd: String, currentId: String?): List<ConversationSummary> =
    sessions.asSequence().filter {
      cwd.isNotBlank() && it.cwd == cwd && it.id != currentId && !it.isSubAgent && it.messageCount > 0
    }.sortedByDescending { it.modifiedAt }.take(2).toList()

/** 只查本地元数据；打开菜单不会连接 MCP 或读取项目文件正文。 */
internal suspend fun referenceCandidates(
    context: Context,
    orchestrator: AgentOrchestrator,
    workspace: File?,
    currentId: String?,
    query: String,
    kind: String?,
): List<ReferenceCandidate> {
  fun candidate(type: String, id: String, label: String, detail: String, path: String = "") =
      ReferenceCandidate(ComposerReference(type, id, label.replace('\n', ' ').take(80), path, 0, 0), detail)
  val result = mutableListOf<ReferenceCandidate>()
  if (kind == null || kind == "session") {
    recentReferenceSessions(orchestrator.listConversations(), workspace?.absolutePath.orEmpty(), currentId).forEach {
      result += candidate("session", it.id, it.title,
          "${it.id.take(8)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.modifiedAt))}")
    }
  }
  if (kind == null || kind == "agent") {
    orchestrator.builtinAgentStore.enabled().forEach {
      result += candidate("agent", it.toolName(), it.name, it.description)
    }
    orchestrator.customAgentStore.usable().forEach {
      result += candidate("agent", it.toolName(), it.name, it.description)
    }
  }
  if (kind == null || kind == "skill") {
    orchestrator.skillRegistry.reload()
    orchestrator.skillRegistry.all().filter { it.isUsable }.forEach {
      result += candidate("skill", it.name, it.name, it.description)
    }
  }
  if (kind == null || kind == "plugin") {
    McpServers(context).enabled().forEach {
      // 地址是配置中的稳定键，但可能带 token，不放进模型正文或候选说明。
      val key = java.security.MessageDigest.getInstance("SHA-256")
          .digest((it.type + ":" + it.url).toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
      result += candidate("plugin", key, if (it.label.isNotBlank()) it.label else "MCP ${key.take(8)}", it.type)
    }
  }
  val matches = result.filter { query.isEmpty() || it.reference.label.contains(query, true) || it.detail.contains(query, true) }.toMutableList()
  if ((kind == null || kind == "file") && workspace?.isDirectory == true) {
    matches += fileReferenceCandidates(HostFileAccessProvider(workspace), workspace, query)
  }
  return matches.take(80)
}

internal suspend fun fileReferenceCandidates(provider: FileAccessProvider, workspace: File, query: String): List<ReferenceCandidate> {
  val root = workspace.canonicalFile
  val queue = java.util.ArrayDeque<Pair<File, Int>>()
  queue.add(root to 0)
  val visited = mutableSetOf<String>()
  val ignored = setOf(".git", ".gradle", ".idea", "build", "node_modules", ".codegraph", ".kotlin")
  val found = mutableListOf<ReferenceCandidate>()
  var scanned = 0
  while (queue.isNotEmpty() && scanned < 6000 && found.size < 60) {
    currentCoroutineContext().ensureActive()
    val (directory, depth) = queue.removeFirst()
    if (depth > 12 || !visited.add(directory.path)) continue
    for (name in provider.listFilesRecursive(directory.path, 1)) {
      currentCoroutineContext().ensureActive()
      if (++scanned > 6000) break
      val child = provider.copyToLocal(File(directory, name).path).canonicalFile
      if (!child.toPath().startsWith(root.toPath())) continue
      if (child.isDirectory) {
        if (child.name !in ignored) queue.add(child to depth + 1)
      } else if (child.isFile) {
        val relative = child.relativeTo(root).invariantSeparatorsPath
        if (query.isNotBlank() && !relative.contains(query, true)) continue
        found += ReferenceCandidate(ComposerReference("file", relative, child.name, child.path, 0, 0), relative)
        if (found.size == 60) break
      }
    }
  }
  return found
}
