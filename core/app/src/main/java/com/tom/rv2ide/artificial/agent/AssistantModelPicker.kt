/*
 * This file is part of AndroidCodeStudio.
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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.secrets.ApiKey
import com.tom.rv2ide.databinding.ItemAssistantPickerRowBinding
import com.tom.rv2ide.resources.R.string

/**
 * 悬浮助手的服务商 / 模型选择面板。
 *
 * <p><b>为什么放进面板而不是设置页</b>：切换模型是高频操作——遇到复杂任务换个强模型、
 * 额度用完换个服务商，都在对话中途发生。埋进「设置 → AI 助手」意味着每次切换要离开对话、
 * 点进三层、回来、再滚回原来的位置。设置页仍然保留（那里配密钥），但选择本身必须在
 * 对话旁边。
 *
 * <p><b>服务商与模型分两段而不是两个下拉框</b>：两者是联动关系（换服务商要连带换模型），
 * 分两段可以就地刷新模型列表，用户看得见「我换了服务商，模型也跟着变了」。
 * 两个下拉框做不到这种可见性。
 *
 * <p><b>为什么点服务商不关闭面板</b>：换服务商后用户大概率还要挑一个模型。
 * 关闭再重开是一次多余操作。选模型才是终点，所以只有它关闭面板。
 */
object AssistantModelPicker {

  /**
   * 显示选择面板。
   *
   * @param onChanged 选择生效后的回调，用于刷新面板标题上的「服务商 / 模型」文案
   */
  fun show(context: Context, onChanged: () -> Unit) {
    val agents = Agents(context)
    val sheet = BottomSheetDialog(context)
    val root =
        LayoutInflater.from(context)
            .inflate(R.layout.dialog_assistant_model_picker, null, false)

    val list = root.findViewById<ViewGroup>(R.id.pickerList)
    val empty = root.findViewById<View>(R.id.pickerEmpty)

    /**
     * 重建整个列表。
     *
     * <p>每次选择后整体重建而不是局部改勾：切换服务商或模型会连带改变选中态与
     * 「当前」标记，局部更新的分支比重新渲染更容易写错，而行数最多几十行。
     */
    fun rebuild() {
      val currentProvider = agents.getProvider()
      val currentModel = agents.getAgent()

      list.removeAllViews()
      // 只列**已配置**的服务商：没填密钥的切过去必然失败，预设里那十几个
      // 模型名对用户只是噪音。这与参考项目（cc-haha）一致。
      val configured = configuredProviders(context)

      empty.visibility = if (configured.isEmpty()) View.VISIBLE else View.GONE

      for (entry in configured) {
        list.addView(
            groupHeader(
                context = context,
                container = list,
                entry = entry,
                selected = entry.id == currentProvider,
                onClick = {
                  switchProvider(context, agents, entry.id)
                  onChanged()
                  // 不关闭：换完服务商通常还要在它下面挑模型。
                  rebuild()
                },
            ))

        for (model in entry.models) {
          list.addView(
              pickerRow(
                  context = context,
                  container = list,
                  title = model,
                  // 「当前」标记只在**当前服务商**的当前模型上出现：
                  // 两个服务商可能配了同名模型，都标上会让用户分不清实际在用哪个。
                  subtitle =
                      if (model == currentModel && entry.id == currentProvider) {
                        context.getString(string.ai_assistant_model_current)
                      } else {
                        null
                      },
                  selected = model == currentModel && entry.id == currentProvider,
                  indent = true,
                  onClick = {
                    // 先写模型再写服务商：setAgent 会按模型名反查服务商并覆写 provider，
                    // 顺序反了会被这个反查覆盖掉。
                    agents.setAgent(model)
                    agents.setProvider(entry.id)
                    onChanged()
                    sheet.dismiss()
                  },
              ))
        }

        // 自定义端点与本地模型需要用户自己填值，各补一个入口行。
        if (entry.id == CUSTOM_PROVIDER_ID) {
          list.addView(
              pickerRow(
                  context = context,
                  container = list,
                  title = context.getString(string.ai_agent_custom_model),
                  subtitle = context.getString(string.ai_assistant_custom_model_hint),
                  selected = false,
                  indent = true,
                  onClick = { promptCustomModel(context, agents, onChanged, sheet) },
              ))
        }
        if (entry.id == LOCAL_PROVIDER_ID) {
          list.addView(
              pickerRow(
                  context = context,
                  container = list,
                  title = context.getString(string.ai_assistant_configure_local),
                  subtitle = localModelSummary(context),
                  selected = false,
                  indent = true,
                  onClick = { promptLocalModel(context, agents, onChanged, sheet) },
              ))
        }
      }
    }

    root.findViewById<View>(R.id.pickerSettings).setOnClickListener {
      sheet.dismiss()
      AssistantSettings.open(context)
    }

    sheet.setContentView(root)
    rebuild()
    sheet.show()
  }

