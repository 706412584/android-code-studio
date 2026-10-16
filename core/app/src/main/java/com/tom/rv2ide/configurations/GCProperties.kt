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
package com.tom.rv2ide.configurations

import com.tom.rv2ide.projects.IProjectManager

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

object GCProperties {
    /**
     * 当前项目根路径；**没有打开项目时为空串**。
     *
     * <p><b>为什么不能是启动即求值的 val</b>：原实现 `val userProject =
     * ProjectManagerImpl.getInstance().projectDir...` 在 object 类加载时求值，而
     * `projectDir` 的 getter 在未打开项目时 `checkNotNull` 抛 IllegalStateException。
     * 后果：任何在「没打开项目」时触达本 object 的入口都直接闪退——实测从助手顶栏
     * 点 Git 图标（GitClientFragment 读本属性）即崩。
     *
     * <p>改成惰性 getter：每次读取时查当前项目；未打开时返回空串，由调用方
     * （GitViewModel 等）按「无项目」正常降级，而不是把进程带走。
     */
    val userProject: String
        get() =
            try {
                IProjectManager.getInstance().projectDirOrNull?.absolutePath.orEmpty()
            } catch (e: RuntimeException) {
                ""
            }
}
