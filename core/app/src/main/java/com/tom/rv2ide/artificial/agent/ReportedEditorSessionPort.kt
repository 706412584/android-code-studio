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

package com.tom.rv2ide.artificial.agent

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.tom.rv2ide.activities.editor.EditorHandlerActivity
import com.tom.rv2ide.ai.tool.EditorSessionPort
import com.tom.rv2ide.ai.tool.GatedAnalyzer
import com.tom.rv2ide.interfaces.IEditorHandler
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.models.SaveResult
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * 把 IDE 编辑器会话（[IEditorHandler]）接到 AI 工具层的适配器。
 *
 * **为什么需要适配**：`core/ai-tool` 是纯 JVM、零 Android 依赖的模块，不能引用
 * `IEditorHandler` / `CodeEditorView`（它们引用 View 与 sora-editor）。本类在 app 层
 * 完成读取与类型转换，并负责**切主线程**——工具的调用方在后台线程。
 *
 * **线程模型（关键）**：
 * - `snapshot` / `openAt`：后台线程调用 → [onMainAndWait] 切主线程执行、有界等待。
 *   已在主线程时直接执行（再 post 会自锁）。
 * - `saveAll`：**必须从后台线程驱动**。底层保存实现（`saveResultInternal`）内部会
 *   `withContext(Dispatchers.Main)`，若调用方在主线程上 `runBlocking` 等待，主线程被占住，
 *   内部的主线程任务永远排不上 → 死锁。因此这里检测到主线程调用时直接返回失败。
 *
 * **会话按调用时现查**（[EditorSessionRegistry]）：端口实例无状态，用户可能在会话中途
 * 才打开项目编辑器，固化实例会永久拿到「不可用」。
 */
class ReportedEditorSessionPort : EditorSessionPort {

  override fun isAvailable(): Boolean = EditorSessionRegistry.get() != null

  override fun unavailableReason(): String =
      if (isAvailable()) {
        ""
      } else {
        "当前没有在编辑器中打开项目（编辑器页面不存在或已关闭）。" +
            "请先在编辑器中打开项目，再使用编辑器相关功能。"
      }

  override fun snapshot(): EditorSessionPort.Snapshot {
    val handler = EditorSessionRegistry.get()
        ?: return EditorSessionPort.Snapshot.unavailable(unavailableReason())
    return onMainAndWait(MAIN_THREAD_TIMEOUT_MS) { readSnapshot(handler) }
        ?: EditorSessionPort.Snapshot.unavailable("读取编辑器状态超时（主线程繁忙），请稍后重试。")
  }

  override fun openAt(
      absolutePath: String,
      startLine: Int,
      startColumn: Int,
      endLine: Int,
      endColumn: Int,
  ): EditorSessionPort.OpenResult {
    val handler = EditorSessionRegistry.get()
        ?: return EditorSessionPort.OpenResult.failed(unavailableReason())
    val file = File(absolutePath)
    if (!file.exists()) {
      return EditorSessionPort.OpenResult.failed("文件不存在：$absolutePath")
    }

    // 前台判定要在切主线程之前算：RESUMED 表示用户此刻能看到跳转结果。
    val visible =
        handler is LifecycleOwner &&
            handler.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    val outcome =
        onMainAndWait(MAIN_THREAD_TIMEOUT_MS) {
          // 必须用 openFileAndSelect：openFile 对**已打开**的文件直接返回旧 index，
          // 不会重新应用选区（见 EditorHandlerActivity.openFileAndGetIndex）。
          handler.openFileAndSelect(
              file, Range(Position(startLine, startColumn), Position(endLine, endColumn)))
          true
        }
    if (outcome == null) {
      return EditorSessionPort.OpenResult.failed("打开文件超时（主线程繁忙），请稍后重试。")
    }
    // 选区由 openFileAndSelect 内部 postInLifecycle 异步应用：已提交，不等待、不轮询。
    return EditorSessionPort.OpenResult.opened(visible)
  }

