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

package com.tom.rv2ide.hotupdate

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * 补丁包（zip）的纯逻辑操作，不依赖 Android——可在 JVM 上单测。
 *
 * <p>与 [HotUpdateManager] 分开的理由：这里是最容易出错、也最需要回归保护的部分
 * （dex 命名规范化、base 版本读取），把它做成纯函数才能用普通单元测试锁住；
 * 而 `HotUpdateManager` 依赖 `Context` / SDK 单例，只能在设备上验。
 */
object PatchPackage {

  /** 补丁 zip 内的元数据文件名。 */
  const val MANIFEST = "patch.json"

  /**
   * 把补丁 zip 内的 `*.dex` 重命名为连续的 `classes.dex`、`classes2.dex`、…。
   *
   * <p><b>为什么必须做</b>：Android 的 `DexPathList` 只按 `classes.dex` → `classes2.dex`
   * → … 的**连续**编号查找 dex；一旦跳号，从缺口开始的所有 dex 都不可见。而 patch-cli
   * 生成补丁时沿用 base APK 里的 dex 名（ACS 有 12+ 个 dex，会产出 `classes8.dex` /
   * `classes12.dex`），补丁里没有 `classes.dex`，于是 ART 找不到补丁中的任何类——
   * 表现为「补丁应用成功、DEX 注入 ✓、日志无异常，但代码毫无变化」的**静默失败**。
   *
   * <p>其余条目（[MANIFEST]、资源、so）原样保留；[MANIFEST] 排在输出最前，便于人工排查。
   * dex 内容逐字节原样复制（dex 不引用自身文件名，改名安全）。
   *
   * @return 重命名后的 dex 文件名列表（按新名）
   */
  fun normalizeDexNames(src: File, dest: File): List<String> {
    ZipFile(src).use { zin ->
      val all = zin.entries().toList()
      val dexEntries = all.filter { it.name.endsWith(".dex") }.sortedBy { it.name }
      require(dexEntries.isNotEmpty()) { "补丁包内没有 .dex 条目：${src.name}" }

      val others =
          all.filter { !it.name.endsWith(".dex") }.sortedBy { if (it.name == MANIFEST) 0 else 1 }

      val outNames = mutableListOf<String>()
      ZipOutputStream(FileOutputStream(dest)).use { zout ->
        for (entry in others) {
          zout.putNextEntry(ZipEntry(entry.name))
          zin.getInputStream(entry).use { it.copyTo(zout, 8192) }
          zout.closeEntry()
        }
        dexEntries.forEachIndexed { i, entry ->
          val name = if (i == 0) "classes.dex" else "classes${i + 1}.dex"
          outNames += name
          zout.putNextEntry(ZipEntry(name))
          zin.getInputStream(entry).use { it.copyTo(zout, 8192) }
          zout.closeEntry()
        }
      }
      return outNames
    }
  }

  /**
   * 读取补丁 zip 内 [MANIFEST] 的 `baseVersionCode`。
   *
   * <p>这是「补丁能否应用」的判据：补丁是「上一版本 → 本版本」的增量，只能装在生成它时
   * 所用的版本上。SDK 的 `validatePatchFormat` 只校验包名、**不校验 base 版本**，
   * 装了不匹配的补丁会静默注入错误 dex，所以必须自己查。
   *
   * @return base versionCode；缺失或格式异常返回 -1
   */
  fun readBaseVersionCode(patch: File): Int {
    return try {
      ZipFile(patch).use { zip ->
        val entry = zip.getEntry(MANIFEST) ?: return -1
        val json = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        JSONObject(json).optInt("baseVersionCode", -1)
      }
    } catch (e: Exception) {
      -1
    }
  }

  /** 列出 zip 内的条目名（按 zip 内顺序）。测试与诊断用。 */
  fun entryNames(zip: File): List<String> = ZipFile(zip).use { it.entries().toList().map { e -> e.name } }
}
