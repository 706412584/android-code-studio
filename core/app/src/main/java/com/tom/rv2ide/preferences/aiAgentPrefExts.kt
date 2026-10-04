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
import android.widget.Toast
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agent.codegraph.CodeGraphInstaller
import com.tom.rv2ide.artificial.agent.codegraph.CodeGraphManager
import com.tom.rv2ide.preferences.internal.prefManager
import com.tom.rv2ide.services.AssistantOverlayService
import com.tom.rv2ide.resources.R.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // 四个二级页，而不是把 27 个条目平铺在一页里。
    //
    // 原先只有一个分组（AgentToolingGroup）装了 15 项，加上密钥区的 7 项，
    // 一页要滚动很久才能看完，且「密钥」与「技能」这类毫不相干的配置挨在一起。
    // 拆页粒度参照参考项目：顶层 4 个入口、每页 ≤8 项、每个分组 ≤6 项。
    addPreference(ProvidersPage())
    addPreference(ToolsPage())
    addPreference(CapabilitiesPage())
    addPreference(AdvancedPage())
  }
}

/*
 * 二级页统一用 [IPreferenceScreen] 而不是 [IPreferenceGroup]。
 *
 * Screen 的 children 会被 `IDEPreferencesFragment` 展开成一个**可点击入口**，
 * 点进去用同一个 Fragment 渲染它的 children——因此二级页不需要新的 Activity 或 Fragment。
 * Group 则渲染成 `PreferenceCategory`（不可点，只作分组标题），语义不对。
 *
 * 四个页面各自直接实现接口，没有抽公共基类：`@Parcelize` 不支持抽象类
 * （编译期报 "'Parcelable' should not be an 'abstract' class"），
 * 而基类里本来也只有三个属性，抽出来省不下什么。
 */

/**
 * 服务商与密钥。
 *
 * <p><b>从「一排固定密钥框」改为「服务商管理」</b>：旧实现为 6 个服务商各写死一个
 * 偏好键，其余 7 个 OpenAI 兼容服务商共用一个键——配了 GLM 的密钥切到 Kimi 时会
 * 拿着 GLM 的密钥去请求，而且两个服务商无法同时配置。
 *
 * <p>现在密钥随服务商记录走（{@code providers.json}），每份互不干扰；
 * 增删改都在 [ProviderManagementPreference] 里完成。
 */
@Parcelize
private class ProvidersPage(
    override val key: String = "idepref_ai_agent_providers",
    override val title: Int = R.string.ai_agent_page_providers_title,
    override val summary: Int? = R.string.ai_agent_page_providers_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(AIAgentEnabled())
    addPreference(ProviderManagementPreference())
  }
}

/**
 * 工具与权限：助手被允许做到什么程度，以及命令怎么执行。
 *
 * <p>这些配置存在独立的 "ai_agent_tools" SharedPreferences 里（由 AgentToolSettings 读取），
 * 而不是主 prefManager——工具层刻意不依赖 Android，配置通过窄接口注入，
 * 这里只是把同一份存储暴露到设置界面。
 */
@Parcelize
private class ToolsPage(
    override val key: String = "idepref_ai_agent_tools",
    override val title: Int = R.string.ai_agent_page_tools_title,
    override val summary: Int? = R.string.ai_agent_page_tools_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    // 「Agent 模式（工具调用）」开关已移除：它原先只决定 ChatFragment 走旧路径
    // （单发生成 + FILE_TO_MODIFY）还是新路径（工具调用循环）。ChatFragment 与旧路径
    // 一并删除后，悬浮助手**始终**走工具调用循环，这个开关没有任何东西可切换——
    // 留着会让用户以为关掉它能让助手变成纯对话，实际毫无作用。
    // 「助手被允许做到什么程度」由对话模式（ChatModePreference）表达。
    addPreference(ChatModePreference())
    addPreference(PermissionModePreference())
    addPreference(AuthorizedToolsPreference())
    addPreference(ShellBackendPreference())
    addPreference(CodeCompletionSwitch())
  }
}

