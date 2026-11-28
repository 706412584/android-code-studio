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

package com.tom.rv2ide.inflater.internal

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * View used to show views that cannot be inflated.
 *
 * @author Akash Yadav
 */
class ErrorView(file: LayoutFile, name: String, context: Context, message: String) :
    ViewImpl(file = file, name = name, view = createErrView(context, message)) {
  override val tag: String = name
}

class ErrorLayout(file: LayoutFile, name: String, context: Context) :
    ViewGroupImpl(file = file, name = name, view = createErrLayout(context)) {
  override val tag: String = name
}

private fun createErrView(context: Context, message: String): View {
  // 标签名和降级说明由层级树、属性面板展示，不让全屏自绘装饰盖住其他控件。
  return View(context).apply { contentDescription = message }
}

private fun createErrLayout(context: Context): ViewGroup {
  return FrameLayout(context)
}
