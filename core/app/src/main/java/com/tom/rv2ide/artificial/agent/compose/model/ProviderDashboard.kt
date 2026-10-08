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

package com.tom.rv2ide.artificial.agent.compose.model

/**
 * 自定义面板查询结果。
 */
data class ProviderDashboardResult(
    val card: AdaptiveCardRoot = AdaptiveCardRoot(),
    val rawOutput: String = ""
)

/**
 * 自定义面板状态。
 *
 * <p>照搬自 Aharou `feature/settings/domain/model/ProviderDashboard.kt`（纯数据，
 * 无依赖）。填充该状态的 Runner（Aharou `ProviderDashboardRunner`，注入
 * CommandEngine/ContainerInstaller，在 Linux 容器里跑用户脚本）**不在本次移植
 * 范围内**——ACS 没有对应容器引擎，由未来接线任务决定如何产出该状态。
 */
sealed interface ProviderDashboardState {
    data object Idle : ProviderDashboardState
    data object Loading : ProviderDashboardState
    data class Success(val result: ProviderDashboardResult) : ProviderDashboardState
    data class Error(val message: String, val rawOutput: String = "") : ProviderDashboardState
}
