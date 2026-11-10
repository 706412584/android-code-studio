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

import com.hierynomus.sshj.common.KeyDecryptionFailedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.slf4j.LoggerFactory

/**
 * 共享的 SSH 连接管理器：持有两条独立的 sshj [SSHClient]——exec 通道供命令执行，
 * SFTP 通道供文件读写。两者隔离，SFTP 的 Buffer 溢出不会拖垮命令通道。
 *
 * 连接配置（host/port/username/auth）由调用方在 [connect] 时传入。连接断开后下次 [connect]
 * 重新建立；SFTP 通道按需惰性建立（见 [sftp]）。exec 侧操作串行化（[mutex]），SFTP 侧由
 * 调用方串行化（sshj 的 SFTPClient 非线程安全）。
 *
 * **「一次登录，之后一直复用」**：[connect] 建立后连接一直保留，所有 exec/shell/sftp 操作都复用
 * 同一条 transport，不会每次调用重新握手。连接跟随项目/远程模式的生命周期：
 * [startSupervisor] 负责探活与断线重连，[disconnect] / [stopSupervisor] 负责收尾。
 *
 * 移植说明：
 * - 去掉 Hilt（`@Singleton` / `@Inject` / `@ApplicationContext`），改为普通构造器注入，
 *   实例由调用方创建并持有；
 * - 日志从源项目的 `FileLogger` 换成 slf4j；
 * - 主机密钥校验用 [SshHostKeyVerifier]（TOFU：首次静默信任、指纹变化拒绝）；
 * - 删掉了依赖源项目 `ExecutionMode` 枚举的 `superviseByExecutionMode`：改由调用方在进入/退出
 *   远程模式时调 [startSupervisor] / [stopSupervisor]（见 [supervisionActive]）；
 * - 删掉了依赖源项目 `ContainerInstaller` / `GitCredential` 的 git 凭据注入与文档同步
 *   （`uploadGitCredentialConfig` / `removeGitCredentialConfig` / `uploadDocs`），
 *   它们属上层功能而非传输层。
 */
