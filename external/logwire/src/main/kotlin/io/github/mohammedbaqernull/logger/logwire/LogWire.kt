/*
 *  This file is part of LogWire.
 *
 *  LogWire is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  LogWire is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with LogWire.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package io.github.mohammedbaqernull.logger.logwire

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import io.github.mohammedbaqernull.logger.ILogWireService
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * LogWire - A lightweight logcat reader and forwarder for Android applications.
 *
 * This class provides automatic logcat reading and forwarding to a centralized log receiver service.
 * It reads logcat output for the current process and sends parsed log entries via AIDL to the
 * LogReceiverService for display and management.
 *
 * Features:
 * - Automatic initialization via ContentProvider
 * - Process-specific logcat filtering
 * - AIDL-based inter-component communication
 * - Thread-safe log reading and forwarding
 * - Singleton pattern for app-wide usage
 *
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
class LogWire private constructor(private val context: Context) {
    
    /** AIDL service interface for sending logs to the receiver */
    private var logService: ILogWireService? = null
    
    /** Current application package name */
    private val packageName = context.packageName
    
    /** Background thread for reading logcat output */
    private var logcatThread: Thread? = null

    /** 当前 logcat 子进程；停止时销毁它让阻塞的 readLine 立即返回。 */
    private var logcatProcess: Process? = null

    /**
     * 读取线程的代次。每启动一个新线程递增；线程循环里校验自己那一代是否仍是当前代，
     * 不是则退出。
     *
     * <p><b>为什么需要它</b>：`readLine()` 是阻塞 IO，不响应 [Thread.interrupt]，
     * 仅靠 `isRunning` 标志有致命竞态——重连时新线程把 `isRunning` 置回 true，
     * 被中断的旧线程恰好在这时解除阻塞，就会看到「isRunning=true」而继续运行，
     * 于是**两条 logcat 进程同时上报，日志重复**。代次校验让旧线程无条件退出，
     * 与新线程的状态无关。
     */
    @Volatile
    private var readerGeneration = 0

    /** Flag indicating if logcat reader is actively running */
    @Volatile
    private var isRunning = false
    
    /** Flag indicating if service connection is established */
    @Volatile
    private var isConnected = false

    /**
     * 是否已注册绑定（`bindService` 返回过 true 且尚未 `unbindService`）。
     *
     * <p>与 [isConnected] 必须分开：一次 `bindService` 必须配一次 `unbindService`，
     * 否则绑定泄漏。断开（服务进程死掉）时 [isConnected] 已是 false，但绑定注册
     * 仍在——此时直接再 `bindService` 会叠加第二个绑定。重连前先解掉旧的。
     */
    @Volatile
    private var isBound = false

    /**
     * 是否应保持连接（用户/宿主未调用 [stop]）。
     *
     * <p>与 [isConnected] 的区别：后者是「此刻连没连上」，本字段是「还想不想连」。
     * 重试逻辑必须看这个——连接失败/断开时 [isConnected] 为 false，但恰恰是
     * 最需要继续重试的时刻。
     */
    @Volatile
    private var shouldBeConnected = false

    /** 重试用的主线程 Handler（bindService 的失败回调与重试都在主线程）。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前退避间隔（毫秒）。成功后重置为初始值。 */
    private var retryDelayMs = INITIAL_RETRY_DELAY_MS

    /** 已排队的重试任务；非 null 表示已有一次重试在等待，避免重复排队。 */
    private var retryRunnable: Runnable? = null

    private val TAG = "LogWire"

    /**
     * Service connection callback handler.
     * Manages the lifecycle of the AIDL service connection.
     */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "✅ Service connected!")
            logService = ILogWireService.Stub.asInterface(service)
            isConnected = true
            // 连上就重置退避：下次断开从最短间隔重试，而不是沿用上次的长间隔。
            retryDelayMs = INITIAL_RETRY_DELAY_MS
            cancelPendingRetry()

            try {
                logService?.registerApp(packageName)
                Log.d(TAG, "✅ Registered app: $packageName")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to register app", e)
            }

            startLogcatReader()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "❌ Service disconnected")
            stopLogcatReader()
            logService = null
            isConnected = false
            // 系统只在「服务进程崩溃」时回调这里，之后**不会**自动重连
            // （BIND_AUTO_CREATE 只保证首次创建）。宿主（ACS）被 force-stop 后重启、
            // 或用户从最近任务划掉再打开，都会走到这条路径——不主动重连就永久失联。
            scheduleRetry()
        }
    }

    /**
     * 排一次延迟重连（若已有排队则 no-op）。
     *
     * <p>指数退避：1s → 2s → 4s … 上限 [MAX_RETRY_DELAY_MS]。宿主可能长时间不在
     * （用户在别的应用里、ACS 被系统杀掉），固定短间隔会白白唤醒 CPU；
     * 而完全放弃重试则会让「先开目标应用、后开 IDE」这个很常见的顺序永久失效
     * （实测踩到：绑定失败后即使 IDE 后来启动，目标应用也不会再上报）。
     */
    private fun scheduleRetry() {
        if (!shouldBeConnected) {
            return
        }
        if (retryRunnable != null) {
            return
        }
        val delay = retryDelayMs
        val runnable = Runnable {
            retryRunnable = null
            if (!shouldBeConnected) {
                return@Runnable
            }
            Log.d(TAG, "🔄 Retrying bind (delay was ${delay}ms)")
            doBind()
        }
        retryRunnable = runnable
        mainHandler.postDelayed(runnable, delay)
        // 下次退避加倍（封顶）：本次失败后如果马上又要重试，间隔会更长。
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun cancelPendingRetry() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
    }
    
    /**
     * Starts the LogWire service by binding to the LogReceiverService.
     *
     * This method:
     * 1. Verifies the logger package is installed
     * 2. Creates an explicit intent to the LogReceiverService
     * 3. Binds to the service with BIND_AUTO_CREATE flag
     *
     * 绑定失败（宿主尚未启动/已被杀）或连接断开时会**自动退避重试**，
     * 因此「先启动目标应用、后打开 IDE」这种顺序也能最终连上
     * （旧实现只绑一次，失败即永久放弃，必须重启目标应用才能恢复）。
     */
    fun start() {
        Log.d(TAG, "🚀 Starting LogWire for package: $packageName")

        // Check if logger app is installed
        val loggerPackage = io.github.mohammedbaqernull.logger.configurations.PACKAGE_NAME
        try {
            context.packageManager.getPackageInfo(loggerPackage, 0)
            Log.d(TAG, "✅ Logger app is installed")
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "❌ Logger app NOT installed: $loggerPackage")
            return
        }

        shouldBeConnected = true
        retryDelayMs = INITIAL_RETRY_DELAY_MS
        doBind()
    }

    /**
     * 执行一次绑定尝试；失败则排下一次重试。
     *
     * <p>与 [start] 分开：重试路径也要走同一段绑定逻辑，但不能再跑一次
     * 「应用是否安装」的检查（那次检查的结果不会变，且重试时重复打日志是噪音）。
     */
    private fun doBind() {
        // 重连前先解掉可能仍存在的旧绑定：断开路径（onServiceDisconnected）留下的
        // 绑定注册必须清理，否则叠加绑定会泄漏，且旧连接的回调会与新连接交错。
        unbindIfBound()

        val loggerPackage = io.github.mohammedbaqernull.logger.configurations.PACKAGE_NAME
        val intent = Intent().apply {
            component = ComponentName(
                loggerPackage,
                "io.github.mohammedbaqernull.logger.service.LogReceiverService"
            )
        }

        try {
            val bound = context.bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
            Log.d(TAG, "Bind service result: $bound")

            if (bound) {
                isBound = true
            } else {
                // 宿主服务当前不可用（进程不在）。这是**可恢复**的——用户随后打开
                // IDE 就会让它可用，因此排重试而不是放弃。
                Log.e(TAG, "❌ Failed to bind - service not available, will retry")
                scheduleRetry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception binding service, will retry", e)
            scheduleRetry()
        }
    }

    /** 解绑（若当前有绑定注册）。失败只记日志——重连路径不应因解绑异常而中断。 */
    private fun unbindIfBound() {
        if (!isBound) {
            return
        }
        isBound = false
        try {
            context.unbindService(serviceConnection)
        } catch (e: Exception) {
            Log.w(TAG, "Unbind failed (continuing)", e)
        }
    }
    
    /**
     * Starts a background thread to read logcat output.
     * 
     * The logcat reader:
     * - Filters logs by current process PID
     * - Uses brief format for parsing
     * - Retrieves last 100 log lines plus streaming output
     * - Parses and forwards each log line to the service
     * - Runs until explicitly stopped or service disconnects
     * 
     * Thread safety: Only one logcat reader thread runs at a time.
     */
    private fun startLogcatReader() {
        if (isRunning) {
            Log.d(TAG, "⚠️ Logcat reader already running")
            return
        }

        Log.d(TAG, "📖 Starting logcat reader...")
        isRunning = true
        // 递增代次：任何仍存活的旧线程会在下一轮循环校验时发现自己过期并退出。
        val generation = ++readerGeneration

        logcatThread = Thread {
            var process: Process? = null
            var reader: BufferedReader? = null
            try {
                // Get current process PID for filtering
                val pid = android.os.Process.myPid()
                Log.d(TAG, "Starting logcat for PID: $pid")

                // Start reading logcat with:
                // -v brief: Use brief output format
                // --pid=$pid: Filter by process ID
                // -T 100: Show last 100 lines then continue streaming
                process = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-v", "brief", "--pid=$pid", "-T", "100")
                )
                logcatProcess = process
                reader = BufferedReader(InputStreamReader(process.inputStream))

                Log.d(TAG, "✅ Logcat reader started, reading logs...")

                var lineCount = 0
                var parsedCount = 0
                var line: String?

                // 双条件：isRunning 表达「想不想读」，generation 表达「是不是当前这一代」。
                // 后者挡住重连时旧线程复活的竞态（见 readerGeneration 注释）。
                while (isRunning && isConnected && generation == readerGeneration) {
                    line = reader.readLine()
                    if (line == null) {
                        Log.d(TAG, "❌ Logcat stream ended")
                        break
                    }

                    lineCount++

                    if (parseAndSendLog(line)) {
                        parsedCount++
                    }

                    // Progress logging every 50 lines
                    if (lineCount % 50 == 0) {
                        Log.d(TAG, "📊 Read: $lineCount lines, Sent: $parsedCount logs")
                    }
                }

                Log.d(TAG, "Logcat reader stopped. Total lines: $lineCount, Parsed: $parsedCount")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Logcat reader error", e)
            } finally {
                // 只有自己仍是当前代时才清理共享字段——否则会把新线程刚设好的
                // process 引用清掉，导致新线程的进程无法被 stop 销毁。
                try {
                    reader?.close()
                } catch (_: Exception) {
                }
                try {
                    process?.destroy()
                } catch (_: Exception) {
                }
                if (generation == readerGeneration) {
                    logcatProcess = null
                    isRunning = false
                }
            }
        }
        logcatThread?.start()
    }
    
    /**
     * Parses a logcat line and sends it to the LogReceiverService.
     * 
     * Expected format: "LEVEL/TAG(PID): message"
     * Example: "D/MainActivity(12345): Hello World"
     * 
     * @param line Raw logcat output line
     * @return true if the line was successfully parsed and sent, false otherwise
     * 
     * The parser:
     * - Extracts log level (V/D/I/W/E/A)
     * - Extracts tag name
     * - Extracts message content
     * - Converts log level to integer value
     * - Sends via AIDL to the service
     * - Filters out LogWire's own debug logs to prevent loops
     */
    private fun parseAndSendLog(line: String): Boolean {
        if (!isConnected) return false
        
        try {
            if (line.isBlank()) return false
            
            // Brief format regex: "LEVEL/TAG(PID): message"
            val regex = """^([VDIWEA])/([^(]+)\(\s*\d+\)\s*:\s+(.+)$""".toRegex()
            val match = regex.find(line.trim())
            
            if (match == null) {
                return false
            }
            
            val groups = match.groupValues
            if (groups.size < 4) return false
            
            val levelChar = groups[1]
            val tag = groups[2].trim()
            val message = groups[3]
            
            if (tag.isEmpty() || message.isEmpty()) return false
            
            // Prevent infinite loop by not sending our own debug logs
            if (tag == TAG) return false
            
            // Convert log level character to Android log level integer
            val level = when (levelChar) {
                "V" -> 2  // VERBOSE
                "D" -> 3  // DEBUG
                "I" -> 4  // INFO
                "W" -> 5  // WARN
                "E" -> 6  // ERROR
                "A" -> 7  // ASSERT
                else -> 4 // Default to INFO
            }
            
            logService?.sendLog(packageName, tag, message, level, System.currentTimeMillis())
            
            // Sample debug logging (10% of messages)
            if (Math.random() < 0.1) {
                Log.d(TAG, "✅ Sent: [$levelChar/$tag] ${message.take(50)}${if(message.length > 50) "..." else ""}")
            }
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing/sending log: $line", e)
            return false
        }
    }
    
    /**
     * Stops the logcat reader thread.
     * 
     * Sets the running flag to false and interrupts the thread.
     * The thread will terminate gracefully on its next iteration.
     */
    private fun stopLogcatReader() {
        Log.d(TAG, "🛑 Stopping logcat reader...")
        isRunning = false
        // 销毁子进程让阻塞中的 readLine 立即返回（interrupt 对阻塞 IO 无效）。
        // 这是让线程真正退出的**必要**手段，否则线程会一直卡在 readLine 上，
        // 直到 logcat 自身有输出才可能醒来看一眼标志。
        try {
            logcatProcess?.destroy()
        } catch (_: Exception) {
        }
        logcatProcess = null
        logcatThread?.interrupt()
        logcatThread = null
    }
    
    /**
     * Stops LogWire and unbinds from the service.
     * 
     * This method:
     * 1. Stops the logcat reader thread
     * 2. Unbinds from the LogReceiverService
     * 3. Cleans up connection state
     * 
     * Safe to call multiple times - checks connection state before unbinding.
     */
    fun stop() {
        // 先掐掉「还想连」的意图与已排队的重试，否则 stop 之后重试仍会发起绑定，
        // 表现为「已经停了却还在连」。
        shouldBeConnected = false
        cancelPendingRetry()
        try {
            stopLogcatReader()
            // 用 isBound 而不是 isConnected：断开状态下连接已断但绑定注册还在，
            // 只看 isConnected 会漏掉解绑（旧实现就有这个问题）。
            if (isBound) {
                unbindIfBound()
                Log.d(TAG, "Unbound from service")
            }
            isConnected = false
            logService = null
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding", e)
        }
    }

    companion object {
        /** Singleton instance of LogWire */
        @Volatile
        private var instance: LogWire? = null

        /** 首次重试的延迟（毫秒）。 */
        private const val INITIAL_RETRY_DELAY_MS = 1_000L

        /**
         * 重试延迟上限（毫秒）。
         *
         * <p>60s：宿主长时间不在时每分钟一次尝试，对电量影响可忽略；
         * 而一旦宿主可用，最坏也只等一分钟就连上。
         */
        private const val MAX_RETRY_DELAY_MS = 60_000L
        
        /**
         * Initializes the LogWire singleton instance.
         * 
         * This method is thread-safe using double-checked locking.
         * Should be called once during application startup, typically
         * via LogWireInitializer ContentProvider.
         * 
         * @param context Application context for service binding
         */
        fun initialize(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = LogWire(context.applicationContext)
                        instance?.start()
                    }
                }
            }
        }
    }
}