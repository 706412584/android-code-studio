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

package com.tom.rv2ide.activities

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.widget.FrameLayout
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.graphics.Insets
import androidx.core.view.isVisible
import androidx.transition.TransitionManager
import androidx.transition.doOnEnd
import com.google.android.material.transition.MaterialSharedAxis
import com.tom.rv2ide.activities.editor.EditorActivityKt
import com.tom.rv2ide.app.EdgeToEdgeIDEActivity
import com.tom.rv2ide.databinding.ActivityMainBinding
import com.tom.rv2ide.preferences.internal.GeneralPreferences
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.resources.R.string
import com.tom.rv2ide.templates.ITemplateProvider
import com.tom.rv2ide.utils.DialogUtils
import com.tom.rv2ide.utils.flashInfo
import com.tom.rv2ide.R
import com.tom.rv2ide.viewmodel.MainViewModel
import com.tom.rv2ide.viewmodel.MainViewModel.Companion.SCREEN_AI
import com.tom.rv2ide.viewmodel.MainViewModel.Companion.SCREEN_MAIN
import com.tom.rv2ide.viewmodel.MainViewModel.Companion.SCREEN_TEMPLATE_DETAILS
import com.tom.rv2ide.viewmodel.MainViewModel.Companion.SCREEN_TEMPLATE_LIST
import com.tom.rv2ide.setup.updater.lsp.KotlinLspUpdater
import com.tom.rv2ide.setup.updater.lsp.data.LSPProperties
import java.io.File

class MainActivity : EdgeToEdgeIDEActivity() {

  private lateinit var tomIDEUpdater: TomIDEUpdater
  private val viewModel by viewModels<MainViewModel>()
  private var _binding: ActivityMainBinding? = null

  private val onBackPressedCallback =
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          viewModel.apply {

            if (creatingProject.value == true) {
              return@apply
            }

            val newScreen =
                when (currentScreen.value) {
                  SCREEN_TEMPLATE_DETAILS -> SCREEN_TEMPLATE_LIST
                  SCREEN_TEMPLATE_LIST -> SCREEN_MAIN
                  // AI 助手页与模板向导是并列分支：返回键直接回主页，不回模板列表。
                  SCREEN_AI -> SCREEN_MAIN
                  else -> SCREEN_MAIN
                }

            if (currentScreen.value != newScreen) {
              // back = true：只有返回键能确定这是后退，转场方向据此判定。
              setScreen(newScreen, back = true)
            }
          }
        }
      }

  private val binding: ActivityMainBinding
    get() = checkNotNull(_binding)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    tomIDEUpdater = TomIDEUpdater(this)
    tomIDEUpdater.checkForUpdates()

    openLastProject()

    viewModel.currentScreen.observe(this) { screen ->
      if (screen == -1) {
        return@observe
      }

      onScreenChanged(screen)
      onBackPressedCallback.isEnabled = screen != SCREEN_MAIN
    }

    if (viewModel.currentScreen.value == -1 && viewModel.previousScreen == -1) {
      viewModel.setScreen(SCREEN_MAIN)
    } else {
      onScreenChanged(viewModel.currentScreen.value)
    }
    
    onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    val updater = KotlinLspUpdater(this)
    updater.checkForUpdates(LSPProperties.kotlinLspVersion)
    
            
  }

  override fun onApplySystemBarInsets(insets: Insets) {
    _binding?.fragmentContainersParent?.setPadding(insets.left, 0, insets.right, insets.bottom)
  }

  private fun onScreenChanged(screen: Int?) {
    val previous = viewModel.previousScreen
    if (previous != -1) {
      val setAxisToX =
          (previous == SCREEN_TEMPLATE_LIST || previous == SCREEN_TEMPLATE_DETAILS) &&
              (screen == SCREEN_TEMPLATE_LIST || screen == SCREEN_TEMPLATE_DETAILS)

      val axis =
          if (setAxisToX) {
            MaterialSharedAxis.X
          } else {
            MaterialSharedAxis.Y
          }

      // 方向判定：返回键触发的切换一律是后退；否则看编号是否增大。
      //
      // 旧判据 `(screen - previous) == 1` 在 AI 页（SCREEN_AI=3）上会失效：
      // 主页(0) → AI(3) 差 3，会被误判成后退而播反向动画。编号是否增大对
      // 「主页→模板列表→详情」仍成立，对「主页↔AI」也成立，且不依赖编号连续。
      val isForward = if (viewModel.navigatingBack) false else (screen ?: 0) > previous

      val transition = MaterialSharedAxis(axis, isForward)
      transition.doOnEnd {
        viewModel.isTransitionInProgress = false
        onBackPressedCallback.isEnabled = viewModel.currentScreen.value != SCREEN_MAIN
      }

      viewModel.isTransitionInProgress = true
      TransitionManager.beginDelayedTransition(binding.root, transition)
    }

    val currentFragment =
        when (screen) {
          SCREEN_MAIN -> binding.main
          SCREEN_TEMPLATE_LIST ->
              com.tom.rv2ide.templates.AtcInterface().create(this)
          SCREEN_TEMPLATE_DETAILS -> binding.templateDetails
          SCREEN_AI -> binding.assistantPage
          else -> throw IllegalArgumentException("Invalid screen id: '$screen'")
        }

    for (fragment in
        arrayOf(
            binding.main,
            binding.templateList,
            binding.templateDetails,
            binding.assistantPage,
        )) {
      fragment.isVisible = fragment == currentFragment
    }
  }

  override fun bindLayout(): View {
    _binding = ActivityMainBinding.inflate(layoutInflater)
    return binding.root
  }

  private fun openLastProject() {
    binding.root.post { tryOpenLastProject() }
  }

  private fun tryOpenLastProject() {
    if (!GeneralPreferences.autoOpenProjects) {
      return
    }

    val openedProject = GeneralPreferences.lastOpenedProject
    if (GeneralPreferences.NO_OPENED_PROJECT == openedProject) {
      return
    }

    if (TextUtils.isEmpty(openedProject)) {
      app
      flashInfo(string.msg_opened_project_does_not_exist)
      return
    }

    val project = File(openedProject)
    if (!project.exists()) {
      flashInfo(string.msg_opened_project_does_not_exist)
      return
    }

    if (GeneralPreferences.confirmProjectOpen) {
      askProjectOpenPermission(project)
      return
    }

    openProject(project)
  }

  private fun askProjectOpenPermission(root: File) {
    val builder = DialogUtils.newMaterialDialogBuilder(this)
    builder.setTitle(string.title_confirm_open_project)
    builder.setMessage(getString(string.msg_confirm_open_project, root.absolutePath))
    builder.setCancelable(false)
    builder.setPositiveButton(string.yes) { _, _ -> openProject(root) }
    builder.setNegativeButton(string.no, null)
    builder.show()
  }

  internal fun openProject(root: File) {
    IProjectManager.getInstance().openProject(root)
    startActivity(Intent(this, EditorActivityKt::class.java))
  }

  override fun onDestroy() {
    tomIDEUpdater.cancelDownload()
    ITemplateProvider.getInstance().release()
    super.onDestroy()
    _binding = null
  }
}