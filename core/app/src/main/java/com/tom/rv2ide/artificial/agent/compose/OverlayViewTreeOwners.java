/*
 * This file is part of AndroidCodeStudio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.compose;

import android.view.View;
import androidx.lifecycle.LifecycleOwner;
import androidx.savedstate.SavedStateRegistryOwner;

/**
 * 给「没有 Activity 的视图树」（系统悬浮窗）补上 Compose 需要的 owner。
 *
 * <p><b>为什么存在</b>：Compose 的 {@code ComposeView} 在附加到窗口时会沿视图树向上找
 * {@code ViewTreeLifecycleOwner} 来创建 Recomposer（WindowRecomposerFactory.LifecycleAware）。
 * Activity / Fragment 宿主自动有它；悬浮窗挂在 WindowManager 下什么都没有，不手动挂上就崩：
 * {@code IllegalStateException: ViewTreeLifecycleOwner not found}（实机复现）。
 *
 * <p><b>为什么是 Java 而不是 Kotlin</b>：{@code ViewTreeLifecycleOwner.set} /
 * {@code ViewTreeSavedStateRegistryOwner.set} 的 Kotlin 门面在部分组合版本下
 * Kotlin 编译器解析不到（metadata 兼容问题，实测 Unresolved reference），
 * 而 Java 直接调静态方法不受影响。工具只有两个转发方法，成本可忽略。
 */
public final class OverlayViewTreeOwners {

  private OverlayViewTreeOwners() {}

  /** 把 owner 挂到视图上；ComposeView 附加后沿树向上就能找到。 */
  public static void install(View view, LifecycleOwner owner, SavedStateRegistryOwner registry) {
    androidx.lifecycle.ViewTreeLifecycleOwner.set(view, owner);
    androidx.savedstate.ViewTreeSavedStateRegistryOwner.set(view, registry);
  }
}