  /** 一个已配置的服务商及其可用模型。 */
  private class ProviderEntry(val id: String, val label: String, val models: List<String>)

  /**
   * 已配置的服务商清单（含各自已配置的模型）。
   *
   * <p><b>「已配置」的判据必须与发请求那条路径完全一致</b>（[AgentModelConfigs.isProviderUsable]）。
   * 但**只遍历 [ProviderConfigStore] 的记录是不够的**：判据读的是 `ApiKey` 的偏好槽位，
   * 与 `providers.json` 无关。两者会分叉的实测情形：
   *
   * - `providers.json` 不存在，而 `migrateLegacyKeys` 没产出任何记录——它只迁移
   *   **有独立密钥**的服务商，且共用密钥槽位只归给「当前选中的、非 custom 的」服务商。
   *   用户密钥落在共用槽位而当前服务商是 custom 时，记录为空。
   * - 用户直接改过偏好（不经服务商管理界面）。
   *
   * 此时旧逻辑返回空列表 → 选择器显示「还没有配置任何服务商」，而当前服务商其实可用。
   *
   * <p>修法：以**记录**为主（它带 label 与各槽位模型），再补上「记录里没有、但当前
   * 正被使用且可用」的服务商。后者没有记录可查，模型名只能取当前值。
   */
  private fun configuredProviders(context: Context): List<ProviderEntry> {
    val records = ProviderConfigStore(context).load()
    val result = ArrayList<ProviderEntry>()
    val seen = HashSet<String>()
    for (record in records) {
      val id = record.getId()
      if (!AgentModelConfigs.isProviderUsable(id, AgentOrchestrator.customBaseUrlFor(context, id))) {
        continue
      }
      // 模型一个都没配的服务商仍然列出来：用户点它至少能切到该服务商，
      // 再由下面那个「填写模型名」的行引导他去填。整条隐藏会让他以为
      // 「我明明加了服务商，为什么列表里没有」。
      result.add(ProviderEntry(id, record.getLabel(), configuredModels(record)))
      seen.add(id)
    }

    // 兜底：当前服务商可用但没有记录，补一条，否则用户看不到自己正在用的那个。
    val agents = Agents(context)
    val currentId = agents.getProvider()
    if (!seen.contains(currentId) &&
        AgentModelConfigs.isProviderUsable(
            currentId, AgentOrchestrator.customBaseUrlFor(context, currentId))) {
      val currentModel = ContextSizeParser.stripSuffix(agents.getAgent())
      result.add(
          ProviderEntry(
              currentId,
              ProviderPresets.labelFor(currentId),
              if (currentModel.isBlank()) emptyList() else listOf(currentModel),
          ))
    }
    return result
  }

  /**
   * 某服务商**已配置**的模型清单。
   *
   * <p>取记录里的非空槽位，按槽位顺序去重。刻意**不回退到预设表**：
   * 预设那十几个模型名用户大多没配过，列出来点一下就切过去了，然后请求失败——
   * 用户会以为是自己选错了模型，而不是「这个模型还没配」。
   *
   * <p>槽位为空表示「与主模型相同」，因此 `resolveSlot` 会把空槽位折回主模型，
   * 去重后不会重复出现。
   */
  private fun configuredModels(record: ProviderConfig): List<String> {
    return ProviderConfig.SLOT_ORDER.map { record.resolveSlot(it) }
        .filter { it.isNotBlank() }
        .distinct()
        .map { ContextSizeParser.stripSuffix(it) }
  }

