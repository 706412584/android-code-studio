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

import com.tom.rv2ide.ai.protocol.ModelProtocolType
import com.tom.rv2ide.ai.protocol.SimpleHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 从服务商的 {@code /v1/models} 拉取真实可用的模型清单。
 *
 * <p><b>为什么需要它</b>：预设表里的模型名是写死的，厂商发新模型或下线旧模型后，
 * 用户只能手填或等应用更新。拉取目录让用户从服务端返回的真实列表里挑，
 * 不再需要猜模型名。
 *
 * <p><b>失败是常态，不是异常</b>：不同服务商的响应结构不一致（有的返回
 * {@code {"data":[{"id":...}]}}，有的直接是数组，有的用 {@code models} 键），
 * 有的干脆不实现这个端点。因此本类**返回空列表而不是抛异常**，
 * 由界面提示「未能获取，请手动填写」——拉不到目录不该阻断用户配置服务商。
 *
 * <p>网络调用阻塞，必须在 IO 线程调用。
 */
object ModelCatalogFetcher {

  private const val CONNECT_TIMEOUT_MS = 15_000
  private const val READ_TIMEOUT_MS = 20_000

  /**
   * 拉取模型清单。
   *
   * @return 模型 id 列表（已去重、排序）；失败时返回空列表
   */
  fun fetch(config: ProviderConfig): List<String> {
    return fetchDetailed(config).models
  }

  /**
   * 模型条目的**模态**信息（来自 `/models` 的声明）。
   *
   * <p>存在的理由：网关常在条目里声明能力（实测 loomy2api 给
   * `{"type":"image","capabilities":{"output_modalities":["image"]}}`），
   * 而模型**名字**未必含关键词——`doubao-seedream-5-lite` 是生图模型却不含
   * "image"，只按名字筛会把它从候选里漏掉。有声明时以声明为准，
   * 没声明时调用方退回关键词匹配（见 [ModelCatalogEntry.matchesOutputModality]）。
   */
  data class ModelCatalogEntry(
      val id: String,
      /** `type` 字段（如 `image`/`chat`/`video`）；空串表示未声明。 */
      val type: String,
      /** `capabilities.output_modalities`（如 `["image"]`）；空表示未声明。 */
      val outputModalities: List<String>,
  ) {
    /**
     * 该模型是否产出指定模态。
     *
     * <p>判定顺序：先看 [outputModalities] 声明，再看 [type] 声明；两者都没有时
     * 返回 false——调用方据此退回关键词匹配（用 [id] 判断），
     * 这样「有声明」的网关用声明、「没声明」的网关行为与从前一致。
     */
    fun declaresOutputModality(modality: String): Boolean {
      if (outputModalities.isNotEmpty()) {
        return outputModalities.any { it.equals(modality, ignoreCase = true) }
      }
      return type.equals(modality, ignoreCase = true)
    }
  }

  /**
   * 拉取并把**失败原因**一并带回。
   *
   * <p>旧版失败只回空列表，调用方无法区分「服务商目录里确实没有这类模型」和
   * 「网络根本拉不到」（实测踩过：设备直连海外端点 100% 丢包，提示却一样是
   * 「没有找到模型」，用户被误导成模型名问题）。UI 据此给不同文案。
   */
  data class FetchResult(
      val models: List<String>,
      val error: String,
      /**
       * 逐条目的模态声明；解析不到时为空列表。
       *
       * <p>默认空列表：所有旧调用点（只关心 [models]）行为不变。
       */
      val entries: List<ModelCatalogEntry> = emptyList(),
  )

  fun fetchDetailed(config: ProviderConfig): FetchResult {
    val baseUrl = config.getBaseUrl().trimEnd('/')
    if (baseUrl.isEmpty()) {
      return FetchResult(emptyList(), "未配置 Base URL")
    }

    val url = baseUrl + "/models"
    return try {
      val request = SimpleHttpClient.Request(url, "GET", null)
      request.connectTimeoutMs = CONNECT_TIMEOUT_MS
      request.readTimeoutMs = READ_TIMEOUT_MS
      if (config.getApiKey().isNotEmpty()) {
        request.headers["Authorization"] = "Bearer " + config.getApiKey()
      }
      // Anthropic 的鉴权头与 OpenAI 不同，用它自己的头才拉得到目录。
      if (config.getProtocolType() == ModelProtocolType.ANTHROPIC_MESSAGES) {
        request.headers["x-api-key"] = config.getApiKey()
        request.headers["anthropic-version"] = "2023-06-01"
      }
      val response = SimpleHttpClient.execute(request)
      // 非 2xx 直接当失败：401 会返回一个 JSON 错误对象，硬解析会得到空列表，
      // 但那掩盖了「密钥不对」这个真正原因，调用方无法区分。
      if (response.code !in 200..299) {
        FetchResult(emptyList(), "HTTP ${response.code}（密钥或端点有误）")
      } else {
        val entries = parseEntries(response.body)
        FetchResult(entries.map { it.id }, "", entries)
      }
    } catch (e: Throwable) {
      FetchResult(emptyList(), describe(e))
    }
  }

