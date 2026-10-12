/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.templates.android

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

object TemplateRegistry {
  private val templates = mutableListOf<Template>()

  init {
    // Register all templates here
    register(NoActivity())
    register(BasicActivity())
    register(EmptyActivity())
    register(ComposeEmptyActivity())
    register(BottomNavigationActivity())
    register(NavigationDrawerActivity())
    register(ResponsiveActivity())
    register(GameActivity())
    register(NativeCpp())
    register(LibGdx())
    register(QuickDevelop())
    register(ComposeMultiplatform())
    register(ReactNative())
    register(Flutter())
    // register(FullscreenActivity())
    // register(TabbedActivity())
    // register(ScrollingActivity())
    // register(EmptyComposeActivity())
    // register(BlankFragment())
    // register(ListFragment())
    // register(CppProject())
  }

  private fun register(template: Template) {
    templates.add(template)
  }

  /**
   * 全部模板，**跨平台模板排最前**。
   *
   * <p>排序放在这里而不是注册处：注册顺序表达的是「代码组织顺序」，而展示顺序是
   * 产品决策（跨平台是 ACS 的差异化能力，值得优先曝光）。两者分开后，将来调整
   * 展示顺序不必动注册表。
   *
   * <p>稳定排序：跨平台组内与其余组内都保持注册顺序。
   */
  fun getAllTemplates(): List<Template> =
      templates.sortedByDescending { if (it.isCrossPlatform) 1 else 0 }

  fun getTemplateByName(displayName: String): Template? {
    return templates.find { it.displayName == displayName }
  }
}
