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

package com.tom.rv2ide.inflater.internal.adapters

import android.content.Context
import android.graphics.PorterDuff
import android.os.Build
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.RelativeLayout.ABOVE
import android.widget.RelativeLayout.ALIGN_BASELINE
import android.widget.RelativeLayout.ALIGN_BOTTOM
import android.widget.RelativeLayout.ALIGN_END
import android.widget.RelativeLayout.ALIGN_LEFT
import android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM
import android.widget.RelativeLayout.ALIGN_PARENT_END
import android.widget.RelativeLayout.ALIGN_PARENT_LEFT
import android.widget.RelativeLayout.ALIGN_PARENT_RIGHT
import android.widget.RelativeLayout.ALIGN_PARENT_START
import android.widget.RelativeLayout.ALIGN_PARENT_TOP
import android.widget.RelativeLayout.ALIGN_RIGHT
import android.widget.RelativeLayout.ALIGN_START
import android.widget.RelativeLayout.ALIGN_TOP
import android.widget.RelativeLayout.BELOW
import android.widget.RelativeLayout.CENTER_HORIZONTAL
import android.widget.RelativeLayout.CENTER_IN_PARENT
import android.widget.RelativeLayout.CENTER_VERTICAL
import android.widget.RelativeLayout.END_OF
import android.widget.RelativeLayout.LEFT_OF
import android.widget.RelativeLayout.RIGHT_OF
import android.widget.RelativeLayout.START_OF
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import com.tom.rv2ide.annotations.inflater.ViewAdapter
import com.tom.rv2ide.annotations.uidesigner.IncludeInDesigner
import com.tom.rv2ide.annotations.uidesigner.IncludeInDesigner.Group.WIDGETS
import com.tom.rv2ide.inflater.AttributeHandlerScope
import com.tom.rv2ide.inflater.IAttribute
import com.tom.rv2ide.inflater.INamespace
import com.tom.rv2ide.inflater.IView
import com.tom.rv2ide.inflater.IViewAdapter
import com.tom.rv2ide.inflater.internal.IncludeView
import com.tom.rv2ide.inflater.models.UiWidget
import com.tom.rv2ide.inflater.utils.newAttribute
import com.tom.rv2ide.resources.R

/**
 * Attribute adapter for [View].
 *
 * @author Akash Yadav
 */
@ViewAdapter(forView = View::class)
@IncludeInDesigner(group = WIDGETS)
open class ViewAdapter<T : View> : IViewAdapter<T>() {

  /**
   * 放行 `app`（`http://schemas.android.com/apk/res-auto`）命名空间。
   *
   * <p>库控件的属性大多在 `app:` 下（ConstraintLayout 的 `layout_constraint*`、Material
   * 组件的 `cardCornerRadius` 等）。基类原先只接受 `android:`，这些属性会在进入具体适配器
   * **之前**就被丢弃。未识别的 `app:` 属性由 [AttributeHandlerScope.applyInternal] 返回
   * false（只记录、不报错），因此统一放行是安全的。
   */
  override fun canHandleNamespace(nsUri: String?): Boolean {
    return super.canHandleNamespace(nsUri) || nsUri == INamespace.APP.uri
  }