  override fun saveAll(timeoutMs: Long): EditorSessionPort.SaveReport {
    // 主线程防御：见类注释的线程模型。这是「不报错但永久挂起」类问题，必须显式挡掉。
    if (Looper.myLooper() == Looper.getMainLooper()) {
      return EditorSessionPort.SaveReport.failed("内部错误：saveAll 不能在主线程调用。")
    }
    val handler = EditorSessionRegistry.get()
        ?: return EditorSessionPort.SaveReport.failed(unavailableReason())
    val activity =
        handler as? EditorHandlerActivity
            ?: return EditorSessionPort.SaveReport.failed("当前编辑器界面不支持保存操作。")

    // 1) 主线程收集保存计划：文件、index、是否修改、是否外部冲突。
    //    冲突判定复用 CodeEditorView.hasExternalDiskChange —— 与自动保存让路
    //    （BaseEditorActivity.performAutoSave）用的是同一个标志。
    val plan =
        onMainAndWait(MAIN_THREAD_TIMEOUT_MS) {
          activity.editorViewModel.getOpenedFiles().mapIndexedNotNull { index, file ->
            val view = handler.getEditorForFile(file) ?: return@mapIndexedNotNull null
            SaveItem(index, file.absolutePath, view.isModified, view.hasExternalDiskChange)
          }
        } ?: return EditorSessionPort.SaveReport.failed("读取编辑器状态超时，请稍后重试。")

    val saved = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    // 累计 gradle/xml 标志（SaveResult 由 saveResultInternal 就地写入）。
    val saveResult = SaveResult()

    // 2) 逐文件保存：冲突文件跳过（避免把 buffer 覆盖到更新的外部内容上）；
    //    未修改文件自然跳过（save() 对未修改文件返回 false，无副作用）。
    //    用 handler.saveResult(index, result) 而不是整包 saveAll——后者无法按文件跳过冲突。
    //    与既有 saveAll(notify=true) 的差异：不触发 flashSuccess 提示，用户从助手卡片看结果。
    val outcome =
        GatedAnalyzer.run(
            GatedAnalyzer.BlockingTask {
              runBlocking {
                for (item in plan) {
                  if (item.conflict) {
                    skipped.add(item.path)
                    continue
                  }
                  if (!item.modified) {
                    continue
                  }
                  handler.saveResult(item.index, saveResult)
                  // 成功判定：保存前 modified=true，保存后应变 false（markUnmodified 在 save() 内）。
                  val stillModified =
                      withContext(Dispatchers.Main) {
                        handler.getEditorForFile(File(item.path))?.isModified == true
                      }
                  if (!stillModified) {
                    saved.add(item.path)
                  }
                  // 仍 modified 的文件计入未保存，但不列为冲突；结果里如实报告（不计入 saved）。
                }
              }
              true
            },
            timeoutMs)

    // 3) gradle 文件被保存时请求同步（与 saveAll(requestSync=true) 的既有行为一致，
    //    否则后续构建可能用旧配置）。
    if (saveResult.gradleSaved) {
      onMainAndWait(MAIN_THREAD_TIMEOUT_MS) { activity.editorViewModel.isSyncNeeded = true }
    }

    if (outcome === GatedAnalyzer.TIMED_OUT) {
      return EditorSessionPort.SaveReport.failed(
          "保存超时（超过 ${timeoutMs / 1000} 秒），可能仍有文件未保存" +
              "（已保存 ${saved.size} 个，跳过 ${skipped.size} 个冲突文件），请稍后重试。")
    }
    return EditorSessionPort.SaveReport.of(saved, skipped)
  }

  /** 保存计划的一项；只用于在主线程收集与后台驱动之间传递纯数据。 */
  private data class SaveItem(
      val index: Int,
      val path: String,
      val modified: Boolean,
      val conflict: Boolean,
  )

  /** 在主线程读编辑器状态（调用方已在主线程）。 */
  private fun readSnapshot(handler: IEditorHandler): EditorSessionPort.Snapshot {
    // 需要「已打开文件列表」——IEditorHandler 没有暴露枚举 API，唯一的公开来源是
    // BaseEditorActivity 的 editorViewModel（public val）与 EditorViewModel.getOpenedFiles()。
    // 因此 cast 到具体实现类。若将来出现别的 IEditorHandler 实现，这里降级为「不可用」
    // 而不是硬崩（届时再考虑把枚举能力上提到接口）。
    val activity =
        handler as? EditorHandlerActivity
            ?: return EditorSessionPort.Snapshot.unavailable("当前编辑器界面不支持状态读取（未知的编辑器实现）。")

    val opened = activity.editorViewModel.getOpenedFiles()
    val currentEditor = handler.getCurrentEditor()
    val currentFile = currentEditor?.file

    val files =
        opened.map { file ->
          val editorView = handler.getEditorForFile(file)
          EditorSessionPort.OpenFile(
              file.absolutePath, editorView?.isModified == true, file == currentFile)
        }

    val cursor = currentEditor?.editor?.cursor
    val hasCursor = cursor != null
    val hasSelection = cursor?.isSelected == true

    return EditorSessionPort.Snapshot.of(
        currentFile?.absolutePath ?: "",
        if (hasCursor) cursor!!.leftLine else -1,
        if (hasCursor) cursor!!.leftColumn else -1,
        if (hasSelection) cursor!!.leftLine else -1,
        if (hasSelection) cursor!!.leftColumn else -1,
        if (hasSelection) cursor!!.rightLine else -1,
        if (hasSelection) cursor!!.rightColumn else -1,
        files,
        handler.areFilesModified())
  }

  /** 在任意线程上跑到主线程执行并等待完成；已在主线程则直接执行。超时返回 null。 */
  private fun <T> onMainAndWait(timeoutMs: Long, action: () -> T): T? {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      // 已在主线程：直接跑。post 到主线程队列再等它执行会自锁。
      return action()
    }
    val result = AtomicReference<T>()
    val latch = CountDownLatch(1)
    Handler(Looper.getMainLooper())
        .post {
          try {
            result.set(action())
          } catch (t: Throwable) {
            log.warn("编辑器会话主线程操作失败", t)
          } finally {
            latch.countDown()
          }
        }
    return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) result.get() else null
  }

  companion object {
    private val log = LoggerFactory.getLogger(ReportedEditorSessionPort::class.java)

    /** 单次主线程操作的等待上限；主线程卡住时返回明确超时错误而不是永久等待。 */
    private const val MAIN_THREAD_TIMEOUT_MS = 10_000L
  }
}
