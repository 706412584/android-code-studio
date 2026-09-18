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

    val providerList = root.findViewById<ViewGroup>(R.id.pickerProviderList)
    val modelList = root.findViewById<ViewGroup>(R.id.pickerModelList)

    /**
     * 重建两段列表。
     *
     * <p>每次选择后整体重建而不是局部改勾：服务商切换会连带换掉整个模型列表，
     * 局部更新的分支比重新渲染更容易写错，而这里的行数最多二十几行，重建的开销可以忽略。
     */
    fun rebuild() {
      val currentProvider = agents.getProvider()
      val currentModel = agents.getAgent()

      providerList.removeAllViews()
      // 用户记录优先、预设兜底，且按 id 去重：用户可能给某个预设服务商建了记录
      // （改了 baseUrl 或密钥），此时只需显示一条，标签用用户自己起的名字。
      val records = ProviderConfigStore(context).load()
      val recordIds = records.map { it.getId() }
      val orderedIds = recordIds + ProviderPresets.allIds().filterNot { recordIds.contains(it) }
      for (providerId in orderedIds) {
        val record = records.firstOrNull { it.getId() == providerId }
        val label = record?.getLabel() ?: ProviderPresets.labelFor(providerId)
        // 「可用」的判据复用发请求时那条路径（AgentModelConfigs.endpointFor），
        // 而不是在这里另判一次「密钥非空」：后者会漏掉自定义端点
        // （无密钥、但需要 baseUrl），更糟的是会在界面说「可用」而请求仍失败。
        val usable =
            AgentModelConfigs.isProviderUsable(
                providerId,
                AgentOrchestrator.customBaseUrlFor(context, providerId),
            )
        providerList.addView(
            pickerRow(
                context = context,
                container = providerList,
                title = label,
                // 不可用时把原因写在副标题里。只靠颜色区分的话，用户要先切过去、
                // 发一条消息、失败，才知道问题在哪。
                subtitle =
                    if (usable) null else context.getString(string.ai_assistant_no_api_key),
                selected = providerId == currentProvider,
                onClick = {
                  switchProvider(context, agents, providerId)
                  onChanged()
                  // 不关闭：换完服务商通常还要挑模型。
                  rebuild()
                },
            ))
      }

      modelList.removeAllViews()
      for (model in modelsFor(providerId = currentProvider, currentModel = currentModel)) {
        modelList.addView(
            pickerRow(
                context = context,
                container = modelList,
                title = model,
                subtitle = null,
                selected = model == currentModel,
                onClick = {
                  agents.setAgent(model)
                  onChanged()
                  sheet.dismiss()
                },
            ))
      }

      // 自定义端点没有预设模型列表，得让用户自己填模型名——否则选中它之后
      // 模型永远是空的，请求必然失败。
      if (currentProvider == "custom") {
        modelList.addView(
            pickerRow(
                context = context,
                container = modelList,
                title = context.getString(string.ai_agent_custom_model),
                subtitle = context.getString(string.ai_assistant_custom_model_hint),
                selected = false,
                onClick = { promptCustomModel(context, agents, onChanged, sheet) },
            ))
      }

      // 本地模型需要 baseUrl + 模型名，两个值都只有用户知道。原先这个入口在旧侧栏的
      // 设置页里（LocalLLMConfigDialog），移除侧栏后没有别处能设——不在这里补上，
      // 「本地模型」会是一个选中即失败的选项。
      if (currentProvider == "localllm") {
        modelList.addView(
            pickerRow(
                context = context,
                container = modelList,
                title = context.getString(string.ai_assistant_configure_local),
                subtitle = localModelSummary(context),
                selected = false,
                onClick = { promptLocalModel(context, agents, onChanged, sheet) },
            ))
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

  /** 当前服务商的模型清单；用户记录优先，其次预设，最后回退到当前值。 */
  private fun modelsFor(providerId: String, currentModel: String): List<String> {
    // 记录里的非空槽位都列出来，让用户在助手面板里就能在 main/haiku/sonnet/opus
    // 之间切——否则配了 4 个槽位却只能用到 main，等于白配。
    val record = AgentModelConfigs.recordFor(providerId)
    if (record != null) {
      val fromSlots =
          ProviderConfig.SLOT_ORDER.map { record.resolveSlot(it) }
              .filter { it.isNotBlank() }
              .distinct()
              .map { ContextSizeParser.stripSuffix(it) }
      if (fromSlots.isNotEmpty()) {
        return fromSlots
      }
    }

    val preset = ProviderPresets.modelsFor(providerId)
    if (preset.isNotEmpty()) {
      return preset
    }
    // 自定义端点：预设表里没有模型，把用户已填的那个（如果有）当作唯一选项，
    // 否则「模型」段会是空的，看起来像界面坏了。
    return if (currentModel.isNotBlank()) listOf(currentModel) else emptyList()
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
      onClick: () -> Unit,
  ): View {
    val binding =
        ItemAssistantPickerRowBinding.inflate(LayoutInflater.from(context), container, false)
    binding.pickerRowTitle.text = title
    // 选中态用主题主色。取色走 android.R.attr.colorPrimary 而不是
    // com.google.android.material.R.attr.colorPrimary —— 后者里根本没有这个 attr
    // （Material3 的 colorPrimary 落在 android 命名空间），引用它编译不过。
    binding.pickerRowTitle.setTextColor(
        com.google.android.material.color.MaterialColors.getColor(
            binding.root,
            if (selected) android.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorOnSurface,
        ))
    binding.pickerRowSubtitle.text = subtitle
    binding.pickerRowSubtitle.visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
    binding.pickerRowCheck.visibility = if (selected) View.VISIBLE else View.GONE
    binding.root.setOnClickListener { onClick() }
    return binding.root
  }

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
