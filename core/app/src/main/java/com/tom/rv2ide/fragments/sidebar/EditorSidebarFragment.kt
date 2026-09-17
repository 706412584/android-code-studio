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

package com.tom.rv2ide.fragments.sidebar

import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.marginBottom
import androidx.core.view.marginTop
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMarginsRelative
import androidx.core.view.updatePadding
import com.tom.rv2ide.databinding.FragmentEditorSidebarBinding
import com.tom.rv2ide.fragments.FragmentWithBinding
import com.tom.rv2ide.utils.EditorSidebarActions

/**
 * Fragment for showing the default items in the editor activity's sidebar.
 *
 * @author Akash Yadav
 */
class EditorSidebarFragment :
    FragmentWithBinding<FragmentEditorSidebarBinding>(FragmentEditorSidebarBinding::inflate) {

  /**
   * 活动回调送来的系统栏 inset。
   *
   * <p>活动在 decorView attach 时调 [onApplyWindowInsets]，而本 Fragment 是懒加载的——
   * 那时 [_binding] 还是 null。若直接丢弃，侧栏就永远拿不到底部 inset（图标会被系统导航栏
   * 盖住）。因此先存下来，等视图创建后再补上。
   */
  private var pendingInsets: Insets? = null

  internal fun onApplyWindowInsets(insets: Insets) {
    pendingInsets = insets
    _binding?.apply {
      title.updateLayoutParams<MarginLayoutParams> {
        updateMarginsRelative(
            top = title.marginTop + insets.top,
        )
      }
      fragmentContainer.updateLayoutParams<MarginLayoutParams> {
        updateMarginsRelative(
            bottom = fragmentContainer.marginBottom + insets.bottom,
        )
      }
      sidebarContainer.updatePadding(
          top = sidebarContainer.paddingTop + insets.top,
          bottom = sidebarContainer.paddingBottom + insets.bottom,
          left = sidebarContainer.paddingLeft + insets.left,
      )
      navigation.updatePadding(bottom = insets.bottom)
    }
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    // 底部导航行必须避开系统导航栏，但 inset 有两条来源都不能直接用：
    //
    // 1) BaseEditorActivity.onApplySystemBarInsets 在 decorView attach 时调用
    //    onApplyWindowInsets()，那是**一次性**回调。抽屉里的侧栏 Fragment 是懒加载的，
    //    视图创建晚于该回调，此时 _binding 仍为 null，inset 被静默丢弃。
    // 2) 本视图自己的 insets 回调兜不住：根布局 activity_editor.xml 带
    //    android:fitsSystemWindows="true"，edge-to-edge 下它会先消费掉 insets，
    //    子视图收到的 systemBars.bottom 恒为 0，padding 加了等于没加。
    //
    // 所以这里在视图创建后主动补一次：优先用活动回调已存下的 inset，
    // 没有则从**根窗口**取（根窗口 insets 不受子视图消费影响）。
    // 回调保留下来，以应对旋转、导航栏模式切换等后续变化。
    val pending = pendingInsets
    if (pending != null) {
      onApplyWindowInsets(pending)
    } else {
      applyNavigationBarInset(view)
    }

    ViewCompat.setOnApplyWindowInsetsListener(binding.navigation) { v, insets ->
      val consumed = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
      val bottom =
          if (consumed > 0) {
            consumed
          } else {
            ViewCompat.getRootWindowInsets(v)
                ?.getInsets(WindowInsetsCompat.Type.systemBars())
                ?.bottom ?: 0
          }
      v.updatePadding(bottom = bottom)
      insets
    }

    EditorSidebarActions.setup(this)
  }

  /** 从根窗口取系统栏 inset 并补到导航行上，使图标不被系统导航栏遮挡。 */
  private fun applyNavigationBarInset(view: View) {
    val bottom =
        ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
            ?.bottom ?: 0
    _binding?.navigation?.updatePadding(bottom = bottom)
  }

  /** Get the (nullable) binding object for this fragment. */
  internal fun getBinding() = _binding
}