class RemoteSshConnection(
    private val hostKeyStore: SshHostKeyStore,
    private val privateKeyStore: SshPrivateKeyStore
) {

  private val log = LoggerFactory.getLogger(RemoteSshConnection::class.java)

  /** 主机密钥校验器由本类按 store 构造，调用方不需要单独传。 */
  private val hostKeyVerifier = SshHostKeyVerifier(hostKeyStore)

  @Volatile private var sshClient: SSHClient? = null

  /**
   * 独立的 SFTP 通道：与 exec 各用一条 SSH transport。sshj 的 SFTP 有间歇性 Buffer 溢出
   * （hierynomus/sshj#461），共用 transport 时崩溃会一并拖垮 Bash/终端；分开后只影响文件通道，
   * 崩溃由 [invalidateSftp] + 下次 [sftp] 重建兜底。
   */
  @Volatile private var sftpSshClient: SSHClient? = null

  @Volatile private var sftpClient: SFTPClient? = null

  private val mutex = Mutex()
  private val sftpLock = Mutex()

  private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)

  /** 连接状态流，供 UI 显示指示器、工作区初始化等待连接就绪。 */
  val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

  /** 重连成功后回调，供上层重新加载。由 [startSupervisor] 注册。 */
  private var onReconnected: (suspend () -> Unit)? = null

  private var supervisorJob: Job? = null

  /** 认证失败标记：看到它就不做快速重连，否则反复重试会触发服务器防爆破（实测被封 IP）。 */
  @Volatile private var isAuthFailure = false

  /**
   * 上一次认证失败的时间与当时的配置。冷却检查放在 [connect] 而不是只放 supervisor，
   * 是因为工具调用、文件访问、MCP 重建这些入口各自直接连，绕开 supervisor 的退避后
   * 实测能在两分钟内撞十几次认证（触发云厂商爆破告警）。
   */
  @Volatile private var authFailureAt = 0L

  @Volatile private var authFailureConfig: RemoteConnectionConfig? = null

  companion object {
    /** 认证失败后的重试间隔：5 分钟，足够避开大多数 fail2ban 窗口。 */
    private const val AUTH_FAILURE_BACKOFF_MS = 5 * 60 * 1000L

    /**
     * 纯映射：远端工作区根 + 本机项目目录名。
     *
     * <p>抽成 static 是为了可测——它决定「命令在远端哪个目录跑」，而错算的后果是
     * `cd` 失败让整条命令以 exit-signal 中止（真机实测报「SSH 连接失败: 未知错误」）。
     *
     * <p>可见性为 internal 而非 private：单测在同模块的 test 源集里要能调它。
     *
     * @param remoteWorkspacePath 配置里的远端工作区根；空白表示未配置 → 返回 null
     * @param projectPath 本机项目绝对路径，只取其最后一段目录名
     */
    @JvmStatic
    internal fun mapToRemoteWorkspace(remoteWorkspacePath: String?, projectPath: String): String? {
      val raw = remoteWorkspacePath?.trim().orEmpty()
      if (raw.isEmpty()) {
        return null
      }
      // 只去掉一个尾斜杠：工作区根本身是 "/" 时必须保留它，否则会拼出 "MyApp" 这种相对路径。
      val base = if (raw.length > 1) raw.trimEnd('/') else raw
      val name = projectPath.trim().trimEnd('/').substringAfterLast('/')
      if (name.isEmpty()) {
        return base
      }
      return if (base == "/") "/$name" else "$base/$name"
    }
  }

  /**
   * 当前是否处于远程执行模式（即 supervisor 是否在跑）。切回本地后「断线即重连」的所有入口
   * 都要据它空转，否则 supervisor 被停掉了，网络恢复 / 回到前台仍会把 SSH 连接建回来。
   *
   * 源项目由 `ExecutionMode` 的 Flow 驱动；ACS 这轮不引入该枚举，改由 [startSupervisor] /
   * [stopSupervisor] 直接维护。顺带修掉源实现的一个隐患：直接调 `startSupervisor` 而不经过
   * 模式监听时，该标记永远为 false，[tryReconnectIfDisconnected] 会一直空转（永不重连）。
   */
  @Volatile private var supervisionActive = false

  /** 远程服务器上的真实 home 路径（连接成功后查 $HOME 缓存），供文件工具展开 ~。 */
  @Volatile
  var remoteHome: String? = null
    private set

  /** 当前连接配置快照，供重连与路径映射使用。 */
  @Volatile
  var config: RemoteConnectionConfig? = null
    private set

  /** 是否认证类失败（密码错、账号被拒、密钥不被接受）：重试再快也没用，只会撞防爆破。 */
  private fun isAuthError(e: Throwable): Boolean {
    // 冷却期内的拦截异常不是新的一次认证失败，否则每拦一次都会把冷却起点往后推。
    if (e is AuthCooldownException) return false
    return friendlySshError(e).contains("认证", ignoreCase = true) ||
        e.message?.contains("auth", ignoreCase = true) == true
  }

  /** 冷却期内拦截 connect 时抛出；不参与「认证失败」判定，也不刷新冷却起点。 */
  private class AuthCooldownException(message: String) : IllegalStateException(message)

  /** 剩余冷却时长（毫秒）；配置变了（改密码、换主机）说明用户已修正，冷却作废。 */
  private fun remainingAuthCooldownMs(cfg: RemoteConnectionConfig): Long {
    val at = authFailureAt
    if (at == 0L || authFailureConfig != cfg) return 0
    return (AUTH_FAILURE_BACKOFF_MS - (System.currentTimeMillis() - at)).coerceAtLeast(0)
  }

  suspend fun connect(config: RemoteConnectionConfig) = mutex.withLock {
    // 凭据为空时直接报根因：否则 sshj 只会抛出
    // 「Exhausted available authentication methods」，与「密码错」「服务器拒绝」分不开，
    // 而且会白跑一轮认证（服务器侧可能计入防爆破失败次数）。
    when (val auth = config.auth) {
      is RemoteAuth.Password ->
          if (auth.password.isBlank()) {
            throw IllegalStateException("SSH 连接失败：未填写密码（凭据为空，不是密码错）")
          }
      is RemoteAuth.PrivateKey ->
          if (auth.privateKeyPath.isBlank()) {
            throw IllegalStateException("SSH 连接失败：未填写私钥路径（凭据为空）")
          }
    }
    // 配置没变时，冷却期内不发起真实连接。所有入口最终都走这里，退避才真的拦得住。
    val cooldownLeftMs = remainingAuthCooldownMs(config)
    if (cooldownLeftMs > 0) {
      throw AuthCooldownException(
          "SSH 认证失败：距上次失败不足 5 分钟，已暂停连接（还需 ${(cooldownLeftMs + 59_999) / 60_000} 分钟）")
    }
    if (isConnected() && this.config == config) return@withLock
    disconnectInternal()
    this.config = config
    _connectionState.value = ConnectionState.CONNECTING
    try {
      withContext(Dispatchers.IO) {
        val client = newSshClient(config)
        sshClient = client
        // 查远程真实 home（root 是 /root，普通用户是 /home/xxx），供文件工具展开 ~
        runCatching {
          val s = client.startSession()
          val cmd = s.exec("echo \$HOME")
          remoteHome =
              java.io.BufferedReader(java.io.InputStreamReader(cmd.inputStream))
                  .readText()
                  .trim()
                  .ifEmpty { null }
          s.close()
        }
        // 注册 transport 断开监听：连接断开时即时推状态，不等 supervisor 轮询
        client.transport.setDisconnectListener { reason, info ->
          log.warn("SSH 连接断开: {}, {}", reason, info)
          _connectionState.value = ConnectionState.DISCONNECTED
        }
        log.info("SSH 已连接 {}:{} as {}", config.host, config.port, config.username)
      }
      _connectionState.value = ConnectionState.CONNECTED
      authFailureAt = 0L
      authFailureConfig = null
      isAuthFailure = false
    } catch (e: Exception) {
      _connectionState.value = ConnectionState.FAILED
      if (isAuthError(e)) {
        authFailureAt = System.currentTimeMillis()
        authFailureConfig = config
        isAuthFailure = true
      }
      throw e
    }
  }

  fun removeHostKey(host: String, port: Int) {
    hostKeyStore.remove(host, port)
  }

  fun savedHostKeys(): Map<String, String> = hostKeyStore.entries()

  /** 无参 connect：用上次保存的 config 重连。 */
  suspend fun connect() {
    val cfg = config ?: throw IllegalStateException("未配置 SSH 连接")
    connect(cfg)
  }

  suspend fun disconnect() = mutex.withLock {
    // 回拉必须在断开**之前**：连接断了就没法读远端文件了。
    // 远程模式下 AI 可能往 ~/shared 写过东西（信箱、待办），只在连上时拉一次不够——
    // 连接不断就不会再拉，写完的信永远回不到手机。断开时正是切回本地、需要数据落地的时刻。
    if (isConnected()) {
      runCatching { onBeforeDisconnect?.invoke(this) }
          .onFailure { log.warn("断开前回拉失败", it) }
    }
    disconnectInternal()
  }

  /** 断开前的回调（由上层注册，用于回拉共享区等收尾动作）。 */
  @Volatile private var onBeforeDisconnect: (suspend (RemoteSshConnection) -> Unit)? = null

  fun setOnBeforeDisconnect(block: suspend (RemoteSshConnection) -> Unit) {
    onBeforeDisconnect = block
  }

  private fun disconnectInternal() {
    closeSftpInternal()
    runCatching { sshClient?.disconnect() }
    sshClient = null
    _connectionState.value = ConnectionState.DISCONNECTED
  }

  /** 关闭并清空独立 SFTP 通道；下次 [sftp] 会按当前 config 重建。 */
  private fun closeSftpInternal() {
    runCatching { sftpClient?.close() }
    runCatching { sftpSshClient?.disconnect() }
    sftpClient = null
    sftpSshClient = null
  }

  /**
   * 建立一条 SSH transport 并完成认证与保活（exec 与 SFTP 各建一条）。
   *
   * 用 [DefaultConfig] 覆写 keepAlive provider 为 [KeepAliveProvider.KEEP_ALIVE]：sshj 默认的 HEARTBEAT
   * 只单向发心跳、不校验回应，对端已死也判不出失联；KEEP_ALIVE（KeepAliveRunner）要求服务器回应
   * keepalive 请求，连续 5 个间隔（5 × 30s）收不到回应即判 CONNECTION_LOST 并断开 transport，
   * 由 supervisor 走重连。
   *
   * 超时：sshj 的 connectTimeout/timeout 默认均为 0（无限等），TCP SYN 重试约 130s 是唯一兜底。
   * 两者均取 15s，与同项目 SftpSyncClient/FtpSyncClient 的取值一致。sshj 的 Reader 捕获
   * SocketTimeoutException 后仅重试、不清连接，故 15s 读超时不会误断空闲但健康的连接。
   */
  private fun newSshClient(config: RemoteConnectionConfig): SSHClient {
    // 必须在构造 SSHClient 之前完成：DefaultConfig 初始化密钥交换算法时会查 SecurityUtils
    // 的 provider，晚一步就退回 Android 裁剪版 BC，X25519 协商失败。
    SshSecurity.ensureInstalled()
    return SSHClient(DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }).apply {
        addHostKeyVerifier(hostKeyVerifier)
        connectTimeout = 15_000
        timeout = 15_000
        connect(config.host, config.port)
        when (val auth = config.auth) {
          is RemoteAuth.Password -> authPassword(config.username, auth.password)
          is RemoteAuth.PrivateKey -> {
            val pem = privateKeyStore.readPem(auth.privateKeyPath)
            val passwordFinder = auth.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
            authPublickey(config.username, loadKeys(pem, null, passwordFinder))
          }
        }
        // 启用 SSH 保活（KEEP_ALIVE：要求服务器回应 keepalive，同时兼作失联检测）
        runCatching {
          connection.keepAlive?.let {
            it.setKeepAliveInterval(30)
            it.start()
          }
        }
      }
  }

  fun isConnected(): Boolean =
      sshClient?.isConnected == true && sshClient?.isAuthenticated == true

  /** 开一个 exec session 执行命令。调用方负责关闭返回的 Command。 */
  fun startExecSession(command: String): Session.Command {
    val client = sshClient ?: throw IllegalStateException("SSH 未连接")
    val session = client.startSession()
    val cmd = session.exec(command)
    // 立即给远端进程的 stdin 送 EOF：所有调用方只读输出。不关的话，远端按「stdin 是否可读」
    // 决定行为的程序（无路径参数的 rg 等）会把 channel 当待读输入，一直等到命令超时。
    runCatching { cmd.outputStream.close() }
    return cmd
  }

  /**
   * 开 exec 会话但**保留 stdin 可写**，供大内容经 stdin 传输（避免命令行参数超过远端 ARG_MAX）。
   * 调用方负责写入并关闭 [Session.Command.outputStream]、读取输出、关闭 session。
   */
  fun startExecSessionWithStdin(command: String): Session.Command {
    val client = sshClient ?: throw IllegalStateException("SSH 未连接")
    return client.startSession().exec(command)
  }

  /**
   * 在远端起一个长驻进程并保留 stdin，供 stdio 型 MCP server 双向通信。
   *
   * 与 [startExecSessionWithStdin] 的区别：那条要求调用方自己管 session 与引号，这里直接返回
   * [Process]，让上层现成的 StdioTransport 不用为远程单写一套。
   */
  fun startProcess(commandLine: String): SshCommandProcess {
    val client = sshClient ?: throw IllegalStateException("SSH 未连接")
    val session = client.startSession()
    val command = session.exec(commandLine)
    return SshCommandProcess(session, command, commandLine.take(80))
  }

  /**
   * 本地工作区路径 → 服务器上的工作区目录（`<remoteWorkspacePath>/<目录名>`）。
   * 与命令侧 cd 目标同一口径，供 MCP stdio 远端起进程复用。
   */
  fun remoteWorkspaceFor(projectPath: String): String? =
      mapToRemoteWorkspace(config?.remoteWorkspacePath, projectPath)


  /** 开一个新的 Session，供调用方分配 PTY 并启动 shell。调用方负责关闭 Session。 */
  fun startShellSession(): Session {
    val client = sshClient ?: throw IllegalStateException("SSH 未连接")
    return client.startSession()
  }

  /**
   * 回拉远端共享区的小文件到本地（远端 → 本地的增量合并）。
   *
   * 只推不拉会让远程模式写的信丢掉：AI 在远端改了 `~/shared/inbox/x.md`，不回拉就永远
   * 到不了手机，App 也不会投递；而且下次连上还会被本地旧版本盖回去。
   *
   * 冲突取舍：本地文件较新（mtime 更新）时不覆盖，只回写远端更新或本地没有的——
   * 没做三方合并，但不静默丢远端内容。
   */
  suspend fun pullSharedFiles(localRoot: java.io.File) {
    val client = sshClient ?: return
    val home = remoteHome ?: return
    val remoteRoot = "${home.trimEnd('/')}/shared"
    withContext(Dispatchers.IO) {
      runCatching {
        val s = client.startSession()
        // 只取 <=1MB 的普通文件，与推送上限一致；文件名与修改时间用 tab 分隔。
        // 注意 `-size -1M` 不能用：GNU find 的 -size 按块数且小数向上取整，
        // 非空文件也算 1 块，于是条件变成「小于 1 块」= 只匹配 0 字节文件，
        // 结果一个都列不出来（2026-10-28 实测：回拉一直空转的根因）。
        val cmd =
            s.exec(
                "cd ${shellQuote(remoteRoot)} 2>/dev/null || exit 0; " +
                    "find . -type f -size -1024k -printf '%T@\\t%p\\n' 2>/dev/null")
        val listing = cmd.inputStream.bufferedReader().readText()
        cmd.join()
        s.close()
        val sftp = sftp()
        var pulled = 0
        listing.lineSequence().forEach { line ->
          val parts = line.split('\t', limit = 2)
          if (parts.size != 2) return@forEach
          val remoteEpoch = parts[0].toDoubleOrNull()?.times(1000)?.toLong() ?: return@forEach
          val rel = parts[1].removePrefix("./").trim()
          if (rel.isEmpty() || rel.startsWith("..") || rel.contains("/../")) return@forEach
          val dest = java.io.File(localRoot, rel)
          // 本地更新就不覆盖：用户的本地编辑不该被远端旧版本冲掉。
          if (dest.isFile && dest.lastModified() >= remoteEpoch) return@forEach
          runCatching {
            dest.parentFile?.mkdirs()
            // RemoteFile 不是 InputStream，读法只能是 read(offset, buf, off, len)，
            // 而这里只回拉 <=1MB 的小文件，直接整块读完再写盘。
            val bytes =
                sftp.open(remoteRoot + "/" + rel).use { rf ->
                  val buf = ByteArray(64 * 1024)
                  val out = java.io.ByteArrayOutputStream()
                  var offset = 0L
                  while (true) {
                    val n = rf.read(offset, buf, 0, buf.size)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    offset += n
                  }
                  out.toByteArray()
                }
            dest.writeBytes(bytes)
            dest.setLastModified(remoteEpoch)
            pulled++
          }
              .onFailure { log.warn("回拉共享区文件失败: {}", rel, it) }
        }
        if (pulled > 0) log.info("已从远端回拉 {} 个共享区文件", pulled)
      }
          .onFailure { log.warn("回拉远端共享区失败", it) }
    }
  }

  /**
   * 获取独立的 SFTP client（惰性建立第二条 SSH 连接，与 exec 隔离）。调用方不应关闭它——
   * 由 [disconnect] 统一管理。连接失效（未连接/未认证）时丢弃重建，覆盖 SFTP 通道崩溃后的自愈。
   */
  suspend fun sftp(): SFTPClient = sftpLock.withLock {
    val cfg = config ?: throw IllegalStateException("SSH 未配置")
    sftpClient
        ?.takeIf { sftpSshClient?.isConnected == true && sftpSshClient?.isAuthenticated == true }
        ?.let { return@withLock it }
    closeSftpInternal()
    val client = withContext(Dispatchers.IO) { newSshClient(cfg) }
    sftpSshClient = client
    val sftp = withContext(Dispatchers.IO) { client.newSFTPClient() }
    sftpClient = sftp
    sftp
  }

  /** 丢弃当前 SFTP 通道（通道异常后调用），下次 [sftp] 会重建。 */
  suspend fun invalidateSftp() = sftpLock.withLock { closeSftpInternal() }

  /** 若已配置但未连接，立即尝试重连一次。返回是否最终连通。供 App 回到前台时主动触发。 */
  suspend fun tryReconnectIfDisconnected(): Boolean {
    // 本地模式下没有远程连接要恢复：网络恢复、回到前台都会调到这里，放行就会把刚断开的连接重连回来。
    if (!supervisionActive) return false
    val cfg = config ?: return false
    if (isConnected()) return true
    _connectionState.value = ConnectionState.CONNECTING
    return try {
      connect(cfg)
      log.info("SSH 重连成功（前台触发）")
      try {
        onReconnected?.invoke()
      } catch (e: kotlinx.coroutines.CancellationException) {
        // 回调里的取消同样要透传：吞掉会让已取消的重连继续往下走，把取消记成一次回调失败。
        throw e
      } catch (e: Exception) {
        log.warn("SSH 已重连但重连后回调失败", e)
      }
      true
    } catch (e: kotlinx.coroutines.CancellationException) {
      // 取消必须原样透传：吐成「重连失败」会把 stopSupervisor 的取消当成一次真正的连接失败。
      throw e
    } catch (e: Exception) {
      log.warn("SSH 重连失败（前台触发）", e)
      // 认证类失败单独记：反复重试会触发服务器防爆破，比「连不上」严重得多。
      isAuthFailure = isAuthError(e)
      if (isAuthFailure) {
        // 与 connect() 共用同一份冷却时间戳，避免各入口各记一套。
        authFailureAt = System.currentTimeMillis()
        authFailureConfig = cfg
      }
      _connectionState.value = ConnectionState.FAILED
      false
    }
  }

  /**
   * 启动连接监督协程：定期探活，连接断开则自动重连（指数退避），
   * 重连成功后触发 [onReconnected] 回调并维护 [connectionState]。
   * 幂等：重复调用不会起多个 supervisor。
   *
   * 与源实现的差异：源项目另有一个 `superviseByExecutionMode(scope, Flow<ExecutionMode>, …)`
   * 负责在「远程/本地」模式切换时自动起停本方法；ACS 这轮不引入 `ExecutionMode`，
   * 由调用方在进入远程模式时调本方法、退出时调 [stopSupervisor]。
   */
  fun startSupervisor(scope: CoroutineScope, onReconnected: suspend () -> Unit) {
    this.onReconnected = onReconnected
    supervisionActive = true
    if (supervisorJob?.isActive == true) return
    supervisorJob = scope.launch {
      var backoffMs = 5000L
      val maxBackoffMs = 30000L
      while (isActive) {
        delay(backoffMs)
        val cfg = config ?: continue
        if (isConnected()) {
          backoffMs = 15000 // 连接正常，拉长探活间隔（仅兜底，断开有 DisconnectListener 即时通知）
          continue
        }
        // 连接已断，尝试重连
        val ok = tryReconnectIfDisconnected()
        // 认证失败单独长退避：密码错/账号改了时反复重连会触发服务器防爆破（fail2ban），
        // 实测直接把手机 IP 封了。这种失败重试再快也没用，只会把事情弄得更糟。
        val authFailed = !ok && isAuthFailure
        if (!ok) {
          backoffMs =
              if (authFailed) {
                AUTH_FAILURE_BACKOFF_MS
              } else {
                (backoffMs * 2).coerceAtMost(maxBackoffMs)
              }
          // 不在这里 delay：循环顶部已按 backoffMs 等过一次，再等一次会让
          // 认证失败实际变成 10 分钟。
        } else {
          isAuthFailure = false
          backoffMs = 15000
        }
      }
    }
  }

  /**
   * 停止连接监督。切回非远程执行模式时调用：supervisor 只服务远程模式，
   * 留着会在本地模式下继续探活并按退避重连，把已经无用的远程连接重新建起来。
   * 幂等：supervisor 本来就没跑时直接返回。
   */
  fun stopSupervisor() {
    supervisionActive = false
    supervisorJob?.cancel()
    supervisorJob = null
  }

  /**
   * 更新 `~/workspace` 符号链接指向当前选中工作区的远程路径，让 AI 用 `~/workspace/...` 路径时
   * Bash 命令（pwd 等）能直接访问到正确的工作区目录。应在工作区选中/初始化后调用。
   *
   * `~/workspace` 已是真实目录时跳过（工作区根被配成了 `~/workspace` 的情况）：此时 `ln` 会把
   * 链接建到该目录**内部**，形成自引用死循环，破坏整个工作区；跳过时命令链路会回退到真实路径。
   */
  suspend fun updateWorkspaceSymlink(workspacePath: String) {
    val client = sshClient ?: return
    val ws = workspacePath.trimEnd('/')
    if (ws.isEmpty()) return
    withContext(Dispatchers.IO) {
      runCatching {
        val session = client.startSession()
        val cmd =
            session.exec(
                "if [ -d ~/workspace ] && [ ! -L ~/workspace ]; then echo skip; " +
                    "else ln -sfn ${shellQuote(ws)} ~/workspace 2>/dev/null; echo done; fi")
        val out = java.io.BufferedReader(java.io.InputStreamReader(cmd.inputStream)).readText().trim()
        session.close()
        if (out == "skip") {
          log.warn("~/workspace 已是真实目录，跳过符号链接（工作区根可能配成了 ~/workspace）")
        }
      }
          .onFailure { log.warn("更新 workspace 符号链接失败: {}", ws, it) }
    }
  }

  /**
   * 释放 App 内置资源（子代理定义、面板示例脚本等）到远程目录。
   * 仅补齐服务器上缺失的文件、**不覆盖已存在**（保留用户修改），脚本赋可执行位。
   * 与源项目一样在连接/重连后调用——远程模式下全局 agents/scripts 以远端为准，
   * 内置默认需在远端存在才能「开箱即用」。二进制安全（base64 传输）。
   */
  suspend fun uploadBuiltinFiles(
      destSubdir: String,
      files: Map<String, ByteArray>,
      executable: Boolean = false,
      /**
       * 相对 home 的容器目录，默认 `.aharou`；共享区传空串（落在 `~/shared`）。
       *
       * 注意：这是从源项目逐字移植的默认值，ACS 若要换自己的目录名，请在调用点显式传入，
       * 不要改这里的默认值——上传路径要与 SshShellBackend 侧的约定保持一致。
       */
      rootDir: String = ".aharou"
  ) {
    if (files.isEmpty()) return
    val client = sshClient ?: return
    val home = remoteHome ?: return
    val root = rootDir.trim('/')
    val destDir =
        home.trimEnd('/') + "/" + (if (root.isEmpty()) "" else "$root/") + destSubdir.trim('/')
    withContext(Dispatchers.IO) {
      runCatching {
        val mk = client.startSession()
        mk.exec("mkdir -p ${shellQuote(destDir)}").join()
        mk.close()
        for ((name, bytes) in files) {
          val dest = "$destDir/$name"
          // name 可含子目录（如 skills/config-channel/SKILL.md）：先建父目录，
          // 否则重定向会因目录不存在而失败。
          val parent = dest.substringBeforeLast('/', "")
          if (parent.isNotEmpty() && parent != destDir) {
            val md = client.startSession()
            md.exec("mkdir -p ${shellQuote(parent)}").join()
            md.close()
          }
          val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
          val chmod = if (executable) " chmod +x ${shellQuote(dest)};" else ""
          val session = client.startSession()
          // $b64 是 base64 无需转义；路径统一走 shellQuote，防路径含单引号时被截断/注入
          session
              .exec(
                  "if [ ! -e ${shellQuote(dest)} ]; then printf %s '$b64' | base64 -d > ${shellQuote(dest)};$chmod fi")
              .join()
          session.close()
        }
        log.info("已释放 {} 个内置文件到远程 {}", files.size, destDir)
      }
          .onFailure { log.warn("释放内置文件到远程失败: {}", destSubdir, it) }
    }
  }

  /**
   * exec + printf 写一个文本文件到远程，内容与路径都由 [shellQuote] 转义。
   *
   * 源项目里它是私有方法，只被文档同步与 git 凭据注入使用；那两个功能未移植，这里把它公开成
   * 一个通用传输原语，供后续的 ShellBackend / 文件工具直接写入远端配置。
   */
  suspend fun writeRemoteTextFile(dest: String, content: String) {
    val client = sshClient ?: throw IllegalStateException("SSH 未连接")
    withContext(Dispatchers.IO) {
      val session = client.startSession()
      // 写临时文件再 mv 覆盖：直接 `>` 写会在中途断连时留下空或半截文件，凭据/ gitconfig
      // 一次写坏后续 git 操作就报看不懂的错。mv 同分区是原子替换。
      val tmp = "$dest.tmp"
      session
          .exec(
              "printf %s ${shellQuote(content)} > ${shellQuote(tmp)} && mv -f ${shellQuote(tmp)} ${shellQuote(dest)}")
          .join()
      session.close()
    }
  }
}