  /**
   * 切换服务商，并把模型一并换成该服务商的推荐值。
   *
   * <p>必须连带换模型：模型名属于服务商（`gpt-4o` 发给 DeepSeek 必然 404），
   * 只换服务商会让下一个请求立刻失败，而用户以为是自己选错了。
   *
   * <p>写入顺序是先模型后服务商：{@code Agents.setAgent} 会按模型名反查服务商并写一次
   * provider，若先写 provider 就会被这个反查覆盖回去。
   */
  private fun switchProvider(context: Context, agents: Agents, providerId: String) {
    // 用户记录里的模型优先：那是用户在服务商管理界面显式配过的值。
    // 预设只是「没配过时的建议」，不该覆盖用户的配置。
    val record = AgentModelConfigs.recordFor(providerId)
    val fromRecord = record?.getMainModel().orEmpty()
    if (fromRecord.isNotEmpty()) {
      agents.setAgent(ContextSizeParser.stripSuffix(fromRecord))
      agents.setProvider(providerId)
      return
    }

    val models = ProviderPresets.modelsFor(providerId)
    if (models.isNotEmpty()) {
      agents.setAgent(models[0])
    }
    agents.setProvider(providerId)

    // 自定义端点的模型名不在预设表里，只能沿用用户此前填过的值；没填过就留空，
    // 由下面的「模型」行引导他去填。
    if (providerId == "custom" && ApiKey.getCustomModel().isNotBlank()) {
      agents.setAgent(ApiKey.getCustomModel())
    }
  }

