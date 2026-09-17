/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from LineCode Pro (https://github.com/LangLang03/LineCodePro),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

package com.tom.rv2ide.ai.tool;

/**
 * 用户对一次危险工具调用的答复。
 *
 * <p><b>为什么是三档而不是布尔</b>：布尔只有「放行/拒绝」，用户每遇到一个危险工具都只有
 * 两个选择——要么每次手动点，要么永久放行全部。前者在长任务里点十几次，后者等于关掉保护。
 * 三档把「这次」与「以后」分开：
 * <ul>
 *   <li>{@link #ALLOW_ONCE}——只放行本次调用。这是默认的安全选项。</li>
 *   <li>{@link #ALLOW_ALWAYS}——放行本次，并持久化一条
 *       {@link ToolPermissionRule} 规则，使**同一工具、同一参数粒度**的后续调用不再询问。
 *       粒度不是「整个工具」，见 {@link ToolPermissionRule} 的说明。</li>
 *   <li>{@link #DENY}——拒绝本次。拒绝必须能回灌给模型（工具结果里带原因），
 *       使模型调整策略而不是重试同一条命令。</li>
 * </ul>
 */
public enum DangerousToolDecision {

  /** 仅放行本次调用，不产生持久化规则。 */
  ALLOW_ONCE,

  /** 放行本次调用，并持久化一条规则供后续相同粒度的调用复用。 */
  ALLOW_ALWAYS,

  /** 拒绝本次调用。 */
  DENY
}
