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

package com.tom.rv2ide.artificial.agent

import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.tom.rv2ide.ai.tool.OcrPort
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * [OcrPort] 的 ML Kit bundled 中文实现。
 *
 * **为什么用 bundled 版**：设备可能无 GMS（黑鲨实测无），bundled 版模型与推理库
 * 都打进 APK（text-recognition-chinese AAR 的 assets + text-recognition-bundled-common
 * 的 .so），thick-client 路径只查本地 module descriptor，运行时不触 GMS、不下载。
 *
 * **为什么单例 + 懒创建 + 串行**：TextRecognizer 创建要加载模型（首次数百 ms），
 * 每次调用新建是浪费；ML Kit 检测器实例的并发语义不保证，这里用一把锁把
 * process+await 串行化（OCR 本身慢，串行的代价可忽略）。
 *
 * **线程前提**：`recognize` 在 agent 工作线程调用（`Tasks.await` 同步阻塞安全），
 * 任何情况下不得在主线程调用。
 */
object MlKitOcrPort : OcrPort {

  private const val ENGINE_NAME = "mlkit-text-recognition-chinese(bundled)"

  /** 消化遗留 task 的等待上限；超过则丢弃旧 recognizer（见 recognize 内的说明）。 */
  private const val DRAIN_TIMEOUT_MS = 30_000L

  private val lock = Any()

  @Volatile
  private var recognizer: TextRecognizer? = null

  /**
   * 上一次调用遗留的未完结 task（超时/中断时非 null）。
   *
   * <p>**为什么必须记录**：{@code Tasks.await} 超时只是"放弃等待"，底层 task 仍在
   * 同一 recognizer 上跑。下一次调用若直接 {@code process()}，就是在同一实例上并发
   * 调用——ML Kit 不保证检测器实例的并发语义。串行保证不能只在"正常路径"成立。
   */
  private var pendingTask: com.google.android.gms.tasks.Task<*>? = null

  private fun recognizer(): TextRecognizer =
      recognizer
          ?: synchronized(lock) {
            recognizer
                ?: TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                    .also { recognizer = it }
          }

  override fun recognize(imageBytes: ByteArray, timeoutMs: Long): OcrPort.Result {
    if (imageBytes.isEmpty()) {
      return OcrPort.Result.failure("图片字节为空")
    }
    val bitmap =
        try {
          BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Throwable) {
          return OcrPort.Result.failure("图片解码失败: ${e.message}")
        }
    if (bitmap == null) {
      return OcrPort.Result.failure("图片解码失败：不是可识别的 PNG/JPEG")
    }
    // settled = ML Kit 的 task 是否已确定完结。只有完结后才能安全回收 bitmap：
    // Tasks.await 超时/被中断只表示"放弃等待"，底层 task 可能仍在后台读这张图，
    // 此时 recycle() 会造成 native use-after-free（进程级崩溃）。
    var settled = false
    var task: com.google.android.gms.tasks.Task<*>? = null
    return try {
      val lines =
          synchronized(lock) {
            // 先消化上一次遗留的未完结 task，恢复"串行"保证（见 pendingTask 注释）。
            drainPendingTask()
            task = recognizer().process(InputImage.fromBitmap(bitmap, 0))
            val text = Tasks.await(task, timeoutMs, TimeUnit.MILLISECONDS)
            settled = true // await 正常返回 = task 已完结
            val out = ArrayList<OcrPort.Line>()
            for (block in text.textBlocks) {
              for (line in block.lines) {
                val box = line.boundingBox ?: continue // 极少数行可能无 box，跳过
                out.add(OcrPort.Line(line.text, box.left, box.top, box.right, box.bottom))
              }
            }
            out
          }
      OcrPort.Result.ok(lines, ENGINE_NAME)
    } catch (e: TimeoutException) {
      // 注意：**不回收 bitmap**（settled 仍 false）——task 可能还在跑。
      // 记录未完结 task：下一次调用先消化它，避免同一 recognizer 上并发 process。
      synchronized(lock) { pendingTask = task }
      OcrPort.Result.failure("OCR 超时（${timeoutMs}ms）。可缩小 region 或降低 scale 后重试。")
    } catch (e: ExecutionException) {
      settled = true // task 内部抛异常 = task 已完结，可安全回收
      OcrPort.Result.failure("OCR 引擎错误: ${e.cause?.message ?: e.message}")
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      // 同超时：task 状态未知，不回收；记录以便下次消化。
      synchronized(lock) { pendingTask = task }
      OcrPort.Result.failure("OCR 被中断（用户可能点了停止）")
    } catch (e: Throwable) {
      OcrPort.Result.failure("OCR 失败: ${e.javaClass.simpleName}: ${e.message}")
    } finally {
      if (settled) bitmap.recycle()
    }
  }

  /**
   * 消化遗留的未完结 task（必须在 lock 内调用）。
   *
   * <p>正常等它完结（引擎只是慢）；等不到（引擎卡死）就丢弃旧 recognizer——
   * 旧 task 继续跑在旧实例上、其 bitmap 也没被回收，不会 use-after-free。
   */
  private fun drainPendingTask() {
    val pending = pendingTask ?: return
    try {
      Tasks.await(pending, DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      pendingTask = null
    } catch (e: TimeoutException) {
      recognizer?.close()
      recognizer = null
      pendingTask = null
    } catch (e: Throwable) {
      // 旧 task 以异常完结（含中断）：忽略异常，继续本次调用。
      pendingTask = null
    }
  }
}
