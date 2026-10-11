/*
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

package com.tom.rv2ide.artificial.agent

import com.termux.shared.shell.ShellUtils
import com.termux.shared.termux.shell.TermuxShellManager
import com.tom.rv2ide.ai.tool.TerminalPort
import java.util.ArrayList

/**
 * [TerminalPort] 的 app 层实现：从 Termux 的全局 shell 管理器读会话屏幕内容。
 *
 * <p><b>为什么不需要 bindService</b>：[TermuxShellManager] 是全局单例，其
 * `mTermuxSessions` 是 public 的 `List<TermuxSession>`；每个 TermuxSession 暴露
 * `getTerminalSession()`，屏幕内容经 `ShellUtils.getTerminalSessionTranscriptText`
 * 读取。与 [TermuxShellBackend]（需要 bind 服务以**创建**命令）不同，读取只需这个
 * 静态单例，因此本实现是纯读、无生命周期负担。
 *
 * <p><b>只读</b>：不提供向会话写入的能力（见 [TerminalPort] 的类注释）。
 */
class ReportedTerminalPort : TerminalPort {

  override fun sessions(): List<TerminalPort.Session> {
    val out = ArrayList<TerminalPort.Session>()
    val sessions = try {
      TermuxShellManager.getShellManager().mTermuxSessions
    } catch (t: Throwable) {
      return out
    }
    // 复制一份再遍历：用户可能同时在终端里开/关会话，直接遍历会 ConcurrentModification。
    val snapshot = try {
      ArrayList(sessions)
    } catch (t: Throwable) {
      return out
    }
    snapshot.forEachIndexed { index, termuxSession ->
      try {
        val terminalSession = termuxSession.terminalSession ?: return@forEachIndexed
        val transcript =
            ShellUtils.getTerminalSessionTranscriptText(terminalSession, true, false) ?: ""
        out.add(
            TerminalPort.Session(
                index,
                terminalSession.title ?: "",
                terminalSession.isRunning,
                terminalSession.cwd ?: "",
                transcript))
      } catch (t: Throwable) {
        // 单个会话读取失败不影响其它会话。
      }
    }
    return out
  }

  override fun isAvailable(): Boolean = true

  override fun unavailableReason(): String = ""
}
