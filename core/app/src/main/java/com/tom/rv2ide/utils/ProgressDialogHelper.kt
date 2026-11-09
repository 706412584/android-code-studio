/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
*/
package com.tom.rv2ide.utils

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.tom.rv2ide.R

/**
 * Git 等操作的**非阻塞**进度指示。
 *
 * <p><b>为什么从模态进度框改为 Snackbar</b>：原先它是 `setCancelable(false)` 的
 * Material AlertDialog，包住整个界面——用户在拉取/推送期间看不到也点不动别的，
 * 而 Git 操作本就允许继续看列表/日志。现在是一个带不确定进度条的 Snackbar：
 * 只占屏底一条，界面仍可用，操作完成后由结果反馈（列表刷新 / Snackbar）接管。
 *
 * <p><b>类名保留</b>：三个 Git Fragment 都按 `ProgressDialogHelper` 这个类型持有它，
 * 对外 API（show / updateMessage / dismiss / isShowing）不变，调用点无需改动。
 */
class ProgressDialogHelper(private val context: Context) {

  private var snackbar: Snackbar? = null
  private var messageView: TextView? = null

  fun show(message: String? = null) {
    dismiss()

    val activity = activityOf(context) ?: return
    val root = activity.findViewById<View>(android.R.id.content) ?: return

    val content = LayoutInflater.from(activity).inflate(R.layout.layout_git_progress_snackbar, null)
    messageView = content.findViewById(R.id.gitProgressMessage)
    messageView?.text = message ?: context.getString(R.string.common_please_wait)

    val bar = Snackbar.make(root, "", Snackbar.LENGTH_INDEFINITE)
    // 用自定义视图替换 Snackbar 的默认文本行：保留 Snackbar 自身的背景/圆角/位置，
    // 只在内容区放入「进度条 + 说明文字」。
    val textParent =
        bar.view.findViewById<View>(com.google.android.material.R.id.snackbar_text)?.parent
            as? ViewGroup
    if (textParent != null) {
      textParent.removeAllViews()
      textParent.addView(
          content,
          ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.WRAP_CONTENT,
          ),
      )
    }
    snackbar = bar
    bar.show()
  }

  fun updateMessage(message: String) {
    messageView?.text = message
  }

  fun dismiss() {
    snackbar?.dismiss()
    snackbar = null
    messageView = null
  }

  fun isShowing(): Boolean = snackbar?.isShown == true

  /**
   * 从 Context（可能是 ContextWrapper）取其 Activity；取不到返回 null。
   *
   * <p>接受任意 `Activity` 而不是只认 `AppCompatActivity`：Snackbar 只要求一个 View 作为
   * 锚点，宿主是不是 AppCompat 无关。此前只匹配 AppCompatActivity，在纯 Activity 宿主里
   * 会静默不显示——调用方以为进度框出来了，实际什么都没发生。
   */
  private fun activityOf(context: Context): android.app.Activity? {
    var ctx: Context? = context
    while (ctx is android.content.ContextWrapper) {
      if (ctx is android.app.Activity) return ctx
      ctx = ctx.baseContext
    }
    return ctx as? android.app.Activity
  }
}
