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

package com.tom.rv2ide.ai.tool;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 编辑工具搭车诊断：把「改完这个文件后有没有编译错误」直接附到编辑结果里。
 *
 * <p><b>为什么要搭车而不是只靠 {@code diagnostics} 工具</b>：模型改完一个文件后，
 * 绝大多数情况下下一步就是「确认没写坏」。让模型显式再调一次工具，它常常会忘；
 * 附在结果里则由系统保证它一定看到——这是「编辑→报错→修」闭环最自然的形态。
 *
 * <p><b>为什么必须严格有界</b>：编辑是**高频**操作，若每次编辑都等语言服务器分析，
 * 一次会话会被拖垮。因此这里用 {@link #TIMEOUT_MS} 的短超时；超时/失败一律静默跳过
 * （不往结果里塞噪音——模型不需要知道「诊断没跑成」，那只会让它分心）。
 *
 * <p>只对 Java/Kotlin 生效：语言服务器里只有它们能按需分析（XML/Clang 恒返回 NO_UPDATE）。
 */
final class FileDiagnostics {

  /**
   * 搭车诊断的等待上限。
   *
   * <p>这个预算是**真的硬上限**（见 {@link GatedAnalyzer}）：语言服务器的 analyze 是阻塞式
   * 实现、不响应协程取消，所以不能靠协程超时；这里用独立可中断线程加 {@code join(timeout)}，
   * 到点即放弃等待。取 1.5s：编辑是高频路径，任何更长的等待都是对交互的伤害。
   */
  static final long TIMEOUT_MS = 1_500L;

  /** 搭车诊断最多列出多少条（超出只报数量，全量请用 diagnostics 工具）。 */
  static final int MAX_ITEMS = 20;

  /**
   * 列出该文件的编译诊断；不可用/超时/失败时返回空串。
   *
   * <p>返回空串的三种含义在搭车场景下**无需区分**（与 {@link DiagnosticsTool} 不同）：
   * 那里「分析不了」必须显式告知，因为用户是主动来查诊断的；而这里是编辑的附注，
   * 没拿到就不提，模型需要时可用 diagnostics 工具主动查。
   */
  static String describe(ToolContext context, File file) {
    try {
      return describeUnsafe(context, file);
    } catch (Throwable t) {
      // 兜底：搭车诊断绝不能影响编辑结果。
      //
      // 这不是过度防御——`describe` 的调用点在 file_edit/file_write **写盘成功之后**、
      // 返回 ok(...) 之前，且被外层 catch(Exception) 包着。诊断里任何未预期异常（若冒泡）
      // 都会把「已经成功的编辑」翻成 error；而 DiffRecorder 见到 error 就**不记录 diff**，
      // 于是用户既看到「编辑失败」、又拿不到回滚入口，文件却已经改了。用 Throwable 而非
      // Exception：连 NoClassDefFoundError 之类的链接错误也不该破坏编辑。
      return "";
    }
  }

  private static String describeUnsafe(ToolContext context, File file) {
    if (context == null) {
      return "";
    }
    DiagnosticsPort port = context.getDiagnostics();
    if (port == null || !port.isAvailable() || !port.isSupported(file.getAbsolutePath())) {
      return "";
    }
    DiagnosticsPort.Report report = port.analyze(file.getAbsolutePath(), TIMEOUT_MS);
    if (report == null || report.isUnavailable() || report.isFailed()) {
      return "";
    }
    List<DiagnosticsPort.Item> items =
        DiagnosticsMessages.sorted(
            DiagnosticsMessages.filterBySeverity(
                report.getItems(), DiagnosticsMessages.parseSeverity("warning")));
    if (items.isEmpty()) {
      return "";
    }
    String displayPath;
    try {
      displayPath = FileToolPathPolicy.displayPath(context.getHomePath(), file);
    } catch (IOException e) {
      displayPath = file.getName();
    }
    return DiagnosticsMessages.block(displayPath, items, MAX_ITEMS);
  }

  private FileDiagnostics() {}
}
