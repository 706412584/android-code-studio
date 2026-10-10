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
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import androidx.preference.Preference
import com.tom.rv2ide.databinding.DialogProviderFormBinding
import com.tom.rv2ide.ai.protocol.ModelProtocolType
import com.tom.rv2ide.artificial.agent.ContextSizeParser
import com.tom.rv2ide.artificial.agent.ModelCatalogFetcher
import com.tom.rv2ide.artificial.agent.ProviderConfig
import com.tom.rv2ide.artificial.agent.ProviderConfigStore
import com.tom.rv2ide.artificial.agent.ProviderPresets
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.resources.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

  private companion object {
    /** 1M 窗口的 token 数，由解析器算出，避免在 UI 里散落字面量 1000000。 */
    private val ONE_M_TOKENS: Int = ContextSizeParser.parse("1m")
  }

  /**
   * 程序化回填勾选态期间置 true，让 {@code setOnCheckedChangeListener} 跳过 {@link #apply1m}。
   *
   * <p>没有这个守卫就会静默丢后缀：从声明 {@code [1m]} 的槽位切到声明 {@code [200k]} 的槽位时，
   * 回填把勾选框由「勾」改「不勾」会触发监听器，而监听器按「不勾」把模型名改写成无后缀，
   * 用户的 {@code [200k]} 就没了。勾选框只应响应用户的真实点击。
   */
  private var suppress1mListener = false

  override fun onCreatePreference(context: Context): Preference {
    return Preference(context).apply {
      key = this@ProviderManagementPreference.key
      title = context.getString(this@ProviderManagementPreference.title)
      summary = summaryText(context)
    }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    showList(context) { preference.summary = summaryText(context) }
    return true
  }

  /** 副标题给出「共几个 / 几个可用」，让用户不必点进去就知道状态。 */
  private fun summaryText(context: Context): String {
    val records = ProviderConfigStore(context).load()
    val usable = records.count { it.isUsable() }
    return "${context.getString(R.string.ai_agent_providers_summary)} · $usable/${records.size}"
  }

  // ---- 列表 ----

  private fun showList(context: Context, onChanged: () -> Unit) {
    val records = ProviderConfigStore(context).load()
    val labels =
        if (records.isEmpty()) {
          arrayOf(context.getString(R.string.ai_agent_provider_empty))
        } else {
          records.map { "${it.getLabel()}  ·  ${stateText(context, it)}" }.toTypedArray()
        }

    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(R.string.ai_agent_providers_title)
        .setItems(labels) { _, which ->
          // 空列表时只有一条提示项，点它等同于点「添加」。
          if (records.isEmpty()) showForm(context, null, onChanged)
          else showActions(context, records[which], onChanged)
        }
        .setPositiveButton(R.string.ai_agent_provider_add) { _, _ ->
          showForm(context, null, onChanged)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private fun stateText(context: Context, record: ProviderConfig): String =
      when {
        !record.isUsable() -> context.getString(R.string.ai_agent_provider_incomplete)
        record.needsApiKey() -> context.getString(R.string.ai_agent_provider_no_key)
        else -> context.getString(R.string.ai_agent_provider_ready)
      }

  private fun showActions(context: Context, record: ProviderConfig, onChanged: () -> Unit) {
    val options =
        arrayOf(
            context.getString(R.string.ai_agent_provider_edit),
            context.getString(R.string.ai_agent_provider_delete),
        )
    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(record.getLabel())
        .setItems(options) { _, which ->
          when (which) {
            0 -> showForm(context, record, onChanged)
            1 -> confirmDelete(context, record, onChanged)
          }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private fun confirmDelete(context: Context, record: ProviderConfig, onChanged: () -> Unit) {
    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setMessage(context.getString(R.string.ai_agent_provider_delete_confirm, record.getLabel()))
        .setPositiveButton(R.string.ai_agent_provider_delete) { _, _ ->
          ProviderConfigStore(context).delete(record.getId())
          onChanged()
          showList(context, onChanged)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  // ---- 表单 ----

  /**
   * 添加 / 编辑表单。
   *
   * <p>用 XML 布局（{@code dialog_provider_form.xml}）而不是代码构建：本仓库其它
   * 对话框（dialog_add_remote / dialog_clone / dialog_branch_name）统一用
   * TextInputLayout + OutlinedBox，标签浮动在边框上。代码构建的裸 EditText
   * 加一行独立 TextView 标签，看起来像另一种设计语言，且输入后标签不会浮动、
   * 看不出哪个框已填。
   *
   * <p>新建与编辑共用同一份布局，差异只有两处：新建时显示「从预设开始」下拉，
   * 编辑时隐藏它、并把密钥框提示改成「留空表示保留当前密钥」。
   */
  private fun showForm(context: Context, existing: ProviderConfig?, onChanged: () -> Unit) {
    val isNew = existing == null
    val presets = ProviderPresets.all()
    val seed =
        existing
            ?: presets.firstOrNull()?.let { ProviderConfigStore.presetToConfig(it, "") }
            ?: ProviderConfig("", "", ModelProtocolType.OPENAI_COMPATIBLE, "", "", emptyArray())

    val binding = DialogProviderFormBinding.inflate(LayoutInflater.from(context))
    val protocolTypes = ModelProtocolType.entries
    val slotInputs = slotInputs(binding)
    val slotChecks = slotChecks(binding)

    slotChecks.forEachIndexed { i, check ->
      check.setOnCheckedChangeListener { _, isChecked -> apply1m(binding, i, isChecked) }
    }

    // 下拉选中后重算该槽位的「1M」勾选态——拉回的模型名不带后缀，
    // 与旧「底部清单点选」行为保持一致。
    slotInputs.forEachIndexed { i, input ->
      input.setOnItemClickListener { _, _, _, _ -> sync1mCheck(binding, i) }
    }

    // 预设下拉（仅新建）。预设是「快速填充」而不是唯一来源：选中只覆盖连接信息，
    // 不动密钥——用户可能已经输入了密钥。
    val presetLabels = presets.map { it.getLabel() }
    if (isNew) {
      binding.providerPreset.setSimpleItems(presetLabels.toTypedArray())
      binding.providerPreset.setText(presetLabels.firstOrNull().orEmpty(), false)
      binding.providerPreset.setOnItemClickListener { _, _, position, _ ->
        val preset = presets.getOrNull(position) ?: return@setOnItemClickListener
        binding.providerName.setText(preset.getId())
        binding.providerBaseUrl.setText(preset.getBaseUrl())
        // 协议必须跟着预设走：Anthropic Messages 与 OpenAI 兼容是两套完全不同的
        // 请求格式，选了 Grok 却留着 Anthropic 协议，请求必然失败。
        val index = protocolTypes.indexOf(preset.getProtocolType())
        if (index >= 0) {
          binding.providerProtocol.setText(protocolTypes[index].getLabel(), false)
        }
        preset.getSlotModels().forEachIndexed { i, model ->
          slotInputs.getOrNull(i)?.setText(model)
        }
        slotChecks.indices.forEach { sync1mCheck(binding, it) }
      }
    } else {
      binding.presetLayout.visibility = View.GONE
    }

    binding.providerName.setText(seed.getId())
    binding.providerBaseUrl.setText(seed.getBaseUrl())
    if (!isNew) {
      binding.providerApiKey.hint = context.getString(R.string.ai_agent_provider_api_key_keep)
    }
    binding.providerProtocol.setSimpleItems(
        protocolTypes.map { it.getLabel() }.toTypedArray()
    )
    binding.providerProtocol.setText(seed.getProtocolType().getLabel(), false)
    seed.getSlotModels().forEachIndexed { i, model -> slotInputs.getOrNull(i)?.setText(model) }
    slotChecks.indices.forEach { sync1mCheck(binding, it) }

    /** 读表单成草稿。密钥框留空时沿用 seed 的密钥（编辑场景）。 */
    fun readDraft(): ProviderConfig {
      val typedKey = binding.providerApiKey.text?.toString()?.trim().orEmpty()
      val key = if (typedKey.isEmpty()) seed.getApiKey() else typedKey
      val id = binding.providerName.text?.toString()?.trim().orEmpty()
      val selectedProtocol =
          protocolTypes.getOrElse(protocolTypes.indexOfFirst { it.getLabel() == binding.providerProtocol.text.toString() }) {
            ModelProtocolType.OPENAI_COMPATIBLE
          }
      // 槽位模型名以勾选框为准再对一次账：用户可能先勾「1M」、后手填模型名，
      // 此时输入框还没有后缀，只靠 setOnCheckedChangeListener 会漏掉这次勾选。
      // 只「加」不「减」：未勾选时原样保留，避免抹掉用户手输的 [200k]/[1m]。
      val models =
          slotInputs.mapIndexed { i, input ->
            val raw = input.text?.toString()?.trim().orEmpty()
            if (slotChecks.getOrNull(i)?.isChecked == true) with1mSuffix(raw, true) else raw
          }
      // ProviderConfig 是 Java 类，只能按位置传参。
      return ProviderConfig(
          id,
          id,
          selectedProtocol,
          binding.providerBaseUrl.text?.toString()?.trim().orEmpty(),
          key,
          models.toTypedArray(),
      )
    }

    wireFetch(context, binding, { readDraft() })

    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(if (isNew) R.string.ai_agent_provider_add else R.string.ai_agent_provider_edit)
        .setView(binding.root)
        .setPositiveButton(R.string.ai_agent_provider_save) { _, _ ->
          val draft = readDraft()
          // 校验放在提交时而不是禁用按钮：AlertDialog 的按钮在 show 之前拿不到。
          if (draft.getId().isEmpty() ||
              draft.getBaseUrl().isEmpty() ||
              draft.getMainModel().isEmpty()) {
            Toast.makeText(context, R.string.ai_agent_provider_required, Toast.LENGTH_LONG).show()
            return@setPositiveButton
          }
          ProviderConfigStore(context).upsert(draft)
          syncCurrentSelection(context, draft)
          onChanged()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  /**
   * 把「当前正在用的模型」同步成刚保存的主模型。
   *
   * <p><b>为什么必须同步</b>：请求路径解析模型名时，用户在面板上的显式选择优先于记录里的
   * 主模型（见 `AgentModelConfigs.modelIdFor`），而那份显式选择存在 `Agents` 偏好里。
   * 本界面只写 `providers.json` 记录——不改偏好就会出现「在管理界面把主模型从 A 改成 B、
   * 面板也显示 B、实际请求却仍发 A」的假反馈。
   *
   * <p>只对**当前服务商**同步：改别的服务商的模型不该影响眼下正在用的这个。
   *
   * <p>存去后缀的名字，与选择器里的写法一致（`AssistantModelPicker.switchProvider`）——
   * 后缀是本地元数据，由槽位声明承载，不该混进「当前选择」这个值里。
   */
  private fun syncCurrentSelection(context: Context, saved: ProviderConfig) {
    val agents = Agents(context)
    if (agents.getProvider() != saved.getId()) {
      return
    }
    val main = ContextSizeParser.stripSuffix(saved.getMainModel())
    if (main.isNotEmpty()) {
      agents.setAgent(main)
    }
  }

  /**
   * 接上「获取模型」按钮。
   *
   * <p>拉取成功后把真实清单注入 4 个槽位下拉框（ExposedDropdownMenu）——用户点输入框
   * 右侧箭头即可从列表直选，也可继续手输，与协议框同一交互。拉取失败只提示，不阻断
   * 保存：有些服务商不实现这个端点，而用户手填模型名照样能用。
   */
  private fun wireFetch(
      context: Context,
      binding: DialogProviderFormBinding,
      readDraft: () -> ProviderConfig,
  ) {
    val button = binding.providerFetchModels

    button.setOnClickListener {
      val draft = readDraft()
      button.isEnabled = false
      button.setText(R.string.ai_agent_provider_fetching)
      // 网络调用不能阻塞 UI 线程。
      CoroutineScope(Dispatchers.IO).launch {
        val models = ModelCatalogFetcher.fetch(draft)
        withContext(Dispatchers.Main) {
          button.isEnabled = true
          button.setText(R.string.ai_agent_provider_fetch_models)
          if (models.isEmpty()) {
            Toast.makeText(context, R.string.ai_agent_provider_fetch_empty, Toast.LENGTH_LONG)
                .show()
            return@withContext
          }
          Toast.makeText(
                  context,
                  context.getString(R.string.ai_agent_provider_fetch_ok, models.size),
                  Toast.LENGTH_SHORT,
              )
              .show()
          injectModelChoices(binding, models)
        }
      }
    }
  }

  /**
   * 把拉取到的模型清单塞进每个槽位下拉框。
   *
   * <p>下拉项用 {@link ContextSizeParser#stripSuffix} 剥掉后缀——后缀是本地元数据，
   * 由「1M」勾选框承载（点选后 {@link #sync1mCheck} 会按新名重算勾选态）。
   */
  private fun injectModelChoices(binding: DialogProviderFormBinding, models: List<String>) {
    val names = models.map { ContextSizeParser.stripSuffix(it) }.distinct()
    slotInputs(binding).forEach { input ->
      input.setSimpleItems(names.toTypedArray())
    }
  }

  /** 4 个模型槽位下拉框，顺序与 {@link ProviderConfig#SLOT_ORDER} 一致。 */
  private fun slotInputs(binding: DialogProviderFormBinding): List<MaterialAutoCompleteTextView> =
      listOf(
          binding.providerSlotMain,
          binding.providerSlotHaiku,
          binding.providerSlotSonnet,
          binding.providerSlotOpus,
      )

  /** 4 个「1M」勾选框，顺序与 {@link #slotInputs} 一一对应。 */
  private fun slotChecks(binding: DialogProviderFormBinding): List<android.widget.CheckBox> =
      listOf(
          binding.providerSlotMain1m,
          binding.providerSlotHaiku1m,
          binding.providerSlotSonnet1m,
          binding.providerSlotOpus1m,
      )

  /**
   * 按勾选态给模型名加/去 {@code [1m]} 后缀，返回处理后的模型名。
   *
   * <p>不用字符串拼接：模型名可能已带 {@code [200k]}，直接追加会得到
   * {@code foo[200k][1m]} 这种非法值（解析器只看最后一个后缀，语义被静默改写）。
   * 正确做法是先 {@link ContextSizeParser#stripSuffix} 剥掉已有后缀，再按需附上新后缀。
   *
   * <p>基名为空时原样返回——空槽位无法承载后缀，留到 {@code readDraft} 里对账。
   */
  private fun with1mSuffix(model: String, checked: Boolean): String {
    val base = ContextSizeParser.stripSuffix(model)
    if (base.isEmpty()) return base
    return if (checked) "$base[${ContextSizeParser.format(ONE_M_TOKENS)}]" else base
  }

  /** 勾选框 → 输入框：用户真实点击时立即改写该槽位的模型名。 */
  private fun apply1m(binding: DialogProviderFormBinding, index: Int, checked: Boolean) {
    // 程序化回填期间不响应，否则会把已有的 [200k] 等后缀抹掉。
    if (suppress1mListener) return
    val input = slotInputs(binding).getOrNull(index) ?: return
    input.setText(with1mSuffix(input.text?.toString()?.trim().orEmpty(), checked))
  }

  /**
   * 模型名 → 勾选框：回填/换模型后重算勾选态。
   *
   * <p>只认「窗口恰好是 1M」。{@code [200k]} 或 {@code [2m]} 都不勾，
   * 避免把用户的声明静默改成 1M。设置 isChecked 会触发监听器，因此用
   * {@link #suppress1mListener} 把这次程序化改动隔离掉。
   */
  private fun sync1mCheck(binding: DialogProviderFormBinding, index: Int) {
    val input = slotInputs(binding).getOrNull(index) ?: return
    val check = slotChecks(binding).getOrNull(index) ?: return
    val size = ContextSizeParser.parseFromModelId(input.text?.toString()?.trim().orEmpty())
    suppress1mListener = true
    try {
      check.isChecked = size == ONE_M_TOKENS
    } finally {
      suppress1mListener = false
    }
  }
}
