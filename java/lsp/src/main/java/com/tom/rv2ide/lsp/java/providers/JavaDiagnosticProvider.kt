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
package com.tom.rv2ide.lsp.java.providers

import android.os.Process

import com.tom.rv2ide.lsp.java.compiler.CompileTask
import com.tom.rv2ide.lsp.java.compiler.JavaCompilerService
import com.tom.rv2ide.lsp.java.compiler.SourceFileObject
import com.tom.rv2ide.lsp.java.providers.DiagnosticsProvider.findDiagnostics
import com.tom.rv2ide.lsp.java.utils.CancelChecker
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.progress.ProgressManager
import com.tom.rv2ide.progress.ProgressManager.Companion.abortIfCancelled
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.projects.IWorkspace
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * Code analyzer for java source code.
 *
 * @author Akash Yadav
 */
class JavaDiagnosticProvider(
    /**
     * Resolves the workspace this provider belongs to.
     *
     * <p>The language server is a process-wide singleton serving the active project tab, so the
     * workspace must come from the server (last `setupWorkspace`), not from the global
     * `IProjectManager` — during a tab switch the singleton may already point at another project.
     * The default keeps standalone/AI call sites working.
     */
    private val workspaceProvider: () -> IWorkspace? = {
      IProjectManager.getInstance().getWorkspace()
    }
) {

  private val analyzeTimestamps = mutableMapOf<Path, Instant>()

  // 诊断缓存必须**按文件存**。此前是单字段：分析文件 B 会覆盖它，之后再查未改动的 A
  // 时命中 analyzeTimestamps[A] 的时间戳，却返回了 B 的诊断——跨文件串台的误报。
  // AI 诊断工具会连续分析多个文件（目录模式/多次单文件），而本 provider 是语言服务器
  // 的单例成员、跨调用存活，于是串台必然发生。analyzeTimestamps 本就是按文件的，对齐它。
  private val cachedDiagnostics = mutableMapOf<Path, DiagnosticResult>()
  private var analyzing = AtomicBoolean(false)
  private var analyzingThread: AnalyzingThread? = null

  companion object {

    private val log = LoggerFactory.getLogger(JavaDiagnosticProvider::class.java)
  }

  fun analyze(file: Path): DiagnosticResult {

    val module =
        workspaceProvider()?.findModuleForFile(file, false)
            ?: return DiagnosticResult.NO_UPDATE
    val compiler = JavaCompilerService(module)

    abortIfCancelled()

    log.debug("Analyzing: {}", file)

    // 缓存判定必须用**磁盘 mtime**，不能用 FileManager.getLastModified：
    // 后者对编辑器打开过的文件返回 activeDocument 的内存时间（DocumentOpen 时刻），
    // 之后 AI/外部工具直写磁盘时它不更新——于是「文件已改」被判成「没改」，
    // 直接返回上一次的 cachedDiagnostics。实测后果：AI 把删掉的 return 加回去，
    // 诊断仍粘滞报「缺少返回语句」，直到文件再次经编辑器改动。
    // 磁盘判定与下方编译器读盘（SourceFileObject→FileManager.getDocumentContents 仅在
    // 编辑器打开时优先内存，而那个内容会随 DocumentChangeEvent 同步）语义一致。
    val modifiedAt =
        try {
          java.nio.file.Files.getLastModifiedTime(file).toInstant()
        } catch (e: Exception) {
          FileManager.getLastModified(file)
        }
    val analyzedAt = analyzeTimestamps[file]
    val cached = cachedDiagnostics[file]

    if (cached != null && analyzedAt?.isAfter(modifiedAt) == true) {
      log.debug("Using cached analyze results...")
      return cached
    }

    analyzingThread?.let { analyzingThread ->
      if (analyzing.get()) {
        log.debug("Cancelling currently analyzing thread...")
        ProgressManager.instance.cancel(analyzingThread)
        this.analyzingThread = null
      }
    }

    analyzing.set(true)

    val analyzingThread =
        AnalyzingThread(compiler, file).also {
          analyzingThread = it
          it.start()
          it.join()
        }

    return analyzingThread.result.also { this.analyzingThread = null }
  }

  fun isAnalyzing(): Boolean {
    return this.analyzing.get()
  }

  fun cancel() {
    this.analyzingThread?.cancel()
  }

  fun clearTimestamp(file: Path) {
    analyzeTimestamps.remove(file)
    cachedDiagnostics.remove(file)
  }

  /**
   * Drop every cached timestamp/diagnostic.
   *
   * <p>Called when the server switches workspace: diagnostics of the previous project must not be
   * served for the same-named file in the new one, and the cached timestamps refer to compilers
   * that are about to be destroyed.
   */
  fun clearAll() {
    analyzeTimestamps.clear()
    cachedDiagnostics.clear()
    cancel()
  }

  private fun doAnalyze(file: Path, task: CompileTask): DiagnosticResult {
    val result =
        if (!isTaskValid(task)) {
          // Do not use Collections.emptyList ()
          // The returned list is accessed and the list returned by Collections.emptyList()
          // throws exception when trying to access.
          log.info("Using cached diagnostics")
          cachedDiagnostics[file] ?: DiagnosticResult.NO_UPDATE
        } else DiagnosticResult(file, findDiagnostics(task, file).sortedBy { it.range })
    return result.also {
      log.info("Analyze file completed. Found {} diagnostic items", result.diagnostics.size)
    }
  }

  private fun isTaskValid(task: CompileTask?): Boolean {
    abortIfCancelled()
    return task?.task != null && task.roots != null && task.roots.size > 0
  }

  /**
   * 为诊断构造**磁盘快照**的源文件对象。
   *
   * `SourceFileObject(path)` 的内容是惰性的：javac 通过 `getCharContent` 读内容时，
   * 它会走 [FileManager.getDocumentContents]——而那个方法对**编辑器打开过的文件**
   * 优先返回 activeDocument 的内存内容。AI 工具（或任何外部进程）直写磁盘后，
   * 编辑器缓冲并不同步，于是诊断拿到的还是旧代码：实测 AI 把删掉的 `return` 加回去，
   * 编译器仍然「看到」没有 return 的版本，报出粘滞的「缺少返回语句」。
   *
   * 修复：诊断在**开始分析这一刻**把磁盘内容读死进快照（contents + 磁盘 mtime），
   * javac 从此只认这份快照——编辑器内存内容不再参与诊断，磁盘才是唯一真相。
   * 读盘失败时退回惰性模式（path-only），行为与旧版一致。
   */
  private fun analyzeSource(file: Path): SourceFileObject {
    return try {
      val bytes = java.nio.file.Files.readAllBytes(file)
      val mtime = java.nio.file.Files.getLastModifiedTime(file).toInstant()
      SourceFileObject(file, String(bytes, Charsets.UTF_8), mtime)
    } catch (e: Exception) {
      log.debug("Diagnostic snapshot fallback to lazy mode for {}", file, e)
      SourceFileObject(file)
    }
  }

  inner class AnalyzingThread(val compiler: JavaCompilerService, val file: Path) :
      Thread("JavaAnalyzerThread") {

    var result: DiagnosticResult = DiagnosticResult.NO_UPDATE

    fun cancel() {
      ProgressManager.instance.cancel(this)
    }

    override fun run() {
      Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
      result =
          try {
                compiler.compile(listOf(analyzeSource(file))).get { task ->
                      doAnalyze(file, task)
                    }
              } catch (err: Throwable) {
                if (CancelChecker.isCancelled(err)) {
                  log.error("Analyze request cancelled")
                } else {
                  log.warn("Unable to analyze file", err)
                }
                DiagnosticResult.NO_UPDATE
              } finally {
                compiler.destroy()
                analyzing.set(false)
              }
              .also {
                cachedDiagnostics[file] = it
                analyzeTimestamps[file] = Instant.now()
              }
    }
  }
}
