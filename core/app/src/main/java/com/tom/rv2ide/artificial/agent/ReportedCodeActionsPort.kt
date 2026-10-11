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
    val actionOutcome = outcome as ActionOutcome

    // 写盘用 rewrite 后捕获的文本（edits 的真实依据）再做一次冲突比对——
    // 编译前那次比对与写盘之间隔着整个编译+rewrite（可达 30s），窗口内用户保存
    // 就会错位。这里把窗口收窄到「rewrite 完成 → 写盘」的毫秒级。
    return applyAndWrite(actionOutcome, actionOutcome.compilerText, file, action)
  }

  /** runAction 的失败载体（GatedAnalyzer 返回 Object，用这个类型携带文案）。 */
  private class Failure(val message: String)

  /**
   * runAction 的成功载体：edits + **rewrite 之后**的编译器可见文本。
   *
   * <p>为什么带文本：edits 的坐标基于编译器看到的内容，而编译+rewrite 可能耗时数秒到
   * 30s（首次编译）。冲突检测在编译前做过一次，但那个窗口内用户若在编辑器改动并保存，
   * edits 与磁盘文本就会错位。写盘前用这份「edits 依据的文本」再比对一次磁盘，
   * 把窗口收窄到「rewrite 完成 → 写盘」的毫秒级。
   */
  private class ActionOutcome(val edits: Map<Path, Array<TextEdit>>, val compilerText: String?)

  /**
   * 装配并执行 rewrite。**在 GatedAnalyzer 工作线程内运行**。
   *
   * @return [ActionOutcome] 或 [Failure]
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
              // 三个方法级动作都是「诊断驱动」：先取离光标最近的同类诊断，再由
              // 该诊断的 range 反推方法（与上游 SuppressUncheckedWarningAction /
              // RemoveUnusedThrowsAction 同语义——它们用 diagnostic.range 调
              // findMethod）。不能「方法取光标 + 诊断取文件第一条」：同一文件有
              // 多条同类诊断时（常见），会把修改写到光标所在但诊断不属于它的方法上，
              // 是静默的语义错误改动。
              val code =
                  when (action) {
                    "add_throws" -> CODE_UNREPORTED_EXCEPTION
                    "remove_unused_throws" -> CODE_UNUSED_THROWS
                    else -> CODE_UNCHECKED
                  }
              // 一次 acquire/release 内完成：取最近诊断 + 由诊断定位方法。
              val located = locateMethodForDiagnostic(compiler, file, code, line, column)
              when (action) {
                "add_throws" -> {
                  // 诊断驱动优先；无诊断但显式给了异常类名时回退到光标定位
                  // （用户指名了「加什么」，光标指名了「加到哪个方法」）。
                  val ptr =
                      located?.first
                          ?: findMethod(compiler, file, line, column)
                          ?: return Failure("光标处不在方法内，且附近没有受检异常诊断。")
                  val exception =
                      argument.ifEmpty {
                        val name =
                            located?.second?.getMessage(java.util.Locale.US)
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
                  val ptr =
                      located?.first
                          ?: return Failure("未找到未抛出的 throws 诊断（光标附近没有 unused throws）。" +
                              "可显式传 argument（异常类名）或把光标移到该方法处。")
                  val exception =
                      argument.ifEmpty {
                        located.second?.getMessage(java.util.Locale.US)
                            ?.let { CodeActionUtils.extractNotThrownExceptionName(it) } ?: ""
                      }
                  if (exception.isEmpty()) {
                    return Failure("未找到未抛出的 throws 诊断，也未提供 argument（异常类名）。")
                  }
                  RemoveException(ptr.className, ptr.methodName, ptr.erasedParameterTypes, exception)
                }
                else -> {
                  // suppress_unchecked：方法与诊断严格对应（避免抑制错方法）。
                  val ptr =
                      located?.first
                          ?: return Failure("未找到 unchecked 相关警告（光标附近没有可抑制的 unchecked 调用）。")
                  AddSuppressWarningAnnotation(
                      ptr.className, ptr.methodName, ptr.erasedParameterTypes)
                }
              }
            }
            else -> return Failure("未实现的 action：$action")
          }
      // 独立 acquire/release：rewrite 内部自己驱动编译。
      val edits = rewrite.rewrite(compiler)
      // 捕获 edits 依据的文本（rewrite 后立即取，窗口最小）。失败不阻断——
      // 写盘前还有一次磁盘比对，null 时退化为「不额外校验」。
      val text =
          try {
            compiler.parse(file).root.sourceFile.getCharContent(true).toString()
          } catch (e: Throwable) {
            null
          }
      ActionOutcome(edits, text)
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

  /**
   * 取**离光标最近**的指定诊断，并由**该诊断的位置**反推所在方法（一次 acquire/release）。
   *
   * <p>为什么必须两者关联：方法级动作若是「方法取光标、诊断取文件第一条」，同一文件有
   * 多条同类诊断（很常见：多个 unchecked 调用、多个未抛 throws）时，会把修改写到
   * 光标所在但诊断并不属于它的方法上——静默的语义错误改动。上游 Action
   * （`SuppressUncheckedWarningAction` / `RemoveUnusedThrowsAction`）用
   * `diagnostic.range` 调 `findMethod`，这里等价：用诊断的 startPosition 走
   * `FindMethodDeclarationAt`。
   *
   * <p>诊断按 source 文件过滤：`compile(file)` 的批次可能包含依赖文件的诊断，
   * 不过滤会拿别的文件的诊断来定位本文件的方法。source 为 null 时无法归属，
   * 按「可能属于本文件」纳入（单文件编译下多为本文件）。
   *
   * @return (方法指针, 诊断) 或 null（无同类诊断 / 位置不可用 / 无法定位方法）
   */
  private fun locateMethodForDiagnostic(
      compiler: com.tom.rv2ide.lsp.java.compiler.JavaCompilerService,
      file: Path,
      code: String,
      line: Int,
      column: Int,
  ): Pair<com.tom.rv2ide.lsp.java.utils.MethodPtr, jdkx.tools.Diagnostic<out jdkx.tools.JavaFileObject>>? {
    return try {
      compiler.compile(file).get { task ->
        val cursorOffset = CodeActionUtils.findPosition(task, Position(line, column))
        val candidates =
            task.diagnostics.filter { d ->
              d.code == code &&
                  (d.getSource() == null || d.getSource().toUri() == file.toUri())
            }
        val diag =
            if (cursorOffset < 0) {
              // 光标越界：退化为「第一条」（与旧行为一致，但已按 source 过滤）。
              candidates.firstOrNull()
            } else {
              candidates.minByOrNull { d ->
                val p = d.getStartPosition()
                if (p < 0) Long.MAX_VALUE else kotlin.math.abs(p - cursorOffset)
              }
            } ?: return@get null
        val offset = diag.getStartPosition()
        if (offset < 0) return@get null
        val methodTree =
            com.tom.rv2ide.lsp.java.visitors.FindMethodDeclarationAt(task.task)
                .scan(task.root(), offset)
                ?: return@get null
        val trees = openjdk.source.util.Trees.instance(task.task)
        val path = trees.getPath(task.root(), methodTree) ?: return@get null
        val element =
            trees.getElement(path) as? jdkx.lang.model.element.ExecutableElement
                ?: return@get null
        com.tom.rv2ide.lsp.java.utils.MethodPtr(task.task, element) to diag
      }
    } catch (e: Throwable) {
      log.debug("定位诊断方法失败: {}", e.message)
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
      outcome: ActionOutcome,
      compilerText: String?,
      inputFile: File,
      action: String,
  ): CodeActionsPort.Result {
    val edits = outcome.edits
    if (edits.isEmpty()) {
      return CodeActionsPort.Result.done(false, 0, emptyList(), "未发生改动（该动作在当前状态下不需要执行）。")
    }
    // 写前最后一次冲突比对：edits 依据的文本（rewrite 后捕获）与目标文件当前磁盘
    // 内容必须一致，否则说明编译期间文件被外部改过，edits 坐标会错位。
    // 只对输入文件做（各 Rewrite 当前都写回同一文件）；compilerText 为 null
    // （parse 失败）时跳过——与执行前比对同一策略。
    if (compilerText != null) {
      val diskNow =
          try {
            String(Files.readAllBytes(inputFile.toPath()), StandardCharsets.UTF_8)
          } catch (e: Exception) {
            null
          }
      if (diskNow != null && diskNow != compilerText) {
        return CodeActionsPort.Result.failed(
            "文件在编译期间被外部修改（编辑器保存或其他工具写入），为防错位已放弃本次写入。" +
                "请重试。")
      }
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
      // 改动量 = 被覆盖的原文长度 + 新文本长度（对「等长改写」不再报 0——
      // 长度差 abs(new-old) 在 x++ → x-- 这类同长度改写上是 0，文案失真）。
      for (e in textEdits) {
        val s = indexOf(original, e.range.start.line, e.range.start.column)
        val en = indexOf(original, e.range.end.line, e.range.end.column)
        if (en > s) {
          totalChanged += (en - s) + e.newText.length
        } else {
          totalChanged += e.newText.length
        }
      }
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
