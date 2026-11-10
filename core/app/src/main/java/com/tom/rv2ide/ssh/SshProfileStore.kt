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

/**
 * 一条 SSH 连接配置（用户在设置页填的那一份）。
 *
 * <p><b>为什么不直接存 [RemoteConnectionConfig]</b>：后者携带的是**运行时认证材料**
 * （明文密码 / 已解密的私钥口令），不该被序列化进偏好。这里只存「密码密文」与
 * 「私钥的标识」，连接前才由 [SshProfileStore.resolve] 还原成可用的 [RemoteConnectionConfig]。
 *
 * @param id 稳定标识，用作偏好键与权限 scope 里的 {@code sshProfile}
 * @param name 展示名
 * @param host 主机名或 IP
 * @param port SSH 端口
 * @param username 登录用户名
 * @param auth 认证方式
 * @param remoteWorkspacePath 远端工作区根路径；空串表示用远端 $HOME
 */
data class SshProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val auth: SshAuthRef,
    val remoteWorkspacePath: String = ""
) {
  /** 供权限 scope 与提示词使用的主机标识（`host:port`）。 */
  fun hostKey(): String = "$host:$port"
}

/** 认证方式引用：只存「指向」，不存明文材料。 */
sealed class SshAuthRef {
  /** 密码认证：密文由 [KeystoreCipher] 加密后存在偏好里。 */
  data class Password(val encryptedPassword: String) : SshAuthRef()

  /** 私钥认证：引用 [SshLoginKey.id]，私钥文件本身在应用私有目录。 */
  data class LoginKey(val loginKeyId: String, val passphrase: String? = null) : SshAuthRef()
}

/** SSH 连接配置的持久化存储。 */
interface SshProfileStore {
  /** 全部配置，按添加顺序。 */
  fun all(): List<SshProfile>

  /** 按 id 取；不存在返回 null。 */
  fun find(id: String): SshProfile?

  /** 当前选中的配置 id；未配置时返回 null。 */
  fun activeId(): String?

  /** 当前选中的配置；未配置时返回 null。 */
  fun active(): SshProfile? = activeId()?.let { find(it) }

  fun save(profile: SshProfile)

  fun remove(id: String)

  fun setActiveId(id: String?)

  /**
   * 把配置还原成可连的 [RemoteConnectionConfig]（解密密码/口令）。
   *
   * <p>解密失败（Keystore 条目被系统清除、换了设备恢复备份等）时抛异常——调用方应把它
   * 呈现为「凭据已失效，请重新填写」，而不是笼统的连接失败。
   */
  fun resolve(profile: SshProfile, privateKeys: SshPrivateKeyStore?, loginKeys: SshLoginKeyStore): RemoteConnectionConfig
}

private const val PREFS_NAME = "ssh_profiles"
private const val KEY_ACTIVE = "active_profile_id"

/**
 * 基于应用私有 SharedPreferences 的实现。
 *
 * <p><b>凭据存储的取舍</b>：密码与私钥口令走 [KeystoreCipher]（Android Keystore 加密后
 * 落盘），与 Aharou 一致。这与 `ModelSettings` 里「API Key 明文存」的立场不同——那是
 * 有意的区别：API Key 泄露的后果是「AI 额度被盗用」，私钥泄露的后果是「服务器被登录」，
 * 后者代价高得多，值得多一层加密。
 */
class SharedPrefsSshProfileStore(context: Context) : SshProfileStore {

  private val preferences =
      context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  override fun all(): List<SshProfile> =
      preferences.all.keys
          .filter { it.endsWith("_host") }
          .mapNotNull { raw -> find(raw.removeSuffix("_host")) }
          // 先按 id 排序再 distinctBy：`preferences.all.keys` 的顺序不保证稳定，
          // 不去重前先定序会让「保留哪一条」随进程重启而变——用户看到的条目
          // 时有时无地跳变。
          .sortedBy { it.id }
          // 按「主机+端口+用户名」去重：这三者相同就是同一台机器的同一个账号。
          //
          // 为什么在读取侧也去重：历史版本允许重复保存同一条配置（列表只显示
          // user@host:port，名字往往也相同，用户看到的就是「3 个重复 item」）。
          // 保存侧已改为复用已有 id，但设备上可能已存在旧数据——这里折叠显示，
          // 让存量数据也不再显示成多条。**不删数据**：只是折叠，避免误删用户配置。
          .distinctBy { Triple(it.host, it.port, it.username) }
          // 最后按名称排序，让设置页列表可预期。
          .sortedBy { it.name }

