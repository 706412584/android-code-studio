/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.manage

import com.tom.rv2ide.ai.protocol.ModelProtocolType
import com.tom.rv2ide.artificial.agent.ProviderConfig

/**
 * 服务商管理页的渲染层条目。
 *
 * <p>只做「从 [ProviderConfig] 投影 + 回写」的薄转换，不复制业务逻辑——
 * 槽位顺序、上下文后缀解析都留在 [ProviderConfig] 与 `ContextSizeParser`。
 * 渲染层（Compose）只认这个数据类，从而不直接依赖 ACS 的存储类型。
 */
data class ManageProviderItem(
    val id: String,
    val label: String,
    val protocol: ModelProtocolType,
    val baseUrl: String,
    val apiKey: String,
    /** 4 槽位模型名，顺序同 ProviderConfig.SLOT_ORDER（main/haiku/sonnet/opus）。 */
    val slots: List<String>,
    /** 是否可用于发请求（baseUrl 与主模型都非空）。 */
    val usable: Boolean,
) {
  val mainModel: String get() = slots.firstOrNull().orEmpty()

  /** 回写成存储类型。id 为空表示新建（沿用原行为：新增时 id 取名称）。 */
  fun toConfig(): ProviderConfig =
      ProviderConfig(
          id.ifEmpty { label },
          label,
          protocol,
          baseUrl,
          apiKey,
          slots.toTypedArray(),
      )

  companion object {
    fun of(config: ProviderConfig): ManageProviderItem =
        ManageProviderItem(
            id = config.getId(),
            label = config.getLabel(),
            protocol = config.getProtocolType(),
            baseUrl = config.getBaseUrl(),
            apiKey = config.getApiKey(),
            slots = config.getSlotModels().toList(),
            usable = config.isUsable(),
        )

    /**
     * 新建时的草稿（可带预设预填的连接信息）。
     *
     * <p>[id] 可为空（完全自定义：保存时 `toConfig()` 回退用 label 作 id）。
     * **从预设新建时应传预设 id**：预设 id（如 `deepseek`）是它在本仓库的稳定标识，
     * 且是「当前服务商」偏好里存的值；若留空而回退成 label（如 `DeepSeek`），
     * 后续按 id 反查记录会失配，导致密钥读不出来（实测回归）。
     */
    fun draft(
        id: String = "",
        label: String = "",
        baseUrl: String = "",
        apiKey: String = "",
        slots: List<String> = listOf("", "", "", ""),
        protocol: ModelProtocolType = ModelProtocolType.OPENAI_COMPATIBLE,
    ): ManageProviderItem =
        ManageProviderItem(
            id = id,
            label = label,
            protocol = protocol,
            baseUrl = baseUrl,
            apiKey = apiKey,
            slots = slots,
            usable = baseUrl.isNotBlank() && slots.firstOrNull().orEmpty().isNotBlank(),
        )
  }
}
