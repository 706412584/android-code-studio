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

package com.tom.rv2ide.ai.tool;

import com.tom.rv2ide.ai.tool.api.ToolResult;
import org.json.JSONObject;

/**
 * 允许在构造之后注入 {@link ToolInvoker} 的工具。
 *
 * <p><b>为什么是晚绑定而不是构造参数</b>：编排类工具需要执行器来调用子工具，
 * 而执行器又需要工具注册表（编排工具自己也在表里），二者互相引用。若用构造参数，
 * 就得在装配期打破这个环（先建表、再建执行器、再回填），调用顺序一旦变化就悄悄退化成
 * 直接调用。晚绑定把「接线」变成显式的一步：装配方调 {@code setToolInvoker}，
 * 忘记调用时工具会明确报错，而不是静默绕过权限。
 */
public interface ToolInvokerAware {

  /**
   * 注入工具调用入口。
   *
   * @param invoker 调用入口；装配方在构建执行器后注入
   */
  void setToolInvoker(ToolInvoker invoker);
}
