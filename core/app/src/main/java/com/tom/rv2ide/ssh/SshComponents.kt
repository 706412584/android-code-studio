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

import android.content.Context
import org.slf4j.LoggerFactory

/**
 * SSH 各组件的进程级装配点。
 *
 * <p><b>为什么需要它</b>：[SshSessionRegistry] 必须**全进程唯一**——否则编辑器标签页与
 * AI 工具层会各自持有一份连接表，同一条 SSH 连接被建立两次（用户看到的「一次登录」失效，
 * 服务端看到两条会话）。而这两处一个在 `ViewModel`（无 Context）、一个在 `AgentOrchestrator`
 * （有 Context），无法靠构造注入对齐，故设此单例。
 *
 * <p>与 `BuildConnectionRegistryHolder` 同一模式：延迟初始化，首次访问才读偏好。
 * 容器为 null 表示还没初始化过；用 `@Volatile` + 双重检查保证只建一次。
 */
object SshComponents {

  private val log = LoggerFactory.getLogger(SshComponents::class.java)

  @Volatile private var registry: SshSessionRegistry? = null

  @Volatile private var profileStore: SshProfileStore? = null

  /**
   * 取会话表；未初始化时返回 null（调用方应容忍并给出「SSH 未初始化」）。
   *
   * <p>`@JvmStatic`：Java 调用方（`AgentOrchestrator`）直接写 `SshComponents.sessions()`
   * 即可，不必写 `SshComponents.INSTANCE.sessions()`——后者在 lambda 里尤其啰嗦，
   * 且容易被误写成静态上下文引用。
   */
  @JvmStatic fun sessions(): SshSessionRegistry? = registry

  @JvmStatic fun profiles(): SshProfileStore? = profileStore

  /** 用应用 Context 初始化（幂等）。通常在 Application 启动或首次需要时调用。 */
  @JvmStatic
  fun init(context: Context): SshSessionRegistry {
    registry?.let { return it }
    synchronized(this) {
      registry?.let { return it }
      val app = context.applicationContext
      val profiles = SharedPrefsSshProfileStore(app)
      val loginKeys = SharedPrefsSshLoginKeyStore(app)
      val hostKeys = SharedPrefsSshHostKeyStore(app)
      val privateKeys = SshPrivateKeyStore()
      // 私钥解析（计算指纹）也要完整版 BouncyCastle，必须在首次使用前注册。
      SshSecurity.ensureInstalled()
      val created = SshSessionRegistry(profiles, loginKeys, privateKeys, hostKeys)
      profileStore = profiles
      registry = created
      log.info("SSH 组件已初始化")
      return created
    }
  }

  /** 供测试重置（生产代码不应调用）。 */
  internal fun resetForTest() {
    synchronized(this) {
      registry?.closeAll()
      registry = null
      profileStore = null
    }
  }
}