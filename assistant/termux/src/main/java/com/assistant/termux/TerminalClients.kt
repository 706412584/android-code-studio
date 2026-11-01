/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.assistant.termux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * 会话客户端：把 Termux 的会话事件转成视图刷新 + 剪贴板动作。
 *
 * <p><b>为什么输出刷新要做成「回调一个 viewProvider」而不是持有 view</b>：
 * `TerminalSession` 构造时就会启动 PTY 并可能立刻回调 `onTextChanged`，
 * 而那时 view 还没创建完（它需要 session 才能 attach）。用 provider 延迟取值，
 * 并在内部判空，就不必用 `lateinit`（那会在回调里抛未初始化异常）。
 */
class AssistantSessionClient(
    private val context: Context,
    private val viewProvider: () -> TerminalView?,
    private val onFinished: (TerminalSession) -> Unit,
) : TerminalSessionClient {

  override fun onTextChanged(changedSession: TerminalSession) {
    // 唯一的刷新入口：Termux 攒够一屏数据才回调一次，不必自己再做节流。
    viewProvider()?.onScreenUpdated()
  }

  override fun onTitleChanged(changedSession: TerminalSession) = Unit

  override fun onSessionFinished(finishedSession: TerminalSession) {
    onFinished(finishedSession)
  }

  override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
    if (text.isNullOrEmpty()) return
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
  }

  override fun onPasteTextFromClipboard(session: TerminalSession?) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
    if (text.isEmpty()) return
    // 直接写进 PTY，等价于用户逐字敲进去（终端里的程序能正确收到）。
    val bytes = text.toByteArray(Charsets.UTF_8)
    session?.write(bytes, 0, bytes.size)
  }

  override fun onBell(session: TerminalSession) = Unit

  override fun onColorsChanged(session: TerminalSession) {
    viewProvider()?.onScreenUpdated()
  }

  override fun onTerminalCursorStateChange(state: Boolean) = Unit

  override fun getTerminalCursorStyle(): Int? = null

  override fun logError(tag: String?, message: String?) {
    Log.e(tag ?: TAG, message.orEmpty())
  }

  override fun logWarn(tag: String?, message: String?) {
    Log.w(tag ?: TAG, message.orEmpty())
  }

  override fun logInfo(tag: String?, message: String?) {
    Log.i(tag ?: TAG, message.orEmpty())
  }

  override fun logDebug(tag: String?, message: String?) {
    Log.d(tag ?: TAG, message.orEmpty())
  }

  override fun logVerbose(tag: String?, message: String?) {
    Log.v(tag ?: TAG, message.orEmpty())
  }

  override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
    Log.e(tag ?: TAG, message.orEmpty(), e)
  }

  override fun logStackTrace(tag: String?, e: Exception?) {
    Log.e(tag ?: TAG, "", e)
  }

  private companion object {
    const val TAG = "AssistantTerminal"
  }
}

/**
 * 视图客户端：按键、手势、软键盘。
 *
 * <p><b>刻意不做的事（与参考实现的差异）</b>：不做双指缩放、不做 Ctrl/Alt 虚拟修饰键、
 * 不做长按选择文本。那些都需要额外的 UI（缩放要以 sp 为粒度改 `setTextSize`，
 * 修饰键要一排按钮）。本 App 的终端是「跑命令看输出」的定位，先不引入这些状态。
 * 字号由设置里的固定值决定，不需要运行时调整。
 */
class AssistantViewClient(
    private val context: Context,
    private val viewProvider: () -> TerminalView?,
) : TerminalViewClient {

  override fun onScale(scale: Float): Float = 1f

  override fun onSingleTapUp(e: MotionEvent) {
    // 点一下弹软键盘：终端没有任何其它输入方式（没有硬件键盘的设备上，
    // 不弹键盘用户就完全没法输入）。
    val view = viewProvider() ?: return
    view.requestFocus()
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
    imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
  }

  override fun shouldBackButtonBeMappedToEscape(): Boolean = false

  override fun shouldEnforceCharBasedInput(): Boolean = true

  override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

  override fun isTerminalViewSelected(): Boolean = true

  override fun copyModeChanged(copyMode: Boolean) = Unit

  override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
    // 回车交给 Termux 自己处理（它会正确编码成 PTY 需要的 CR）。
    // 其它键也一律放行——自定义拦截会在不同 IME 上表现不一致。
    return false
  }

  override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

  override fun onLongPress(e: MotionEvent): Boolean = false

  override fun readControlKey(): Boolean = false

  override fun readAltKey(): Boolean = false

  override fun readShiftKey(): Boolean = false

  override fun readFnKey(): Boolean = false

  override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean =
      false

  override fun onEmulatorSet() {
    viewProvider()?.onScreenUpdated()
  }

  override fun logError(tag: String?, message: String?) {
    Log.e(tag ?: TAG, message.orEmpty())
  }

  override fun logWarn(tag: String?, message: String?) {
    Log.w(tag ?: TAG, message.orEmpty())
  }

  override fun logInfo(tag: String?, message: String?) {
    Log.i(tag ?: TAG, message.orEmpty())
  }

  override fun logDebug(tag: String?, message: String?) {
    Log.d(tag ?: TAG, message.orEmpty())
  }

  override fun logVerbose(tag: String?, message: String?) {
    Log.v(tag ?: TAG, message.orEmpty())
  }

  override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
    Log.e(tag ?: TAG, message.orEmpty(), e)
  }

  override fun logStackTrace(tag: String?, e: Exception?) {
    Log.e(tag ?: TAG, "", e)
  }

  private companion object {
    const val TAG = "AssistantTerminal"
  }
}
