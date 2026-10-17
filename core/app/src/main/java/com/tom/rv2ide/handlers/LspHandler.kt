package com.tom.rv2ide.handlers

// import com.tom.rv2ide.lsp.clang.ClangLanguageServer || planned for v..03
import android.content.Context
import com.tom.rv2ide.lsp.api.ILanguageClient
import com.tom.rv2ide.lsp.api.ILanguageServerRegistry
import com.tom.rv2ide.lsp.java.JavaLanguageServer
import com.tom.rv2ide.lsp.clang.ClangLanguageServer
import com.tom.rv2ide.lsp.dart.DartLanguageServer
import com.tom.rv2ide.lsp.kotlin.KotlinLanguageServer
import com.tom.rv2ide.lsp.typescript.TypeScriptLanguageServer
import com.tom.rv2ide.lsp.xml.XMLLanguageServer
import com.tom.rv2ide.projects.IWorkspace

/** @author Akash Yadav */
object LspHandler {

  fun registerLanguageServers(context: Context) {
    ILanguageServerRegistry.getDefault().apply {
      getServer(JavaLanguageServer.SERVER_ID) ?: register(JavaLanguageServer())
      getServer(KotlinLanguageServer.SERVER_ID) ?: register(KotlinLanguageServer(context))
      getServer(ClangLanguageServer.SERVER_ID) ?: register(ClangLanguageServer(context))
      getServer(XMLLanguageServer.SERVER_ID) ?: register(XMLLanguageServer())
      // 外部进程服务器（懒启动：首次打开对应类型文件才起进程）
      getServer(TypeScriptLanguageServer.SERVER_ID) ?: register(TypeScriptLanguageServer())
      getServer(DartLanguageServer.SERVER_ID) ?: register(DartLanguageServer())
    }
  }

  fun connectClient(client: ILanguageClient) {
    ILanguageServerRegistry.getDefault().connectClient(client)
  }

  /**
   * Route all language servers to the workspace of the project session that is now in the
   * foreground.
   *
   * <p>Must be called on every tab switch: servers are process-wide and only re-configured by
   * [ILanguageServerRegistry.setActiveWorkspace], so a switch that only changes the Activity would
   * leave diagnostics/completion pointed at the previous project.
   *
   * @param workspace The active workspace, or `null` when no project is open.
   * @param force Re-push even if the workspace is already active (use after a re-sync).
   */
  fun setActiveWorkspace(workspace: IWorkspace?, force: Boolean = false) {
    ILanguageServerRegistry.getDefault().setActiveWorkspace(workspace, force)
  }

  /** The workspace the language servers are currently configured for. */
  fun getActiveWorkspace(): IWorkspace? {
    return ILanguageServerRegistry.getDefault().activeWorkspace
  }

  /**
   * Stop the servers belonging to one project session.
   *
   * <p>With multiple tabs open, closing one tab must **not** call [destroyLanguageServers] — that
   * shuts down every registered server (including the ones still serving the other tabs) and
   * clears the registry, after which a remaining tab would have to re-register everything.
   *
   * <p>Servers are process-wide and currently keep a single workspace, so there is nothing
   * per-session to shut down; this method instead makes the *remaining* active session take over
   * the servers. Only [destroyLanguageServers] (last session closed / process exit) tears them
   * down.
   *
   * <p><b>Not called by the current integration.</b> S2 keeps every tab inside a single Activity,
   * so closing a tab does not destroy the Activity; `BaseEditorActivity.applyProjectSwitch` already
   * re-routes the servers via [setActiveWorkspace] when switching to the remaining project, which
   * covers this case. This method is kept for a future integration that tears down per-tab
   * Activities/holders (S3/S4) — use it there instead of `destroyLanguageServers`.
   *
   * @param workspace The workspace of the session being closed.
   * @param remainingActiveWorkspace Workspace that should become active, or `null` if none remains.
   */
  @Suppress("unused")
  fun stopSession(
      workspace: IWorkspace?,
      remainingActiveWorkspace: IWorkspace?,
  ) {
    val registry = ILanguageServerRegistry.getDefault()
    if (remainingActiveWorkspace == null) {
      // No sessions left: this is the only case where a full shutdown is correct.
      registry.destroy()
      return
    }

    val active = registry.activeWorkspace
    if (workspace != null && active != null && !sameProjectDir(workspace, active)) {
      // A background tab was closed: the servers never pointed at it, and re-configuring them
      // would needlessly invalidate the remaining project's compiler caches.
      return
    }

    // The closing tab was the one the servers were configured for: hand them over to the
    // remaining session. `force` because the server-side caches were built for the closing
    // project and must be rebuilt for this one even if the registry already tracked it.
    registry.setActiveWorkspace(remainingActiveWorkspace, true)
  }

  private fun sameProjectDir(first: IWorkspace, second: IWorkspace): Boolean {
    return try {
      first.getProjectDir().canonicalPath == second.getProjectDir().canonicalPath
    } catch (e: Exception) {
      first.getProjectDir().absolutePath == second.getProjectDir().absolutePath
    }
  }

  fun destroyLanguageServers(isConfigurationChange: Boolean) {
    if (isConfigurationChange) {
      return
    }
    ILanguageServerRegistry.getDefault().destroy()
  }
}
