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
   * <p><b>含资源条目的补丁会被拒绝</b>（见 [containsResources]）——这类补丁会整体替换
   * `resources.arsc`，而资源 ID 跨版本会漂移，未被差分 dex 覆盖的代码会引用到错误的资源。
   *
   * <p>其余条目（[MANIFEST]、so）原样保留；[MANIFEST] 排在输出最前，便于人工排查。
   * dex 内容逐字节原样复制（dex 不引用自身文件名，改名安全）。
   *
   * @return 重命名后的 dex 文件名列表（按新名）
   * @throws IllegalArgumentException 补丁含资源条目，或无 dex 条目
   */
  fun normalizeDexNames(src: File, dest: File): List<String> {
    ZipFile(src).use { zin ->
      val all = zin.entries().toList()
      val resourceEntries =
          all.filter { it.name == "resources.arsc" || it.name.startsWith("res/") }
      require(resourceEntries.isEmpty()) {
        "补丁含资源变更（${resourceEntries.size} 个条目），拒绝应用：" +
            "补丁会整体替换 resources.arsc，而资源 ID 跨版本会漂移，" +
            "未被差分 dex 覆盖的代码会引用到错误的资源。请改用整包更新。"
      }
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
   * 补丁是否含资源条目（`resources.arsc` 或 `res/` 下的文件）。
   *
   * <p><b>含资源的补丁一律不能用</b>：补丁里的 `resources.arsc` 是**整体替换**，而资源 ID
   * 跨版本会漂移（r11→r12 实测：10308 个共有资源里 981 个 ID 变了）。补丁只带差分 dex，
   * 未被覆盖的那些 dex 仍引用旧 ID，在新表里指向别的资源——界面出现大量无关文字
   * （实测：主页「打开项目」显示成「找不到未解析的类名」）。
   *
   * <p>资源 ID 漂移对**整包更新**无害（代码与资源一起换），只对补丁致命。
   * CI 侧已拒绝生成这类补丁（见 `tools/hotupdate-normalize-patch.py`），
   * 这里再查一次是双保险：清单可能残留旧补丁，或用户设备上有历史下载的补丁文件。
   */
  fun containsResources(patch: File): Boolean {
    return try {
      ZipFile(patch).use { zip ->
        zip.entries().asSequence().any { e ->
          e.name == "resources.arsc" || e.name.startsWith("res/")
        }
      }
    } catch (e: Exception) {
      // 读不出来交给后续流程报错；这里不把「打不开」当成「含资源」。
      false
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