  private fun describe(e: Throwable): String {
    val message = e.message
    val detail = if (message.isNullOrBlank()) e.javaClass.simpleName else message
    return "网络请求失败：$detail"
  }

  /**
   * 从响应体里提取模型 id。
   *
   * <p>刻意不依赖固定结构：见过的形态至少有四种（{@code data[].id}、
   * {@code models[].id}、{@code models[].name}、顶层数组），写死一种会让其余服务商
   * 全部拉不到。这里按「哪个键存在就用哪个」依次尝试。
   *
   * <p>公开且带 `@JvmStatic`：解析逻辑是这里最容易出错的部分，必须有单测覆盖，
   * 而测试是纯 JVM Java 测试（该模块的测试不带 Robolectric）。
   */
  @JvmStatic
  fun parseModelIds(body: String?): List<String> {
    // 可空参数：Kotlin 侧 `String.isBlank()` 对 null 会抛 NPE，而 Java 调用方
    // （以及网络层的异常路径）确实可能传 null。契约是「任何异常输入都返回空列表」。
    if (body.isNullOrBlank()) {
      return emptyList()
    }
    return try {
      val trimmed = body.trim()
      val array =
          when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
              val root = JSONObject(trimmed)
              root.optJSONArray("data")
                  ?: root.optJSONArray("models")
                  // 部分网关把列表包在 result / body 下
                  ?: root.optJSONObject("result")?.optJSONArray("data")
                  ?: JSONArray()
            }
          }
      val ids = mutableListOf<String>()
      for (i in 0 until array.length()) {
        val item = array.opt(i)
        val id =
            when (item) {
              is JSONObject -> item.optString("id").ifBlank { item.optString("name") }
              is String -> item
              else -> ""
            }
        if (id.isNotBlank() && !ids.contains(id)) {
          ids.add(id)
        }
      }
      ids.sorted()
    } catch (e: Throwable) {
      emptyList()
    }
  }

  /**
   * 解析模型条目（含模态声明），是 [parseModelIds] 的超集。
   *
   * <p>复用 [parseModelIds] 的容错策略（四种列表形态），额外读两个可选字段：
   * {@code type} 与 {@code capabilities.output_modalities}。缺失时留空，
   * 由调用方决定退回关键词匹配。
   */
  @JvmStatic
  fun parseEntries(body: String?): List<ModelCatalogEntry> {
    if (body.isNullOrBlank()) {
      return emptyList()
    }
    return try {
      val trimmed = body.trim()
      val array =
          when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
              val root = JSONObject(trimmed)
              root.optJSONArray("data")
                  ?: root.optJSONArray("models")
                  ?: root.optJSONObject("result")?.optJSONArray("data")
                  ?: JSONArray()
            }
          }
      val entries = mutableListOf<ModelCatalogEntry>()
      for (i in 0 until array.length()) {
        val item = array.opt(i)
        if (item !is JSONObject) {
          val id = item as? String ?: continue
          if (id.isNotBlank() && entries.none { it.id == id }) {
            entries.add(ModelCatalogEntry(id, "", emptyList()))
          }
          continue
        }
        val id = item.optString("id").ifBlank { item.optString("name") }
        if (id.isBlank() || entries.any { it.id == id }) {
          continue
        }
        val type = item.optString("type", "")
        val modalities = mutableListOf<String>()
        item.optJSONObject("capabilities")?.optJSONArray("output_modalities")?.let { arr ->
          for (j in 0 until arr.length()) {
            val m = arr.optString(j, "")
            if (m.isNotBlank()) {
              modalities.add(m)
            }
          }
        }
        entries.add(ModelCatalogEntry(id, type, modalities))
      }
      entries.sortedBy { it.id }
    } catch (e: Throwable) {
      emptyList()
    }
  }
}
