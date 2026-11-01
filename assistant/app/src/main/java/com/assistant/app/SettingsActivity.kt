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
import com.assistant.ui.AssistantPrefs
import com.assistant.ui.AssistantSettingsState
import com.assistant.ui.AssistantUiStyleStore
import com.assistant.ui.ProviderOption
import com.assistant.ui.components.independent.ProviderProtocol
import com.assistant.ui.theme.AppThemePreset
import com.assistant.ui.theme.AssistantThemePrefs
import java.io.File

/**
 * 设置页宿主。
 *
 * <p><b>两级结构</b>（与参考项目 Aharou 一致）：首页是分组清单
 * （模型 / 外观 / 对话 / 关于），点「模型与凭据」进子页。
 *
 * <p>Activity 只做三件事：把引擎的 [ProviderPresets] 投影成渲染层的 [ProviderOption]、
 * 读写各项偏好、处理子页导航。界面逻辑全在渲染层。
 */
class SettingsActivity : ComponentActivity() {

  private lateinit var settings: ModelSettings
  private lateinit var styleStore: AssistantUiStyleStore
  private lateinit var permissionPrefs: PermissionPrefs
  private lateinit var memoryPrefs: MemoryPrefs
  private lateinit var mediaSettings: com.assistant.engine.MediaSettings
  private lateinit var mcpSettings: com.assistant.engine.McpSettings

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    settings = ModelSettings(this)
    styleStore = AssistantUiStyleStore(this)
    permissionPrefs = PermissionPrefs(this)
    memoryPrefs = MemoryPrefs(this)
    mediaSettings = com.assistant.engine.MediaSettings(this)
    mcpSettings = com.assistant.engine.McpSettings(this)

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
            // 页面路由：主页 / 模型与凭据 / 提示词模板 / 记忆 / Skill。
            // 用字符串而不是多个布尔：多个布尔会出现「两个都为 true」的非法状态。
            var route by remember { mutableStateOf("home") }
            // 偏好不是 Compose 状态，改完要让首页重新求值——用这个计数当触发器。
            var recomposeKey by remember { mutableStateOf(0) }

            // 读一次 recomposeKey，**必须在路由判断之前**：
            // Compose 只让「读过该状态的作用域」重组。原先放在 home 分支里时，
            // 子页（服务商/记忆/…）改动数据后 recomposeKey++ 不会让子页重组，
            // 表现就是「从预设新建后列表不刷新」（用户实测反馈）。
            // 放在最外层，任何一页改动都会让整个 setContent 重新求值。
            @Suppress("UNUSED_EXPRESSION") recomposeKey

            // 子页按返回键回首页，而不是退出 Activity。
            BackHandler(enabled = route != "home") { route = "home" }