/** 能力：自定义 Agent、技能、长期记忆、MCP 服务。都是「扩展助手能做什么」的配置。 */
@Parcelize
private class CapabilitiesPage(
    override val key: String = "idepref_ai_agent_capabilities",
    override val title: Int = R.string.ai_agent_page_capabilities_title,
    override val summary: Int? = R.string.ai_agent_page_capabilities_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(AssistantOverlayPreference())
    addPreference(CustomAgentsPreference())
    addPreference(SkillsPreference())
    addPreference(MemoriesPreference())
    addPreference(McpServersPreference())
    addPreference(CodeGraphPreference())
  }
}

/**
 * 应用外系统悬浮入口。
 *
 * <p><b>为什么需要它</b>：`AssistantOverlayService` 能挂出系统级悬浮窗，但没有入口就
 * 等于不存在。本项负责「申请悬浮权限 + 拉起/关闭服务」这条闭环。
 *
 * <p><b>为什么不是 [SwitchPreference]</b>：Switch 的语义是一份**持久布尔**，而「悬浮窗
 * 是否显示」是**运行时状态**（服务在不在跑），由系统、用户、进程死亡共同决定，无法用
 * 一个存进 SharedPreferences 的开关表达。用普通条目 + 动态 summary 反映真实状态。
 *
 * <p><b>权限闭环（选「再点一次」）</b>：Android 的 `SYSTEM_ALERT_WINDOW` 是特殊权限，
 * 只能跳系统页让用户手动开启，没有「授权回调」。因此：点击时若 `canDrawOverlays` 为
 * false，则跳 `ACTION_MANAGE_OVERLAY_PERMISSION`；用户回来后**再点一次**即生效。
 * 为让用户知道「该再点一次」，summary 每次渲染都重读权限与服务状态——回来后能看到
 * 「已授予权限，点击开启」而不是停在旧文案。刻意不挂 `onResume`：那要改偏好框架，
 * 侵入大于收益。
 *
 * <p><b>summary 的时序</b>：必须在 [onCreateView]（`super` 之后）里写，不能在
 * [onCreatePreference] 里写——基类会用静态 `summary` 资源覆盖前者（与
 * [CodeGraphPreference] 同一个坑）。
 */
@Parcelize
private class AssistantOverlayPreference(
    override val key: String = "assistant_overlay",
    override val title: Int = R.string.ai_agent_overlay_title,
    override val summary: Int? = R.string.ai_agent_overlay_summary,
) : BasePreference() {

  override fun onCreatePreference(context: Context): Preference =
      androidx.preference.Preference(context).apply { key = "assistant_overlay" }

  override fun onCreateView(context: Context): Preference {
    val pref = super.onCreateView(context)
    // super 之后：此时静态 summary 已写完，动态状态才不会被盖掉。
    pref.summary = describe(context)
    return pref
  }

  /** 状态 → 一行摘要（区分「无权限 / 有权限未开 / 已开」三种情况）。 */
  private fun describe(context: Context): String =
      when {
        !canDrawOverlays(context) -> context.getString(R.string.ai_agent_overlay_need_permission)
        AssistantOverlayService.isShowing -> context.getString(R.string.ai_agent_overlay_on)
        else -> context.getString(R.string.ai_agent_overlay_off)
      }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context

    // 无权限：跳系统授权页。requestDisplayOverOtherAppsPermission 内部会按上下文
    // 决定是否加 FLAG_ACTIVITY_NEW_TASK（Activity 上下文不加，Service 上下文加），
    // 这里传 Activity 或 Activity 派生的 Context 都安全。
    if (!canDrawOverlays(context)) {
      val activity = context as? android.app.Activity
      val error =
          if (activity != null) {
            com.termux.shared.android.PermissionUtils.requestDisplayOverOtherAppsPermission(activity)
          } else {
            com.termux.shared.android.PermissionUtils.requestDisplayOverOtherAppsPermission(context)
          }
      if (error != null) {
        // 拉不起系统页（极少数 ROM 没有该 Activity）：给出可操作的提示而不是静默。
        Toast.makeText(context, R.string.ai_agent_overlay_no_settings, Toast.LENGTH_LONG).show()
      }
      return true
    }

    // 有权限：按当前状态开关。
    if (AssistantOverlayService.isShowing) {
      AssistantOverlayService.hide(context)
    } else {
      AssistantOverlayService.show(context)
    }
    // 立即按「点击后的预期状态」刷新一次；真正的状态以后再次打开设置时重读。
    // 不写死为 isShowing 的即时值——服务启停是异步的，写死会读到旧值。
    preference.summary =
        context.getString(
            if (AssistantOverlayService.isShowing) R.string.ai_agent_overlay_off
            else R.string.ai_agent_overlay_on)
    return true
  }

  private fun canDrawOverlays(context: Context): Boolean =
      com.termux.shared.android.PermissionUtils.checkDisplayOverOtherAppsPermission(context)
}

