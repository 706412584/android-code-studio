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

package com.tom.rv2ide.preferences

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 弹窗里的表单字段：`TextInputLayout`（OutlinedBox + 浮动标签）+ `TextInputEditText`。
 *
 * <p><b>为什么统一到这个</b>：AI 设置里多处弹窗直接用裸 `android.widget.EditText` 加一行
 * 独立标签，观感与仓库其它表单不一致，且输入后标签不浮动、看不出哪个框已填、没有错误态。
 * 这里一次封装，各处复用——取 `?attr/textInputOutlinedStyle` 构造，与 Material 的
 * OutlinedBox 规范一致。
 *
 * <p>不用手搓 padding：Material 组件自带正确的内边距与最小高度，硬编码像素只会在
 * 不同密度/字号下错位。
 */

/** 单行输入字段，返回其 `TextInputLayout`（取文本用 `layout.editText`）。 */
internal fun dialogEditTextField(
    context: Context,
    labelText: CharSequence,
    initialText: String? = null,
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    helperText: CharSequence? = null,
): TextInputLayout {
  // 用主题里的 OutlinedBox 样式构造（第三个参数传 attr），而不是造完再改
  // boxBackgroundMode：后者只换背景形状，轮廓描边、内边距等由 style 决定，
  // 与 XML 里用 `Widget.Material3.TextInputLayout.OutlinedBox` 的表单观感不一致。
  val layout =
      TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle)
          .apply {
            hint = labelText
            if (helperText != null) this.helperText = helperText
          }
  val field =
      TextInputEditText(layout.context).apply {
        this.inputType = inputType
        setText(initialText ?: "")
        isSingleLine = true
      }
  layout.addView(field)
  return layout
}

/**
 * 多行输入字段（提示词、规则、Agent prompt 等）。
 *
 * @param monospace 是否等宽字体（提示词/代码类文本用等宽更易读）
 * @param minLines 最小行数
 */
internal fun dialogMultilineField(
    context: Context,
    labelText: CharSequence,
    initialText: String? = null,
    monospace: Boolean = false,
    minLines: Int = 6,
    helperText: CharSequence? = null,
): TextInputLayout {
  // 用主题里的 OutlinedBox 样式构造（第三个参数传 attr），而不是造完再改
  // boxBackgroundMode：后者只换背景形状，轮廓描边、内边距等由 style 决定，
  // 与 XML 里用 `Widget.Material3.TextInputLayout.OutlinedBox` 的表单观感不一致。
  val layout =
      TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle)
          .apply {
            hint = labelText
            if (helperText != null) this.helperText = helperText
          }
  val field =
      TextInputEditText(layout.context).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        gravity = Gravity.TOP or Gravity.START
        this.minLines = minLines
        if (monospace) typeface = Typeface.MONOSPACE
        setHorizontallyScrolling(false)
        setText(initialText ?: "")
      }
  layout.addView(field)
  return layout
}

/**
 * 把若干字段纵向排进一个**可滚动**容器（弹窗内容用），并按 Material 弹窗的内容留白。
 *
 * <p>可滚动是必须的：多行字段会把弹窗撑高，长内容会把底部按钮挤出窗口
 * （配合仓库既有的 `clampDialogScrollHeight` 限高）。左右 24dp 是 Material 弹窗的
 * 内容边距——用一层 `FrameLayout` 给 padding，而不是给 ScrollView 自身设 padding
 * （后者在内容超高时会被裁掉两侧）。
 */
internal fun dialogFieldColumn(context: Context, vararg children: android.view.View): ScrollView {
  val column =
      LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
      }
  val gap = dpToPx(context, 8)
  children.forEach { child ->
    column.addView(
        child,
        LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            .apply { if (column.childCount > 0) topMargin = gap },
    )
  }

  val padded =
      FrameLayout(context).apply {
        setPadding(dpToPx(context, 24), dpToPx(context, 8), dpToPx(context, 24), 0)
        addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      }

  return ScrollView(context).apply {
    addView(padded, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
  }
}

internal fun dpToPx(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()

/**
 * 把 [dialogFieldColumn] 返回的滚动容器限高到屏幕的 45%。
 *
 * <p>不限高时，长内容（长提示词、粘贴进来的 XML）会把对话框撑到满屏，
 * 底部按钮栏被挤出可视区——用户看到的是「能编辑但找不到保存按钮」。
 * 45% 是标题、提示与按钮都留在可见区后的余量，不是内容长度上限。
 *
 * <p>必须在 `show()` **之前**调用：它通过 `setOnShowListener` 设置布局参数，
 * 因为 AlertController 在 show 时会把 setView 设的高度覆盖成 WRAP_CONTENT。
 */
internal fun clampDialogScrollHeight(
    dialog: android.app.Dialog,
    scroll: ScrollView,
    context: Context,
) {
  dialog.setOnShowListener {
    scroll.layoutParams =
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            (context.resources.displayMetrics.heightPixels * 0.45f).toInt(),
        )
  }
}
