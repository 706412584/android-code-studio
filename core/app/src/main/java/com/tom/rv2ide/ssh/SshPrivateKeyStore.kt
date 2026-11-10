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

import java.io.File
import net.schmizz.sshj.SSHClient
import org.slf4j.LoggerFactory

/**
 * SSH 私钥文件的加密读写：私钥以 Keystore 加密后的密文落盘，读取时在内存解密后交给 sshj。
 *
 * 明文只存在于内存中，容器内进程即便读到私钥文件也只是密文。旧版明文文件在首次读取时惰性加密回写。
 *
 * 移植说明：源项目用它自己的 `FileLogger` 记日志，这里统一换成 slf4j。构造器不再依赖
 * Android [Context]——本类只用裸 [File] 操作，不需要 `@ApplicationContext`。
 */
class SshPrivateKeyStore {

  private val log = LoggerFactory.getLogger(SshPrivateKeyStore::class.java)

  /** 加密写入私钥文件（覆盖原内容）。 */
  fun write(path: String, plain: ByteArray) {
    val file = File(path)
    file.parentFile?.mkdirs()
    file.writeBytes(KeystoreCipher.encryptBytes(plain))
  }

  /** 读取私钥明文 PEM：密文解密，旧明文原样返回并惰性加密回写。 */
  fun readPem(path: String): String {
    val file = File(path)
    val bytes = file.readBytes()
    if (!KeystoreCipher.isEncryptedBytes(bytes)) {
      // 旧版明文文件：本次按明文使用，并尝试加密回写（失败不阻断连接）。
      runCatching { file.writeBytes(KeystoreCipher.encryptBytes(bytes)) }
          .onFailure { log.warn("私钥明文迁移加密失败: {}", file.name, it) }
      return String(bytes, Charsets.UTF_8)
    }
    return String(KeystoreCipher.decryptBytes(bytes), Charsets.UTF_8)
  }

  /** 计算私钥对应公钥的 SHA-256 指纹；加密私钥（需口令）无法解析时返回 null。 */
  fun fingerprint(pem: String): String? = try {
    // loadKeys 走 sshj 的密钥解析，同样需要完整版 BC 才能解 Ed25519/新格式私钥。
    SshSecurity.ensureInstalled()
    val keyProvider = SSHClient().loadKeys(pem, null, null)
    // getPublic() 在 Kotlin 里是关键字 property（`public`），这里直接调 Java getter 避免歧义。
    sshHostKeyFingerprint(keyProvider.getPublic())
  } catch (e: Exception) {
    null
  }
}