/** 高级：提示词模板与自动切换服务商。改动频率低，但出问题时要能找到。 */
@Parcelize
private class AdvancedPage(
    override val key: String = "idepref_ai_agent_advanced",
    override val title: Int = R.string.ai_agent_page_advanced_title,
    override val summary: Int? = R.string.ai_agent_page_advanced_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(PromptTemplatePreference())
    addPreference(AutoSwitchProviderSwitch())
  }
}

/** 读写 "ai_agent_tools" 存储的助手。 */
private fun agentPrefs(context: Context) =
    context.applicationContext.getSharedPreferences("ai_agent_tools", Context.MODE_PRIVATE)

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
 * skill：查看已加载的 skill 与所在目录。
 *
 * <p><b>为什么需要查看入口</b>：skill 是用户自己放进目录的文档，而「放进去了但没生效」
 * 是最常见的困惑——文件名不对、frontmatter 格式错、正文为空都会导致它被静默跳过。
 * 这里展示实际加载到的名字与目录路径，让用户能自行定位问题。
 */
@Parcelize
private class SkillsPreference(
    override val key: String = "skills",
    override val title: Int = R.string.ai_agent_skills_title,
    override val summary: Int? = R.string.ai_agent_skills_summary,
) : BasePreference() {

  override fun onCreatePreference(context: Context): Preference {
    val count = skillRegistry(context).size()
    return androidx.preference.Preference(context).apply {
      key = "skills"
      title = context.getString(R.string.ai_agent_skills_title)
      summary =
          if (count == 0) context.getString(R.string.ai_agent_skills_none)
          else context.getString(R.string.ai_agent_skills_count, count)
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val registry = skillRegistry(context)
    val skills = registry.all()

    val message =
        buildString {
          append(context.getString(R.string.ai_agent_skills_dir))
          append('\n')
          append(skillsDir(context).absolutePath)
          append("\n\n")
          if (skills.isEmpty()) {
            append(context.getString(R.string.ai_agent_skills_none))
            append("\n\n")
            append(context.getString(R.string.ai_agent_skills_format))
          } else {
            for (skill in skills) {
              append(skill.toPromptLine()).append('\n')
            }
          }
        }

    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(R.string.ai_agent_skills_title)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok, null)
        .show()
    return true
  }

  private fun skillsDir(context: Context) =
      java.io.File(java.io.File(context.filesDir, "ai"), "skills")

  private fun skillRegistry(context: Context) =
      com.tom.rv2ide.ai.tool.skill.SkillRegistry.load(skillsDir(context))
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
              // 标出传输类型：同一个 server 用 http 还是 sse 连不通时表现完全不同
              // （前者打不开长连接，后者 POST 无响应），出问题时需要一眼看到用的是哪个。
              val type =
                  if (server.type == com.tom.rv2ide.artificial.agent.McpServers.TYPE_SSE) "SSE"
                  else "HTTP"
              "$state [$type] ${server.displayName()}"
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
                        // 必须带上 item.type：四参构造器默认成 http，
                        // 漏了它会让用户切换启用状态时把 SSE 配置静默改成 HTTP。
                        com.tom.rv2ide.artificial.agent.McpServers.Server(
                            item.url, item.label, !item.enabled, item.type)
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

    // 传输类型选择。默认选中 http：两套传输的地址形态不同（http 是消息端点、
    // sse 是事件流端点），选错时连接会以很难归因的方式失败，因此必须让用户显式选择
    // 而不是靠地址猜。
    val typeLabels =
        arrayOf(
            context.getString(R.string.ai_agent_mcp_type_http),
            context.getString(R.string.ai_agent_mcp_type_sse))
    container.addView(
        android.widget.TextView(context).apply {
          text = context.getString(R.string.ai_agent_mcp_type_label)
          setPadding(0, 24, 0, 4)
        })
    val typePicker =
        android.widget.Spinner(context).apply {
          adapter =
              android.widget.ArrayAdapter(
                  context, android.R.layout.simple_spinner_dropdown_item, typeLabels)
        }
    container.addView(typePicker)

    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(R.string.ai_agent_mcp_add)
        .setMessage(R.string.ai_agent_mcp_add_hint)
        .setView(container)
        .setPositiveButton(android.R.string.ok) { _, _ ->
          val url = urlField.text?.toString()?.trim().orEmpty()
          if (url.isNotEmpty()) {
            val type =
                if (typePicker.selectedItemPosition == 1)
                    com.tom.rv2ide.artificial.agent.McpServers.TYPE_SSE
                else com.tom.rv2ide.artificial.agent.McpServers.TYPE_HTTP
            com.tom.rv2ide.artificial.agent.McpServers(context)
                .add(
                    com.tom.rv2ide.artificial.agent.McpServers.Server(
                        url, labelField.text?.toString()?.trim().orEmpty(), true, type))
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

/**
 * 代码补全开关。
 *
 * <p>存在独立的 "ai_preferences" 存储里（键 {@code code_completion_enabled}），
 * 不是主 prefManager——{@code CodeCompletionManager} 与 {@code ChatFragment}
 * 都从这个存储读，改这里才会被它们看到。
 *
 * <p>生效方式是写偏好：`ChatFragment` 注册了该存储的变更监听，会据此挂载/卸载补全。
 * 本类不直接调用 CodeCompletionManager——那需要 Activity 级的作用域与编辑器引用，
 * 而设置页两者都没有。
 */
@Parcelize
private class CodeCompletionSwitch(
    override val key: String = "code_completion_enabled",
    override val title: Int = R.string.ai_agent_code_completion,
    override val summary: Int? = R.string.ai_agent_code_completion_summary,
) : SwitchPreference(
    setValue = { enabled ->
      com.tom.rv2ide.app.BaseApplication.getBaseInstance()
          .getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
          .edit()
          .putBoolean("code_completion_enabled", enabled)
          .apply()
    },
    getValue = {
      com.tom.rv2ide.app.BaseApplication.getBaseInstance()
          .getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
          .getBoolean("code_completion_enabled", true)
    },
) {
  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).apply {
      key = "code_completion_enabled"
      title = context.getString(R.string.ai_agent_code_completion)
      summary = context.getString(R.string.ai_agent_code_completion_summary)
    }
  }
}

/**
 * 出错时自动切换服务商。
 *
 * <p>原先只有旧侧栏的设置页能改它（`ProviderSwitchDialog.isAutoSwitchEnabled` /
 * `setAutoSwitch`，存在主偏好存储的 {@code auto_switch_providers}）。旧页面移除后
 * 没有入口，这个开关就只能一直保持上一次的值，因此在这里补上。
 */
@Parcelize
private class AutoSwitchProviderSwitch(
    override val key: String = "auto_switch_providers",
    override val title: Int = R.string.ai_agent_auto_switch,
    override val summary: Int? = R.string.ai_agent_auto_switch_summary,
) : SwitchPreference(
    setValue = { enabled -> prefManager.putBoolean("auto_switch_providers", enabled) },
    getValue = { prefManager.getBoolean("auto_switch_providers", false) },
) {
  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).apply {
      key = "auto_switch_providers"
      title = context.getString(R.string.ai_agent_auto_switch)
      summary = context.getString(R.string.ai_agent_auto_switch_summary)
    }
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

/**
 * CodeGraph 语义索引：安装、状态、卸载。
 *
 * <p><b>为什么状态要显示成三态而不是「装了/没装」</b>：CodeGraph 依赖 Termux 里的
 * Node.js 运行时，而程序本体与运行时的安装是两件独立的事。只判「程序在不在」会把
 * 「程序解包了但 node 没装」显示成可用，然后 AI 一调用就失败——用户拿不到任何线索。
 * 因此拆成「已安装 / 安装不完整（缺 X）/ 未安装」，并把缺的东西直接写在界面上。
 */
@Parcelize
private class CodeGraphPreference(
    override val key: String = "codegraph",
    override val title: Int = R.string.ai_agent_codegraph_title,
    override val summary: Int? = R.string.ai_agent_codegraph_summary,
) : BasePreference() {

  override fun onCreatePreference(context: Context): Preference =
      androidx.preference.Preference(context).apply {
        key = "codegraph"
        title = context.getString(R.string.ai_agent_codegraph_title)
      }

  /**
   * 建好视图后再异步查状态。
   *
   * <p><b>为什么不能在 [onCreatePreference] 里刷</b>：基类
   * {@code BasePreference.onCreateView} 拿到本方法的返回值**之后**，才用静态元数据覆盖
   * summary（`this.summary?.let { pref.summary = context.getString(it) }`）。
   * 在 onCreatePreference 里写 summary 会被它盖掉——实测表现是状态永远显示占位文案
   * 「未安装」，即使装好了也一样。
   *
   * <p>放在 super 之后就没有这个时序问题：那时静态 summary 已经写完了。
   */
  override fun onCreateView(context: Context): Preference {
    val pref = super.onCreateView(context)
    // 状态要读磁盘（程序体积、已索引项目数）并起一次进程跑 --version，不能放主线程。
    refreshSummary(pref)
    return pref
  }

  /** 异步查状态并刷新摘要。 */
  private fun refreshSummary(preference: Preference) {
    val context = preference.context
    val manager = CodeGraphManager(context)
    CoroutineScope(Dispatchers.IO).launch {
      val status =
          try {
            manager.status()
          } catch (e: Exception) {
            null
          }
      withContext(Dispatchers.Main) { preference.summary = describe(context, status) }
    }
  }

  /** 状态 → 一行摘要。 */
  private fun describe(context: Context, status: CodeGraphManager.Status?): String {
    if (status == null) {
      return context.getString(R.string.ai_agent_codegraph_state_missing)
    }
    return when {
      status.installed -> {
        val indexed =
            if (status.indexedProjectCount > 0) {
              " · " +
                  context.getString(
                      R.string.ai_agent_codegraph_indexed, status.indexedProjectCount)
            } else {
              ""
            }
        context.getString(
            R.string.ai_agent_codegraph_state_installed,
            status.version ?: "?",
            formatSize(status.sizeBytes),
        ) + indexed
      }
      // 只有**真的缺 Termux 包**时才报「不完整」并列出包名。反过来（包都齐了、只是程序
      // 没装或 wrapper 缺失）应报「未安装」——那时列不出任何包名，报「缺少」却空着
      // 会让用户完全摸不着头脑。这是真机验证时才暴露的问题。
      status.missingPackages.isNotEmpty() ->
          context.getString(
              R.string.ai_agent_codegraph_state_partial,
              status.missingPackages.joinToString("、"),
          )
      else -> context.getString(R.string.ai_agent_codegraph_state_missing)
    }
  }

  private fun formatSize(bytes: Long): String =
      when {
        bytes <= 0L -> "0"
        bytes >= 1024L * 1024 * 1024 ->
            String.format(java.util.Locale.US, "%.1fG", bytes / 1073741824.0)
        bytes >= 1024L * 1024 ->
            String.format(java.util.Locale.US, "%.0fM", bytes / 1048576.0)
        else -> String.format(java.util.Locale.US, "%.0fK", bytes / 1024.0)
      }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val manager = CodeGraphManager(context)
    val dialog =
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_agent_codegraph_title)
            .setMessage(R.string.ai_agent_codegraph_about)
            .setPositiveButton(R.string.ai_agent_codegraph_install, null)
            .setNeutralButton(R.string.ai_agent_codegraph_uninstall, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

    dialog.setOnShowListener {
      val install = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
      val uninstall = dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)
      install.setOnClickListener { startInstall(context, manager, dialog, install) }
      uninstall.setOnClickListener {
        // 卸载会删约 134MB 程序，先确认。索引在各项目里，不在此列。
        MaterialAlertDialogBuilder(context)
            .setMessage(R.string.ai_agent_codegraph_uninstall_confirm)
            .setPositiveButton(R.string.ai_agent_codegraph_uninstall) { _, _ ->
              CodeGraphInstaller.uninstall()
              Toast.makeText(context, R.string.ai_agent_codegraph_uninstalled, Toast.LENGTH_SHORT)
                  .show()
              dialog.dismiss()
              refreshSummary(preference)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
      }
    }
    dialog.show()
    return true
  }

  /**
   * 走完整安装流程，把进度写回按钮文字。
   *
   * <p>进度直接复用对话框的确定按钮，不额外弹一个进度框：安装是用户刚点的动作，
   * 就地反馈最不容易让人以为「没反应」。
   */
  private fun startInstall(
      context: Context,
      manager: CodeGraphManager,
      dialog: androidx.appcompat.app.AlertDialog,
      button: android.widget.Button,
  ) {
    button.isEnabled = false
    button.setText(R.string.ai_agent_codegraph_installing)
    CoroutineScope(Dispatchers.IO).launch {
      // 先查缺哪些 Termux 包：缺了就直接说清楚，别让用户等完 16MB 下载才发现跑不起来。
      val missing = CodeGraphInstaller.missingPackages()
      if (missing.isNotEmpty()) {
        val result = manager.installTermuxPackages(missing)
        if (!result.ok) {
          val output = result.combined()
          val message =
              if (output.contains("timed out") || output.contains("Could not connect")) {
                // 设备出网被拦时 apt 会超时。给出可执行的下一步，而不是笼统的「失败」。
                context.getString(
                    R.string.ai_agent_codegraph_missing_deps, missing.joinToString("、"))
              } else {
                context.getString(
                    R.string.ai_agent_codegraph_install_failed, output.take(300))
              }
          withContext(Dispatchers.Main) {
            button.isEnabled = true
            button.setText(R.string.ai_agent_codegraph_install)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
          }
          return@launch
        }
      }

      val result =
          manager.install(
              object : CodeGraphManager.ProgressListener {
                override fun onStage(message: String) {}

                override fun onProgress(fraction: Float?) {
                  if (fraction == null) {
                    return
                  }
                  val percent = (fraction * 100).toInt()
                  // 回调在 IO 线程，改控件必须切主线程。
                  CoroutineScope(Dispatchers.Main).launch {
                    button.text =
                        context.getString(R.string.ai_agent_codegraph_downloading, percent)
                  }
                }
              })

      withContext(Dispatchers.Main) {
        button.isEnabled = true
        button.setText(R.string.ai_agent_codegraph_install)
        if (result.isSuccess) {
          Toast.makeText(context, R.string.ai_agent_codegraph_install_ok, Toast.LENGTH_SHORT)
              .show()
          dialog.dismiss()
        } else {
          Toast.makeText(
                  context,
                  context.getString(
                      R.string.ai_agent_codegraph_install_failed,
                      result.exceptionOrNull()?.message ?: "?",
                  ),
                  Toast.LENGTH_LONG,
              )
              .show()
        }
      }
    }
  }
}
