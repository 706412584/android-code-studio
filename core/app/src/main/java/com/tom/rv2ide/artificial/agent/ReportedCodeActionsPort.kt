/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.ai.tool.CodeActionsPort
import com.tom.rv2ide.ai.tool.GatedAnalyzer
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.java.JavaLanguageServer
import com.tom.rv2ide.lsp.java.rewrite.AddException
import com.tom.rv2ide.lsp.java.rewrite.AddImport
import com.tom.rv2ide.lsp.java.rewrite.AddSuppressWarningAnnotation
import com.tom.rv2ide.lsp.java.rewrite.CreateMissingMethod
import com.tom.rv2ide.lsp.java.rewrite.ImplementAbstractMethods
import com.tom.rv2ide.lsp.java.rewrite.RemoveException
import com.tom.rv2ide.lsp.java.rewrite.Rewrite
import com.tom.rv2ide.lsp.java.utils.CodeActionUtils
import com.tom.rv2ide.javac.services.util.JavaDiagnosticUtils
import com.tom.rv2ide.lsp.models.TextEdit
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import org.slf4j.LoggerFactory

/**
 * 把 Java 语言服务器的「代码动作」（类型系统感知的生成/重构）接到 AI 工具层。
 *
 * **为什么绕过 ActionItem 体系直接调 Rewrite**：现有 code action
 * （`BaseJavaCodeAction` → `ILanguageClient.performCodeAction` → `IDELanguageClientImpl`）
 * 强制要求编辑器 Activity，且把编辑写进 `IDEEditor`（文件未打开时还会打开页签）。
 * 而 `Rewrite.rewrite(compiler)` 是纯计算——只依赖 `CompilerProvider`，返回
 * `Map<Path, TextEdit[]>`。在本类里应用 edits → 写盘 → 经 [DocumentSyncPort] 通知编辑器，
 * 与已验证的 [ReportedCodeFormatPort] 完全同构，且 java/lsp 模块零侵入。
 *
 * **信号量不可重入（关键）**：`SynchronizedTask` 是 `Semaphore(1)`，
 * `rewrite()` 内部自己调 `compiler.compile/parse`。因此**绝不能在 `compile().get{}`
 * 回调内再调 `rewrite()`**——会死锁。所有 AST 数据提取（诊断、findMethod）与
 * `rewrite()` 调用必须是两次独立的 acquire/release。
 */
