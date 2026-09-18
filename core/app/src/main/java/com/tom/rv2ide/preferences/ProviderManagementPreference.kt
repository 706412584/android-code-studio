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

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.preference.Preference
import com.tom.rv2ide.databinding.DialogProviderFormBinding
import com.tom.rv2ide.ai.protocol.ModelProtocolType
import com.tom.rv2ide.artificial.agent.ModelCatalogFetcher
import com.tom.rv2ide.artificial.agent.ProviderConfig
import com.tom.rv2ide.artificial.agent.ProviderConfigStore
import com.tom.rv2ide.artificial.agent.ProviderPresets
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

    AlertDialog.Builder(context)
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
    AlertDialog.Builder(context)
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
    AlertDialog.Builder(context)
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
    val slotInputs =
        listOf(
            binding.providerSlotMain,
            binding.providerSlotHaiku,
            binding.providerSlotSonnet,
            binding.providerSlotOpus,
        )

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

    /** 读表单成草稿。密钥框留空时沿用 seed 的密钥（编辑场景）。 */
    fun readDraft(): ProviderConfig {
      val typedKey = binding.providerApiKey.text?.toString()?.trim().orEmpty()
      val key = if (typedKey.isEmpty()) seed.getApiKey() else typedKey
      val id = binding.providerName.text?.toString()?.trim().orEmpty()
      val selectedProtocol =
          protocolTypes.getOrElse(protocolTypes.indexOfFirst { it.getLabel() == binding.providerProtocol.text.toString() }) {
            ModelProtocolType.OPENAI_COMPATIBLE
          }
      // ProviderConfig 是 Java 类，只能按位置传参。
      return ProviderConfig(
          id,
          id,
          selectedProtocol,
          binding.providerBaseUrl.text?.toString()?.trim().orEmpty(),
          key,
          slotInputs.map { it.text?.toString()?.trim().orEmpty() }.toTypedArray(),
      )
    }

    wireFetch(context, binding.providerFetchModels, slotInputs) { readDraft() }

    AlertDialog.Builder(context)
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
          onChanged()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  /**
   * 接上「拉取模型列表」按钮。
   *
   * <p>拉取成功后把每个槽位输入框变成可点的选择器——用户从服务端返回的真实列表里挑，
   * 不必猜模型名。拉取失败只提示，不阻断保存：有些服务商不实现这个端点，
   * 而用户手填模型名照样能用。
   */
  private fun wireFetch(
      context: Context,
      button: com.google.android.material.button.MaterialButton,
      slotInputs: List<EditText>,
      readDraft: () -> ProviderConfig,
  ) {
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
          slotInputs.forEach { input ->
            input.setOnClickListener {
              AlertDialog.Builder(context)
                  .setTitle(R.string.ai_agent_provider_pick_model)
                  .setItems(models.toTypedArray()) { _, which -> input.setText(models[which]) }
                  .setNegativeButton(android.R.string.cancel, null)
                  .show()
            }
          }
        }
      }
    }
  }
}
