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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * ACS 无障碍服务。
 *
 * 让 Agent「看得懂、点得动」宿主屏幕：读窗口/节点树、返回键、手势点击/滑动、
 * 系统级截图、设置节点文本。**无需 Shizuku/adb 权限**——这是它与
 * `phone_screenshot`/`phone_click` 等 shell 后端工具的本质区别。
 *
 * <p>需用户在系统「无障碍」设置里手动开启（Android 禁止程序化开启）。
 * 未开启时 [getInstance] 返回 null，工具层据此给出可操作指引。
 *
 * <p><b>为什么不用 uiautomator</b>：u2 server 要注册 UiAutomation，需要
 * `RETRIEVE_WINDOW_CONTENT` 权限，实测只有 Shizuku（adb 级）能跑（见 U2ServerManager）。
 * 无障碍服务则不需要任何额外授权——用户勾选一次即可，代价是需用户手动操作。
 *
 * <p>移植自 Aharou 的 `AharouAccessibilityService`（逻辑照搬，仅改包名与日志实现）。
 */
class AcsAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AcsA11y"
        private const val EVENT_RING_CAP = 1024

        private val log = LoggerFactory.getLogger(AcsAccessibilityService::class.java)

        @Volatile
        private var _instance: AcsAccessibilityService? = null

        // @JvmStatic：工具层与设置页都是 Java/Kotlin 混用，加注解后 Java 侧
        // 可直接写 AcsAccessibilityService.getInstance()，不必经 Companion。
        @JvmStatic
        fun getInstance(): AcsAccessibilityService? = _instance

        /** 服务是否已连接（设置页展示用）。 */
        @JvmStatic
        fun isConnected(): Boolean = _instance != null
    }

    data class RecordedEvent(
        val type: String,
        val packageName: String?,
        val className: String?,
        val text: String?,
        val timestamp: Long,
    )

    private val eventRing = ConcurrentLinkedQueue<RecordedEvent>()
    private val eventListeners = CopyOnWriteArrayList<(RecordedEvent) -> Unit>()

    val nodeRegistry: NodeRegistry = NodeRegistry()

    override fun onCreate() {
        super.onCreate()
        _instance = this
        log.info("service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance = this
        // MIUI 等 ROM 会反复杀掉并重绑本服务；此处留痕便于诊断「服务出现故障」窗口。
        log.info("service connected (manufacturer={})", Build.MANUFACTURER)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val rec = RecordedEvent(
            type = AccessibilityEvent.eventTypeToString(event.eventType),
            packageName = event.packageName?.toString(),
            className = event.className?.toString(),
            text = event.text?.joinToString(" ") { it?.toString() ?: "" }?.takeIf { it.isNotBlank() },
            timestamp = System.currentTimeMillis(),
        )
        eventRing.offer(rec)
        while (eventRing.size > EVENT_RING_CAP) eventRing.poll()
        for (listener in eventListeners) {
            try {
                listener(rec)
            } catch (_: Throwable) {
            }
        }
    }

    override fun onInterrupt() {
        log.info("service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (_instance === this) _instance = null
        eventRing.clear()
        nodeRegistry.clear()
        eventListeners.clear()
        log.info("service destroyed")
    }

    fun snapshotEvents(): List<RecordedEvent> = eventRing.toList()

    fun rootNodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows ?: emptyList()) {
                w.root?.let { out.add(it) }
            }
        } catch (_: Throwable) {
        }
        if (out.isEmpty()) {
            try {
                rootInActiveWindow?.let { out.add(it) }
            } catch (_: Throwable) {
            }
        }
        return out
    }

    fun windowInfos(): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        for ((displayId, list) in windowsByDisplay()) {
            for (w in list) {
                try {
                    val root = w.root
                    out.add(
                        mapOf(
                            "displayId" to displayId,
                            "windowId" to w.id,
                            "type" to when (w.type) {
                                AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
                                AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
                                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
                                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "overlay"
                                else -> "other"
                            },
                            "title" to (w.title?.toString() ?: ""),
                            "focused" to w.isFocused,
                            "active" to w.isActive,
                            "rootPackage" to (root?.packageName?.toString() ?: ""),
                            "rootChildren" to (root?.childCount ?: -1),
                        )
                    )
                } catch (_: Throwable) {
                }
            }
        }
        return out
    }

    /**
     * 所有显示上的窗口（虚拟屏/影子屏在 displayId != 0 上）。
     * `windows` 只给当前显示，所以这里优先用 `windowsOnAllDisplays`（API 30+），拿不到再退回当前显示。
     */
    private fun windowsByDisplay(): List<Pair<Int, List<AccessibilityWindowInfo>>> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val all = windowsOnAllDisplays
                val out = ArrayList<Pair<Int, List<AccessibilityWindowInfo>>>(all.size())
                for (i in 0 until all.size()) out.add(all.keyAt(i) to all.valueAt(i))
                if (out.isNotEmpty()) return out
            } catch (_: Throwable) {
            }
        }
        return listOf(Display.DEFAULT_DISPLAY to (windows ?: emptyList()))
    }

    fun foregroundPackage(): Pair<String?, String?> {
        return try {
            val r = rootInActiveWindow
            (r?.packageName?.toString()) to (r?.className?.toString())
        } catch (_: Throwable) {
            null to null
        }
    }

    // @JvmOverloads：工具层是 Java（与现有 phone_* 工具一致），Kotlin 默认参数
    // 从 Java 不可见，不加注解则每次调用都要显式传超时。
    @JvmOverloads
    fun dispatchSimpleGesture(path: Path, startTime: Long, durationMs: Long, timeoutMs: Long = 5000): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, startTime, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val done = CountDownLatch(1)
        val ok = booleanArrayOf(false)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                ok[0] = true
                done.countDown()
            }

            override fun onCancelled(g: GestureDescription?) {
                ok[0] = false
                done.countDown()
            }
        }
        Handler(Looper.getMainLooper()).post { dispatchGesture(gesture, callback, null) }
        return done.await(timeoutMs, TimeUnit.MILLISECONDS) && ok[0]
    }

    /** 截图结果；成功时 [bitmap] 非空，调用方负责 recycle。 */
    data class ShotResult(
        val bitmap: Bitmap?,
        val errorCode: String? = null,
        val errorMessage: String? = null,
    )

    /** 系统级截图（API 30+）；阻塞至 [timeoutMs]。 */
    @RequiresApi(Build.VERSION_CODES.R)
    @JvmOverloads
    fun captureScreenshot(displayId: Int = Display.DEFAULT_DISPLAY, timeoutMs: Long = 5_000): ShotResult {
        val done = CountDownLatch(1)
        val resultRef = java.util.concurrent.atomic.AtomicReference(
            ShotResult(null, "TIMEOUT", "takeScreenshot timed out")
        )
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            takeScreenshot(displayId, executor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hwBuffer: HardwareBuffer = screenshot.hardwareBuffer
                        val cs: ColorSpace? = screenshot.colorSpace
                        val bmp = Bitmap.wrapHardwareBuffer(hwBuffer, cs)
                        try {
                            hwBuffer.close()
                        } catch (_: Throwable) {
                        }
                        if (bmp == null) {
                            resultRef.set(ShotResult(null, "DECODE_FAILED", "wrapHardwareBuffer returned null"))
                        } else {
                            val sw = bmp.copy(Bitmap.Config.ARGB_8888, false)
                            bmp.recycle()
                            resultRef.set(ShotResult(sw))
                        }
                    } catch (t: Throwable) {
                        resultRef.set(
                            ShotResult(null, "DECODE_FAILED", "${t.javaClass.simpleName}: ${t.message ?: ""}")
                        )
                    } finally {
                        done.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    resultRef.set(ShotResult(null, "TAKE_SCREENSHOT_FAILED", "takeScreenshot error code=$errorCode"))
                    done.countDown()
                }
            })
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                log.warn("takeScreenshot timed out after {}ms", timeoutMs)
            }
        } catch (t: Throwable) {
            resultRef.set(ShotResult(null, "INTERNAL", "${t.javaClass.simpleName}: ${t.message ?: ""}"))
        } finally {
            executor.shutdown()
        }
        return resultRef.get()
    }

    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
