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

import java.security.KeyPairGenerator
import java.security.PublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSH 主机指纹与 TOFU 校验的行为锁定。
 *
 * <p><b>为什么值得钉住</b>：这是整个 SSH 功能里唯一的安全边界，而它的失败形态是**静默**的
 * ——指纹校验一旦退化成「永远返回 true」，连接照常工作、日志也不会报错，只有中间人攻击
 * 时才表现为「一切正常但数据被转发」。因此不能只靠实机验证（实机无法构造中间人）。
 *
 * <p>覆盖三档 TOFU 语义：
 * 1. 无已保存指纹 → 自动保存并信任（首次连接静默放行，用户要求的行为）
 * 2. 已保存且一致 → 放行
 * 3. 已保存但**不一致** → 拒绝（这一条是 TOFU 的全部价值所在）
 */
class SshHostKeyVerifierTest {

  /** 内存版 store，避免依赖 Android SharedPreferences。 */
  private class FakeStore : SshHostKeyStore {
    private val entries = HashMap<String, String>()

    private fun key(host: String, port: Int) = "$host:$port"

    override fun get(host: String, port: Int): String? = entries[key(host, port)]

    override fun save(host: String, port: Int, fingerprint: String) {
      entries[key(host, port)] = fingerprint
    }

    override fun remove(host: String, port: Int) {
      entries.remove(key(host, port))
    }

    override fun entries(): Map<String, String> = HashMap(entries)
  }

  private fun generateKey(): PublicKey {
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    return generator.generateKeyPair().public
  }

  @Test
  fun fingerprintUsesSha256Base64WithoutPadding() {
    // 格式必须与 ssh-keygen -lf 的输出一致（SHA256:<base64 无填充>），
    // 否则用户没法把 App 显示的指纹和服务器上 `ssh-keygen -lf` 的结果对照验证。
    val fingerprint = sshHostKeyFingerprint(generateKey())

    assertTrue("应以 SHA256: 开头: $fingerprint", fingerprint.startsWith("SHA256:"))
    val base64 = fingerprint.removePrefix("SHA256:")
    assertFalse("base64 不应有 padding 等号: $base64", base64.contains("="))
    assertTrue("其余部分不应为空", base64.isNotEmpty())
  }

  @Test
  fun fingerprintIsStableForSameKey() {
    // 同一把公钥必须每次算出同一个指纹——否则每次连接都被判成「指纹变化」，功能直接不可用。
    val key = generateKey()

    assertEquals(sshHostKeyFingerprint(key), sshHostKeyFingerprint(key))
  }

  @Test
  fun firstConnectionIsTrustedAndSaved() {
    // TOFU 第一档：用户明确要求「首次连接静默信任」，不能抛待确认异常。
    val store = FakeStore()
    val verifier = SshHostKeyVerifier(store)
    val key = generateKey()

    val ok = verifier.verify("example.com", 22, key)

    assertTrue("首次连接应放行", ok)
    assertNotNull("首次连接应把指纹存下来", store.get("example.com", 22))
    assertEquals(sshHostKeyFingerprint(key), store.get("example.com", 22))
  }

  @Test
  fun sameFingerprintIsAcceptedOnLaterConnections() {
    val store = FakeStore()
    val verifier = SshHostKeyVerifier(store)
    val key = generateKey()
    verifier.verify("example.com", 22, key)

    assertTrue("指纹一致时必须放行（否则每次连接都被拒）", verifier.verify("example.com", 22, key))
  }

  @Test
  fun changedFingerprintIsRejected() {
    // TOFU 第三档，也是唯一的安全边界：服务器出示的密钥与已保存的不一致时必须拒绝。
    // 若这条退化成放行，「中间人替换服务器密钥」与「服务器正常换密钥」就无法区分了。
    val store = FakeStore()
    val original = generateKey()
    val originalFingerprint = sshHostKeyFingerprint(original)
    store.save("example.com", 22, originalFingerprint)
    val verifier = SshHostKeyVerifier(store)

    val ok = verifier.verify("example.com", 22, generateKey())

    assertFalse("指纹变化必须拒绝", ok)
    // 也不能悄悄把新指纹存下来——那等于下次就放行了，中间人只需连接两次即可绕过。
    assertEquals("指纹变化时不得覆盖已保存的值", originalFingerprint, store.get("example.com", 22))
  }

  @Test
  fun differentPortsAreIndependent() {
    // 同一台主机的不同端口是不同的服务，指纹各记各的（ssh 用 22，但 git over ssh 常用别的）。
    val store = FakeStore()
    val verifier = SshHostKeyVerifier(store)
    val key = generateKey()
    verifier.verify("example.com", 22, key)

    assertNull("未连接过的端口不应有指纹", store.get("example.com", 2222))
    verifier.verify("example.com", 2222, key)
    assertNotNull("连过之后才有指纹", store.get("example.com", 2222))
  }
}