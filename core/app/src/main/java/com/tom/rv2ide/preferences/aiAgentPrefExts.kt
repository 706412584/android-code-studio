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

package com.tom.rv2ide.preferences

import android.content.Context
import androidx.preference.Preference
import com.tom.rv2ide.R
import com.tom.rv2ide.preferences.internal.prefManager
import com.tom.rv2ide.resources.R.string
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/** * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null */
@Parcelize
class AIAgentPreferencesScreen(
    override val key: String = "idepref_ai_agent",
    override val title: Int = string.ai_agent_title,
    override val summary: Int? = string.ai_agent_description,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(AIAgentConfig())
  }
}

@Parcelize
private class AIAgentConfig(
    override val key: String = "idepref_ai_agent_config",
    override val title: Int = string.ai_agent_title,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

  @IgnoredOnParcel private var geminiApiKeyPref: GeminiApiKey? = null
  @IgnoredOnParcel private var deepseekApiKeyPref: DeepseekApiKey? = null
  @IgnoredOnParcel private var openAIApiKeyPref: OpenAIApiKey? = null
  @IgnoredOnParcel private var anthropicApiKeyPref: AnthropicApiKey? = null
  @IgnoredOnParcel private var grokApiKeyPref: GrokApiKey? = null
  @IgnoredOnParcel private var openAiCompatibleApiKeyPref: OpenAiCompatibleApiKey? = null

  init {
    val aiAgentEnabled = AIAgentEnabled { isEnabled -> updateApiKeyPreferencesState(isEnabled) }

    geminiApiKeyPref = GeminiApiKey()
    deepseekApiKeyPref = DeepseekApiKey()
    openAIApiKeyPref = OpenAIApiKey()
    anthropicApiKeyPref = AnthropicApiKey()
    grokApiKeyPref = GrokApiKey()
    openAiCompatibleApiKeyPref = OpenAiCompatibleApiKey()

    addPreference(aiAgentEnabled)
    addPreference(geminiApiKeyPref!!)
    addPreference(deepseekApiKeyPref!!)
    addPreference(openAIApiKeyPref!!)
    addPreference(anthropicApiKeyPref!!)
    addPreference(grokApiKeyPref!!)
    addPreference(openAiCompatibleApiKeyPref!!)
    addPreference(AgentToolingGroup())
  }

  private fun updateApiKeyPreferencesState(isEnabled: Boolean) {
    geminiApiKeyPref?.setEnabled(isEnabled)
    deepseekApiKeyPref?.setEnabled(isEnabled)
    openAIApiKeyPref?.setEnabled(isEnabled)
    anthropicApiKeyPref?.setEnabled(isEnabled)
    grokApiKeyPref?.setEnabled(isEnabled)
    openAiCompatibleApiKeyPref?.setEnabled(isEnabled)
  }
}

/**
 * Agent 工具调用循环的相关设置。
 *
 * 这些配置存在独立的 "ai_agent_tools" SharedPreferences 里（由 AgentToolSettings 读取），
 * 而不是主 prefManager——工具层刻意不依赖 Android，配置通过窄接口注入，
 * 这里只是把同一份存储暴露到设置界面。
 */
@Parcelize
private class AgentToolingGroup(
    override val key: String = "idepref_ai_agent_tooling",
    override val title: Int = R.string.ai_agent_mode_title,
    override val summary: Int? = R.string.ai_agent_mode_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

  init {
    addPreference(AgentModeSwitch())
    addPreference(ChatModePreference())
    addPreference(CustomAgentsPreference())
    addPreference(PermissionModePreference())
    addPreference(AuthorizedToolsPreference())
    addPreference(PromptTemplatePreference())
    addPreference(MemoriesPreference())
    addPreference(McpServersPreference())
    addPreference(ShellBackendPreference())
    addPreference(
        CustomEndpointField(
            key = "ai_agent_custom_base_url",
            title = R.string.ai_agent_custom_base_url,
            hint = "https://your-gateway.example.com/v1",
        ))
    addPreference(
        CustomEndpointField(
            key = "ai_agent_custom_api_key",
            title = R.string.ai_agent_custom_api_key,
            hint = "sk-...",
        ))
    addPreference(
        CustomEndpointField(
            key = "ai_agent_custom_model",
            title = R.string.ai_agent_custom_model,
            hint = "e.g. agnes-2.5-flash",
        ))
  }

  override fun onCreatePreference(context: Context): Preference {
    return androidx.preference.Preference(context).apply {
      key = "idepref_ai_agent_tooling"
      title = context.getString(R.string.ai_agent_mode_title)
      summary = context.getString(R.string.ai_agent_mode_summary)
    }
  }
}

/** 读写 "ai_agent_tools" 存储的助手。 */
private fun agentPrefs(context: Context) =
    context.applicationContext.getSharedPreferences("ai_agent_tools", Context.MODE_PRIVATE)