  override fun createAttrHandlers(create: (String, AttributeHandlerScope<T>.() -> Unit) -> Unit) {
    create("alpha") { view.alpha = parseFloat(value, def = 1f) }
    create("background") { view.background = parseDrawable(context, value) }
    create("backgroundTint") { view.backgroundTintList = parseColorStateList(context, value) }
    create("backgroundTintMode") { view.backgroundTintMode = parsePorterDuffMode(value) }
    create("clipToOutline") { view.clipToOutline = parseBoolean(value) }
    create("contentDescription") { view.contentDescription = parseString(value) }
    create("contextClickable") { view.isContextClickable = parseBoolean(value) }
    create("defaultFocusHighlightEnabled") {
      view.defaultFocusHighlightEnabled = parseBoolean(value)
    }
    create("drawingCacheQuality") {
      @Suppress("DEPRECATION")
      view.drawingCacheQuality = parseDrawingCacheQuality(value)
    }
    create("duplicateParentState") { view.isDuplicateParentStateEnabled = parseBoolean(value) }
    create("elevation") { view.elevation = parseDimensionF(context, value) }
    create("fadeScrollbars") { view.isScrollbarFadingEnabled = parseBoolean(value) }
    create("fadingEdgeLength") { view.setFadingEdgeLength(parseDimension(context, value)) }
    create("filterTouchesWhenObscured") { view.filterTouchesWhenObscured = parseBoolean(value) }
    create("foreground") { view.foreground = parseDrawable(context, value) }
    create("foregroundGravity") { view.foregroundGravity = parseGravity(value) }
    create("foregroundTint") { view.foregroundTintList = parseColorStateList(context, value) }
    create("foregroundTintMode") { view.foregroundTintMode = parsePorterDuffMode(value) }
    create("id") { view.id = parseId(file.resName, value) }
    create("minHeight") { view.minimumWidth = parseDimension(context, value) }
    create("minWidth") { view.minimumHeight = parseDimension(context, value) }
    create("padding") {
      parseDimension(context, value).also {
        view.updatePadding(left = it, top = it, right = it, bottom = it)
      }
    }
    create("paddingLeft") { view.updatePadding(left = parseDimension(context, value)) }
    create("paddingTop") { view.updatePadding(top = parseDimension(context, value)) }
    create("paddingRight") { view.updatePadding(right = parseDimension(context, value)) }
    create("paddingBottom") { view.updatePadding(bottom = parseDimension(context, value)) }
    create("paddingStart") { view.updatePaddingRelative(start = parseDimension(context, value)) }
    create("paddingEnd") { view.updatePaddingRelative(end = parseDimension(context, value)) }
    create("rotation") { view.rotation = parseFloat(value) }
    create("rotationX") { view.rotationX = parseFloat(value) }
    create("rotationY") { view.rotationY = parseFloat(value) }
    create("scaleX") { view.scaleX = parseFloat(value) }
    create("scaleY") { view.scaleY = parseFloat(value) }
    create("scrollX") { view.scrollX = parseInteger(value) }
    create("scrollY") { view.scrollY = parseInteger(value) }
    create("textAlignment") { view.textAlignment = parseTextAlignment(value) }
    create("textDirection") { view.textDirection = parseTextDirection(value) }
    create("tooltipText") { view.tooltipText = parseString(value) }
    create("transformPivotX") { view.pivotX = parseFloat(value) }
    create("transformPivotY") { view.pivotY = parseFloat(value) }
    create("translationX") { view.translationX = parseFloat(value) }
    create("translationY") { view.translationY = parseFloat(value) }
    create("translationZ") { view.translationZ = parseFloat(value) }
    create("visibility") { view.visibility = parseVisibility(value) }
  }

  override fun AttributeHandlerScope<T>.applyLayoutParams(): Boolean {

    var applied = false

    if (layoutParams is LinearLayout.LayoutParams) {
      applied = applyLinearLayoutParams(layoutParams, name, value)
    }

    if (!applied && layoutParams is RelativeLayout.LayoutParams) {
      applied = applyRelativeLayoutParams(layoutParams, file.resName, name, value)
    }

    if (!applied && layoutParams is FrameLayout.LayoutParams) {
      applied = applyFrameLayoutParams(layoutParams, name, value)
    }

    if (!applied && layoutParams is MarginLayoutParams) {
      applied = applyMarginParams(context, layoutParams, name, value)
    }

    // ConstraintLayout 的约束是**子控件**的布局参数（app:layout_constraint*），
    // 由子控件自己的适配器应用；因此放在基类分发里，而不是 ConstraintLayoutAdapter。
    // 注意 ConstraintLayout.LayoutParams 继承 MarginLayoutParams，上面 margin 分支
    // 对约束属性返回 false，不会吞掉它们。
    if (!applied && layoutParams is ConstraintLayout.LayoutParams) {
      applied = applyConstraintLayoutParams(context, layoutParams, file.resName, name, value)
    }

    if (!applied) {
      applied = applyLayoutParams(context, layoutParams, name, value)
    }

    if (applied) {
      view.layoutParams = layoutParams
    }

    return applied
  }

