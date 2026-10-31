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
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.assistant.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.assistant.engine.ModelSettings
import com.assistant.engine.ProviderPresets
import com.assistant.ui.AssistantModelSettingsScreen
import com.assistant.ui.ProviderOption
import com.assistant.ui.components.independent.ProviderProtocol

/**
 * 模型设置页的宿主。
 *
 * <p><b>为什么这一层这么薄</b>：界面本身是 [AssistantModelSettingsScreen]（Compose，
 * 复用 Aharou 移植过来的设置页组件）。Activity 只负责三件事：把引擎的
 * [ProviderPresets] 投影成渲染层认得的 [ProviderOption]、读写 [ModelSettings]、
 * 保存后跳转对话页。界面逻辑一行都不在这里——那样才能单测、也才能被别的宿主复用。
 */
class SettingsActivity : ComponentActivity() {

  private lateinit var settings: ModelSettings

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    settings = ModelSettings(this)

    // 引擎的预设表 → 渲染层的投影。协议类型也一起映射：
    // 服务商 logo 与「是否支持原生工具」都靠它区分。
    val providerOptions: List<ProviderOption> =
        ProviderPresets.all().map { preset ->
          ProviderOption(
              id = preset.id,
              label = preset.label,
              models = preset.models,
              requiresApiKey = preset.isRequiresApiKey,
              protocol =
                  if (preset.protocolType ==
                      com.tom.rv2ide.ai.protocol.ModelProtocolType.ANTHROPIC_MESSAGES) {
                    ProviderProtocol.ANTHROPIC_MESSAGES
                  } else {
                    ProviderProtocol.OPENAI_COMPATIBLE
                  },
          )
        }

    val view =
        ComposeView(this).apply {
          setContent {
            // 这些状态每次进入页面从持久化读一次初值；改动立刻写回，
            // 因此「切换服务商 → Key 输入框跟着换」是天然正确的。
            var providerId by remember { mutableStateOf(settings.providerId()) }
            var model by remember { mutableStateOf(settings.modelId()) }
            var apiKey by remember { mutableStateOf(settings.apiKey(providerId)) }
            var baseUrl by remember { mutableStateOf(settings.customBaseUrl(providerId)) }

            // 状态行要实时反映「还差什么」，因此每次重组都算一次。
            val problem =
                when {
                  baseUrl.isEmpty() &&
                      ProviderPresets.find(providerId)?.baseUrl.isNullOrEmpty() -> "请填写 API 地址"
                  model.isEmpty() -> "请选择模型"
                  ProviderPresets.find(providerId)?.isRequiresApiKey == true && apiKey.isEmpty() ->
                      "请填写 ${ProviderPresets.labelFor(providerId)} 的 API Key"
                  else -> ""
                }

            AssistantModelSettingsScreen(
                providers = providerOptions,
                selectedProviderId = providerId,
                selectedModel = model,
                apiKey = apiKey,
                customBaseUrl = baseUrl,
                readinessProblem = problem,
                onSelectProvider = { id ->
                  providerId = id
                  settings.setProviderId(id)
                  // 换服务商：模型与凭据都跟着换到该服务商自己的记录。
                  val preset = ProviderPresets.find(id)
                  model = preset?.defaultModel.orEmpty()
                  settings.setModelId(model)
                  apiKey = settings.apiKey(id)
                  baseUrl = settings.customBaseUrl(id)
                },
                onSelectModel = { selected ->
                  model = selected
                  settings.setModelId(selected)
                },
                onApiKeyChange = {
                  apiKey = it
                  settings.setApiKey(providerId, it)
                },
                onBaseUrlChange = {
                  baseUrl = it
                  settings.setCustomBaseUrl(providerId, it)
                },
                onSave = {
                  if (settings.isReady()) {
                    Toast.makeText(this@SettingsActivity, "配置已保存", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@SettingsActivity, ChatActivity::class.java))
                    finish()
                  } else {
                    Toast.makeText(
                            this@SettingsActivity,
                            settings.readinessProblem().ifEmpty { "配置不完整" },
                            Toast.LENGTH_LONG)
                        .show()
                  }
                },
            )
          }
        }

    setContentView(view)
  }
}