@Parcelize
private class AgentModeSwitch(
    override val key: String = "agent_mode",
    override val title: Int = R.string.ai_agent_mode_title,
    override val summary: Int? = R.string.ai_agent_mode_summary,
) : SwitchPreference(
    setValue = { enabled -> },
    getValue = { false },
) {
  override fun onCreatePreference(context: Context): Preference {
    val prefs = agentPrefs(context)
    return androidx.preference.SwitchPreference(context).apply {
      key = "agent_mode"
      title = context.getString(R.string.ai_agent_mode_title)
      summary = context.getString(R.string.ai_agent_mode_summary)
      isChecked = prefs.getBoolean("agent_mode", false)
      setOnPreferenceChangeListener { _, newValue ->
        prefs.edit().putBoolean("agent_mode", newValue as Boolean).apply()
        true
      }
    }
  }
}

@Parcelize
private class PermissionModePreference(
    override val key: String = "permission_mode",
    override val title: Int = R.string.ai_agent_permission_title,
    override val summary: Int? = R.string.ai_agent_permission_summary,
) : BasePreference() {

  private val labels =
      arrayOf(
          "auto|自动放行（不询问）",
          "confirm|危险工具需确认",
          "readonly|只读（禁止写入与命令）",
      )

  override fun onCreatePreference(context: Context): Preference {
    val current = agentPrefs(context).getString("permission_mode", "confirm")
    return androidx.preference.Preference(context).apply {
      key = "permission_mode"
      title = context.getString(R.string.ai_agent_permission_title)
      summary = labels.firstOrNull { it.substringBefore('|') == current }?.substringAfter('|')
          ?: "危险工具需确认"
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val values = labels.map { it.substringBefore('|') }.toTypedArray()
    val shown = labels.map { it.substringAfter('|') }.toTypedArray()
    val current = agentPrefs(context).getString("permission_mode", "confirm")
    val checked = values.indexOf(current).coerceAtLeast(0)

    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(R.string.ai_agent_permission_title)
        .setSingleChoiceItems(shown, checked) { dialog, which ->
          agentPrefs(context).edit().putString("permission_mode", values[which]).apply()
          preference.summary = shown[which]
          dialog.dismiss()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    return true
  }
}

/**
 * 对话模式选择。
 *
 * <p>模式决定提示词里给出多少行动授权：对话（不调用工具）、计划（只读调查 + 出方案）、
 * 执行（默认，可读写）、受控执行（危险操作先确认）。用户对「AI 能做什么」的期待在不同
 * 场景差别很大，一个「是否允许写文件」的开关表达不了。
 */
@Parcelize
private class ChatModePreference(
    override val key: String = "chat_mode",
    override val title: Int = R.string.ai_agent_chat_mode_title,
    override val summary: Int? = R.string.ai_agent_chat_mode_summary,
) : BasePreference() {

  override fun onCreatePreference(context: Context): Preference {
    val current = com.tom.rv2ide.artificial.agent.PrefsChatModeStore(context).get()
    return androidx.preference.Preference(context).apply {
      key = "chat_mode"
      title = context.getString(R.string.ai_agent_chat_mode_title)
      summary = "${current.label} — ${current.description}"
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val store = com.tom.rv2ide.artificial.agent.PrefsChatModeStore(context)
    val modes = com.tom.rv2ide.ai.agent.prompt.ChatMode.all()
    val shown = modes.map { "${it.label} — ${it.description}" }.toTypedArray()
    val checked = modes.indexOf(store.get()).coerceAtLeast(0)

    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(R.string.ai_agent_chat_mode_title)
        .setSingleChoiceItems(shown, checked) { dialog, which ->
          val selected = modes[which]
          store.set(selected)
          preference.summary = "${selected.label} — ${selected.description}"
          dialog.dismiss()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    return true
  }
}

/**
 * 系统提示词模板编辑。
 *
 * <p><b>为什么需要这个入口</b>：提示词直接决定模型的行为风格。此前它硬编码在
 * {@code AgentPromptBuilder} 里，改一个措辞要重新编译整个应用。现在文本是数据，
 * 用户可在这里改并立刻生效。
 *
 * <p>编辑界面同时给出**可用占位符清单**：写错占位符名不会报错，只会静默变成空串，
 * 表现为「模型行为莫名其妙」——这是最难排查的一类问题，因此必须在编辑处就可见。
 */
@Parcelize
private class PromptTemplatePreference(
    override val key: String = "prompt_template",
    override val title: Int = R.string.ai_agent_prompt_template_title,
    override val summary: Int? = R.string.ai_agent_prompt_template_summary,
) : BasePreference() {

  override fun onCreatePreference(context: Context): Preference {
    val store = com.tom.rv2ide.artificial.agent.PrefsPromptTemplateStore(context)
    val customized = store.readAll().size
    return androidx.preference.Preference(context).apply {
      key = "prompt_template"
      title = context.getString(R.string.ai_agent_prompt_template_title)
      summary =
          if (customized == 0) context.getString(R.string.ai_agent_prompt_template_default)
          else context.getString(R.string.ai_agent_prompt_template_customized, customized)
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val store = com.tom.rv2ide.artificial.agent.PrefsPromptTemplateStore(context)

    val templates =
        listOf(
            com.tom.rv2ide.ai.agent.prompt.PromptTemplates.SYSTEM_PROMPT,
            com.tom.rv2ide.ai.agent.prompt.PromptTemplates.WORKSPACE_CONTEXT,
            com.tom.rv2ide.ai.agent.prompt.PromptTemplates.TOOLS_CONTEXT,
            com.tom.rv2ide.ai.agent.prompt.PromptTemplates.TOOL_CALL_FORMAT,
            com.tom.rv2ide.ai.agent.prompt.PromptTemplates.TODO_SECTION,
            com.tom.rv2ide.ai.agent.prompt.PromptTemplates.NOTES,
            com.tom.rv2ide.ai.agent.prompt.PromptTemplates.chatModeTemplateId(
                com.tom.rv2ide.ai.agent.prompt.ChatMode.CHAT),
            com.tom.rv2ide.ai.agent.prompt.PromptTemplates.chatModeTemplateId(
                com.tom.rv2ide.ai.agent.prompt.ChatMode.PLAN),
            com.tom.rv2ide.ai.agent.prompt.PromptTemplates.chatModeTemplateId(
                com.tom.rv2ide.ai.agent.prompt.ChatMode.AGENT),
            com.tom.rv2ide.ai.agent.prompt.PromptTemplates.chatModeTemplateId(
                com.tom.rv2ide.ai.agent.prompt.ChatMode.CONTROL),
        )

    val labels =
        templates
            .map { id ->
              val mark = if (store.isCustomized(id)) " ●" else ""
              id + mark
            }
            .toTypedArray()

    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(R.string.ai_agent_prompt_template_title)
        .setItems(labels) { _, which -> editTemplate(context, store, templates[which], preference) }
        .setNeutralButton(R.string.ai_agent_prompt_template_reset_all) { _, _ ->
          templates.forEach { store.reset(it) }
          preference.summary = context.getString(R.string.ai_agent_prompt_template_default)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    return true
  }

  /** 编辑单个模板：预填当前内容（自定义或默认），并展示可用占位符。 */
  private fun editTemplate(
      context: Context,
      store: com.tom.rv2ide.artificial.agent.PrefsPromptTemplateStore,
      templateId: String,
      preference: Preference,
  ) {
    val current = store.resolve(templateId)
    val unknown = com.tom.rv2ide.ai.agent.prompt.PromptRenderer.unknownPlaceholders(current)

    val editText =
        android.widget.EditText(context).apply {
          setText(current)
          // 多行 + 等宽：提示词是带换行的结构化文本，单行输入框没法用。
          inputType =
              android.text.InputType.TYPE_CLASS_TEXT or
                  android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
          gravity = android.view.Gravity.TOP or android.view.Gravity.START
          minLines = 8
          typeface = android.graphics.Typeface.MONOSPACE
          setHorizontallyScrolling(false)
        }
    val scroll = android.widget.ScrollView(context).apply { addView(editText) }

    // 校验提示：拼错的占位符会静默变空串，必须在保存前让用户看到。
    val hint =
        buildString {
          append(context.getString(R.string.ai_agent_prompt_template_placeholders))
          append('\n')
          append(com.tom.rv2ide.ai.agent.prompt.PromptPlaceholders.all().joinToString("  ") { "{{$it}}" })
          if (unknown.isNotEmpty()) {
            append("\n\n")
            append(context.getString(R.string.ai_agent_prompt_template_unknown))
            append('\n')
            append(unknown.joinToString("  ") { "{{$it}}" })
          }
        }

    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(templateId)
        .setMessage(hint)
        .setView(scroll)
        .setPositiveButton(android.R.string.ok) { _, _ ->
          store.write(templateId, editText.text?.toString().orEmpty())
          preference.summary =
              context.getString(
                  R.string.ai_agent_prompt_template_customized,
                  store.readAll().size,
              )
        }
        .setNeutralButton(R.string.ai_agent_prompt_template_reset) { _, _ ->
          store.reset(templateId)
          preference.summary = context.getString(R.string.ai_agent_prompt_template_default)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }
}

/**
 * 自定义 agent：新增、编辑、启停、删除。
 *
 * <p><b>为什么需要它</b>：内置提示词只能覆盖通用场景。用户对自己的项目有特定要求
 * （「审查时必须检查是否遗漏了 i18n 字符串」），把它固化成一个自定义 agent 后，
 * 模型可以通过 {@code agentx_<名字>} 工具调用它，用户不必每次在对话里重复说明。
 */
@Parcelize
private class CustomAgentsPreference(
    override val key: String = "custom_agents",
    override val title: Int = R.string.ai_agent_custom_agents_title,
    override val summary: Int? = R.string.ai_agent_custom_agents_summary,
) : BasePreference() {

  override fun onCreatePreference(context: Context): Preference {
    val agents = customAgentStore(context).all()
    return androidx.preference.Preference(context).apply {
      key = "custom_agents"
      title = context.getString(R.string.ai_agent_custom_agents_title)
      summary =
          if (agents.isEmpty()) context.getString(R.string.ai_agent_custom_agents_none)
          else
              context.getString(
                  R.string.ai_agent_custom_agents_count,
                  agents.count { it.isUsable() },
                  agents.size,
              )
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    showList(preference.context, preference)
    return true
  }

  private fun customAgentStore(context: Context) =
      com.tom.rv2ide.ai.agent.command.CustomAgentStore(
          java.io.File(java.io.File(context.filesDir, "ai"), "custom_agents.json"))

  private fun showList(
      context: Context,
      preference: Preference,
  ) {
    val store = customAgentStore(context)
    val agents = store.all()

    val labels =
        agents
            .map { agent ->
              val state = if (agent.isUsable()) "✓" else "✗"
              "$state ${agent.name}"
            }
            .toMutableList()
    labels.add(context.getString(R.string.ai_agent_custom_agents_add))

    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(R.string.ai_agent_custom_agents_title)
        .setItems(labels.toTypedArray()) { _, which ->
          if (which == agents.size) {
            edit(context, store, null, preference)
          } else {
            edit(context, store, agents[which], preference)
          }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  /** 编辑一个 agent；existing 为 null 表示新建。 */
  private fun edit(
      context: Context,
      store: com.tom.rv2ide.ai.agent.command.CustomAgentStore,
      existing: com.tom.rv2ide.ai.agent.command.CustomAgent?,
      preference: Preference,
  ) {
    val container = android.widget.LinearLayout(context).apply {
      orientation = android.widget.LinearLayout.VERTICAL
      setPadding(48, 24, 48, 0)
    }
    val nameField = com.google.android.material.textfield.TextInputEditText(context).apply {
      hint = context.getString(R.string.ai_agent_custom_agents_name_hint)
      setText(existing?.name.orEmpty())
      isEnabled = existing == null // 名字是工具的标识，改名等于换一个工具
    }
    val descriptionField = com.google.android.material.textfield.TextInputEditText(context).apply {
      hint = context.getString(R.string.ai_agent_custom_agents_description_hint)
      setText(existing?.description.orEmpty())
    }
    val promptField = com.google.android.material.textfield.TextInputEditText(context).apply {
      hint = context.getString(R.string.ai_agent_custom_agents_prompt_hint)
      setText(existing?.prompt.orEmpty())
      inputType =
          android.text.InputType.TYPE_CLASS_TEXT or
              android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
      gravity = android.view.Gravity.TOP or android.view.Gravity.START
      minLines = 6
    }
    container.addView(nameField)
    container.addView(descriptionField)
    container.addView(promptField)

    val builder =
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(
                if (existing == null) R.string.ai_agent_custom_agents_add
                else R.string.ai_agent_custom_agents_edit)
            .setMessage(R.string.ai_agent_custom_agents_hint)
            .setView(android.widget.ScrollView(context).apply { addView(container) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
              val agent =
                  com.tom.rv2ide.ai.agent.command.CustomAgent(
                      nameField.text?.toString().orEmpty(),
                      descriptionField.text?.toString().orEmpty(),
                      promptField.text?.toString().orEmpty(),
                      existing?.isEnabled ?: true,
                  )
              val error = store.save(agent)
              if (error.isNotEmpty()) {
                android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_LONG)
                    .show()
              }
              preference.summary = refreshSummary(context)
            }
            .setNegativeButton(android.R.string.cancel, null)

    if (existing != null) {
      builder.setNeutralButton(
          if (existing.isEnabled) R.string.ai_agent_custom_agents_disable
          else R.string.ai_agent_custom_agents_enable) { _, _ ->
        store.save(existing.withEnabled(!existing.isEnabled))
        preference.summary = refreshSummary(context)
      }
    }

    builder.show()
  }

  private fun refreshSummary(context: Context): String {
    val agents = customAgentStore(context).all()
    return if (agents.isEmpty()) context.getString(R.string.ai_agent_custom_agents_none)
    else
        context.getString(
            R.string.ai_agent_custom_agents_count, agents.count { it.isUsable() }, agents.size)
  }
}

/**
 * 长期记忆：查看、删除、清空。
 *
 * <p><b>为什么必须有查看与删除入口</b>：记忆会进入后续每一轮的提示词。用户看不到
 * 里面存了什么，就无法理解「AI 为什么知道这件事」，也无法纠正一条记错的内容——
 * 而错误的记忆会持续影响所有后续对话。
 */
@Parcelize
private class MemoriesPreference(
    override val key: String = "memories",
    override val title: Int = R.string.ai_agent_memory_title,
    override val summary: Int? = R.string.ai_agent_memory_summary,
) : BasePreference() {

  override fun onCreatePreference(context: Context): Preference {
    val count = memoryStore(context).size()
    return androidx.preference.Preference(context).apply {
      key = "memories"
      title = context.getString(R.string.ai_agent_memory_title)
      summary =
          if (count == 0) context.getString(R.string.ai_agent_memory_none)
          else context.getString(R.string.ai_agent_memory_count, count)
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    showList(preference.context, preference)
    return true
  }

  private fun memoryStore(context: Context) =
      com.tom.rv2ide.ai.tool.memory.MemoryStore(
          java.io.File(java.io.File(context.filesDir, "ai"), "memories.json"))

  private fun showList(
      context: Context,
      preference: Preference,
  ) {
    val store = memoryStore(context)
    val entries = store.all()
    if (entries.isEmpty()) {
      com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
          .setTitle(R.string.ai_agent_memory_title)
          .setMessage(R.string.ai_agent_memory_none)
          .setPositiveButton(android.R.string.ok, null)
          .show()
      return
    }

    val labels = entries.map { it.toLine() }.toTypedArray()
    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(R.string.ai_agent_memory_title)
        .setItems(labels) { _, which ->
          // 点选即删除。记忆条目通常很短，选中即意味着「这条不对」，
          // 再弹一层确认只会多一次点击。
          store.remove(entries[which].getId())
          preference.summary = refreshSummary(context)
        }
        .setNeutralButton(R.string.ai_agent_memory_clear_all) { _, _ ->
          store.clear()
          preference.summary = refreshSummary(context)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private fun refreshSummary(context: Context): String {
    val count = memoryStore(context).size()
    return if (count == 0) context.getString(R.string.ai_agent_memory_none)
    else context.getString(R.string.ai_agent_memory_count, count)
  }
}

/**
 * MCP server 配置：添加、查看、删除。
 *
 * <p><b>为什么需要这个入口</b>：MCP 的价值在于「接入任意现成的工具服务而不改本项目
 * 代码」，但如果没有配置界面，用户无从告诉应用去连哪个 server——功能等于不存在。
 *
 * <p>列表里显示每个 server 的地址与启用状态；删除即从配置中移除。启用/禁用通过重新
 * 添加控制（保留地址），避免用户为了临时关掉一个 server 而丢掉它的配置。
 */
@Parcelize
private class McpServersPreference(
    override val key: String = "mcp_servers",
    override val title: Int = R.string.ai_agent_mcp_title,
    override val summary: Int? = R.string.ai_agent_mcp_summary,
) : BasePreference() {

  override fun onCreatePreference(context: Context): Preference {
    val servers = com.tom.rv2ide.artificial.agent.McpServers(context).all()
    return androidx.preference.Preference(context).apply {
      key = "mcp_servers"
      title = context.getString(R.string.ai_agent_mcp_title)
      summary =
          if (servers.isEmpty()) context.getString(R.string.ai_agent_mcp_none)
          else context.getString(R.string.ai_agent_mcp_count, servers.count { it.enabled }, servers.size)
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    showList(preference.context, preference)
    return true
  }

  private fun showList(
      context: Context,
      preference: Preference,
  ) {
    val store = com.tom.rv2ide.artificial.agent.McpServers(context)
    val servers = store.all()

    val labels =
        servers
            .map { server ->
              val state = if (server.enabled) "✓" else "✗"
              "$state ${server.displayName()}"
            }
            .toMutableList()
    labels.add(context.getString(R.string.ai_agent_mcp_add))

    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(R.string.ai_agent_mcp_title)
        .setItems(labels.toTypedArray()) { _, which ->
          if (which == servers.size) {
            showAddDialog(context, preference)
          } else {
            showServerActions(context, preference, servers[which])
          }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private fun showServerActions(
      context: Context,
      preference: Preference,
      server: com.tom.rv2ide.artificial.agent.McpServers.Server,
  ) {
    val store = com.tom.rv2ide.artificial.agent.McpServers(context)
    val actions =
        arrayOf(
            context.getString(
                if (server.enabled) R.string.ai_agent_mcp_disable
                else R.string.ai_agent_mcp_enable),
            context.getString(R.string.ai_agent_mcp_remove),
        )

    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(server.displayName())
        .setItems(actions) { _, which ->
          when (which) {
            0 -> {
              val updated = store.all()
              val next = mutableListOf<com.tom.rv2ide.artificial.agent.McpServers.Server>()
              for (item in updated) {
                next.add(
                    if (item.url == server.url)
                        com.tom.rv2ide.artificial.agent.McpServers.Server(
                            item.url, item.label, !item.enabled)
                    else item)
              }
              store.save(next)
            }
            1 -> store.remove(server.url)
          }
          preference.summary = refreshSummary(context)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private fun showAddDialog(
      context: Context,
      preference: Preference,
  ) {
    val container = android.widget.LinearLayout(context).apply {
      orientation = android.widget.LinearLayout.VERTICAL
      setPadding(48, 24, 48, 0)
    }
    val urlField = com.google.android.material.textfield.TextInputEditText(context).apply {
      hint = "https://mcp.example.com/mcp"
      inputType =
          android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
    }
    val labelField = com.google.android.material.textfield.TextInputEditText(context).apply {
      hint = context.getString(R.string.ai_agent_mcp_label_hint)
    }
    container.addView(urlField)
    container.addView(labelField)

    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(R.string.ai_agent_mcp_add)
        .setMessage(R.string.ai_agent_mcp_add_hint)
        .setView(container)
        .setPositiveButton(android.R.string.ok) { _, _ ->
          val url = urlField.text?.toString()?.trim().orEmpty()
          if (url.isNotEmpty()) {
            com.tom.rv2ide.artificial.agent.McpServers(context)
                .add(
                    com.tom.rv2ide.artificial.agent.McpServers.Server(
                        url, labelField.text?.toString()?.trim().orEmpty(), true))
            preference.summary = refreshSummary(context)
          }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private fun refreshSummary(context: Context): String {
    val servers = com.tom.rv2ide.artificial.agent.McpServers(context).all()
    return if (servers.isEmpty()) context.getString(R.string.ai_agent_mcp_none)
    else context.getString(R.string.ai_agent_mcp_count, servers.count { it.enabled }, servers.size)
  }
}

/**
 * 已持久化的危险工具授权规则：查看与逐条撤销。
 *
 * <p><b>为什么必须有这个入口</b>：用户在弹窗里点「始终允许」时只看到一条命令，
 * 不可能记住自己后来放行了什么。没有撤销入口，规则只会越积越多，最终等同于关掉确认——
 * 而用户对此毫无感知。这里让授权可见、可撤销，才使「始终允许」是一个负责任的选择。
 */
@Parcelize
private class AuthorizedToolsPreference(
    override val key: String = "dangerous_rules",
    override val title: Int = R.string.ai_agent_authorized_title,
    override val summary: Int? = R.string.ai_agent_authorized_summary,
) : BasePreference() {

  override fun onCreatePreference(context: Context): Preference {
    val rules = persistedRules(context)
    return androidx.preference.Preference(context).apply {
      key = "dangerous_rules"
      title = context.getString(R.string.ai_agent_authorized_title)
      summary =
          if (rules.isEmpty()) context.getString(R.string.ai_agent_authorized_none)
          else context.getString(R.string.ai_agent_authorized_count, rules.size)
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val rules = persistedRules(context).toTypedArray()
    if (rules.isEmpty()) {
      com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
          .setTitle(R.string.ai_agent_authorized_title)
          .setMessage(R.string.ai_agent_authorized_none)
          .setPositiveButton(android.R.string.ok, null)
          .show()
      return true
    }

    val shown =
        rules.map { com.tom.rv2ide.ai.tool.ToolPermissionRule.describe(it) }.toTypedArray()
    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(R.string.ai_agent_authorized_title)
        .setItems(shown) { _, which ->
          // 单条撤销：不提供「全部清空」之外的批量操作，避免误触清掉全部授权后
          // 用户不知道哪些被清掉了。
          com.tom.rv2ide.artificial.agent.AgentToolSettings(context)
              .forgetDangerousToolRule(rules[which])
          preference.summary = context.getString(R.string.ai_agent_authorized_count, rules.size - 1)
        }
        .setNeutralButton(R.string.ai_agent_authorized_clear_all) { _, _ ->
          com.tom.rv2ide.artificial.agent.AgentToolSettings(context).clearDangerousToolRules()
          preference.summary = context.getString(R.string.ai_agent_authorized_none)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    return true
  }

  private fun persistedRules(context: Context): List<String> {
    // 排序只为让列表稳定：SharedPreferences 的 StringSet 顺序不保证，
    // 不排序时每次打开设置项的展示顺序都可能不同。
    return com.tom.rv2ide.artificial.agent.AgentToolSettings(context)
        .getDangerousToolRules()
        .sorted()
  }
}

@Parcelize
private class ShellBackendPreference(
    override val key: String = "shell_backend",
    override val title: Int = R.string.ai_agent_shell_backend_title,
    override val summary: Int? = R.string.ai_agent_shell_backend_summary,
) : BasePreference() {

  private val values = arrayOf("termux", "shizuku")
  private val shown = arrayOf("内置终端 Termux（无 adb 权限）", "Shizuku（adb 权限）")

  override fun onCreatePreference(context: Context): Preference {
    val current = agentPrefs(context).getString("shell_backend", "termux")
    return androidx.preference.Preference(context).apply {
      key = "shell_backend"
      title = context.getString(R.string.ai_agent_shell_backend_title)
      summary = shown[values.indexOf(current).coerceAtLeast(0)]
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val current = agentPrefs(context).getString("shell_backend", "termux")
    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(R.string.ai_agent_shell_backend_title)
        .setSingleChoiceItems(shown, values.indexOf(current).coerceAtLeast(0)) { dialog, which ->
          agentPrefs(context).edit().putString("shell_backend", values[which]).apply()
          preference.summary = shown[which]
          dialog.dismiss()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    return true
  }
}

/**
 * 自定义 OpenAI 兼容端点的一个可编辑字段。
 *
 * 三个字段（baseUrl / key / model）的读写逻辑完全相同，只有 key、标题与提示语不同，
 * 因此把差异作为构造参数传入，而不是复制三份实现。
 */
@Parcelize
private class CustomEndpointField(
    override val key: String,
    override val title: Int,
    override val summary: Int? = R.string.ai_agent_custom_hint,
    private val hint: String = "",
) : BasePreference() {

  override fun onCreatePreference(context: Context): Preference {
    return androidx.preference.Preference(context).apply {
      key = this@CustomEndpointField.key
      title = context.getString(this@CustomEndpointField.title)
      summary = fieldSummary(context, this@CustomEndpointField.key)
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val fieldKey = this@CustomEndpointField.key
    val editText =
        android.widget.EditText(context).apply {
          setText(agentPrefs(context).getString(fieldKey, ""))
          hint = this@CustomEndpointField.hint
          setSingleLine(true)
        }
    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(this@CustomEndpointField.title)
        .setView(editText)
        .setPositiveButton(android.R.string.ok) { _, _ ->
          agentPrefs(context).edit().putString(fieldKey, editText.text.toString().trim()).apply()
          preference.summary = fieldSummary(context, fieldKey)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    return true
  }

  private fun fieldSummary(context: Context, fieldKey: String): String {
    val value = agentPrefs(context).getString(fieldKey, "")
    if (value.isNullOrBlank()) {
      return context.getString(R.string.ai_agent_custom_hint)
    }
    // API key 只显示前 8 位，避免在设置界面完整暴露
    return if (fieldKey.endsWith("api_key")) "API Key: ${value.take(8)}..." else value
  }
}


@Parcelize
private class AIAgentEnabled(
    override val key: String = "ai_agent_enabled",
    override val title: Int = R.string.ai_agent_enable,
    @IgnoredOnParcel private val onStateChanged: ((Boolean) -> Unit)? = null,
) :
    SwitchPreference(
        setValue = { isEnabled ->
          prefManager.putBoolean("ai_agent_enabled", isEnabled)
          onStateChanged?.invoke(isEnabled)
        },
        getValue = { prefManager.getBoolean("ai_agent_enabled", false) },
    ) {

  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).apply {
      key = "ai_agent_enabled"
      title = context.getString(R.string.ai_agent_enable)
      summary = context.getString(R.string.ai_agent_enable_summary)
    }
  }
}


/**
 * 通用 OpenAI 兼容服务商的密钥（Groq / 智谱 / Kimi / 通义千问 / MiniMax / 硅基流动 / OpenRouter）。
 *
 * <p>它们讲同一种协议，只是 baseUrl 与模型名不同，因此共用一个密钥槽位。为每个服务商
 * 各开一个偏好键会让「加一个服务商」变成「改三处代码」——而这正是预设表要消除的成本。
 * 当前使用哪个服务商由 {@code ai_provider_name} 决定。
 */
@Parcelize
private class OpenAiCompatibleApiKey(
    override val key: String = "ai_agent_openai_compatible_api_key",
    override val title: Int = R.string.ai_agent_openai_compatible_api_key,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = "ai_agent_openai_compatible_api_key"
          title = context.getString(R.string.ai_agent_openai_compatible_api_key)
          summary = getSummaryText(context)
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context

    val editText = android.widget.EditText(context)
    editText.setText(prefManager.getString("ai_agent_openai_compatible_api_key", ""))
    editText.hint = context.getString(R.string.ai_agent_openai_compatible_api_key_hint)

    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(R.string.ai_agent_openai_compatible_api_key)
        .setMessage(R.string.ai_agent_openai_compatible_api_key_summary)
        .setView(editText)
        .setPositiveButton(android.R.string.ok) { _, _ ->
          prefManager.putString(
              "ai_agent_openai_compatible_api_key",
              editText.text.toString().trim(),
          )
          preference.summary = getSummaryText(context)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun getSummaryText(context: Context): String {
    val apiKey = prefManager.getString("ai_agent_openai_compatible_api_key", "")
    return if (apiKey.isBlank()) {
      context.getString(R.string.ai_agent_api_key_unset)
    } else {
      context.getString(R.string.ai_agent_api_key_set, apiKey.take(8))
    }
  }
}

@Parcelize
private class GrokApiKey(
    override val key: String = "ai_agent_grok_api_key",
    override val title: Int = R.string.ai_agent_grok_api_key,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = "ai_agent_grok_api_key"
          title = context.getString(R.string.ai_agent_grok_api_key)
          summary = getSummaryText()
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context

    val editText = android.widget.EditText(context)
    editText.setText(prefManager.getString("ai_agent_grok_api_key", ""))
    editText.hint = "Enter your xAI Grok API key"

    val dialog =
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(context)
            .setTitle("Grok API Key")
            .setMessage("Enter your xAI Grok API key")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
              val apiKey = editText.text.toString().trim()
              prefManager.putString("ai_agent_grok_api_key", apiKey)
              preference.summary = getSummaryText()
            }
            .setNegativeButton("Cancel", null)
            .create()

    dialog.show()
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun getSummaryText(): String {
    val apiKey = prefManager.getString("ai_agent_grok_api_key", "")
    return if (apiKey.isBlank()) "Click to set API key" else "API Key: ${apiKey.take(8)}..."
  }
}

@Parcelize
private class GeminiApiKey(
    override val key: String = "ai_agent_gemini_api_key",
    override val title: Int = R.string.ai_agent_api_key,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = "ai_agent_gemini_api_key"
          title = context.getString(R.string.ai_agent_api_key)
          summary = getSummaryText()
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context

    val editText = android.widget.EditText(context)
    editText.setText(prefManager.getString("ai_agent_gemini_api_key", ""))
    editText.hint = "Enter your Google Gemini API key"

    val dialog =
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(context)
            .setTitle("Gemini API Key")
            .setMessage("Enter your Google Gemini API key")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
              val apiKey = editText.text.toString().trim()
              prefManager.putString("ai_agent_gemini_api_key", apiKey)
              preference.summary = getSummaryText()
            }
            .setNegativeButton("Cancel", null)
            .create()

    dialog.show()
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun getSummaryText(): String {
    val apiKey = prefManager.getString("ai_agent_gemini_api_key", "")
    return if (apiKey.isBlank()) "Click to set API key" else "API Key: ${apiKey.take(8)}..."
  }
}

@Parcelize
private class DeepseekApiKey(
    override val key: String = "ai_agent_deepseek_api_key",
    override val title: Int = R.string.ai_agent_deepseek_api_key,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = "ai_agent_deepseek_api_key"
          title = context.getString(R.string.ai_agent_deepseek_api_key)
          summary = getSummaryText()
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context

    val editText = android.widget.EditText(context)
    editText.setText(prefManager.getString("ai_agent_deepseek_api_key", ""))
    editText.hint = "Enter your Deepseek API key"

    val dialog =
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(context)
            .setTitle("Deepseek API Key")
            .setMessage("Enter your Deepseek API key")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
              val apiKey = editText.text.toString().trim()
              prefManager.putString("ai_agent_deepseek_api_key", apiKey)
              preference.summary = getSummaryText()
            }
            .setNegativeButton("Cancel", null)
            .create()

    dialog.show()
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun getSummaryText(): String {
    val apiKey = prefManager.getString("ai_agent_deepseek_api_key", "")
    return if (apiKey.isBlank()) "Click to set API key" else "API Key: ${apiKey.take(8)}..."
  }
}

@Parcelize
private class OpenAIApiKey(
    override val key: String = "ai_agent_openai_api_key",
    override val title: Int = R.string.ai_agent_openai_api_key,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = "ai_agent_openai_api_key"
          title = context.getString(R.string.ai_agent_openai_api_key)
          summary = getSummaryText()
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context

    val editText = android.widget.EditText(context)
    editText.setText(prefManager.getString("ai_agent_openai_api_key", ""))
    editText.hint = "Enter your OpenAI API key"

    val dialog =
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(context)
            .setTitle("OpenAI API Key")
            .setMessage("Enter your OpenAI API key")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
              val apiKey = editText.text.toString().trim()
              prefManager.putString("ai_agent_openai_api_key", apiKey)
              preference.summary = getSummaryText()
            }
            .setNegativeButton("Cancel", null)
            .create()

    dialog.show()
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun getSummaryText(): String {
    val apiKey = prefManager.getString("ai_agent_openai_api_key", "")
    return if (apiKey.isBlank()) "Click to set API key" else "API Key: ${apiKey.take(8)}..."
  }
}

@Parcelize
private class AnthropicApiKey(
    override val key: String = "ai_agent_anthropic_api_key",
    override val title: Int = R.string.ai_agent_anthropic_api_key,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference =
        androidx.preference.Preference(context).apply {
          key = "ai_agent_anthropic_api_key"
          title = context.getString(R.string.ai_agent_anthropic_api_key)
          summary = getSummaryText()
          isEnabled = prefManager.getBoolean("ai_agent_enabled", false)
        }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context

    val editText = android.widget.EditText(context)
    editText.setText(prefManager.getString("ai_agent_anthropic_api_key", ""))
    editText.hint = "Enter your Anthropic API key"

    val dialog =
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(context)
            .setTitle("Anthropic API Key")
            .setMessage("Enter your Anthropic API key")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
              val apiKey = editText.text.toString().trim()
              prefManager.putString("ai_agent_anthropic_api_key", apiKey)
              preference.summary = getSummaryText()
            }
            .setNegativeButton("Cancel", null)
            .create()

    dialog.show()
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }

  private fun getSummaryText(): String {
    val apiKey = prefManager.getString("ai_agent_anthropic_api_key", "")
    return if (apiKey.isBlank()) "Click to set API key" else "API Key: ${apiKey.take(8)}..."
  }
}
