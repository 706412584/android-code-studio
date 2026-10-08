/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from Aharou (https://github.com/520huxiangli/Aharou),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

package com.tom.rv2ide.artificial.agent.compose.compat

import androidx.compose.ui.Modifier

/**
 * Aharou 新手引导的挂点 no-op 投影（源自 `feature/onboarding/presentation/OnboardingModifier.kt`
 * 的 `Modifier.onboardingTarget(step)`）。
 *
 * <p><b>为什么是 no-op</b>：调研确认移植范围内只用到这一个挂点 + 3 个 step 常量，而
 * `SpotlightOverlay`（463 行全屏引导层） ACS 不需要——ACS 已有自己的 `OnboardingActivity`，
 * 叠加第二套引导只会打架。挂点保留成 no-op 使移植组件的调用点逐字保留：将来若真要接
 * 引导层，只需在本文件里把标记写进一个 CompositionLocal，调用点零改动。
 */
enum class OnboardingStep {
  OPEN_SIDEBAR,
  SELECT_MODEL,
  SET_REASONING,
}

fun Modifier.onboardingTarget(step: OnboardingStep): Modifier = this