  private fun promptCustomModel(
      context: Context,
      agents: Agents,
      onChanged: () -> Unit,
      sheet: BottomSheetDialog,
  ) {
    val input =
        android.widget.EditText(context).apply {
          setText(ApiKey.getCustomModel())
          hint = context.getString(string.ai_assistant_custom_model_hint)
          setSingleLine(true)
        }
    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(string.ai_agent_custom_model)
        .setView(input)
        .setPositiveButton(android.R.string.ok) { _, _ ->
          val model = input.text.toString().trim()
          if (model.isNotEmpty()) {
            ApiKey.setCustomModel(model)
            agents.setAgent(model)
            onChanged()
          }
          sheet.dismiss()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  /** 本地模型的当前配置，作为配置行的副标题。未配置时返回 null（不显示副标题）。 */
  private fun localModelSummary(context: Context): String? {
    val baseUrl = localPrefs(context).getString(KEY_LOCAL_BASE_URL, null)
    val model = localPrefs(context).getString(KEY_LOCAL_MODEL_NAME, null)
    if (baseUrl.isNullOrBlank() || model.isNullOrBlank()) {
      return null
    }
    return "$baseUrl · $model"
  }

  /**
   * 编辑本地模型配置（baseUrl + 模型名）。
   *
   * <p>用两个输入框而不是复用 [com.tom.rv2ide.artificial.dialogs.LocalLLMConfigDialog]：
   * 那是个 `BottomSheetDialogFragment`，从 BottomSheet 里再弹 BottomSheet 会叠两层
   * 遮罩，且它依赖 `parentFragmentManager`——悬浮助手是从 Activity 起的，没有
   * FragmentManager 可用。
   */
  private fun promptLocalModel(
      context: Context,
      agents: Agents,
      onChanged: () -> Unit,
      sheet: BottomSheetDialog,
  ) {
    val prefs = localPrefs(context)
    val baseUrlInput =
        android.widget.EditText(context).apply {
          setText(prefs.getString(KEY_LOCAL_BASE_URL, DEFAULT_LOCAL_BASE_URL))
          hint = context.getString(string.ai_agent_custom_base_url)
          setSingleLine(true)
        }
    val modelInput =
        android.widget.EditText(context).apply {
          setText(prefs.getString(KEY_LOCAL_MODEL_NAME, DEFAULT_LOCAL_MODEL))
          hint = context.getString(string.ai_agent_custom_model)
          setSingleLine(true)
        }
    val container =
        android.widget.LinearLayout(context).apply {
          orientation = android.widget.LinearLayout.VERTICAL
          setPadding(dp(context, 24), dp(context, 8), dp(context, 24), 0)
          addView(baseUrlInput)
          addView(modelInput)
        }

    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(string.ai_assistant_configure_local)
        .setView(container)
        .setPositiveButton(android.R.string.ok) { _, _ ->
          val baseUrl = baseUrlInput.text.toString().trim()
          val model = modelInput.text.toString().trim()
          prefs.edit()
              .putString(KEY_LOCAL_BASE_URL, baseUrl)
              .putString(KEY_LOCAL_MODEL_NAME, model)
              .apply()
          // 模型名也写进 agents：请求取的是 Agents.getAgent()，
          // 而本地模型的模型名不属于任何预设，必须显式同步过去。
          if (model.isNotEmpty()) {
            agents.setAgent(model)
          }
          onChanged()
          sheet.dismiss()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  /**
   * 本地模型配置的存储。
   *
   * <p>刻意用默认偏好存储（与 `LocalLLM` 的 `BaseApplication.prefManager` 同一份），
   * 而不是新建一份：`LocalLLM.hasValidApiKey` 与 `initialize` 都从这里读，
   * 写成别的存储会让配置看起来保存了但实际不生效。
   */
  private fun localPrefs(context: Context) =
      androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)

  private fun dp(context: Context, value: Int): Int =
      (value * context.resources.displayMetrics.density).toInt()

  /** 一行选项。选中态用勾 + 主色标题表示（仅描边在深色主题下不够明显）。 */
  private fun pickerRow(
      context: Context,
      container: ViewGroup,
      title: String,
      subtitle: String?,
      selected: Boolean,
      indent: Boolean,
      onClick: () -> Unit,
  ): View {
    val binding =
        ItemAssistantPickerRowBinding.inflate(LayoutInflater.from(context), container, false)
    binding.pickerRowTitle.text = title
    // 选中态用主题主色。取**应用**命名空间的 R.attr.colorPrimary：
    // Material 的 R.attr 里确实没有 colorPrimary（那样写编译不过），但应用侧有，
    // 且 ACS 主题定义的正是应用那一份。写 android.R.attr.colorPrimary 只是碰巧
    // 与框架默认值同值才看起来对，主题一旦偏离就会取错。
    binding.pickerRowTitle.setTextColor(
        com.google.android.material.color.MaterialColors.getColor(
            binding.root,
            if (selected) R.attr.colorPrimary
            else com.google.android.material.R.attr.colorOnSurface,
        ))
    binding.pickerRowSubtitle.text = subtitle
    binding.pickerRowSubtitle.visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
    binding.pickerRowCheck.visibility = if (selected) View.VISIBLE else View.GONE
    if (indent) {
      // 在原有内边距之上再缩进一级。加而不是覆盖：模板里的 padding 是给
      // 组标题用的，模型行需要「组标题的位置 + 一级缩进」。
      val extra = dp(context, 20)
      binding.root.setPadding(
          binding.root.paddingStart + extra,
          binding.root.paddingTop,
          binding.root.paddingEnd,
          binding.root.paddingBottom,
      )
    }
    binding.root.setOnClickListener { onClick() }
    return binding.root
  }

  /**
   * 服务商组标题。
   *
   * <p>点它 = 切到该服务商的**主模型**（模型行是切到具体某个模型）。
   * 这样「换个服务商随便用用」不需要先展开再挑模型，一次点击到位。
   */
  private fun groupHeader(
      context: Context,
      container: ViewGroup,
      entry: ProviderEntry,
      selected: Boolean,
      onClick: () -> Unit,
  ): View {
    val binding =
        ItemAssistantPickerRowBinding.inflate(LayoutInflater.from(context), container, false)
    binding.pickerRowTitle.text = entry.label
    binding.pickerRowTitle.setTextColor(
        com.google.android.material.color.MaterialColors.getColor(
            binding.root,
            if (selected) R.attr.colorPrimary
            else com.google.android.material.R.attr.colorOnSurface,
        ))
    binding.pickerRowSubtitle.text =
        context.getString(string.ai_assistant_model_configured) + " · " + entry.models.size
    binding.pickerRowSubtitle.visibility = View.VISIBLE
    binding.pickerRowCheck.visibility = if (selected) View.VISIBLE else View.GONE
    binding.root.setOnClickListener { onClick() }
    return binding.root
  }

  /** 自定义端点的服务商 id。与 ProviderPresets 里的一致。 */
  private const val CUSTOM_PROVIDER_ID = "custom"

  /** 本地模型的服务商 id。 */
  private const val LOCAL_PROVIDER_ID = "localllm"

  /** 本地模型配置的偏好键。与 `LocalLLM` 读取的键一致。 */
  private const val KEY_LOCAL_BASE_URL = "local_llm_base_url"
  private const val KEY_LOCAL_MODEL_NAME = "local_llm_model_name"

  /** 与 `LocalLLMConfigDialog` 里的默认值保持一致。 */
  private const val DEFAULT_LOCAL_BASE_URL = "http://localhost:1234"
  private const val DEFAULT_LOCAL_MODEL = "local-model"

  /** 面板标题栏上的「服务商 / 模型」文案。 */
  fun summaryLabel(context: Context): String {
    val agents = Agents(context)
    val providerId = agents.getProvider()
    return context.getString(
        string.ai_assistant_model_summary,
        ProviderPresets.labelFor(providerId),
        agents.getAgent(),
    )
  }
}
