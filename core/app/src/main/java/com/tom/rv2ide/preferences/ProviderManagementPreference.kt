/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.preferences

import android.content.Context
import androidx.preference.Preference
import com.tom.rv2ide.artificial.agent.ProviderConfigStore
import com.tom.rv2ide.resources.R
import kotlinx.parcelize.Parcelize

/**
 * 服务商管理入口。
 *
 * <p><b>为什么不再是「一排固定密钥输入框」</b>：旧实现为 6 个服务商各写死一个偏好键，
 * 其余 7 个 OpenAI 兼容服务商共用一个键。后果是「配了 GLM 的密钥、切到 Kimi 时
 * 拿着 GLM 的密钥去请求」，而且两个服务商无法同时配置。
 *
 * <p>改成「服务商记录」后密钥随记录走：每个服务商一份，互不干扰。
 * 预设表退化为「快速填充模板」，只在新记录时给出默认 baseUrl 与模型。
 */
@Parcelize
internal class ProviderManagementPreference(
    override val key: String = "ai_agent_providers",
    override val title: Int = R.string.ai_agent_providers_title,
    override val summary: Int? = R.string.ai_agent_providers_summary,
) : BasePreference() {

  override fun onCreatePreference(context: Context): Preference {
    return Preference(context).apply {
      key = this@ProviderManagementPreference.key
      title = context.getString(this@ProviderManagementPreference.title)
      summary = summaryText(context)
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    // 打开独立的服务商管理**页面**（取代原先「列表弹窗 → 动作弹窗 → 长表单弹窗」三层链）。
    // 页面关闭后本条目摘要会在下次渲染时重读（onCreateView 走 summaryText）。
    com.tom.rv2ide.artificial.agent.manage.AssistantManageActivity.start(
        context,
        com.tom.rv2ide.artificial.agent.manage.AssistantManageActivity.SECTION_PROVIDERS)
    return true
  }

  /** 副标题给出「共几个 / 几个可用」，让用户不必点进去就知道状态。 */
  private fun summaryText(context: Context): String {
    val records = ProviderConfigStore(context).load()
    val usable = records.count { it.isUsable() }
    return "${context.getString(R.string.ai_agent_providers_summary)} · $usable/${records.size}"
  }

}
