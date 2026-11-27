/*
 * This file is part of AndroidIDE and AndroidCodeStudio.
 * Licensed under the GNU General Public License, version 3 or any later version.
 */
package com.tom.rv2ide.uidesigner.views

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.uidesigner.R
import java.io.File
import kotlin.math.roundToInt

/** 同一模块 res/layout* 下的布局文件；点击只上报，是否保存由宿主决定。 */
internal class LayoutFilesView(
    context: Context,
    private val files: List<File>,
    private val open: (File) -> Unit,
) : LinearLayout(context) {
  private val search = TextInputEditText(context).apply { isSingleLine = true }
  private val empty = MaterialTextView(context).apply {
    setText(R.string.designer_files_empty)
    setTextAppearance(R.style.TextAppearance_Material3_BodyMedium)
    setPadding(dp(12), dp(12), dp(12), dp(12))
  }
  private val filesAdapter = FilesAdapter()
  private var current: File? = null

  init {
    orientation = VERTICAL
    setPadding(dp(8), dp(8), dp(8), dp(8))
    addView(MaterialTextView(context, null, 0, R.style.DesignerSectionLabel).apply {
      setText(R.string.designer_layout_files)
    })
    addView(TextInputLayout(context).apply {
      hint = context.getString(R.string.designer_files_search)
      endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
      addView(search, LayoutParams(-1, -2))
    }, LayoutParams(-1, -2).apply { topMargin = dp(8) })
    addView(empty, LayoutParams(-1, -2))
    addView(RecyclerView(context).apply {
      layoutManager = LinearLayoutManager(context)
      itemAnimator = null
      adapter = filesAdapter
    }, LayoutParams(-1, 0, 1f))
    search.doAfterTextChanged { submit() }
  }

  fun bind(file: File) {
    if (current == file) return
    current = file
    submit()
  }

  private fun submit() {
    val query = search.text.toString().trim()
    filesAdapter.rows = files.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) ||
        it.parentFile?.name?.contains(query, ignoreCase = true) == true }
    empty.isVisible = filesAdapter.rows.isEmpty()
  }

  private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

  private inner class FilesAdapter : RecyclerView.Adapter<FilesAdapter.Holder>() {
    var rows: List<File> = emptyList()
      set(value) {
        field = value
        notifyDataSetChanged()
      }

    override fun getItemCount() = rows.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LinearLayout(parent.context))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

    inner class Holder(private val row: LinearLayout) : RecyclerView.ViewHolder(row) {
      private val name = MaterialTextView(context).apply {
        setTextAppearance(R.style.TextAppearance_Material3_BodyLarge)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MIDDLE
      }
      private val folder = MaterialTextView(context).apply {
        setTextAppearance(R.style.TextAppearance_Material3_BodySmall)
        isSingleLine = true
      }
      private val highlight = MaterialShapeDrawable(ShapeAppearanceModel.builder().setAllCornerSizes(dp(12).toFloat()).build())

      init {
        row.orientation = VERTICAL
        row.layoutParams = RecyclerView.LayoutParams(-1, -2)
        row.minimumHeight = dp(56)
        row.setPadding(dp(12), dp(6), dp(12), dp(6))
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.addView(name, LayoutParams(-1, -2))
        row.addView(folder, LayoutParams(-1, -2))
        row.background = highlight
        val style = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        row.foreground = style.getDrawable(0)
        style.recycle()
        row.setOnClickListener {
          if (bindingAdapterPosition != RecyclerView.NO_POSITION) open(rows[bindingAdapterPosition])
        }
      }

      fun bind(file: File) {
        val selected = file == current
        row.isSelected = selected
        highlight.fillColor = ColorStateList.valueOf(if (selected) MaterialColors.getColor(row, R.attr.colorSecondaryContainer) else 0)
        name.text = file.name
        name.setTextColor(MaterialColors.getColor(row, if (selected) R.attr.colorOnSecondaryContainer else R.attr.colorOnSurface))
        folder.text = file.parentFile?.name.orEmpty()
        folder.setTextColor(MaterialColors.getColor(row, R.attr.colorOnSurfaceVariant))
      }
    }
  }
}