  override fun createUiWidgets(): List<UiWidget> {
    return listOf(UiWidget(View::class.java, R.string.widget_view, R.drawable.ic_widget_view))
  }

  override fun applyBasic(view: IView) {
    if (view is IncludeView) {
      return
    }

    view.addAttribute(newAttribute(view = view, name = "layout_height", value = "wrap_content"))
    view.addAttribute(newAttribute(view = view, name = "layout_width", value = "wrap_content"))
  }

  override fun isRequiredAttribute(attribute: IAttribute): Boolean {
    if (attribute.namespace?.uri != INamespace.ANDROID.uri) {
      return false
    }

    return when (attribute.name) {
      "layout_width",
      "layout_height" -> true
      else -> false
    }
  }

  protected open fun applyLayoutParams(
      context: Context,
      params: LayoutParams,
      name: String,
      value: String,
  ): Boolean {
    var applied = true
    when (name) {
      "layout_height" -> params.height = parseDimension(context, value)
      "layout_width" -> params.width = parseDimension(context, value)
      else -> applied = false
    }
    return applied
  }

  protected open fun applyMarginParams(
      context: Context,
      params: MarginLayoutParams,
      name: String,
      value: String,
  ): Boolean {
    var handled = true
    when (name) {
      "layout_margin" -> {
        val margin = parseDimension(context, value, 0)
        params.setMargins(margin, margin, margin, margin)
      }
      "layout_marginLeft" -> params.leftMargin = parseDimension(context, value)
      "layout_marginTop" -> params.topMargin = parseDimension(context, value)
      "layout_marginRight" -> params.rightMargin = parseDimension(context, value)
      "layout_marginBottom" -> params.bottomMargin = parseDimension(context, value)
      "layout_marginStart" -> params.marginStart = parseDimension(context, value)
      "layout_marginEnd" -> params.marginEnd = parseDimension(context, value)
      else -> handled = false
    }
    return handled
  }

  protected open fun applyLinearLayoutParams(
      params: LinearLayout.LayoutParams,
      name: String,
      value: String,
  ): Boolean {
    var applied = true
    when (name) {
      "layout_gravity" -> params.gravity = parseGravity(value)
      "layout_weight" -> params.weight = parseFloat(value, 1f)
      else -> applied = false
    }
    return applied
  }

  protected open fun applyRelativeLayoutParams(
      params: RelativeLayout.LayoutParams,
      resName: String,
      name: String,
      value: String,
  ): Boolean {
    var handled = true
    when (name) {
      "layout_above" -> params.addRule(ABOVE, parseId(resName, value))
      "layout_alignBaseline" -> params.addRule(ALIGN_BASELINE, parseId(resName, value))
      "layout_alignBottom" -> params.addRule(ALIGN_BOTTOM, parseId(resName, value))
      "layout_alignEnd" -> params.addRule(ALIGN_END, parseId(resName, value))
      "layout_alignLeft" -> params.addRule(ALIGN_LEFT, parseId(resName, value))
      "layout_alignParentTop" -> setRuleIf(parseBoolean(value), ALIGN_PARENT_TOP, params)
      "layout_alignParentBottom" -> setRuleIf(parseBoolean(value), ALIGN_PARENT_BOTTOM, params)
      "layout_alignParentStart" -> setRuleIf(parseBoolean(value), ALIGN_PARENT_START, params)
      "layout_alignParentEnd" -> setRuleIf(parseBoolean(value), ALIGN_PARENT_END, params)
      "layout_alignParentLeft" -> setRuleIf(parseBoolean(value), ALIGN_PARENT_LEFT, params)
      "layout_alignParentRight" -> setRuleIf(parseBoolean(value), ALIGN_PARENT_RIGHT, params)
      "layout_alignRight" -> params.addRule(ALIGN_RIGHT, parseId(resName, value))
      "layout_alignStart" -> params.addRule(ALIGN_START, parseId(resName, value))
      "layout_alignTop" -> params.addRule(ALIGN_TOP, parseId(resName, value))
      "layout_alignWithParentIfMissing" -> params.alignWithParent = parseBoolean(value)
      "layout_below" -> params.addRule(BELOW, parseId(resName, value))
      "layout_centerHorizontal" -> setRuleIf(parseBoolean(value), CENTER_HORIZONTAL, params)
      "layout_centerInParent" -> setRuleIf(parseBoolean(value), CENTER_IN_PARENT, params)
      "layout_centerVertical" -> setRuleIf(parseBoolean(value), CENTER_VERTICAL, params)
      "layout_toEndOf" -> params.addRule(END_OF, parseId(resName, value))
      "layout_toStartOf" -> params.addRule(START_OF, parseId(resName, value))
      "layout_toLeftOf" -> params.addRule(LEFT_OF, parseId(resName, value))
      "layout_toRightOf" -> params.addRule(RIGHT_OF, parseId(resName, value))
      else -> handled = false
    }
    return handled
  }

