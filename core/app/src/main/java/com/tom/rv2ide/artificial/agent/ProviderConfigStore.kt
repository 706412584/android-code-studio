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

package com.tom.rv2ide.artificial.agent

import android.content.Context
import com.tom.rv2ide.ai.tool.api.ErrorLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 服务商配置的持久化。
 *
 * <p><b>为什么是 JSON 文件而不是数据库</b>：ACS 全仓库没有任何数据库设施
 * （无 Room、无 SQLiteOpenHelper、无 android.database 使用），会话日志也是
 * append-only JSONL。服务商配置条数少（个位数到几十条）、改动不频繁，
 * 一个 JSON 文件足够，且与既有决策一致——不为这一处引入新的存储范式。
 *
 * <p><b>写盘策略是「整表重写 + 临时文件改名」</b>：配置文件小，整表重写的代价可以忽略；
 * 而直接覆盖原文件时若进程被杀，会留下一个半截的 JSON，下次启动整个服务商列表都读不出来。
 * 先写 `.tmp` 再 rename 是原子的，最坏情况也只是丢掉最后一次修改。
 *
 * <p><b>迁移</b>：旧版本把密钥存在按服务商写死的偏好键里（{@code ai_agent_deepseek_api_key}
 * 等），且 7 个 OpenAI 兼容服务商共用一个键。首次读取时把这些键搬进记录，
 * 让用户已配的密钥不丢——迁移只做一次，之后以文件为准。
 */
class ProviderConfigStore(private val context: Context) {

  /** 用户配置的服务商记录。 */
  fun load(): List<ProviderConfig> {
    val file = configFile()
    if (!file.exists()) {
      // 首次运行：先迁移旧密钥，再按预设生成默认记录。
      val migrated = migrateLegacyKeys()
      if (migrated.isNotEmpty()) {
        save(migrated)
      }
      return migrated
    }

    val records =
        try {
          val array = JSONArray(file.readText())
          (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { ProviderConfig.fromJson(it) }
          }
        } catch (e: Throwable) {
          // 文件损坏（半截写入、手工编辑出错）不能让服务商列表整个不可用。
          // 记一笔错误后返回空列表，界面会显示「没有服务商」而不是崩溃。
          ErrorLog.record("provider", "读取服务商配置失败", e, null)
          emptyList()
        }
    return records
  }

  /** 覆盖整表。 */
  fun save(configs: List<ProviderConfig>) {
    val array = JSONArray()
    for (config in configs) {
      try {
        array.put(config.toJson())
      } catch (e: Throwable) {
        ErrorLog.record("provider", "序列化服务商失败: ${config.getId()}", e, null)
      }
    }
    val file = configFile()
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    try {
      tmp.writeText(array.toString())
      // rename 是原子的：要么看到旧文件，要么看到新文件，不会看到半截内容。
      if (!tmp.renameTo(file)) {
        // 某些文件系统上 rename 到已存在的目标会失败，退回「先删再改名」。
        file.delete()
        tmp.renameTo(file)
      }
    } catch (e: Throwable) {
      ErrorLog.record("provider", "写入服务商配置失败", e, null)
      tmp.delete()
    }
  }

  /** 新增或更新一条（按 id 匹配）。返回更新后的整表。 */
  fun upsert(config: ProviderConfig): List<ProviderConfig> {
    val current = load().toMutableList()
    val index = current.indexOfFirst { it.getId() == config.getId() }
    if (index >= 0) {
      current[index] = config
    } else {
      current.add(config)
    }
    save(current)
    return current
  }

  /** 删除一条。返回更新后的整表。 */
  fun delete(id: String): List<ProviderConfig> {
    val current = load().filterNot { it.getId() == id }
    save(current)
    return current
  }

  /** 按 id 取一条；不存在返回 null。 */
  fun find(id: String): ProviderConfig? = load().firstOrNull { it.getId() == id }

  private fun configFile(): File = File(File(context.filesDir, "ai"), "providers.json")

  /**
   * 把旧版本的固定密钥槽位迁移成服务商记录。
   *
   * <p>迁移的难点是**旧数据本身有损**：7 个 OpenAI 兼容服务商共用一个
   * {@code ai_agent_openai_compatible_api_key}，无法知道那个密钥到底属于哪一个。
   * 因此不做猜测——把该密钥放进**当前选中的**那个服务商（那才是用户在用的），
   * 其余服务商留空，由用户按需补。猜错会让用户拿着 A 的密钥去请求 B，比留空更难排查。
   */
  private fun migrateLegacyKeys(): List<ProviderConfig> {
    val prefs = com.tom.rv2ide.preferences.internal.prefManager
    fun key(name: String): String = prefs.getString(name, "").orEmpty()

    // 旧键 → 预设 id。只有这几个服务商有独立键，其余共用兼容键。
    val dedicated =
        mapOf(
            "openai" to key("ai_agent_openai_api_key"),
            "deepseek" to key("ai_agent_deepseek_api_key"),
            "grok" to key("ai_agent_grok_api_key"),
            "claude" to key("ai_agent_anthropic_api_key"),
            "gemini" to key("ai_agent_gemini_api_key"),
            "custom" to key("ai_agent_custom_api_key"),
        )

    val currentProvider = key("ai_provider_name").ifBlank { ProviderPresets.DEFAULT_PROVIDER_ID }
    val sharedCompatibleKey = key("ai_agent_openai_compatible_api_key")

    val records = mutableListOf<ProviderConfig>()
    // 只迁移用户真正配过密钥的服务商，不给全部 19 个预设都生成空记录——
    // 空记录会让「服务商列表」一上来就有 19 条，用户要自己挑哪些是能用的。
    for ((providerId, apiKeyValue) in dedicated) {
      if (apiKeyValue.isBlank()) {
        continue
      }
      val preset = ProviderPresets.find(providerId) ?: continue
      records.add(presetToConfig(preset, apiKeyValue))
    }

    // 共用键归给当前选中的服务商（仅当它是 OpenAI 兼容类且尚无独立密钥）。
    if (sharedCompatibleKey.isNotBlank() &&
        currentProvider != "custom" &&
        !dedicated.containsKey(currentProvider)) {
      ProviderPresets.find(currentProvider)?.let { preset ->
        records.add(presetToConfig(preset, sharedCompatibleKey))
      }
    }

    // 自定义端点：baseUrl / model 也要一起搬，否则迁移后还得手填一遍。
    val customBaseUrl = key("ai_agent_custom_base_url")
    val customModel = key("ai_agent_custom_model")
    if (customBaseUrl.isNotBlank() || customModel.isNotBlank()) {
      val index = records.indexOfFirst { it.getId() == "custom" }
      val merged =
          ProviderConfig(
              "custom",
              "自定义端点",
              com.tom.rv2ide.ai.protocol.ModelProtocolType.OPENAI_COMPATIBLE,
              customBaseUrl,
              key("ai_agent_custom_api_key"),
              arrayOf(customModel, "", "", ""),
          )
      if (index >= 0) records[index] = merged else records.add(merged)
    }

    return records
  }

  companion object {
    /** 把预设转成一条用户记录（密钥可后补）。界面「从预设添加」也走这里。 */
    @JvmStatic
    fun presetToConfig(preset: ProviderPresets.Preset, apiKey: String): ProviderConfig =
        ProviderConfig(
            preset.getId(),
            preset.getLabel(),
            preset.getProtocolType(),
            preset.getBaseUrl(),
            apiKey.orEmpty(),
            preset.getSlotModels(),
        )
  }
}
