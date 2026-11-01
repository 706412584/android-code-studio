/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.assistant.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.assistant.termux.AssistantSessionClient
import com.assistant.termux.AssistantViewClient
import com.assistant.termux.LinuxContainer
import com.assistant.termux.RootfsInstaller
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlin.concurrent.thread

/**
 * 终端页：Termux 的 `TerminalView` + proot 进 Ubuntu 容器。
 *
 * <p><b>为什么是 View 而不是 Compose</b>：`TerminalView` 是传统的自定义 View
 * （重写 onDraw 逐格画字符），Compose 里只能用 AndroidView 包一层——那多一层封装
 * 却不减少任何代码，而本页只有一个全屏子视图，用 XML/代码构建更直接。
 *
 * <p><b>为什么自带 rootfs 安装流程</b>：独立 App 拿不到 ACS 的私有目录
 * （`/data/data/com.tom.rv2ide/files/usr` 是另一个应用的沙箱），Termux bootstrap
 * 也无法跨应用复用。因此必须自己下载一份 Ubuntu base——这也是选 proot 的原因：
 * 官方 base 镜像随处可得，而 Termux bootstrap 依赖 Termux 自己的仓库。
 */
class TerminalActivity : ComponentActivity() {

  private lateinit var container: LinuxContainer
  private var terminalView: TerminalView? = null
  private var session: TerminalSession? = null

  /** 未就绪时显示的引导层（安装 rootfs）。 */
  private lateinit var setupLayer: LinearLayout
  private lateinit var statusText: TextView

  private val main = Handler(Looper.getMainLooper())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    container = LinuxContainer(this)

    val root = FrameLayout(this)
    setupLayer = buildSetupLayer()
    root.addView(setupLayer)
    setContentView(root)

