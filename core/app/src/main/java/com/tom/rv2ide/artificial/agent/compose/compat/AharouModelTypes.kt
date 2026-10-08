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

/**
 * Aharou 模型域类型的**投影**（不是搬运——见各项说明）。
 *
 * <p>调研结论：`AIProviderConfig`（157 行多 Key 轮换/冷却/容器脚本）与 ACS 的
 * `ProviderConfig.java` 形状差异极大，整套搬运会把 Hilt/Room 假设带进来；
 * `ModelMetadata`（models.dev 全量元数据）ACS 完全没有对应物。
 * 这里只保留**被移植组件真正读到的字段**，接线任务用投影函数从 ACS 侧填充。
 */

/**
 * Aharou 推理强度枚举（`feature/agent/domain/model/ReasoningEffort.kt` 照搬）。
 *
 * <p>ACS 协议层用字符串档位（`AiBehaviorSettings.REASONING_AUTO/LOW/MEDIUM/HIGH`），
 * 接线时按名字映射；DEFAULT/NONE 两档在 ACS 无直接对应，保留枚举完整性由接线层决定。
 */
enum class ReasoningEffort {
  DEFAULT,
  NONE,
  LOW,
  MEDIUM,
  HIGH,
  XHIGH,
  MAX,
}

/**
 * 模型元数据的**最小投影**（Aharou `ModelMetadata` 83 行，只留 UI 标签用到的 4 个字段）。
 * null 表示未知——`ModelMetadataTags` 对 null/0 一律不渲染标签。
 */
data class ModelMetadata(
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = false,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
)

/**
 * Aharou `AgentMode` 三档（BUILD/PLAN/AUTO）照搬。
 *
 * <p>⚠️ 与 ACS `ChatMode`（CHAT/PLAN/AGENT/CONTROL）**形状不同**，不能互替：
 * 接线时写显式映射表，不做隐式推断。
 */
enum class AgentMode {
  BUILD,
  PLAN,
  AUTO,
}
