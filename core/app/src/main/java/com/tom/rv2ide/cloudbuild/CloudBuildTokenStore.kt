/*
 * This file is part of AndroidCodeStudio.
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

package com.tom.rv2ide.cloudbuild

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * GitHub Personal Access Token 的加密存储。
 *
 * <p><b>为什么用 EncryptedSharedPreferences</b>：PAT 等同于账号写权限（repo +
 * workflow scope），明文存 SharedPreferences 在 root 设备上可被任意读取。
 * 项目已有此依赖（`androidx.security:security-crypto`，见 `PreferencesManager` 的
 * git 凭据存储），复用同一方案而不是新引入加密库。
 *
 * <p><b>为什么不复用 PreferencesManager 的实例</b>：那个类存的是 git 用户名/密码，
 * 且带「记住凭据」开关。云编译的 token 是独立的生命周期（用户可能只为 CI 配置），
 * 用独立的 prefs 文件避免两处逻辑互相纠缠。
 *
 * <p><b>失败降级</b>：EncryptedSharedPreferences 在部分设备（Keystore 异常、
 * 数据损坏）上创建会抛异常。这里捕获并降级为「无存储」——token 仍需在本次
 * 会话内可用（内存持有），只是不持久化，而不是让整个功能崩溃。
 */
class CloudBuildTokenStore(context: Context) {

  private val appContext = context.applicationContext

  /** 加密存储；初始化失败时为 null（降级为仅内存）。 */
  private val encryptedPrefs = try {
    val masterKey = MasterKey.Builder(appContext)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()
    EncryptedSharedPreferences.create(
      appContext,
      PREFS_NAME,
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
  } catch (e: Throwable) {
    null
  }

  /** 降级时的内存副本（进程内有效）。 */
  @Volatile
  private var memoryToken: String = ""

  /** 是否支持持久化（加密存储可用）。 */
  fun isPersistent(): Boolean = encryptedPrefs != null

  /**
   * 读取 token。
   *
   * <p>内存副本优先：用户在设置界面输入后立即使用，不应受存储层延迟影响。
   */
  fun getToken(): String {
    val memory = memoryToken
    if (memory.isNotEmpty()) {
      return memory
    }
    val stored = encryptedPrefs?.getString(KEY_TOKEN, "") ?: ""
    if (stored.isNotEmpty()) {
      memoryToken = stored
    }
    return stored
  }

  /** 保存 token（空串表示清除）。 */
  fun saveToken(token: String) {
    val value = token.trim()
    memoryToken = value
    try {
      encryptedPrefs?.edit()?.putString(KEY_TOKEN, value)?.apply()
    } catch (e: Throwable) {
      // 写入失败不抛：内存副本仍在，本次会话可用；下次启动会丢失，用户重新输入。
    }
  }

  /** 是否已配置 token。 */
  fun hasToken(): Boolean = getToken().isNotEmpty()

  companion object {
    private const val PREFS_NAME = "cloud_build_credentials"
    private const val KEY_TOKEN = "github_pat"

    /**
     * OAuth App 的 client id（Device Flow 用）。
     *
     * <p><b>为什么 client id 可以硬编码</b>：它本就是公开标识（会出现在授权页
     * URL 里），不是机密——Device Flow 的设计前提就是客户端无 secret。真正
     * 需要保护的是 access_token（用户登录后获得，存加密存储）。
     *
     * <p>应用注册信息：GitHub → Settings → Developer settings → OAuth Apps
     * （名称 "Android Code Studio"，owner 706412584）。Device Flow 不需要
     * client secret，也不使用回调 URL。
     */
    const val GITHUB_OAUTH_CLIENT_ID = "Ov23ctm4BhhWs17BDw5F"

    /** 申请 token 时的权限：repo（推代码/建仓）+ workflow（触发 Actions）。 */
    const val GITHUB_OAUTH_SCOPE = "repo workflow"
  }
}
