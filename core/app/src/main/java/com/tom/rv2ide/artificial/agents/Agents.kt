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

package com.tom.rv2ide.artificial.agents

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class Agents(ctx: Context) {

  private val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)
  private val AGENT_KEY = "ai_agent_model_name"
  private val PROVIDER_KEY = "ai_provider_name"
  
  private val openai_models = arrayOf(
    "gpt-5.1-codex-max",
    "gpt-5.1-codex",
    "gpt-5.1-codex-mini",
    "gpt-5-codex",

    // GPT-5 family (text models)
    "gpt-5-chat-latest",
    "gpt-5-2025-08-07",
    "gpt-5",
    "gpt-5-mini-2025-08-07",
    "gpt-5-mini",
    "gpt-5-nano-2025-08-07",
    "gpt-5-nano",
    "gpt-5-pro-2025-10-06",
    "gpt-5-pro",
    "gpt-5-search-api",           // produces text, coding-capable even if optimized for search
    "gpt-5-search-api-2025-10-14",

    // GPT-5.1 models
    "gpt-5.1-chat-latest",
    "gpt-5.1",
    "gpt-5.1-2025-11-13",

    // GPT-4.1 family (all text)
    "gpt-4.1-2025-04-14",
    "gpt-4.1",
    "gpt-4.1-mini-2025-04-14",
    "gpt-4.1-mini",
    "gpt-4.1-nano-2025-04-14",
    "gpt-4.1-nano",

    // GPT-4o (all text/omni variants except audio, tts, transcribe)
    "gpt-4o",
    "gpt-4o-2024-05-13",
    "gpt-4o-mini-2024-07-18",
    "gpt-4o-mini",
    "gpt-4o-2024-08-06",
    "gpt-4o-2024-11-20",
    "gpt-4o-search-preview-2025-03-11",
    "gpt-4o-search-preview",
    "gpt-4o-mini-search-preview-2025-03-11",
    "gpt-4o-mini-search-preview",

    // O-series (general purpose = coding-capable)
    "o1-2024-12-17",
    "o1",
    "o3-mini",
    "o3-mini-2025-01-31",
    "o3-2025-04-16",
    "o3",
    "o4-mini-2025-04-16",
    "o4-mini",

    // GPT-3.5 (text models, all coding capable)
    "gpt-3.5-turbo",
    "gpt-3.5-turbo-1106",
    "gpt-3.5-turbo-0125",
    "gpt-3.5-turbo-instruct",
    "gpt-3.5-turbo-instruct-0914",
    "gpt-3.5-turbo-16k",

    // Legacy general-purpose LLMs (still text)
    "davinci-002",
    "babbage-002"
  )
  
  private val claude_models = arrayOf(
    "claude-sonnet-4-5-20250929",
    "claude-haiku-4-5-20251001",
    "claude-opus-4-5-20251101",
    "claude-opus-4-1-20250805",
    "claude-opus-4-20250514",
    "claude-sonnet-4-20250514",
    "claude-3-7-sonnet-20250219",
    "claude-3-5-haiku-20241022",
    "claude-3-haiku-20240307"
  )
  
  private val gemini_models = arrayOf(
    "gemini-3-pro-preview",
    "gemini-2.5-pro",
    "gemini-2.5-flash",
    "gemini-2.5-flash-lite",
    "gemini-2.0-flash",
    "gemini-2.0-flash-lite",
    "gemini-1.5-flash",
    "gemini-1.5-pro"
  )
  
  private val deepseek_models = arrayOf(
    "deepseek-chat",
    "deepseek-reasoner"
  )
  
  private val grok_models = arrayOf(
    "grok-4-1-fast-reasoning",
    "grok-4-1-fast-non-reasoning",
    "grok-code-fast-1",
    "grok-4-fast-reasoning",
    "grok-4-fast-non-reasoning",
    "grok-4-0709",
    "grok-3",
    "grok-3-mini",
    "grok-beta",
    "grok-2",
    "grok-2-mini"
  )
  
  private val localllm_models = arrayOf(
    "local-model"
  )
  
  val ai_agents = openai_models + claude_models + gemini_models + deepseek_models + grok_models + localllm_models

  fun getModelsForProvider(providerId: String): Array<String> {
    // 模型清单的唯一来源是预设表（ProviderPresets）。这里保留硬编码列表只为兼容
    // 既有调用方与旧偏好值；预设表里已注册的服务商以预设为准。
    val preset = com.tom.rv2ide.artificial.agent.ProviderPresets.modelsFor(providerId)
    if (preset.isNotEmpty()) {
      return preset.toTypedArray()
    }
    return when(providerId) {
      "openai" -> openai_models
      "gemini" -> gemini_models
      "claude" -> claude_models
      "deepseek" -> deepseek_models
      "grok" -> grok_models
      "localllm" -> localllm_models
      else -> gemini_models
    }
  }

  fun getProviderForModel(modelName: String): String? {
    // 预设表优先：它覆盖了 gemini 之外的全部服务商。
    val fromPresets = com.tom.rv2ide.artificial.agent.ProviderPresets.providerForModel(modelName)
    if (fromPresets != null) {
      return fromPresets
    }
    return when {
      modelName in openai_models -> "openai"
      modelName in gemini_models -> "gemini"
      modelName in claude_models -> "claude"
      modelName in deepseek_models -> "deepseek"
      modelName in grok_models -> "grok"
      modelName in localllm_models -> "localllm"
      else -> null
    }
  }

  fun setAgent(name: String) {
      // 只切换模型名，不擅自改服务商——除非这个模型明确属于另一个服务商。
      // 之前的实现会在模型名匹配不到任何服务商时把 provider 重置为 gemini，
      // 于是用户选了一个新服务商后只要模型名不在硬编码列表里，provider 就被悄悄改掉，
      // 表现为「设置里显示的服务商不是自己选的那个」。
      val detected = getProviderForModel(name)
      if (detected != null) {
        sp.edit().putString(PROVIDER_KEY, detected).apply()
      }
      sp.edit().putString(AGENT_KEY, name).apply()
  }

  fun getAgent(): String {
    val savedModel = sp.getString(AGENT_KEY, null)
    if (!savedModel.isNullOrBlank()) return savedModel

    // 未选过模型时取该服务商的推荐值（预设表首项）。
    val provider = getProvider()
    val presetModels = com.tom.rv2ide.artificial.agent.ProviderPresets.modelsFor(provider)
    if (presetModels.isNotEmpty()) {
      return presetModels[0]
    }
    return when (provider) {
      "openai" -> "gpt-4o"
      "claude" -> "claude-sonnet-4-20250514"
      "deepseek" -> "deepseek-chat"
      "grok" -> "grok-3"
      else -> "deepseek-chat"
    }
  }

  fun setProvider(provider: String) {
    sp.edit().putString(PROVIDER_KEY, provider).apply()
  }

  fun getProvider(): String {
    // 默认值取自预设表，而不是写死 "gemini"：协议层没有 Gemini 的实现，
    // 默认指向它会让首次使用者在配好密钥后仍然一条消息都发不出去。
    val fallback = com.tom.rv2ide.artificial.agent.ProviderPresets.DEFAULT_PROVIDER_ID
    return sp.getString(PROVIDER_KEY, fallback) ?: fallback
  }

  fun isValidModelForProvider(modelName: String, providerId: String): Boolean {
    return modelName in getModelsForProvider(providerId)
  }
}