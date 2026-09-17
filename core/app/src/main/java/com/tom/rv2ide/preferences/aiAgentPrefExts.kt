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
    addPreference(PermissionModePreference())
    addPreference(AuthorizedToolsPreference())
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
