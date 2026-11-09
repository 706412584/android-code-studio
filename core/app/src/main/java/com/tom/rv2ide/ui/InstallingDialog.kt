package com.tom.rv2ide.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.utils.DialogUtils

/**
 * 「安装中」阻塞进度框。
 *
 * <p>走主题统一挂载的 Material 弹窗（[DialogUtils.newMaterialDialogBuilder]）：
 * 底色、圆角、文字色都跟随当前 IDE 主题，不再像旧实现那样强行把窗口背景改成固定的
 * `m3_ref_palette_neutral10`（深色块压在浅色主题上很突兀）。尺寸从裸 px 改为按密度换算的 dp。
 *
 * <p>安装是短时后台动作，保持不可取消（返回键不关闭），避免用户在安装中途误解为已完成。
 */
class InstallingDialog
private constructor(
    private val dialog: androidx.appcompat.app.AlertDialog,
    private val tvMessage: MaterialTextView,
) {
  companion object {
    fun create(context: Context, message: String = "Installing..."): InstallingDialog {
      val density = context.resources.displayMetrics.density
      fun dp(value: Int) = (value * density).toInt()

      val rootLayout =
          LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams =
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
          }

      val progress =
          CircularProgressIndicator(context).apply {
            isIndeterminate = true
            layoutParams =
                LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER }
          }

      val messageView =
          MaterialTextView(context).apply {
            text = message
            // 走主题文字色（不再写死 material_on_surface_emphasis_high_type）。
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            layoutParams =
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    .apply {
                      topMargin = dp(16)
                      gravity = Gravity.CENTER
                    }
          }

      rootLayout.addView(progress)
      rootLayout.addView(messageView)

      val dialog =
          DialogUtils.newMaterialDialogBuilder(context)
              .setView(rootLayout)
              .setCancelable(false)
              .create()

      return InstallingDialog(dialog, messageView)
    }
  }

  fun show() {
    dialog.show()
  }

  fun dismiss() {
    dialog.dismiss()
  }

  fun setMessage(message: String) {
    tvMessage.text = message
  }
}
