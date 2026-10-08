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

package com.tom.rv2ide.artificial.agent.compose.compat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.File
import java.io.InputStream
import kotlin.io.copyTo

/**
 * Aharou 文件访问抽象与图片压缩的**最小映射**。
 *
 * <p><b>为什么接口只留 4 个方法</b>：Aharou 的 `FileAccessProvider` 有 20 多个方法
 * （readLines / listFiles / rename / copy / deleteRecursively …），因为它的工具层整套跑在这个抽象上。
 * 移植范围内的 16 个文件只调用 4 个（[exists] / [writeBytes] / [writeStream] / [copyToLocal]），
 * 所以就只定义这 4 个 —— 实现方（下一阶段的宿主接线）不必凭空补出一整套文件 API。
 *
 * <p>其余符号出处：`WorkspacePathMapper.CONTAINER_ROOT` ← `feature/workspace/domain/WorkspacePathMapper.kt`；
 * `ImageCompressor` ← `feature/agent/domain/tool/file/ImageCompressor.kt`。
 */

/**
 * 文件读写后端抽象（Aharou 同名接口的最小等价物）。
 *
 * <p>Aharou 做这个抽象是为了「本地 PRoot 模式 / 远程 SFTP 模式」两套实现共用一套工具代码。
 * ACS 的 agent 工具直接操作宿主 `java.io.File`（见 `AgentOrchestrator.setWorkspace`），
 * 因此这边只需要一个本地实现 [HostFileAccessProvider]；但接口保留，是为了让移植过来的
 * 渲染层不必知道文件到底在哪 —— 也免得日后真要接远程时再改所有调用点。
 *
 * <p>路径入参统一是「容器路径」（`~/workspace/...`），由实现内部映射到宿主路径。
 */
interface FileAccessProvider {

  /** 文件是否存在。 */
  fun exists(path: String): Boolean

  /**
   * 写入文件原始字节。父目录不存在则自动创建。
   * [overwrite] 为 false 且文件已存在时抛 [java.nio.file.FileAlreadyExistsException]。
   */
  fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean = true)

  /**
   * 从 [input] 流式写入文件，返回写入的字节数；内容不整体驻留内存。
   * 父目录不存在则自动创建。
   */
  fun writeStream(path: String, input: InputStream, overwrite: Boolean = true): Long

  /**
   * 把文件复制到本地临时文件并返回其 [File]。
   *
   * <p>本地实现直接返回映射后的宿主 [File]（不复制）；Aharou 的远程实现会 SFTP 下载到缓存目录。
   * 需要喂 `BitmapFactory` 的能力（缩略图、Markdown 内嵌图）都走这个方法拿本地路径。
   */
  fun copyToLocal(path: String): File

  /**
   * 递归列出目录下的相对路径（波次 3 的 WorkspaceSearchEngine 需要的三件套之一）。
   * 返回相对于 [path] 的路径列表，跳过 `.git` 由调用方负责（与 Aharou 语义一致）。
   */
  fun listFilesRecursive(path: String, maxDepth: Int): List<String>

  /** 文件体积（字节）；不存在或不可读返回 0。 */
  fun fileSize(path: String): Long

  /** 逐行读取文本文件；失败返回 null（调用方按"读不到"处理，与 Aharou 的回退路径一致）。 */
  fun readLines(path: String): List<String>?
}

/**
 * 容器路径的根。
 *
 * <p>只保留 [CONTAINER_ROOT]：移植范围内只有 `ChatAttachmentUtils` 用到它拼附件目录
 * （`"$CONTAINER_ROOT/.aharou/attachments"`），`WorkspacePathMapper` 的
 * 双向映射（`toHost` / `toContainer` / `wsRoot`）无人调用，故不搬。
 *
 * <p>⚠️ **接入时注意**：`.aharou/attachments` 会落在工作区根目录下（即用户的项目目录里）。
 * ACS 的惯例是把这类运行时数据放应用私有目录（`filesDir/ai/…`，见 AgentOrchestrator），
 * 所以接入层更合适的写法是给它一个落在 `filesDir/ai/attachments` 的根，
 * 而不是真的往项目里写 `.aharou`。这是**接入时的取舍**，本层只负责让路径可构造。
 */
object WorkspacePathMapper {
  const val CONTAINER_ROOT = "~/workspace"
}

/**
 * [FileAccessProvider] 的 ACS 实现：直接读写宿主文件，不做模式分支。
 *
 * @param root 与 [WorkspacePathMapper.CONTAINER_ROOT] 对应的**宿主目录**。
 *   接入层应传当前工作区根（`AgentOrchestrator.setWorkspace(File)` 的那个 File）。
 *   `~/workspace/...` 会映射到它下面；其它路径按宿主绝对路径直接使用 —— 因为 ACS 的
 *   agent 工具本来就产出宿主绝对路径（没有容器/远程模式），两种路径都必须在同一层里成立。
 */
class HostFileAccessProvider(private val root: File) : FileAccessProvider {

  override fun exists(path: String): Boolean = resolve(path).exists()