class ReportedCodeActionsPort(private val serverProvider: (String) -> ILanguageServer?) :
    CodeActionsPort {

  override fun isAvailable(): Boolean = unavailableReason().isEmpty()

  override fun unavailableReason(): String {
    try {
      if (serverProvider(SERVER_ID_JAVA) != null) {
        return ""
      }
    } catch (e: RuntimeException) {
      log.debug("语言服务器查询失败: {}", e.message)
    }
    return NO_PROJECT_REASON + "（语言服务器随项目会话启动，请在编辑器中打开该项目）"
  }

  override fun isSupported(filePath: String): Boolean =
      File(filePath).name.lowercase(Locale.US).endsWith(".java")

  override fun supportedActions(): List<String> = SUPPORTED_ACTIONS

  override fun perform(
      absolutePath: String,
      action: String,
      line: Int,
      column: Int,
      argument: String,
      timeoutMs: Long,
  ): CodeActionsPort.Result {
    if (action !in SUPPORTED_ACTIONS) {
      return CodeActionsPort.Result.unsupported(
          "不支持的 action：\"$action\"。可用：${SUPPORTED_ACTIONS.joinToString(", ")}")
    }
    val file = File(absolutePath)
    if (!file.isFile) {
      return CodeActionsPort.Result.failed("文件不存在：$absolutePath")
    }
    val server =
        try {
          serverProvider(SERVER_ID_JAVA)
        } catch (e: RuntimeException) {
          null
        }
    if (server !is JavaLanguageServer) {
      return CodeActionsPort.Result.unavailable("$NO_PROJECT_REASON，或 Java 语言服务器未启动")
    }

    val compiler = server.getCompiler(file.toPath())
    if (compiler.module == null) {
      // 与 ReportedDiagnosticsPort.javaNoUpdateReason 的判据一致：不在模块内的文件无法编译。
      return CodeActionsPort.Result.failed(
          "该文件不属于任何已识别的 Gradle 模块（可能 Gradle 同步尚未完成，或文件不在模块的 src 下）。" +
              "请等待同步完成，或确认文件位置后重试。")
    }

    // 冲突检测（写坏文件的最后一道闸）：edits 的坐标基于**编译器看到的内容**，
    // 而编译器对编辑器打开的文件可能读到内存缓冲（SourceFileObject → FileManager
    // .getDocumentContents 优先内存）。若编辑器有未保存改动，把 edits 应用到磁盘
    // 文本会错位并写坏文件。这里在**执行前**比对两者，不一致就拒绝。
    val diskText =
        try {
          String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
        } catch (e: Exception) {
          return CodeActionsPort.Result.failed("读取文件失败：${e.message}")
        }
    val compilerText =
        try {
          compiler.parse(file.toPath()).root.sourceFile.getCharContent(true).toString()
        } catch (e: Throwable) {
          null
        }
    if (compilerText != null && compilerText != diskText) {
      return CodeActionsPort.Result.failed(
          "文件在编辑器中有未保存的改动，请先保存（或用 save_all）再执行代码动作——" +
              "否则生成的修改会与编辑器缓冲错位。")
    }

    val outcome =
        GatedAnalyzer.run(
            GatedAnalyzer.BlockingTask {
              // 整个「装配 + rewrite」在 GatedAnalyzer 的工作线程里跑（硬超时保护）。
              runAction(compiler, file.toPath(), action, line, column, argument)
            },
            timeoutMs)
    if (outcome === GatedAnalyzer.TIMED_OUT) {
      return CodeActionsPort.Result.timedOut(
          "代码动作超时（超过 ${timeoutMs / 1000} 秒）。可稍后重试——首次编译较慢。")
    }
    if (outcome == null) {
      return CodeActionsPort.Result.failed("代码动作执行异常或未返回结果")
    }
    if (outcome is Failure) {
      return CodeActionsPort.Result.failed(outcome.message)
    }
    val edits = outcome as Map<Path, Array<TextEdit>>

    return applyAndWrite(edits, file, action)
  }

  /** runAction 的失败载体（GatedAnalyzer 返回 Object，用这个类型携带文案）。 */
  private class Failure(val message: String)

  /**
   * 装配并执行 rewrite。**在 GatedAnalyzer 工作线程内运行**。
   *
   * @return Map<Path, TextEdit[]> 或 [Failure]
   */
  private fun runAction(
      compiler: com.tom.rv2ide.lsp.java.compiler.JavaCompilerService,
      file: Path,
      action: String,
      line: Int,
      column: Int,
      argument: String,
  ): Any {
    return try {
      val rewrite: Rewrite =
          when (action) {
            "add_import" -> {
              if (!argument.contains(".")) {
                return Failure("add_import 需要 argument 传完全限定类名（如 java.util.List）。")
              }
              AddImport(file, argument)
            }
            "create_missing_method" -> {
              val offset = findOffset(compiler, file, line, column) ?: return Failure(positionOutOfRange(file))
              CreateMissingMethod(file, offset)
            }
            "implement_abstract_methods" -> {
              // 取第一条 does.not.override.abstract 诊断（独立 acquire/release）。
              val diagnostic = firstDiagnostic(compiler, file, CODE_NOT_OVERRIDE_ABSTRACT)
              if (diagnostic == null) {
                return Failure("未找到 implement_abstract_methods 所需的编译诊断" +
                    "（该类当前没有未实现的抽象方法）。")
              }
              val jcDiagnostic = JavaDiagnosticUtils.asJCDiagnostic(diagnostic)
              if (jcDiagnostic == null) {
                return Failure("无法解析编译诊断（内部类型不匹配）。")
              }
              ImplementAbstractMethods(jcDiagnostic)
            }
            "add_throws", "remove_unused_throws", "suppress_unchecked" -> {
              // 三个方法级动作共享 findMethod 路径（独立 acquire/release）。
              val ptr = findMethod(compiler, file, line, column) ?: return Failure("光标处不在方法内")
              when (action) {
                "add_throws" -> {
                  val exception =
                      argument.ifEmpty {
                        // 从诊断 message 提取（诊断驱动）。找不到诊断时给出可操作指引。
                        val diag = firstDiagnostic(compiler, file, CODE_UNREPORTED_EXCEPTION)
                        val name =
                            diag?.getMessage(java.util.Locale.US)
                                ?.let { CodeActionUtils.extractExceptionName(it) }
                        if (name.isNullOrEmpty()) {
                          return Failure("未找到需要处理的受检异常诊断，也未提供 argument（异常类名）。" +
                              "可显式传 argument（如 java.io.IOException）。")
                        }
                        name
                      }
                  AddException(ptr.className, ptr.methodName, ptr.erasedParameterTypes, exception)
                }
                "remove_unused_throws" -> {
                  val diag = firstDiagnostic(compiler, file, CODE_UNUSED_THROWS)
                  val exception =
                      argument.ifEmpty {
                        diag?.getMessage(java.util.Locale.US)
                            ?.let { CodeActionUtils.extractNotThrownExceptionName(it) } ?: ""
                      }
                  if (exception.isEmpty()) {
                    return Failure("未找到未抛出的 throws 诊断，也未提供 argument（异常类名）。")
                  }
                  RemoveException(ptr.className, ptr.methodName, ptr.erasedParameterTypes, exception)
                }
                else -> {
                  // suppress_unchecked
                  val diag = firstDiagnostic(compiler, file, CODE_UNCHECKED)
                  if (diag == null) {
                    return Failure("未找到 unchecked 相关警告（该位置没有可抑制的 unchecked 调用）。")
                  }
                  AddSuppressWarningAnnotation(
                      ptr.className, ptr.methodName, ptr.erasedParameterTypes)
                }
              }
            }
            else -> return Failure("未实现的 action：$action")
          }
      // 独立 acquire/release：rewrite 内部自己驱动编译。
      rewrite.rewrite(compiler)
    } catch (e: Throwable) {
      log.debug("代码动作执行失败: {}", e.message, e)
      Failure("代码动作执行失败：${e.message}（可能光标处结构不支持该动作）。")
    }
  }

  // ---- 编译器驱动的数据提取（每次都独立 acquire/release，绝不在回调内再进编译器） ----

  /**
   * 取指定诊断码的第一条诊断；无该诊断返回 null。
   *
   * <p>类型是 `jdkx.tools.Diagnostic`（shaded javax.tools，CompileTask.diagnostics
   * 的元素类型），它的 `code` 即诊断码字符串（如 "compiler.err.does.not.override.abstract"）。
   */
  private fun firstDiagnostic(
      compiler: com.tom.rv2ide.lsp.java.compiler.JavaCompilerService,
      file: Path,
      code: String,
  ): jdkx.tools.Diagnostic<out jdkx.tools.JavaFileObject>? {
    return try {
      val result = compiler.compile(file).get { task -> task.diagnostics }
      result.firstOrNull { it.code == code }
    } catch (e: Throwable) {
      log.debug("编译取诊断失败: {}", e.message)
      null
    }
  }

  /** 光标处的 char offset；越界返回 null。用既有 CodeActionUtils.findPosition（独立 acquire/release）。 */
  private fun findOffset(
      compiler: com.tom.rv2ide.lsp.java.compiler.JavaCompilerService,
      file: Path,
      line: Int,
      column: Int,
  ): Int? {
    return try {
      compiler.compile(file).get { task ->
        val position = Position(line, column)
        val offset = CodeActionUtils.findPosition(task, position)
        if (offset < 0) null else offset
      }
    } catch (e: Throwable) {
      log.debug("定位失败: {}", e.message)
      null
    }
  }

  private fun positionOutOfRange(file: Path): String =
      "位置超出文件范围或无法定位（文件：${file.fileName}）。"

  /** 光标处的方法指针；不在方法内返回 null。 */
  private fun findMethod(
      compiler: com.tom.rv2ide.lsp.java.compiler.JavaCompilerService,
      file: Path,
      line: Int,
      column: Int,
  ): com.tom.rv2ide.lsp.java.utils.MethodPtr? {
    return try {
      compiler.compile(file).get { task ->
        val position = Position(line, column)
        CodeActionUtils.findMethod(task, Range(position, position))
      }
    } catch (e: Throwable) {
      log.debug("findMethod 失败: {}", e.message)
      null
    }
  }

  // ---- 应用 edits 与写盘 ----

  private fun applyAndWrite(
      edits: Map<Path, Array<TextEdit>>,
      inputFile: File,
      action: String,
  ): CodeActionsPort.Result {
    if (edits.isEmpty()) {
      return CodeActionsPort.Result.done(false, 0, emptyList(), "未发生改动（该动作在当前状态下不需要执行）。")
    }
    val changedFiles = mutableListOf<String>()
    var totalChanged = 0
    for ((path, textEdits) in edits) {
      val target = path.toFile()
      if (textEdits.isEmpty()) {
        continue
      }
      val original =
          try {
            String(Files.readAllBytes(path), StandardCharsets.UTF_8)
          } catch (e: Exception) {
            return CodeActionsPort.Result.failed("读取文件失败（${target.name}）：${e.message}")
          }
      val updated = applyEdits(original, textEdits)
      if (updated == original) {
        continue
      }
      try {
        Files.write(path, updated.toByteArray(StandardCharsets.UTF_8))
      } catch (e: Exception) {
        return CodeActionsPort.Result.failed("写回文件失败（${target.name}）：${e.message}")
      }
      changedFiles.add(displayPath(target))
      totalChanged += kotlin.math.abs(updated.length - original.length)
    }
    if (changedFiles.isEmpty()) {
      return CodeActionsPort.Result.done(false, 0, emptyList(), "未发生改动。")
    }
    // 通知编辑器（与 ReportedDocumentSyncPort 同一外部写路径：无未保存改动则重载，
    // 有则标冲突并让自动保存让路）。
    for (name in changedFiles) {
      try {
        val f = resolveDisplayPath(name) ?: continue
        documentSync?.notifyChanged(f)
      } catch (e: RuntimeException) {
        log.debug("通知编辑器失败: {}", e.message)
      }
    }
    return CodeActionsPort.Result.done(
        true, totalChanged, changedFiles, "代码动作已完成（$action）。")
  }

  /** 应用 edits：按 line/column 倒序（避免前面的编辑移动后面的偏移）。 */
  private fun applyEdits(original: String, textEdits: Array<TextEdit>): String {
    var text = original
    textEdits
        .sortedWith(
            compareByDescending<TextEdit> { it.range.start.line }
                .thenByDescending { it.range.start.column })
        .forEach { edit ->
          val start = indexOf(text, edit.range.start.line, edit.range.start.column)
          val end = indexOf(text, edit.range.end.line, edit.range.end.column)
          if (start in 0..text.length && end in start..text.length) {
            text = text.substring(0, start) + edit.newText + text.substring(end)
          }
        }
    return text
  }

  /** line/column（0 基）→ char index。 */
  private fun indexOf(text: String, line: Int, column: Int): Int {
    var currentLine = 0
    var index = 0
    while (currentLine < line && index < text.length) {
      if (text[index] == '\n') {
        currentLine++
      }
      index++
    }
    return (index + column).coerceIn(0, text.length)
  }

  private fun displayPath(file: File): String = file.absolutePath

  private fun resolveDisplayPath(path: String): File? = File(path).takeIf { it.isFile }

  /** 文档同步端口（可选注入；为 null 时写盘后不通知编辑器）。 */
  var documentSync: com.tom.rv2ide.ai.tool.DocumentSyncPort? = null

  companion object {
    private val log = LoggerFactory.getLogger(ReportedCodeActionsPort::class.java)

    private const val SERVER_ID_JAVA = "ide.lsp.java"
    private const val NO_PROJECT_REASON = "当前没有打开的项目"

    /** 第一期放行的 action（与 CodeActionsTool 的 enum 一致）。 */
    private val SUPPORTED_ACTIONS =
        listOf(
            "implement_abstract_methods",
            "create_missing_method",
            "add_import",
            "add_throws",
            "remove_unused_throws",
            "suppress_unchecked")

    /** 诊断码常量（不引用 DiagnosticCode 枚举——那是 java/lsp 模块的类型）。 */
    private const val CODE_NOT_OVERRIDE_ABSTRACT = "compiler.err.does.not.override.abstract"
    private const val CODE_UNREPORTED_EXCEPTION =
        "compiler.err.unreported.exception.need.to.catch.or.throw"
    private const val CODE_UNUSED_THROWS = "ide.java.unused.throws"
    private const val CODE_UNCHECKED = "compiler.warn.unchecked.call.mbr.of.raw.type"
  }
}
