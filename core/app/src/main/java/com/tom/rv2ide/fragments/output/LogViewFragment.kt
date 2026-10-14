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

package com.tom.rv2ide.fragments.output

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import com.blankj.utilcode.util.ThreadUtils
import com.tom.rv2ide.R
import com.tom.rv2ide.databinding.FragmentLogBinding
import com.tom.rv2ide.editor.language.treesitter.LogLanguage
import com.tom.rv2ide.editor.language.treesitter.TreeSitterLanguageProvider
import com.tom.rv2ide.editor.schemes.IDEColorScheme
import com.tom.rv2ide.editor.schemes.IDEColorSchemeProvider
import com.tom.rv2ide.fragments.EmptyStateFragment
import com.tom.rv2ide.models.LogLine
import com.tom.rv2ide.utils.ILogger.Level
import com.tom.rv2ide.utils.jetbrainsMono
import io.github.rosemoe.sora.widget.style.CursorAnimator
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min
import org.slf4j.LoggerFactory

/**
 * Fragment to show logs.
 *
 * @author Akash Yadav
 */
abstract class LogViewFragment :
    EmptyStateFragment<FragmentLogBinding>(R.layout.fragment_log, FragmentLogBinding::bind),
    ShareableOutputFragment {

  companion object {

    private val log = LoggerFactory.getLogger(LogViewFragment::class.java)

    /** The maximum number of characters to append to the editor in case of huge log texts. */
    const val MAX_CHUNK_SIZE = 10000

    /**
     * The time duration, in milliseconds which is used to determine whether logs are too frequent
     * or not. If the logs are produced within this time duration, they are considered as too
     * frequent. In this case, the logs are cached and appended in chunks of [MAX_CHUNK_SIZE]
     * characters in size.
     */
    const val LOG_FREQUENCY = 50L

    /**
     * The time duration, in milliseconds we wait before appending the logs. This must be greater
     * than [LOG_FREQUENCY].
     */
    const val LOG_DELAY = 100L

    /**
     * Trim the logs when the number of lines reaches this value. Only [MAX_LINE_COUNT] number of
     * lines are kept in the logs.
     */
    const val TRIM_ON_LINE_COUNT = 5000

    /**
     * The maximum number of lines that are shown in the log view. This value must be less than
     * [TRIM_ON_LINE_COUNT] by a difference of [LOG_FREQUENCY] or preferably, more.
     */
    const val MAX_LINE_COUNT = TRIM_ON_LINE_COUNT - 300

    /**
     * 单行日志的最大字符数；超长截断。
     *
     * <p><b>为什么必须限长</b>：sora 编辑器在插入文本后对**受影响的行**做全量文本测量
     * （{@code CodeEditor.afterInsert → buildMeasureCacheForLines}），单次耗时与行长成正比。
     * 实测一条 MB 级的单行文本（协议层曾把含图片 base64 的请求体写进日志）让主线程
     * 单次测量耗时 1.4 秒——网络重试期间每次失败写一条，主线程被反复冻结，
     * 界面表现为「错误重试时应用卡死」。协议层已截断请求体（治本），这里再设一道
     * 视图层防线：任何来源的单行日志都不允许超过此长度。
     */
    const val MAX_LINE_LENGTH = 4000
  }

  private var lastLog = -1L

  private val cacheLock = ReentrantLock()
  private val cache = StringBuilder()
  private var cacheLineTrack = ArrayBlockingQueue<Int>(MAX_LINE_COUNT, true)

  private val isTrimming = AtomicBoolean(false)

  private val logHandler = Handler(Looper.getMainLooper())
  private val logRunnable =
      object : Runnable {
        override fun run() {
          cacheLock.withLock {
            if (cacheLineTrack.size == MAX_LINE_COUNT) {
              cache.delete(0, cacheLineTrack.poll()!!)
            }

            cacheLineTrack.clear()

            if (cache.length < MAX_CHUNK_SIZE) {
              append(cache)
              cache.clear()
            } else {
              // Append the lines in chunks to avoid UI lags
              val length = min(cache.length, MAX_CHUNK_SIZE)
              append(cache.subSequence(0, length))
              cache.delete(0, length)
            }

            if (cache.isNotEmpty()) {
              // if we still have data left to append, resechedule this
              logHandler.removeCallbacks(this)
              logHandler.postDelayed(this, LOG_DELAY)
            } else {
              trimLinesAtStart()
            }
          }
        }
      }

  fun appendLog(line: LogLine) {

    val lineString =
        if (isSimpleFormattingEnabled()) {
          line.toSimpleString()
        } else {
          line.toString()
        }

    line.recycle()

    appendLine(lineString)
  }

  protected fun appendLine(line: String) {
    var lineStr = line
    // 单行限长：sora 编辑器的插入后测量与行长成正比，MB 级单行会让主线程单次卡 1.4 秒
    // （见 MAX_LINE_LENGTH 注释——错误重试卡死的直接机制）。
    if (lineStr.length > MAX_LINE_LENGTH) {
      lineStr =
          lineStr.substring(0, MAX_LINE_LENGTH) + "…（已截断 " + (lineStr.length - MAX_LINE_LENGTH) + " 字符）"
    }
    if (!lineStr.endsWith("\n")) {
      lineStr += "\n"
    }

    if (
        isTrimming.get() ||
            cache.isNotEmpty() ||
            System.currentTimeMillis() - lastLog <= LOG_FREQUENCY
    ) {
      cacheLock.withLock {
        logHandler.removeCallbacks(logRunnable)

        // If the log lines are too frequent, cache the lines to log them later at once
        cache.append(lineStr)
        logHandler.postDelayed(logRunnable, LOG_DELAY)

        lastLog = System.currentTimeMillis()

        val length = cache.length + 1
        if (!cacheLineTrack.offer(length)) {
          cacheLineTrack.poll()
          cacheLineTrack.offer(length)
        }
      }
      return
    }

    lastLog = System.currentTimeMillis()

    append(lineStr)
    trimLinesAtStart()
  }

  private fun append(chars: CharSequence?) {
    chars?.let {
      ThreadUtils.runOnUiThread {
        _binding?.editor?.append(chars)?.also { emptyStateViewModel.isEmpty.value = false }
      }
    }
  }

  private fun trimLinesAtStart() {
    if (isTrimming.get()) {
      // trimming is already in progress
      return
    }

    ThreadUtils.runOnUiThread {
      _binding?.editor?.text?.apply {
        if (lineCount <= TRIM_ON_LINE_COUNT) {
          isTrimming.set(false)
          return@apply
        }

        isTrimming.set(true)
        val lastLine = lineCount - MAX_LINE_COUNT
        log.debug("Deleting log text till line {}", lastLine)
        delete(0, 0, lastLine, getColumnCount(lastLine))
        isTrimming.set(false)
      }
    }
  }

  abstract fun isSimpleFormattingEnabled(): Boolean

  protected open fun logLine(level: Level, tag: String, message: String) {
    val line = LogLine.obtain(level, tag, message)
    appendLog(line)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    val editor = this.binding.editor
    editor.props.autoIndent = false
    editor.isEditable = false
    editor.dividerWidth = 0f
    editor.isWordwrap = false
    editor.isUndoEnabled = false
    editor.typefaceLineNumber = jetbrainsMono()
    editor.setTextSize(8f)
    editor.typefaceText = jetbrainsMono()
    editor.isEnsurePosAnimEnabled = false
    editor.cursorAnimator =
        object : CursorAnimator {
          override fun markStartPos() {}

          override fun markEndPos() {}

          override fun start() {}

          override fun cancel() {}

          override fun isRunning(): Boolean {
            return false
          }

          override fun animatedX(): Float {
            return 0f
          }

          override fun animatedY(): Float {
            return 0f
          }

          override fun animatedLineHeight(): Float {
            return 0f
          }

          override fun animatedLineBottom(): Float {
            return 0f
          }
        }

    IDEColorSchemeProvider.readSchemeAsync(
        context = requireContext(),
        coroutineScope = editor.editorScope,
        type = LogLanguage.TS_TYPE,
    ) { scheme ->
      val language = TreeSitterLanguageProvider.forType(LogLanguage.TS_TYPE, requireContext())
      checkNotNull(language) { "No TreeSitterLanguage found for type ${LogLanguage.TS_TYPE}" }

      if (scheme is IDEColorScheme) {
        language.setupWith(scheme)
      }

      editor.applyTreeSitterLang(language, LogLanguage.TS_TYPE, scheme)
    }
  }

  override fun onDestroyView() {
    _binding?.editor?.release()
    logHandler.removeCallbacks(logRunnable)
    super.onDestroyView()
  }

  override fun getContent(): String {
    return this._binding?.editor?.text?.toString() ?: ""
  }

  override fun clearOutput() {
    _binding?.editor?.setText("")?.also { emptyStateViewModel.isEmpty.value = true }
  }
}
