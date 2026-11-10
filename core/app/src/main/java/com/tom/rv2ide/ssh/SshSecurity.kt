/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from Aharou (https://github.com/520huxiangli/Aharou),
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

package com.tom.rv2ide.ssh

import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.slf4j.LoggerFactory

/**
 * 注册完整版 BouncyCastle 取代 Android 自带的裁剪版。
 *
 * sshj 0.38.0 用 X25519 做密钥交换，Android 自带的 BC provider 不含 X25519 算法，
 * 需先移除裁剪版再注册 bcprov-jdk18on 的完整版，并告诉 sshj 使用它。**必须在任何 sshj
 * 调用之前完成**，所以它被放在 [RemoteSshConnection.newSshClient] 与
 * [SshPrivateKeyStore.fingerprint] 的入口（幂等，重复调用零开销）。
 *
 * 为什么不像源项目那样在 `Application.onCreate` 里注册：源项目有 Hilt 与统一的 App 启动
 * 钩子，ACS 这轮不改 `IDEApplication`；把注册放在传输层入口既保证时序，也让本包可以整体
 * 摘除而不留全局副作用。
 */
internal object SshSecurity {

  private val log = LoggerFactory.getLogger(SshSecurity::class.java)

  @Volatile private var installed = false

  fun ensureInstalled() {
    if (installed) return
    synchronized(this) {
      if (installed) return
      try {
        // 先移除 Android 自带的裁剪版 BC，再注册完整版 bcprov-jdk18on。
        // 用 addProvider 而非 insertProviderAt(…, 1)：BC 只需存在于 Provider 列表中供 sshj
        // 通过 SecurityUtils.setSecurityProvider("BC") 按名查到即可，无需排到最高优先级。
        // 若抬到第 1 位，会抢占 OkHttp/Conscrypt 初始化默认 SSLContext 时的 KeyStore 查找，
        // BC 注册了 BKS 类型却没有配套默认 truststore，导致抛 KeyStoreException: BKS not found
        // （表现为检测更新等 HTTPS 请求崩溃）。放末尾让系统自带 provider 继续负责 TLS。
        java.security.Security.removeProvider("BC")
        java.security.Security.addProvider(BouncyCastleProvider())
        SecurityUtils.setSecurityProvider("BC")
        installed = true
      } catch (e: Throwable) {
        // 注册失败不吞掉连接：部分设备/ROM 可能已自带可用 BC，让后续 sshj 调用自己报具体错误，
        // 比在这里抛一个与真实原因无关的异常更容易定位。
        log.warn("注册完整版 BouncyCastle 失败，SSH 连接可能因缺少 X25519 而失败", e)
        installed = true
      }
    }
  }
}