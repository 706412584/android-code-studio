/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.manage

/**
 * 「管理页改过数据」的信号。
 *
 * <p><b>为什么需要它</b>：服务商/MCP 改成独立页面后，设置页那一条的摘要（「可用 N/M」）
 * 是 {@code onCreatePreference} 里算好的。返回时 Android 只把原来的 Fragment 恢复，
 * 不会重跑偏好构建，摘要就停在进入页面前的值——用户在页面里删了一个服务商，
 * 回到设置页仍显示旧的计数。旧的弹窗版本有 {@code onChanged} 回调直接刷新，页面版本丢了这条路。
 *
 * <p>用一个一档信号而不是在 {@code onResume} 里无条件重建整页：
 * 重建会重置滚动位置，每次从其它界面返回都重排一遍也浪费。只有真的改过才刷新。
 */
internal object ManageChangeSignal {

  @Volatile private var pending = false

  /** 管理页保存或删除成功后调用。 */
  fun markChanged() {
    pending = true
  }

  /** 取出并清除信号；返回 true 表示需要刷新偏好条目。 */
  fun consume(): Boolean {
    val value = pending
    pending = false
    return value
  }
}