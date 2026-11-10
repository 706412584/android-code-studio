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

import android.content.Context
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.slf4j.LoggerFactory

/** SSH 主机指纹的持久化存储，key 为 `host:port`。 */
interface SshHostKeyStore {
  fun get(host: String, port: Int): String?

  fun save(host: String, port: Int, fingerprint: String)

  fun remove(host: String, port: Int)

  fun entries(): Map<String, String>
}

private const val PREFS_NAME = "ssh_host_keys"

/**
 * 基于应用私有 SharedPreferences 的实现。
 *
 * 构造器直接注入 [Context]（源项目用 Hilt 的 `@ApplicationContext`）；这里内部统一取
 * applicationContext，避免调用方误传 Activity 造成泄漏。
 */
class SharedPrefsSshHostKeyStore(context: Context) : SshHostKeyStore {

  private val preferences =
      context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private fun key(host: String, port: Int): String = "$host:$port"

  override fun get(host: String, port: Int): String? = preferences.getString(key(host, port), null)

  override fun save(host: String, port: Int, fingerprint: String) {
    preferences.edit().putString(key(host, port), fingerprint).apply()
  }

  override fun remove(host: String, port: Int) {
    preferences.edit().remove(key(host, port)).apply()
  }

  override fun entries(): Map<String, String> =
      preferences.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
}

/** 计算主机公钥的 SHA-256 指纹，形如 `SHA256:<base64>`，与 ssh-keygen -lf 的格式一致。 */
fun sshHostKeyFingerprint(key: PublicKey): String {
  val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
  return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

/**
 * SSH 主机密钥校验：**TOFU（Trust On First Use）——首次连接静默信任，指纹变化一律拒绝**。
 *
 * 三档行为：
 * 1. 无已保存指纹 → 自动保存并返回 true（首次连接静默信任，不弹任何确认）；
 * 2. 已保存且一致 → 返回 true；
 * 3. 已保存但**不一致** → 返回 false（sshj 随即以 HOST_KEY_NOT_VERIFIABLE 断开），并记 error 日志。
 *
 * 为什么第 3 条必须保留：TOFU 的全部安全价值都在这一条上。若指纹变化也被静默接受，
 * 「中间人替换服务器密钥」与「服务器正常换密钥」无法区分，等于完全没有中间人防护。
 * 换密钥/重装服务器的场景由用户显式调用 [SshHostKeyStore.remove] 清除旧指纹后重新 TOFU。
 *
 * **与原实现的差异（ACS 取舍）**：源项目在「无已保存指纹」时抛 `SshHostKeyPendingException`
 * 并经全局 StateFlow 弹提醒，等用户点「信任」才放行。ACS 这一轮不做指纹确认 UI，
 * 因此删掉了 `globalPending` / `confirmGlobalPending` / `dismissGlobalPending` /
 * `consumePending` 那一整套弹窗服务机制，改为首次连接静默信任。
 */
class SshHostKeyVerifier(private val store: SshHostKeyStore) : HostKeyVerifier {

  private val log = LoggerFactory.getLogger(SshHostKeyVerifier::class.java)

  override fun findExistingAlgorithms(hostname: String, port: Int): MutableList<String> =
      mutableListOf()

  override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
    val fingerprint = sshHostKeyFingerprint(key)
    val saved = store.get(hostname, port)
    if (saved == null) {
      // 首次连接该主机：静默保存并信任（TOFU）。之后若指纹变化，下面的分支会拒绝。
      store.save(hostname, port, fingerprint)
      log.info(
          "SSH 首次连接 {}:{}，已保存主机密钥指纹（{}）{}，后续指纹变化将被拒绝",
          hostname,
          port,
          key.algorithm,
          fingerprint)
      return true
    }
    if (saved != fingerprint) {
      // 指纹不一致：可能是服务器换了密钥，也可能是中间人。一律拒绝，必须由用户显式清除旧指纹。
      log.error(
          "SSH 主机密钥指纹变化，拒绝连接 {}:{}（{}）：已保存 {}，服务器出示 {}。" +
              "若非服务器换密钥，请警惕中间人攻击；确认无误后清除该主机指纹再重连。",
          hostname,
          port,
          key.algorithm,
          saved,
          fingerprint)
      return false
    }
    return true
  }
}