  override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
    val file = prepare(path, overwrite)
    file.writeBytes(bytes)
  }

  override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long {
    val file = prepare(path, overwrite)
    input.use { source -> file.outputStream().use { target -> source.copyTo(target) } }
    return file.length()
  }

  override fun copyToLocal(path: String): File = resolve(path)

  override fun listFilesRecursive(path: String, maxDepth: Int): List<String> {
    val root = resolve(path)
    if (!root.isDirectory) return emptyList()
    val out = mutableListOf<String>()
    fun walk(dir: File, prefix: String, depth: Int) {
      if (depth > maxDepth) return
      val children = dir.listFiles() ?: return
      for (child in children.sortedBy { it.name }) {
        val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
        if (child.isDirectory) {
          if (child.name == ".git") continue
          out += rel
          walk(child, rel, depth + 1)
        } else {
          out += rel
        }
      }
    }
    walk(root, "", 1)
    return out
  }

  override fun fileSize(path: String): Long = resolve(path).takeIf { it.isFile }?.length() ?: 0L

  override fun readLines(path: String): List<String>? =
      runCatching { resolve(path).takeIf { it.isFile }?.readLines() }.getOrNull()

  /**
   * 容器路径 → 宿主 [File]。
   *
   * <p>只处理 `~/workspace` 前缀，其余原样当宿主路径。刻意不做 `~` 展开（ACS 没有 home 概念，
   * 猜错会静默写到别处），也不做「不存在就回退」之类的兜底 —— 映射必须可预测。
   */
  fun resolve(path: String): File {
    val trimmed = path.trim()
    return when {
      trimmed == WorkspacePathMapper.CONTAINER_ROOT -> root
      trimmed.startsWith("${WorkspacePathMapper.CONTAINER_ROOT}/") ->
          File(root, trimmed.removePrefix("${WorkspacePathMapper.CONTAINER_ROOT}/"))
      else -> File(trimmed)
    }
  }

  private fun prepare(path: String, overwrite: Boolean): File {
    val file = resolve(path)
    if (!overwrite && file.exists()) {
      throw java.nio.file.FileAlreadyExistsException(file.absolutePath)
    }
    file.parentFile?.mkdirs()
    return file
  }
}

/**
 * 附件图片进请求用的压缩管线（Aharou 同名 object 的最小等价物）。
 *
 * <p>只实现被引用的成员：两个阈值常量、[Bounds] / [Encoded]、
 * [decodeBounds] / [encodeToJpeg] / [rawBase64]。
 * Aharou 那个类还有 PNG 回退、缩放与质量二分等内部细节，都包含在 [encodeToJpeg] 里了。
 */
object ImageCompressor {

  /** 进请求的图片最长边上限。 */
  const val HIGH_MAX_EDGE = 1536

  /** 压到该字节数以内（未压到则接受最低质量的结果）。 */
  const val HIGH_TARGET_BYTES = 512 * 1024

  /** 逐档降质量，直到落进目标字节数；都不行就取最后一档。 */
  private val JPEG_QUALITIES = listOf(90, 86, 78, 70, 62)

  data class Bounds(val width: Int, val height: Int)

  data class Encoded(val base64Data: String, val width: Int, val height: Int, val encodedBytes: Long)

  /** 只读尺寸，不解码像素；拿不到尺寸（非图片/损坏）返回 null。 */
  fun decodeBounds(file: File): Bounds? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    val width = options.outWidth
    val height = options.outHeight
    return if (width > 0 && height > 0) Bounds(width, height) else null
  }

  /**
   * 压到最长边 [maxEdge] 内并编码成 JPEG base64。解码失败时抛 [IllegalArgumentException]，
   * 交给调用方决定是报错还是回退原图。
   */
  fun encodeToJpeg(file: File, maxEdge: Int, targetBytes: Int): Encoded {
    val bounds = decodeBounds(file) ?: throw IllegalArgumentException("无法识别图片格式: ${file.name}")
    val options = BitmapFactory.Options().apply { inSampleSize = calculateInSampleSize(bounds.width, bounds.height, maxEdge) }
    val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: throw IllegalArgumentException("无法解码图片: ${file.name}")

    try {
      val scaled = scaleToMaxEdge(decoded, maxEdge)
      try {
        val bytes = compressJpeg(scaled, targetBytes)
        return Encoded(
            base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP),
            width = scaled.width,
            height = scaled.height,
            encodedBytes = bytes.size.toLong())
      } finally {
        if (scaled !== decoded) scaled.recycle()
      }
    } finally {
      decoded.recycle()
    }
  }

  fun rawBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

  /**
   * 采样档位。
   *
   * <p>**照抄 Aharou，含它与 `calculateInSampleSize`（本包 AharouImageDecode.kt）不一致之处**：
   * 这里用 `&&`（等价于约束短边），那边用 `||`（约束长边）。对 4000×300 这类极端长图，
   * 这里会退回全尺寸解码（ARGB_8888 约 4.8MB）再多一步缩放 —— 结果正确，只是多花一次内存。
   * 没有「顺手修好」是因为这会改变移植语义；要改应当作为一次**独立、可验证**的改动
   * （换成长边版后需实测压缩结果一致）。
   */
  private fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    var sample = 1
    var halfWidth = width / 2
    var halfHeight = height / 2
    while (halfWidth / sample >= maxEdge && halfHeight / sample >= maxEdge) {
      sample *= 2
    }
    return sample
  }

  /** 把长边缩到 [maxEdge] 内（等比）。已经够小就原样返回，不做无谓的位图复制。 */
  private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
    val longEdge = maxOf(bitmap.width, bitmap.height)
    if (longEdge <= maxEdge) return bitmap
    val ratio = maxEdge.toFloat() / longEdge
    val targetWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
    val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
  }

  /** 逐档降质量；全都不达标时返回最低质量那一档的结果（宁大不丢）。 */
  private fun compressJpeg(bitmap: Bitmap, targetBytes: Int): ByteArray {
    var last: ByteArray? = null
    for (quality in JPEG_QUALITIES) {
      val out = java.io.ByteArrayOutputStream()
      bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
      val bytes = out.toByteArray()
      last = bytes
      if (bytes.size <= targetBytes) return bytes
    }
    return last ?: ByteArray(0)
  }
}
