/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.services.builder

/**
 * 进程级注册表单例。
 *
 * <p><b>为什么单独放一个 Holder 而不是塞进 Service</b>：工具层（`ai-tool` 的调用方）
 * 在 Service 尚未绑定、甚至编辑器未打开时也可能查询注册表。放在 Service 里会让
 * 「未绑定 → 拿不到注册表 → 只能退回全局 Lookup」成为常态，跨项目隔离就失效了。
 *
 * <p>用对象而不是 `Lookup`：注册表是纯数据容器，没有生命周期语义，也不需要被
 * Service 注销。进程结束时自然回收。
 */
internal object BuildConnectionRegistryHolder {

  private val registry = BuildConnectionRegistry()

  /** Kotlin 侧直接访问。 */
  internal fun registry(): BuildConnectionRegistry = registry

  /**
   * Java 侧访问（工具层与 AgentOrchestrator 是 Java）。
   *
   * <p>返回 `null` 的情况理论上不存在，但保留可空签名让调用方显式处理——
   * 未来若改成可替换实现，调用点不需要再改。
   */
  @JvmStatic
  fun get(): BuildConnectionRegistry? = registry
}