/** 远程 SSH 连接状态，供 UI 指示器与工作区初始化时序判断。 */
enum class ConnectionState {
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
  FAILED
}

/** 把 SSH 连接异常翻译成友好提示，供工具层回显给 AI/用户。 */
fun friendlySshError(e: Throwable): String {
  val msg = e.message.orEmpty()
  return when {
    // 已是明确文案（例如冷却提示），原样透出，别再套一层「SSH 连接失败」。
    "认证失败" in msg -> msg
    e is KeyDecryptionFailedException ->
        "SSH 认证失败：私钥口令不正确或私钥格式不受支持（$msg）"
    e is UserAuthException -> "SSH 认证失败：用户名、密码或密钥未被服务器接受（$msg）"
    "ECONNREFUSED" in msg || "Connection refused" in msg ->
        "无法连接到 SSH 服务器（连接被拒绝），请确认远程服务器已启动且端口正确"
    "UnknownHost" in msg || "unknown host" in msg ->
        "无法连接到 SSH 服务器（未知主机），请检查主机地址"
    "Auth fail" in msg || "authentication" in msg -> "SSH 认证失败，请检查用户名和密码"
    "Network is unreachable" in msg -> "网络不可达，请检查网络连接"
    "SocketTimeout" in msg || "timed out" in msg -> "连接 SSH 服务器超时，请检查网络或服务器状态"
    "SSH 未连接" in msg -> "SSH 未连接，请等待连接恢复或在设置中检查配置"
    else -> "SSH 连接失败: ${e.message ?: "未知错误"}"
  }
}

/** 远程 SSH 连接配置。 */
data class RemoteConnectionConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val auth: RemoteAuth,
    /** 远程服务器上的工作区根路径（AI 的 ~/workspace 映射到此路径）。 */
    val remoteWorkspacePath: String
)

/** SSH 认证方式。源项目定义在 `RemoteSyncClient.kt`，属传输层配置，一并移到这里。 */
sealed class RemoteAuth {
  data class Password(val password: String) : RemoteAuth()

  data class PrivateKey(val privateKeyPath: String, val passphrase: String? = null) : RemoteAuth()
}