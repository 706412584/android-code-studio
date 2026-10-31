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
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.assistant.engine.ModelSettings
import com.assistant.engine.ProviderPresets
import com.assistant.ui.AssistantModelSettingsScreen
import com.assistant.ui.AssistantSettingsScreen
import com.assistant.ui.ProviderOption
import com.assistant.ui.components.independent.ProviderProtocol

/**
 * 设置页宿主。
 *
 * <p><b>两级结构</b>（与参考项目 Aharou 一致）：
 * 首页是分组清单（模型 / 对话 / 关于），点「模型与凭据」进子页。
 * 之前只有一页模型配置——用户进「设置」看到的是服务商清单，
 * 找不到权限、外观、关于，会以为这个 App 没有设置。
 *
 * <p>Activity 只做三件事：把引擎的 [ProviderPresets] 投影成渲染层的 [ProviderOption]、
 * 读写 [ModelSettings]、保存后跳转对话页。界面逻辑全在渲染层。
 */
class SettingsActivity : ComponentActivity() {

  private lateinit var settings: ModelSettings

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    settings = ModelSettings(this)

    // 引擎的预设表 → 渲染层的投影。
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
            /** 当前是否在「模型与凭据」子页。 */
            var showModelSettings by remember { mutableStateOf(false) }

            // 子页按返回键回首页，而不是退出 Activity——否则用户点返回会直接
            // 离开设置，得重新进来才能改别的项。
            BackHandler(enabled = showModelSettings) { showModelSettings = false }

            if (!showModelSettings) {
              AssistantSettingsScreen(
                  modelSummary = modelSummary(),
                  readinessProblem = settings.readinessProblem(),
                  onOpenModelSettings = { showModelSettings = true },
                  onBack = { finish() },
              )
            } else {
              // 这些状态每次进入子页从持久化读一次初值；改动立刻写回，
              // 因此「切换服务商 → Key 输入框跟着换」是天然正确的。
              var providerId by remember { mutableStateOf(settings.providerId()) }
              var model by remember { mutableStateOf(settings.modelId()) }
              var apiKey by remember { mutableStateOf(settings.apiKey(providerId)) }
              var baseUrl by remember { mutableStateOf(settings.customBaseUrl(providerId)) }

              val problem = settings.readinessProblem()

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
                    // 只保存 + 回首页，不强制跳对话页：用户可能是来改某个配置的，
                    // 改完还想继续看别的设置。想开对话从首页或返回键回对话页即可。
                    if (settings.isReady()) {
                      Toast.makeText(this@SettingsActivity, "配置已保存", Toast.LENGTH_SHORT).show()
                      showModelSettings = false
                    } else {
                      Toast.makeText(
                              this@SettingsActivity,
                              settings.readinessProblem().ifEmpty { "配置不完整" },
                              Toast.LENGTH_LONG)
                          .show()
                    }
                  },
                  // 子页顶栏的返回键回到设置首页。
                  onBack = { showModelSettings = false },
              )
            }
          }
        }

    setContentView(view)
  }

  /** 首页「模型与凭据」行的副标题。 */
  private fun modelSummary(): String {
    val label = ProviderPresets.labelFor(settings.providerId())
    val model = settings.modelId()
    return if (model.isEmpty()) label else "$label · $model"
  }
}
