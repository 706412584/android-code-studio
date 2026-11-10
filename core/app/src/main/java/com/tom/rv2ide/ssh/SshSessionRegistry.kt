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

package com.tom.rv2ide.ssh

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * 按项目路径持有 SSH 会话的注册表。
 *
 * <p><b>为什么必须持有而不是每次新建</b>：这是本功能的**核心需求**——用户要「单次登录后
 * 一直可用」。一次 sshj 握手 + 认证要 1-3 秒，若每次 {@code shell_execute} 都重连，AI 每
 * 跑一条命令都要等这么久，且服务端会看到大量短连接（可能触发 fail2ban）。持有实例后，
 * 每次调用只开一个新 channel（毫秒级）。
 *
 * <p><b>生命周期（用户明确要求：随项目/IDE 关闭而中断）</b>:
 * <ul>
 *   <li>项目关闭 → [disconnect]，见 {@code ProjectSessionRegistry.close}
 *   <li>IDE 退出 → [closeAll]，见 {@code IDEApplication}
 *   <li>进程被杀 → 没有回调，但 TCP 连接随之消失，服务端会立刻看到会话结束
 * </ul>
 *
 * <p><b>按项目而不是全局单例</b>：ACS 支持同时打开多个项目（最多 3 个标签），不同项目
 * 可以连不同的服务器。全局单例会让「打开项目 B」把「项目 A 的连接」顶掉。照
 * {@code BuildConnectionRegistry} 的做法按项目路径索引。
 *
 * <p><b>键的归一化</b>：同一项目可能以 `/a/b`、`/a/b/` 出现。不做归一化会为同一项目开出
 * 两条连接。这里只做轻量归一（去尾斜杠 + 反斜杠转正斜杠），不做 canonical 化——那需要
 * 磁盘 IO，而本类在多处被同步调用，不值得为一个边界情况付出代价。
 */
class SshSessionRegistry(
    private val profileStore: SshProfileStore,
    private val loginKeyStore: SshLoginKeyStore,
    private val privateKeyStore: SshPrivateKeyStore,
    private val hostKeyStore: SshHostKeyStore,
) {

  private companion object {
    val log = LoggerFactory.getLogger(SshSessionRegistry::class.java)
  }

  /** 一个项目对应的会话。 */
  private class Entry(
      val connection: RemoteSshConnection,
      /** supervisor 所在的作用域；断开时一并取消，避免它继续尝试重连。 */
      val scope: CoroutineScope,
  )

  private val entries = ConcurrentHashMap<String, Entry>()

  /** 归一化项目路径。见类注释。 */
  private fun keyOf(projectPath: String): String =
      projectPath.trim().replace('\\', '/').trimEnd('/')

  /**
   * 取（或建立）该项目的 SSH 会话。
   *
   * <p>已存在且已连接时直接返回——这正是「单次登录持续可用」的落点。未连接时会尝试连接
   * （包括之前断线的情况，交给 supervisor 的重连逻辑同步等一次结果）。
   *
   * @return 已连接的会话；凭据失效/网络不可达等失败时返回 null，原因通过 [lastError] 取
   */
  fun getOrConnect(projectPath: String): RemoteSshConnection? {
    val key = keyOf(projectPath)
    val existing = entries[key]
    if (existing != null) {
      if (existing.connection.isConnected()) {
        return existing.connection
      }
      // 已存在但断线：同步试一次（supervisor 的异步重连可能要等几秒到 30 秒，
      // 而工具调用方等不起）。成功后继续用同一个实例。
      return if (reconnect(existing)) existing.connection else null
    }

    val profile = profileStore.active()
    if (profile == null) {
      lastError = "未配置 SSH 连接。请先在 AI 设置里填写主机、用户名与凭据。"
      return null
    }
    val config =
        try {
          profileStore.resolve(profile, privateKeyStore, loginKeyStore)
        } catch (e: Exception) {
          lastError = e.message ?: "SSH 配置无效"
          return null
        }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val connection = RemoteSshConnection(hostKeyStore, privateKeyStore)
    val ok =
        try {
          runBlocking { connection.connect(config) }
          // supervisor 负责断线自动重连（指数退避；认证失败 5 分钟冷却，防 fail2ban）。
          connection.startSupervisor(scope) { /* 重连后无需重载任何本地状态 */ }
          true
        } catch (e: Exception) {
          lastError = friendlySshError(e)
          false
        }
    if (!ok) {
      scope.cancel()
      return null
    }
    entries[key] = Entry(connection, scope)
    lastError = null
    log.info("SSH 会话已建立：project={} host={}", key, profile.hostKey())
    return connection
  }

  /** 同步重连一次。成功返回 true。 */
  private fun reconnect(entry: Entry): Boolean =
      try {
        runBlocking { entry.connection.tryReconnectIfDisconnected() }.also { ok ->
          if (ok) log.info("SSH 会话已恢复") else log.warn("SSH 会话重连失败")
        }
      } catch (e: Exception) {
        lastError = friendlySshError(e)
        false
      }

  /**
   * 最近一次失败原因，供工具层回灌给模型。
   *
   * <p>用字段而不是返回值：多个调用点（工具执行、设置页「测试连接」）都需要它，
   * 但让每个方法都返回 `(connection, error)` 二元组会让调用方到处解构。
   */
  @Volatile
  var lastError: String? = null
    private set


  /** 项目关闭时调用：断开该项目的连接并释放 supervisor。 */
  fun disconnect(projectPath: String) {
    val key = keyOf(projectPath)
    val entry = entries.remove(key) ?: return
    // 先停 supervisor 再断开，否则它会在断开后立刻把连接建回来（源项目注释里踩过）。
    entry.connection.stopSupervisor()
    runCatching { runBlocking { entry.connection.disconnect() } }
    entry.scope.cancel()
    log.info("SSH 会话已断开（项目关闭）：project={}", key)
  }

  /** IDE 退出 / 全局清理时调用。 */
  fun closeAll() {
    for (key in entries.keys.toList()) {
      disconnect(key)
    }
  }

  /** 是否已有该项目的活跃会话（供设置页与工具提示用，不触发连接）。 */
  fun isConnected(projectPath: String): Boolean =
      entries[keyOf(projectPath)]?.connection?.isConnected() == true

  /**
   * 拿**已存在**的会话；不触发连接。
   *
   * <p>与 [getOrConnect] 的区别：后者会真的去连（可能要 1-3 秒并产生网络活动），
   * 而只读工具（{@code ssh_info}）不该为了「报告状态」去建立连接。没有会话时返回 null。
   */
  fun peek(projectPath: String): RemoteSshConnection? = entries[keyOf(projectPath)]?.connection

  /** 当前持有的会话数（测试与诊断用）。 */
  internal fun size(): Int = entries.size
}