            if (route == "home") {
              // 每次重组都从持久化读一次：设置项改动后立刻反映在界面上，
              // 不需要额外维护一份内存状态（偏好读写很轻）。
              val state =
                  AssistantSettingsState(
                      modelSummary = modelSummary(),
                      readinessProblem = settings.readinessProblem(),
                      themePresetId =
                          AssistantThemePrefs.presetId(this@SettingsActivity)
                              ?: AppThemePreset.DEFAULT.id,
                      textSize = styleStore.textSize,
                      permissionMode = permissionPrefs.mode(),
                      chatMode = AssistantPrefs.chatMode(this@SettingsActivity),
                      thinkingAutoCollapse =
                          AssistantPrefs.thinkingAutoCollapse(this@SettingsActivity),
                      toolStatusBar = AssistantPrefs.toolStatusBar(this@SettingsActivity),
                      cardScale = AssistantPrefs.cardScale(this@SettingsActivity),
                      textColor = AssistantPrefs.textColor(this@SettingsActivity),
                      memoryEnabled = memoryPrefs.enabled(),
                      skillCount = countSkills(),
                      memoryCount = countMemory(),
                      // 「模型与凭据」入口已移除：它和服务商页是同一件事
                      // （服务商记录里就含密钥），两个入口会让用户不知道该去哪配。
                      onOpenModelSettings = { route = "providers" },
                      onThemePresetChange = { id ->
                        AssistantThemePrefs.setPresetId(this@SettingsActivity, id)
                        // 触发重组：偏好本身不是 Compose 状态，改完要让页面重画。
                        recomposeKey++
                      },
                      onTextSizeChange = { sp ->
                        styleStore.textSize = sp
                        recomposeKey++
                      },
                      onPermissionModeChange = { mode ->
                        permissionPrefs.setMode(mode)
                        recomposeKey++
                      },
                      onChatModeChange = { mode ->
                        AssistantPrefs.setChatMode(this@SettingsActivity, mode)
                        recomposeKey++
                      },
                      onThinkingAutoCollapseChange = { enabled ->
                        AssistantPrefs.setThinkingAutoCollapse(this@SettingsActivity, enabled)
                        recomposeKey++
                      },
                      onToolStatusBarChange = { enabled ->
                        AssistantPrefs.setToolStatusBar(this@SettingsActivity, enabled)
                        recomposeKey++
                      },
                      onCardScaleChange = { scale ->
                        AssistantPrefs.setCardScale(this@SettingsActivity, scale)
                        recomposeKey++
                      },
                      onTextColorChange = { argb ->
                        AssistantPrefs.setTextColor(this@SettingsActivity, argb)
                        recomposeKey++
                      },
                      onMemoryEnabledChange = { enabled ->
                        memoryPrefs.setEnabled(enabled)
                        recomposeKey++
                      },
                      onOpenPromptTemplates = { route = "prompts" },
                      onOpenMemories = { route = "memories" },
                      onOpenSkills = { route = "skills" },
                      onOpenProviders = { route = "providers" },
                      onOpenMcp = { route = "mcp" },
                      onOpenModelManage = { route = "modelManage" },
                      providerCount = settings.providerRecords().all().size,
                      mcpServerCount = mcpSettings.all().size,
                      configuredSceneCount =
                          listOf(
                                  mediaSettings.imageProviderId(),
                                  mediaSettings.videoProviderId(),
                              )
                              .count { it.isNotEmpty() },
                      onBack = { finish() },
                  )
              AssistantSettingsScreen(state = state)
            } else if (route == "prompts") {
              // 提示词模板：8 个内核模板，可编辑/重置。
              val store = com.tom.rv2ide.ai.agent.prompt.PromptTemplateStore.defaults()
              // 用户自定义存在偏好里；resolve 会自动回退到默认。
              val custom =
                  object : com.tom.rv2ide.ai.agent.prompt.PromptTemplateStore {
                    override fun read(templateId: String): String =
                        AssistantPrefs.promptTemplate(this@SettingsActivity, templateId)

                    override fun write(templateId: String, content: String) =
                        AssistantPrefs.setPromptTemplate(this@SettingsActivity, templateId, content)

                    override fun reset(templateId: String) =
                        AssistantPrefs.setPromptTemplate(this@SettingsActivity, templateId, "")

                    override fun readAll(): Map<String, String> =
                        TEMPLATE_IDS.associateWith {
                          AssistantPrefs.promptTemplate(this@SettingsActivity, it)
                        }
                  }
              val items =
                  TEMPLATE_LABELS.map { (id, label) ->
                    com.assistant.ui.PromptTemplateItem(
                        id = id,
                        label = label,
                        content = custom.resolve(id),
                        customized = AssistantPrefs.isPromptTemplateCustomized(this@SettingsActivity, id),
                    )
                  }
              com.assistant.ui.AssistantPromptTemplatesScreen(
                  templates = items,
                  onEdit = { id, text ->
                    custom.write(id, text)
                    route = "home"
                  },
                  onReset = { id -> custom.reset(id) },
                  onBack = { route = "home" },
              )
            } else if (route == "memories") {
              val store = memoryStore()
              val items =
                  store.all().map { entry ->
                    com.assistant.ui.MemoryItem(
                        id = entry.id,
                        text = entry.text,
                        createdAt = entry.createdAt,
                    )
                  }
              com.assistant.ui.AssistantMemoriesScreen(
                  memories = items,
                  onDelete = { id ->
                    store.remove(id)
                    route = "home"
                  },
                  onClearAll = {
                    store.clear()
                    route = "home"
                  },
                  onBack = { route = "home" },
              )
            } else if (route == "skills") {
              val registry = com.tom.rv2ide.ai.tool.skill.SkillRegistry.load(File(filesDir, "skills"))
              val items =
                  registry.all().map { skill ->
                    com.assistant.ui.SkillItem(
                        name = skill.name,
                        description = skill.description,
                        usable = skill.isUsable,
                    )
                  }
              com.assistant.ui.AssistantSkillsScreen(
                  skills = items,
                  skillRoot = File(filesDir, "skills").absolutePath,
                  onBack = { route = "home" },
              )
            } else if (route == "providers") {
              val records = settings.providerRecords()
              val items =
                  records.all().map { record ->
                    com.assistant.ui.ProviderRecordItem(
                        id = record.id,
                        label = record.label,
                        baseUrl = record.baseUrl,
                        apiKey = record.apiKey,
                        slots = record.slotModels.toList(),
                        protocol = record.protocolType.toUi(),
                        usable = record.isUsable,
                    )
                  }
              com.assistant.ui.AssistantProvidersScreen(
                  records = items,
                  presets =
                      ProviderPresets.all().map { preset ->
                        com.assistant.ui.PresetTemplate(
                            id = preset.id,
                            label = preset.label,
                            models = preset.models,
                        )
                      },
                  onAddFromPreset = { presetId ->
                    ProviderPresets.find(presetId)?.let { records.addFromPreset(it) }
                    recomposeKey++
                  },
                  onAddCustom = { /* 表单里保存时走 onUpdate（id 为空） */ },
                  onUpdate = { item ->
                    if (item.id.isEmpty()) {
                      records.addCustom(
                          item.label,
                          item.baseUrl,
                          item.apiKey,
                          item.protocol.toEngine(),
                          item.model,
                      )
                    } else {
                      records.update(
                          item.id,
                          item.label,
                          item.baseUrl,
                          item.apiKey,
                          item.protocol.toEngine(),
                          arrayOf(item.model, "", "", ""),
                      )
                    }
                    recomposeKey++
                  },
                  onDelete = { id ->
                    records.remove(id)
                    recomposeKey++
                  },
                  // 拉取模型清单。**必须切线程**：ui 模块同步调用本回调，
                  // 网络请求跑在主线程会 ANR。
                  onFetchModels = { baseUrl, apiKey, protocol ->
                    val engineProtocol =
                        if (protocol ==
                            com.assistant.ui.components.independent.ProviderProtocol
                                .ANTHROPIC_MESSAGES) {
                          com.tom.rv2ide.ai.protocol.ModelProtocolType.ANTHROPIC_MESSAGES
                        } else {
                          com.tom.rv2ide.ai.protocol.ModelProtocolType.OPENAI_COMPATIBLE
                        }
                    // 用单线程池 + Future.get() 同步等结果：**必须切线程**，
                    // 因为 ui 模块的回调是同步的，网络请求跑主线程会 ANR。
                    // 用标准库而不是 Guava（仓库里没有 guava 依赖）。
                    val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
                    try {
                      pool.submit<Pair<List<String>, String>> {
                            val r =
                                com.assistant.engine.ModelCatalog.fetch(
                                    baseUrl, apiKey, engineProtocol)
                            r.models to r.error
                          }
                          .get()
                    } catch (e: Exception) {
                      emptyList<String>() to ("拉取失败：" + (e.message ?: "未知错误"))
                    } finally {
                      pool.shutdown()
                    }
                  },
                  onBack = { route = "home" },
              )
            } else if (route == "mcp") {
              val items =
                  mcpSettings.all().map { server ->
                    com.assistant.ui.McpServerItem(
                        id = server.id,
                        label = server.label,
                        target = server.target,
                        transport = server.transport,
                        enabled = server.enabled,
                    )
                  }
              com.assistant.ui.AssistantMcpScreen(
                  servers = items,
                  onAdd = { label, target, transport ->
                    mcpSettings.add(label, target, transport)
                    recomposeKey++
                  },
                  onUpdate = { id, label, target, transport, enabled ->
                    mcpSettings.update(id, label, target, transport, enabled)
                    recomposeKey++
                  },
                  onDelete = { id ->
                    mcpSettings.remove(id)
                    recomposeKey++
                  },
                  onBack = { route = "home" },
              )
            } else if (route == "modelManage") {
              val providerItems =
                  settings.providerRecords().all().map { record ->
                    com.assistant.ui.ProviderRecordItem(
                        id = record.id,
                        label = record.label,
                        baseUrl = record.baseUrl,
                        apiKey = record.apiKey,
                        slots = record.slotModels.toList(),
                        protocol = record.protocolType.toUi(),
                        usable = record.isUsable,
                    )
                  }
              com.assistant.ui.AssistantModelManageScreen(
                  providers = providerItems,
                  selections =
                      mapOf(
                          "chat" to (settings.providerId() to settings.modelId()),
                          "image" to
                              (mediaSettings.imageProviderId() to mediaSettings.imageModel()),
                          "video" to
                              (mediaSettings.videoProviderId() to mediaSettings.videoModel()),
                      ),
                  onSelect = { sceneKey, providerId, model ->
                    when (sceneKey) {
                      "chat" -> {
                        settings.setProviderId(providerId)
                        settings.setModelId(model)
                      }
                      "image" ->
                          mediaSettings.setImage(providerId, model, mediaSettings.imageOutputDir())
                      "video" ->
                          mediaSettings.setVideo(providerId, model, mediaSettings.videoOutputDir())
                    }
                    recomposeKey++
                  },
                  onClear = { sceneKey ->
                    when (sceneKey) {
                      "image" -> mediaSettings.clearImage()
                      "video" -> mediaSettings.clearVideo()
                    }
                    recomposeKey++
                  },
                  onBack = { route = "home" },
              )
            } else {
              var providerId by remember { mutableStateOf(settings.providerId()) }
              var model by remember { mutableStateOf(settings.modelId()) }
              var apiKey by remember { mutableStateOf(settings.apiKey(providerId)) }
              var baseUrl by remember { mutableStateOf(settings.customBaseUrl(providerId)) }

              AssistantModelSettingsScreen(
                  providers = providerOptions,
                  selectedProviderId = providerId,
                  selectedModel = model,
                  apiKey = apiKey,
                  customBaseUrl = baseUrl,
                  readinessProblem = settings.readinessProblem(),
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
                    if (settings.isReady()) {
                      Toast.makeText(this@SettingsActivity, "配置已保存", Toast.LENGTH_SHORT).show()
                      route = "home"
                    } else {
                      Toast.makeText(
                              this@SettingsActivity,
                              settings.readinessProblem().ifEmpty { "配置不完整" },
                              Toast.LENGTH_LONG)
                          .show()
                    }
                  },
                  onBack = { route = "home" },
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

  /** 长期记忆存储（与 ConversationRunner 用同一个文件）。 */
  private fun memoryStore() =
      com.tom.rv2ide.ai.tool.memory.MemoryStore(File(filesDir, "memory/memory.jsonl"))

  private fun countSkills(): Int = File(filesDir, "skills").listFiles()?.count { it.isDirectory } ?: 0

  private fun countMemory(): Int {
    val file = File(filesDir, "memory/memory.jsonl")
    if (!file.isFile) return 0
    return try {
      file.useLines { lines -> lines.count { it.isNotBlank() } }
    } catch (e: Exception) {
      0
    }
  }

}

/** 引擎的协议类型 → 渲染层的协议枚举。 */
private fun com.tom.rv2ide.ai.protocol.ModelProtocolType.toUi(): ProviderProtocol =
    if (this == com.tom.rv2ide.ai.protocol.ModelProtocolType.ANTHROPIC_MESSAGES) {
      ProviderProtocol.ANTHROPIC_MESSAGES
    } else {
      ProviderProtocol.OPENAI_COMPATIBLE
    }

/** 渲染层的协议枚举 → 引擎的协议类型。 */
private fun ProviderProtocol.toEngine(): com.tom.rv2ide.ai.protocol.ModelProtocolType =
    if (this == ProviderProtocol.ANTHROPIC_MESSAGES) {
      com.tom.rv2ide.ai.protocol.ModelProtocolType.ANTHROPIC_MESSAGES
    } else {
      com.tom.rv2ide.ai.protocol.ModelProtocolType.OPENAI_COMPATIBLE
    }

/** 内核的 8 个模板 id（与 PromptTemplates 的常量一一对应）。 */
private val TEMPLATE_IDS =
    listOf(
        "systemPrompt",
        "workspaceContext",
        "toolsContext",
        "toolCallFormat",
        "todoSection",
        "guidance",
        "chatModeAgent",
        "notes",
    )

/** 模板 id → 界面显示名。 */
private val TEMPLATE_LABELS: List<Pair<String, String>> =
    listOf(
        "systemPrompt" to "主系统提示词",
        "workspaceContext" to "工作区上下文",
        "toolsContext" to "工具清单段落",
        "toolCallFormat" to "文本工具调用格式",
        "todoSection" to "待办清单段落",
        "guidance" to "工作准则",
        "chatModeAgent" to "执行模式段落",
        "notes" to "收尾注意事项",
    )

/** 权限模式偏好。 */
class PermissionPrefs(context: android.content.Context) {
  private val prefs =
      context.applicationContext.getSharedPreferences("ai_agent_tools", android.content.Context.MODE_PRIVATE)

  fun mode(): String = prefs.getString(KEY, "confirm") ?: "confirm"

  fun setMode(mode: String) = prefs.edit().putString(KEY, mode).apply()

  private companion object {
    const val KEY = "permission_mode"
  }
}

/** 长期记忆开关偏好。 */
class MemoryPrefs(context: android.content.Context) {
  private val prefs =
      context.applicationContext.getSharedPreferences("ai_agent_tools", android.content.Context.MODE_PRIVATE)

  fun enabled(): Boolean = prefs.getBoolean(KEY, true)

  fun setEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY, enabled).apply()

  private companion object {
    const val KEY = "memory_enabled"
  }
}
