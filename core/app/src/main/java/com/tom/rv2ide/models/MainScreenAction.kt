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

package com.tom.rv2ide.models

import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tom.rv2ide.resources.R
import java.util.Collections

/**
 * An action button shown on the main screen.
 *
 * @author Akash Yadav
 */
data class MainScreenAction
@JvmOverloads
constructor(
    val id: Int,
    @StringRes val text: Int,
    @DrawableRes val icon: Int,
    var onClick: ((MainScreenAction, View) -> Unit)? = null,
    var onLongClick: ((MainScreenAction, View) -> Boolean)? = null,
) {

  companion object {

    const val ACTION_CREATE_PROJECT = 0
    const val ACTION_OPEN_PROJECT = 1
    const val ACTION_CLONE_REPO = 2
    const val ACTION_OPEN_TERMINAL = 3
    const val ACTION_PREFERENCES = 4
    const val ACTION_DONATE = 5
    const val ACTION_DOCS = 6
    const val ACTION_AI_ASSISTANT = 7

    /** Get all main screen actions. */
    @JvmStatic
    fun all(): List<MainScreenAction> {
      return mutableListOf<MainScreenAction>().apply {
        val createProject =
            MainScreenAction(ACTION_CREATE_PROJECT, R.string.create_project, R.drawable.ic_add)

        val openProject =
            MainScreenAction(
                ACTION_OPEN_PROJECT,
                R.string.msg_open_existing_project,
                R.drawable.ic_folder,
            )

        val cloneGitRepository =
            MainScreenAction(ACTION_CLONE_REPO, R.string.git_clone_repo, R.drawable.ic_git)

        val openTerminal =
            MainScreenAction(ACTION_OPEN_TERMINAL, R.string.title_terminal, R.drawable.ic_terminal)

        val preferences =
            MainScreenAction(ACTION_PREFERENCES, R.string.msg_preferences, R.drawable.ic_settings)

        val donate = MainScreenAction(ACTION_DONATE, R.string.btn_idecfg, R.drawable.ic_cfg_main)

        val docs = MainScreenAction(ACTION_DOCS, R.string.btn_docs, R.drawable.ic_docs)

        // AI 助手页入口。放在动作列表最前：它是「新建 / 打开项目」之外的第三种
        // 进入工作流的方式（先问再建），且是主页上唯一进入应用内页面的动作。
        //
        // 复用主页动作列表而不是往工具栏加图标：主页动作列表本就是「主页能做的事」
        // 的清单，页面导航在仓库里也只有 `viewModel.setScreen` 一条路。工具栏图标需要
        // 先给 MainActivity 引入 options menu（它当前没有 setSupportActionBar），
        // 那会改动全局工具栏外观，收益只是语义更好，风险不划算。
        val aiAssistant =
            MainScreenAction(
                ACTION_AI_ASSISTANT,
                R.string.ai_assistant_title,
                // 图标在 app 模块（core/app/src/main/res/drawable/ic_ai_agent.xml），
                // 而本文件用的是 resources 模块的 R（见文件头的 import）。两个 R 的简单名
                // 相同，无法同时 import，因此这里全限定到 app 的 R。
                com.tom.rv2ide.R.drawable.ic_ai_agent,
            )

        Collections.addAll(
            this,
            aiAssistant,
            createProject,
            openProject,
            cloneGitRepository,
            openTerminal,
            preferences,
            donate,
            docs,
        )
      }
    }
  }
}
