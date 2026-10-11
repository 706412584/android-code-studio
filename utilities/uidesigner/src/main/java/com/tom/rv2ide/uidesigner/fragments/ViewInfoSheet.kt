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

package com.tom.rv2ide.uidesigner.fragments

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.viewModels
import androidx.navigation.NavController
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.NavGraphNavigator
import androidx.navigation.fragment.FragmentNavigator
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tom.rv2ide.uidesigner.R
import com.tom.rv2ide.uidesigner.databinding.LayoutViewInfoSheetBinding
import com.tom.rv2ide.uidesigner.viewmodel.WorkspaceViewModel

/** @author Akash Yadav */
class ViewInfoSheet : BottomSheetDialogFragment() {

  private var binding: LayoutViewInfoSheetBinding? = null
  private val viewModel by viewModels<WorkspaceViewModel>(ownerProducer = { requireActivity() })

  companion object {

    const val TAG = "ide.uidesigner.viewinfo"
  }

  private val viewInfoBackPressedCallback =
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          this@ViewInfoSheet.binding?.navHost ?: return
          findNavControllerFromFragment().navigateUp()
        }
      }

  override fun onDismiss(dialog: DialogInterface) {
    viewModel.undoManager.enable()
    viewModel.notifyAttrUpdated()
    super.onDismiss(dialog)
  }

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?,
  ): View {
    return LayoutViewInfoSheetBinding.inflate(inflater, container, false)
        .also { this.binding = it }
        .root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    findNavControllerFromFragment().addOnDestinationChangedListener { _, destination, _ ->
      viewInfoBackPressedCallback.isEnabled = destination.id != R.id.viewInfoFragment

      // not disabling the undo manager when going into 'edit mode' will result in a lot
      // AttrUpdatedAction being pushed to UndoManager's undo stack.
      viewModel.undoManager.enabled = destination.id != R.id.attrValueEditorFragment

      if (viewModel.currentDestination == R.id.attrValueEditorFragment) {
        // if we are returning from value editor, then
        // 1 - if we were updating the value of an existing attribute, then the updated value must
        //     be updated in the view
        // 2 - if we were adding a new attribute, then that attribute should be added to the view.
        viewModel.notifyAttrUpdated()
        viewModel.selectedAttr = null
        viewModel.addAttrMode = false
      }

      viewModel.currentDestination = destination.id
    }

    (requireDialog() as ComponentDialog)
        .onBackPressedDispatcher
        .addCallback(viewLifecycleOwner, viewInfoBackPressedCallback)
  }

  /**
   * 取得子 NavHostFragment 的 NavController，并在首次访问时用**代码**建好导航图。
   *
   * <p>导航图不再来自 `res/navigation/nav_viewinfo.xml`：该目录在 AGP 8.13 的 library
   * 资源打包阶段被整个忽略（packaged_res 只含 layout，而 R 符号却由另一路径生成），
   * 导致 `@navigation/nav_viewinfo` 引用必然链接失败。改为代码建图后不再依赖该资源类型。
   *
   * <p>等价于原 XML：起点 viewInfoFragment，另两个目的地 addAttrFragment /
   * attrValueEditorFragment。原 XML 里的 action 与 argument 均未使用——代码用
   * `navigate(R.id.<目的地>)` 直接导航，参数经共享 ViewModel（selectedAttr）传递。
   */
  private fun findNavControllerFromFragment(): NavController {
    val fragment = childFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
    val navController = fragment.navController
    if (navController.currentDestination == null) {
      navController.graph = buildNavGraph(navController)
    }
    return navController
  }

  /** 用 NavigatorProvider 代码建图（不依赖 res/navigation 与 Kotlin DSL 扩展）。 */
  private fun buildNavGraph(navController: NavController): NavGraph {
    val provider = navController.navigatorProvider
    val fragmentNavigator = provider.getNavigator(FragmentNavigator::class.java)
    val graph = NavGraph(provider.getNavigator(NavGraphNavigator::class.java))

    graph.addDestination(fragmentDestination(fragmentNavigator, R.id.viewInfoFragment, ViewInfoFragment::class.java))
    graph.addDestination(fragmentDestination(fragmentNavigator, R.id.addAttrFragment, AddAttrFragment::class.java))
    graph.addDestination(
        fragmentDestination(
            fragmentNavigator, R.id.attrValueEditorFragment, AttrValueEditorFragment::class.java))
    graph.setStartDestination(R.id.viewInfoFragment)
    return graph
  }

  private fun fragmentDestination(
      navigator: FragmentNavigator,
      id: Int,
      fragmentClass: Class<out androidx.fragment.app.Fragment>,
  ): FragmentNavigator.Destination {
    val destination = navigator.createDestination()
    destination.id = id
    destination.setClassName(fragmentClass.name)
    return destination
  }

  override fun onDestroyView() {
    super.onDestroyView()
    this.binding = null
  }
}