  protected open fun applyFrameLayoutParams(
      params: FrameLayout.LayoutParams,
      name: String,
      value: String,
  ): Boolean {
    var applied = true
    if ("layout_gravity" == name) {
      params.gravity = parseGravity(value)
    } else {
      applied = false
    }
    return applied
  }

  /**
   * 应用 ConstraintLayout 的布局参数（`app:layout_constraint*` 等）。
   *
   * <p>这些属性属于**子控件**，由子控件的适配器负责；`0dp` 会被解析成 0，正好是
   * `MATCH_CONSTRAINT` 的取值，无需特殊转换。
   */
  protected open fun applyConstraintLayoutParams(
      context: Context,
      params: ConstraintLayout.LayoutParams,
      resName: String,
      name: String,
      value: String,
  ): Boolean {
    fun constraintId(): Int {
      if (value == "parent") {
        return ConstraintLayout.LayoutParams.PARENT_ID
      }
      // 未解析到的引用回退 UNSET（-1）而不是 0：0 是 PARENT_ID，会把拼错的 ID
      // 当成「连到父容器」，让预览悄悄画错位置。
      return parseId(resName, value, ConstraintLayout.LayoutParams.UNSET)
    }

    fun matchConstraintDefault(): Int {
      return when (value) {
        "spread" -> ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
        "wrap" -> ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_WRAP
        "percent" -> ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
        else -> ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
      }
    }

    fun chainStyle(): Int {
      return when (value) {
        "spread_inside" -> ConstraintLayout.LayoutParams.CHAIN_SPREAD_INSIDE
        "packed" -> ConstraintLayout.LayoutParams.CHAIN_PACKED
        else -> ConstraintLayout.LayoutParams.CHAIN_SPREAD
      }
    }

    fun percent(): Float {
      if (value.endsWith("%")) {
        return value.dropLast(1).toFloatOrNull()?.div(100f) ?: 0f
      }
      return value.toFloatOrNull() ?: 0f
    }

    when (name) {
      // 四边约束；"parent" 解析为 PARENT_ID。
      "layout_constraintBottom_toBottomOf" -> params.bottomToBottom = constraintId()
      "layout_constraintBottom_toTopOf" -> params.bottomToTop = constraintId()
      "layout_constraintTop_toBottomOf" -> params.topToBottom = constraintId()
      "layout_constraintTop_toTopOf" -> params.topToTop = constraintId()
      "layout_constraintStart_toStartOf" -> params.startToStart = constraintId()
      "layout_constraintStart_toEndOf" -> params.startToEnd = constraintId()
      "layout_constraintEnd_toStartOf" -> params.endToStart = constraintId()
      "layout_constraintEnd_toEndOf" -> params.endToEnd = constraintId()
      "layout_constraintLeft_toLeftOf" -> params.leftToLeft = constraintId()
      "layout_constraintLeft_toRightOf" -> params.leftToRight = constraintId()
      "layout_constraintRight_toLeftOf" -> params.rightToLeft = constraintId()
      "layout_constraintRight_toRightOf" -> params.rightToRight = constraintId()

      // 基线约束。
      "layout_constraintBaseline_toBaselineOf" -> params.baselineToBaseline = constraintId()
      "layout_constraintBaseline_toTopOf" -> params.baselineToTop = constraintId()
      "layout_constraintBaseline_toBottomOf" -> params.baselineToBottom = constraintId()

      // 圆形约束。
      "layout_constraintCircle" -> params.circleConstraint = constraintId()
      "layout_constraintCircleRadius" -> params.circleRadius = parseDimension(context, value)
      "layout_constraintCircleAngle" -> params.circleAngle = parseFloat(value)

      // 偏向与宽高比。
      "layout_constraintHorizontal_bias" -> params.horizontalBias = parseFloat(value, 0.5f)
      "layout_constraintVertical_bias" -> params.verticalBias = parseFloat(value, 0.5f)
      "layout_constraintDimensionRatio" -> params.dimensionRatio = value

      // 0dp 下的尺寸行为与上下限。
      "layout_constraintWidth_default" ->
          params.matchConstraintDefaultWidth = matchConstraintDefault()
      "layout_constraintHeight_default" ->
          params.matchConstraintDefaultHeight = matchConstraintDefault()
      "layout_constraintWidth_min" ->
          params.matchConstraintMinWidth = parseDimension(context, value)
      "layout_constraintWidth_max" ->
          params.matchConstraintMaxWidth = parseDimension(context, value)
      "layout_constraintHeight_min" ->
          params.matchConstraintMinHeight = parseDimension(context, value)
      "layout_constraintHeight_max" ->
          params.matchConstraintMaxHeight = parseDimension(context, value)
      "layout_constraintWidth_percent" -> params.matchConstraintPercentWidth = percent()
      "layout_constraintHeight_percent" -> params.matchConstraintPercentHeight = percent()

      // 链与权重。
      "layout_constraintHorizontal_weight" -> params.horizontalWeight = parseFloat(value, 0f)
      "layout_constraintVertical_weight" -> params.verticalWeight = parseFloat(value, 0f)
      "layout_constraintHorizontal_chainStyle" -> params.horizontalChainStyle = chainStyle()
      "layout_constraintVertical_chainStyle" -> params.verticalChainStyle = chainStyle()

      // 受限尺寸与 gone 时的边距。
      "layout_constrainedWidth" -> params.constrainedWidth = parseBoolean(value)
      "layout_constrainedHeight" -> params.constrainedHeight = parseBoolean(value)
      "layout_goneMarginLeft" -> params.goneLeftMargin = parseDimension(context, value)
      "layout_goneMarginTop" -> params.goneTopMargin = parseDimension(context, value)
      "layout_goneMarginRight" -> params.goneRightMargin = parseDimension(context, value)
      "layout_goneMarginBottom" -> params.goneBottomMargin = parseDimension(context, value)
      "layout_goneMarginStart" -> params.goneStartMargin = parseDimension(context, value)
      "layout_goneMarginEnd" -> params.goneEndMargin = parseDimension(context, value)

      // 设计时坐标（仅预览使用）。
      "layout_editor_absoluteX" -> params.editorAbsoluteX = parseDimension(context, value)
      "layout_editor_absoluteY" -> params.editorAbsoluteY = parseDimension(context, value)

      else -> return false
    }
    return true
  }

  protected open fun parseDrawingCacheQuality(value: String): Int {
    return com.tom.rv2ide.inflater.internal.utils.parseDrawingCacheQuality(value)
  }

  protected open fun parseVisibility(value: String): Int {
    return com.tom.rv2ide.inflater.internal.utils.parseVisibility(value)
  }

  protected open fun parseTextAlignment(value: String): Int {
    return com.tom.rv2ide.inflater.internal.utils.parseTextAlignment(value)
  }

  protected open fun parseTextDirection(value: String): Int {
    return com.tom.rv2ide.inflater.internal.utils.parseTextDirection(value)
  }

  protected open fun parsePorterDuffMode(mode: String): PorterDuff.Mode {
    return com.tom.rv2ide.inflater.internal.utils.parsePorterDuffMode(mode)
  }

  @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
  protected open fun isApi29(): Boolean {
    return Build.VERSION.SDK_INT >= 29
  }

  private fun setRuleIf(
      condition: Boolean,
      rule: Int,
      params: RelativeLayout.LayoutParams,
  ) {
    if (condition) {
      params.addRule(rule)
    } else {
      params.removeRule(rule)
    }
  }
}
