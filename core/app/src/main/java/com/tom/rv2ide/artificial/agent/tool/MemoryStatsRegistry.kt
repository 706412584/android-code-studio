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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.tool

/**
 * AI 助手内存分析的数据源注册表。
 *
 * <p><b>为什么是注册表而不是直接读字段</b>：消息列表规模、Markdown 缓存条目、
 * Compose 面板条目数这些数据都在**视图层**（FloatingAssistantView 及其 Compose 面板），
 * 而工具层（MemoryAnalysisTool）不能反向依赖视图。让视图在创建时把自己的统计提供者
 * 注册进来，工具只读快照——两侧通过这个窄接口解耦。
 *
 * <p><b>多视图</b>：三个入口（悬浮/侧栏/全屏）可能同时存活，每个视图注册一份。
 * 快照按注册顺序全部收集（诊断要的是「整个进程」的画像，不是某一个视图）。
 *
 * <p><b>线程</b>：注册/注销在主线程（视图生命周期回调），读取在工具线程——
 * 用 [java.util.concurrent.CopyOnWriteArrayList] 保证遍历安全。
 */
object MemoryStatsRegistry {

  /** 一份视图侧的内存画像（视图在注册时决定怎么填）。 */
  class ViewStats(
      /** 视图形态标识（悬浮/侧栏/全屏），用于报告里区分来源。 */
      @JvmField val viewLabel: String,
      /** 会话 id；空串表示尚未绑定会话。 */
      @JvmField val conversationId: String,
      /** 消息列表条目数（adapter item 数）。 */
      @JvmField val messageCount: Int,
      /** 工具卡片数（含组内）。 */
      @JvmField val toolCardCount: Int,
      /** 正在流式的消息字符数（未落盘缓冲）。 */
      @JvmField val streamingChars: Int,
      /** Markdown 解析缓存的条目数。 */
      @JvmField val markdownCacheEntries: Int,
      /** Markdown 解析缓存的近似权重（字符数，用于估算内存）。 */
      @JvmField val markdownCacheWeight: Int,
      /** 当前待发送队列条数。 */
      @JvmField val queuedCount: Int,
      /** 输入框附件数。 */
      @JvmField val attachmentCount: Int,
      /** 注册中的事件订阅者数（诊断泄漏用）。 */
      @JvmField val subscriberCount: Int,
  )

  /**
   * 统计提供者：每次调用应返回**当前**快照。
   *
   * <p>实现方需容忍来自工具线程的调用，且不得抛异常（抛了会被工具层吞掉并记为
   * 不可用，但那份视图的画像就没了）。
   */
  fun interface Provider {
    fun snapshot(): ViewStats?
  }

  private val providers = java.util.concurrent.CopyOnWriteArrayList<Provider>()

  /** 视图创建时注册。同一实例重复注册不会重复计入。 */
  @JvmStatic
  fun register(provider: Provider) {
    if (provider != null && !providers.contains(provider)) {
      providers.add(provider)
    }
  }

  /** 视图销毁时注销。 */
  @JvmStatic
  fun unregister(provider: Provider?) {
    if (provider != null) {
      providers.remove(provider)
    }
  }

  /** 收集全部视图的当前画像；单个提供者失败只跳过它。 */
  @JvmStatic
  fun snapshotAll(): List<ViewStats> {
    val out = ArrayList<ViewStats>(providers.size)
    for (provider in providers) {
      try {
        provider.snapshot()?.let { out.add(it) }
      } catch (t: Throwable) {
        // 单个视图的统计失败不影响整体报告（诊断工具必须自身健壮）。
      }
    }
    return out
  }

  /** 当前注册的提供者数（诊断用；正常应等于存活的视图数）。 */
  @JvmStatic
  fun providerCount(): Int = providers.size
}