  override fun find(id: String): SshProfile? {
    if (id.isEmpty()) return null
    val host = preferences.getString("${id}_host", null) ?: return null
    val username = preferences.getString("${id}_username", null) ?: return null
    val authKind = preferences.getString("${id}_auth_kind", null) ?: return null
    val auth =
        when (authKind) {
          AUTH_PASSWORD ->
              SshAuthRef.Password(preferences.getString("${id}_password", "") ?: "")
          AUTH_LOGIN_KEY ->
              SshAuthRef.LoginKey(
                  loginKeyId = preferences.getString("${id}_login_key_id", "") ?: "",
                  passphrase =
                      preferences
                          .getString("${id}_passphrase", null)
                          ?.let { runCatching { KeystoreCipher.decryptString(it) }.getOrNull() }
                          ?.takeIf { it.isNotEmpty() })
          else -> return null
        }
    return SshProfile(
        id = id,
        name = preferences.getString("${id}_name", null) ?: id,
        host = host,
        port = preferences.getInt("${id}_port", 22),
        username = username,
        auth = auth,
        remoteWorkspacePath = preferences.getString("${id}_remote_workspace", "") ?: "")
  }

  override fun activeId(): String? = preferences.getString(KEY_ACTIVE, null)?.takeIf { it.isNotEmpty() }

  override fun save(profile: SshProfile) {
    val id = profile.id
    val editor =
        preferences
            .edit()
            .putString("${id}_name", profile.name)
            .putString("${id}_host", profile.host)
            .putInt("${id}_port", profile.port)
            .putString("${id}_username", profile.username)
            .putString("${id}_remote_workspace", profile.remoteWorkspacePath)
    when (val auth = profile.auth) {
      is SshAuthRef.Password -> {
        // 密码本身就是密文（调用方写入前已加密），这里原样存。
        editor.putString("${id}_auth_kind", AUTH_PASSWORD)
        editor.putString("${id}_password", auth.encryptedPassword)
        editor.remove("${id}_login_key_id")
        editor.remove("${id}_passphrase")
      }
      is SshAuthRef.LoginKey -> {
        editor.putString("${id}_auth_kind", AUTH_LOGIN_KEY)
        editor.putString("${id}_login_key_id", auth.loginKeyId)
        if (auth.passphrase.isNullOrEmpty()) {
          editor.remove("${id}_passphrase")
        } else {
          editor.putString("${id}_passphrase", KeystoreCipher.encryptString(auth.passphrase))
        }
        editor.remove("${id}_password")
      }
    }
    editor.apply()
  }

  override fun remove(id: String) {
    val editor = preferences.edit()
    for (suffix in SUFFIXES) {
      editor.remove("${id}_$suffix")
    }
    if (activeId() == id) {
      editor.remove(KEY_ACTIVE)
    }
    editor.apply()
  }

  override fun setActiveId(id: String?) {
    if (id == null) {
      preferences.edit().remove(KEY_ACTIVE).apply()
    } else {
      preferences.edit().putString(KEY_ACTIVE, id).apply()
    }
  }

  override fun resolve(
      profile: SshProfile,
      privateKeys: SshPrivateKeyStore?,
      loginKeys: SshLoginKeyStore
  ): RemoteConnectionConfig {
    val auth =
        when (val ref = profile.auth) {
          is SshAuthRef.Password -> {
            val plain =
                try {
                  KeystoreCipher.decryptString(ref.encryptedPassword)
                } catch (e: Exception) {
                  throw IllegalStateException(
                      "SSH 连接「${profile.name}」的密码已无法解密（可能是系统清除了密钥或从备份恢复）。请重新填写密码。",
                      e)
                }
            RemoteAuth.Password(plain)
          }
          is SshAuthRef.LoginKey -> {
            val key =
                loginKeys.entries().firstOrNull { it.id == ref.loginKeyId }
                    ?: throw IllegalStateException(
                        "SSH 连接「${profile.name}」引用的登录密钥已不存在，请重新选择私钥。")
            if (privateKeys == null) {
              throw IllegalStateException("SSH 私钥组件未初始化，无法使用私钥认证。")
            }
            RemoteAuth.PrivateKey(privateKeyPath = key.path, passphrase = ref.passphrase)
          }
        }
    return RemoteConnectionConfig(
        host = profile.host,
        port = profile.port,
        username = profile.username,
        auth = auth,
        remoteWorkspacePath = profile.remoteWorkspacePath)
  }

  private companion object {
    const val AUTH_PASSWORD = "password"
    const val AUTH_LOGIN_KEY = "login_key"

    /** [remove] 要清理的全部键后缀——漏一个就会在偏好里留下孤儿。 */
    val SUFFIXES =
        listOf(
            "name",
            "host",
            "port",
            "username",
            "remote_workspace",
            "auth_kind",
            "password",
            "login_key_id",
            "passphrase")
  }
}