    if (container.isRootfsInstalled && container.isProotAvailable) {
      startTerminal(root)
    } else {
      showSetup(initialProblem())
    }
  }

  /** 诊断当前缺什么，把原因直接显示出来（而不是让用户看着空白页猜）。 */
  private fun initialProblem(): String =
      when {
        !container.isProotAvailable -> {
          val missing = container.missingProotFiles()
          "proot 组件缺失：${missing.joinToString("、")}\n" +
              "这通常是打包问题（jniLibs 未被解压到 nativeLibraryDir）。"
        }
        else -> "还没有 Linux 环境。需要下载一次 Ubuntu 系统（约 30MB），之后可离线使用。"
      }

  private fun buildSetupLayer(): LinearLayout =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(24))
        statusText =
            TextView(this@TerminalActivity).apply {
              textSize = 14f
              gravity = Gravity.CENTER
              text = initialProblem()
            }
        addView(statusText)

        val install =
            TextView(this@TerminalActivity).apply {
              text = "下载并安装"
              textSize = 16f
              setPadding(dp(16), dp(12), dp(16), dp(12))
              setTextColor(0xFF1A73E8.toInt())
              isClickable = true
              setOnClickListener { installRootfs() }
            }
        addView(install)

        val back =
            TextView(this@TerminalActivity).apply {
              text = "返回"
              textSize = 14f
              setPadding(dp(16), dp(12), dp(16), dp(12))
              isClickable = true
              setOnClickListener { finish() }
            }
        addView(back)
      }

  private fun showSetup(message: String) {
    statusText.text = message
    setupLayer.visibility = View.VISIBLE
  }

  private fun installRootfs() {
    // 安装要下载 30MB 并解压上万个文件，绝不能放主线程。
    thread(name = "rootfs-install") {
      val installer = RootfsInstaller(container)
      val error =
          installer.install(
              onProgress = { done, total ->
                val text =
                    if (total > 0) {
                      "下载中 ${done * 100 / total}%（${done / 1024 / 1024}MB / ${total / 1024 / 1024}MB）"
                    } else {
                      "下载中 ${done / 1024 / 1024}MB"
                    }
                main.post { statusText.text = text }
              },
              onStatus = { status -> main.post { statusText.text = status } },
          )
      main.post {
        if (error != null) {
          showSetup("安装失败：\n$error")
        } else {
          setupLayer.visibility = View.GONE
          startTerminal(findViewById(android.R.id.content) as ViewGroup)
        }
      }
    }
  }

  /**
   * 建会话并挂到视图上。
   *
   * <p><b>必须在主线程</b>：`TerminalSession` 构造时 `new Handler()`，无参构造要求
   * 当前线程有 Looper，否则抛「Can't create handler inside thread that has not
   * called Looper.prepare()」。
   */
  private fun startTerminal(root: ViewGroup) {
    // 必须在建 TerminalSession **之前**：emulator 构造时会把当时的配色方案拷进实例，
    // 改晚了这一屏就是白字白底（实测过）。
    container.applyDarkColorScheme()

    val tv =
        TerminalView(this, null).apply {
          // 字号用固定 sp：本页没有缩放 UI，设一次即可。
          setTextSize(spToPx(12))
        }
    val viewClient = AssistantViewClient(this) { terminalView }
    tv.setTerminalViewClient(viewClient)
    terminalView = tv

    val client = AssistantSessionClient(this, { terminalView }) { finished ->
      main.post { Toast.makeText(this, "会话已结束（exit ${finished.exitStatus}）", Toast.LENGTH_SHORT).show() }
    }

    // 调试/自检用：`--es run_cmd "..."` 时，起一个非交互 bash 执行该命令后退出。
    // 这样 adb 能全自动验证「PTY 双向通畅」——否则验证输入需要真的敲键盘
    // （`adb shell input text/keyevent` 都不经过 Termux 的 IME 通道，实测无效）。
    val runCmd = intent?.getStringExtra(EXTRA_RUN_COMMAND)
    val guestArgs =
        if (runCmd.isNullOrBlank()) {
          listOf("/bin/bash", "-l")
        } else {
          listOf("/bin/bash", "-lc", runCmd)
        }
    val argv = container.buildArgv(guestArgs)
    val env = container.buildEnv()
    val sess =
        TerminalSession(
            argv.first(),
            // cwd 用宿主路径：proot 会把它当容器外的当前目录，绑定后容器内可见。
            container.workspace.absolutePath,
            argv.toTypedArray(),
            envToArray(env),
            2000,
            client,
        )
    session = sess

    tv.attachSession(sess)
    // 先用一个合理初值定尺寸，容器起得快。
    // 真实尺寸由 TerminalView 自己在 onSizeChanged 里按字体度量重算并再调一次
    // updateSize —— 因此这里不必（也无法）读视图的可见列数：Termux 没有暴露
    // visibleColumnCount 之类的 API，列数存在 emulator 里，attach 后才存在。
    sess.updateSize(DEFAULT_COLUMNS, DEFAULT_ROWS, 0, 0)

    // 换成终端视图（setupLayer 留着，出错时还能退回去）。
    val frame = root as? FrameLayout
    if (frame != null && setupLayer.parent === frame) {
      frame.removeView(setupLayer)
      frame.addView(tv, 0, FrameLayout.LayoutParams(MATCH, MATCH))
    } else {
      root.addView(tv)
    }
    tv.requestFocus()
  }

  override fun onDestroy() {
    // 先结束会话再让 Activity 销毁：不结束的话 proot 进程会留在后台，
    // 每次进出终端都多一个僵尸 shell。
    session?.finishIfRunning()
    session = null
    terminalView = null
    super.onDestroy()
  }

  // ---- 小工具 ----

  /** Map → `String[]`（Termux 要的是 `KEY=VALUE` 数组，不是继承宿主环境）。 */
  private fun envToArray(env: Map<String, String>): Array<String> =
      env.map { (k, v) -> "$k=$v" }.toTypedArray()

  private fun dp(value: Int): Int =
      TypedValue.applyDimension(
              TypedValue.COMPLEX_UNIT_DIP,
              value.toFloat(),
              resources.displayMetrics)
          .toInt()

  private fun spToPx(value: Int): Int =
      TypedValue.applyDimension(
              TypedValue.COMPLEX_UNIT_SP,
              value.toFloat(),
              resources.displayMetrics)
          .toInt()

  private companion object {
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    /** PTY 的初始尺寸；TerminalView 量完自身后会按实际可见区域重算。 */
    const val DEFAULT_COLUMNS = 80
    const val DEFAULT_ROWS = 24

    /**
     * 自检入口：`adb shell am start -n com.assistant.app/.TerminalActivity \
     *   --es run_cmd "uname -a"` 会执行该命令并退出。
     *
     * <p>存在的理由：验证「PTY 能收能发」必须往终端里写字，而
     * `adb shell input text` / `input keyevent` 都绕过 Termux 的 IME 通道
     * （实测：屏幕上什么都没出现）。有了这个入口，CI/脚本能全自动验证整条链路。
     */
    const val EXTRA_RUN_COMMAND = "run_cmd"
  }
}
