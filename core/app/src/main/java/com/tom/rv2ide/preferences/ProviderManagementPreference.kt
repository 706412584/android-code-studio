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
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.preference.Preference
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
   * <p>用代码构建而不是 XML：字段里有 4 个结构相同的槽位输入框，写 XML 要重复四段
   * 几乎一样的块，改一处必漏三处。
   *
   * <p>新建与编辑共用同一段构建逻辑，差异只有两点：新建多一个「从预设开始」的下拉，
   * 编辑时密钥框提示「留空表示保留当前密钥」。
   */
  private fun showForm(context: Context, existing: ProviderConfig?, onChanged: () -> Unit) {
    val isNew = existing == null
    val presets = ProviderPresets.all()
    val seed =
        existing
            ?: presets.firstOrNull()?.let { ProviderConfigStore.presetToConfig(it, "") }
            ?: ProviderConfig("", "", ModelProtocolType.OPENAI_COMPATIBLE, "", "", emptyArray())

    val density = context.resources.displayMetrics.density
    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun label(res: Int) {
      root.addView(
          TextView(context).apply {
            setText(res)
            setPadding(0, (12 * density).toInt(), 0, (2 * density).toInt())
          })
    }
    fun field(initial: String, hintText: String = ""): EditText =
        EditText(context).apply {
          setText(initial)
          if (hintText.isNotEmpty()) hint = hintText
          setSingleLine(true)
        }.also { root.addView(it) }

    // ---- 从预设开始（仅新建）----
    // 预设是「快速填充」而不是「唯一来源」：选中后只把连接信息与模型填进下面的输入框，
    // 用户仍可随意改。常见服务商一键可用，特殊网关也能配。
    var presetSpinner: Spinner? = null
    if (isNew) {
      label(R.string.ai_agent_provider_from_preset)
      presetSpinner =
          Spinner(context)
              .apply {
                adapter =
                    ArrayAdapter(
                        context,
                        android.R.layout.simple_spinner_dropdown_item,
                        presets.map { it.getLabel() },
                    )
              }
              .also { root.addView(it) }
    }

    label(R.string.ai_agent_provider_name)
    val nameInput = field(seed.getId())

    label(R.string.ai_agent_provider_base_url)
    val urlInput = field(seed.getBaseUrl())

    label(R.string.ai_agent_provider_api_key)
    // 编辑时密钥框留空 = 保留原密钥，用户不必为了改 baseUrl 而重打一遍密钥。
    val keyInput =
        field("", if (isNew) "" else context.getString(R.string.ai_agent_provider_api_key_keep))

    label(R.string.ai_agent_provider_protocol)
    val protocolSpinner = protocolSelector(context, seed.getProtocolType())
    root.addView(protocolSpinner)

    label(R.string.ai_agent_provider_slots)
    val slotInputs = ProviderConfig.SLOT_ORDER.map { slotInput(context, seed, it) }
    slotInputs.forEach { root.addView(it) }

    val fetchButton = Button(context).apply { setText(R.string.ai_agent_provider_fetch_models) }
    root.addView(fetchButton)

    /** 读表单成草稿。空密钥沿用 seed 的（编辑场景）。 */
    fun readDraft(): ProviderConfig {
      val typedKey = keyInput.text.toString().trim()
      val key = if (typedKey.isEmpty()) seed.getApiKey() else typedKey
      val id = nameInput.text.toString().trim()
      // ProviderConfig 是 Java 类，只能按位置传参。
      return ProviderConfig(
          id,
          id,
          ModelProtocolType.entries.getOrElse(protocolSpinner.selectedItemPosition) {
            ModelProtocolType.OPENAI_COMPATIBLE
          },
          urlInput.text.toString().trim(),
          key,
          slotInputs.map { it.text.toString().trim() }.toTypedArray(),
      )
    }

    wireFetch(context, fetchButton, slotInputs) { readDraft() }

    presetSpinner?.onItemSelectedListener =
        object : AdapterView.OnItemSelectedListener {
          override fun onItemSelected(
              parent: AdapterView<*>?,
              view: View?,
              position: Int,
              id: Long,
          ) {
            val preset = presets.getOrNull(position) ?: return
            // 只覆盖连接信息，不动密钥——用户可能已经输入了密钥。
            nameInput.setText(preset.getId())
            urlInput.setText(preset.getBaseUrl())
            // 协议也必须跟着预设走：Anthropic Messages 与 OpenAI 兼容是两套完全不同的
            // 请求格式，选了 Grok 却留着 Anthropic 协议，请求必然失败。
            val types = ModelProtocolType.entries
            val protocolIndex = types.indexOf(preset.getProtocolType())
            if (protocolIndex >= 0) {
              protocolSpinner.setSelection(protocolIndex)
            }
            val slots = preset.getSlotModels()
            slotInputs.forEachIndexed { index, input ->
              input.setText(slots.getOrElse(index) { "" })
            }
          }

          override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

    val scroll =
        ScrollView(context).apply {
          // 内边距加在容器上而不是表单：ScrollView 只有一层子视图，
          // padding 放这里才能让内容与对话框边缘留出空白。
          setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
          addView(root)
        }

    AlertDialog.Builder(context)
        .setTitle(if (isNew) R.string.ai_agent_provider_add else R.string.ai_agent_provider_edit)
        .setView(scroll)
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
   * 一个槽位输入框。
   *
   * <p>提示语写「角色 · 说明」而不是只写说明：四个框结构完全一样，只靠位置区分
   * 的话用户分不清哪一行是主模型（必填）哪一行可以留空。
   */
  private fun slotInput(context: Context, seed: ProviderConfig, slot: String): EditText {
    val index = ProviderConfig.SLOT_ORDER.indexOf(slot)
    val labelRes =
        when (slot) {
          ProviderConfig.SLOT_MAIN -> R.string.ai_agent_provider_slot_main
          ProviderConfig.SLOT_HAIKU -> R.string.ai_agent_provider_slot_haiku
          ProviderConfig.SLOT_SONNET -> R.string.ai_agent_provider_slot_sonnet
          else -> R.string.ai_agent_provider_slot_opus
        }
    val hintRes =
        if (slot == ProviderConfig.SLOT_MAIN) R.string.ai_agent_provider_slot_hint_main
        else R.string.ai_agent_provider_slot_hint_same
    return EditText(context).apply {
      setText(seed.getSlotModels().getOrElse(index) { "" })
      hint = context.getString(labelRes) + " · " + context.getString(hintRes)
      setSingleLine(true)
    }
  }

  private fun protocolSelector(context: Context, current: ModelProtocolType): Spinner =
      Spinner(context).apply {
        val types = ModelProtocolType.entries
        adapter =
            ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                types.map { it.getLabel() },
            )
        val index = types.indexOf(current)
        if (index >= 0) setSelection(index)
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
      button: Button,
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
