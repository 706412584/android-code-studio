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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一条工作区搜索命中：文件名命中（[line] 为 0）或内容命中（[line] 为 1 起行号，
 * [column]/[length] 给出该行内的命中区间）。
 */
data class WorkspaceSearchHit(
    /** 容器路径，如 `~/workspace/app/build.gradle.kts`。 */
    val path: String,
    /** 文件名（路径最后一段）。 */
    val name: String,
    /** 相对工作区根的目录（无目录时为空串），供结果行做次要信息展示。 */
    val directory: String,
    /** 1 起行号；0 表示这条是文件名命中、没有具体行。 */
    val line: Int,
    /** 命中的整行文本（已去行尾换行）；文件名命中为空串。 */
    val text: String,
    /** 1 起列号；0 表示无（文件名命中）。 */
    val column: Int,
    /** 命中长度（字符数）；0 表示无。 */
    val length: Int
) {
    val isFileName: Boolean get() = line == 0
}

/** 一次搜索的结果：[hits] 为命中列表（文件名命中在前、内容命中在后），[truncated] 表示因上限截断。 */
data class WorkspaceSearchResult(
    val hits: List<WorkspaceSearchHit>,
    val truncated: Boolean,
    /** 实际后端：rg（容器内 ripgrep）/ walk（App 内遍历）/ none（空关键词）。 */
    val engine: String
)

/**
 * 工作区搜索：文件名 + 文件内容，一次调用同时出两类命中。
 *
 * <p>**容器 rg 主路径未移植**：ACS 无 CommandEngine（Aharou 主路径走容器内 ripgrep，
 * 内容用 `rg --json`、文件名用 `rg --files` 后客户端过滤）；若将来接容器按 Aharou 原文补。
 * 这里只有 Aharou 的回退路径——经 [FileAccessProvider] 直接遍历（本地 java.io /
 * 远程 SFTP 都能走），代价是慢，故设更紧的上限。
 */
class WorkspaceSearchEngine(private val fileAccess: FileAccessProvider) {

    private companion object {
        const val TAG = "WorkspaceSearch"

        /** 命中总数上限（文件名 + 内容合计），超出即截断并置 [WorkspaceSearchResult.truncated]。 */
        const val MAX_HITS = 200

        /** 单个文件最多取几条内容命中，避免一个大文件刷屏。 */
        const val MAX_HITS_PER_FILE = 20

        /** 回退遍历（容器未就绪/未装 rg）的预算：深度、扫描文件数、单文件大小。 */
        const val FALLBACK_MAX_DEPTH = 8
        const val FALLBACK_SCAN_FILES = 300
        const val FALLBACK_MAX_FILE_BYTES = 256L * 1024

        const val ENGINE_WALK = "walk"
        const val ENGINE_NONE = "none"

        /** 回退遍历时直接跳过的二进制/大体积扩展名（rg 主路径由 ripgrep 自行判定二进制）。 */
        val BINARY_EXTS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svgz",
            "mp3", "wav", "ogg", "m4a", "flac", "aac",
            "mp4", "mkv", "mov", "avi", "webm",
            "zip", "gz", "bz2", "xz", "7z", "rar", "jar", "apk", "aab", "dex",
            "so", "dylib", "bin", "exe", "class", "o", "a",
            "ttf", "otf", "woff", "woff2", "pdf",
            "db", "sqlite", "jks", "keystore", "p12"
        )
    }

    /** 在工作区里搜 [rawQuery]：空关键词直接空结果，否则走 App 内遍历。 */
    suspend fun search(rawQuery: String): WorkspaceSearchResult {
        val query = rawQuery.trim()
        if (query.isEmpty()) return WorkspaceSearchResult(emptyList(), false, ENGINE_NONE)
        return searchByWalking(query)
    }

    // ── 回退路径：App 内遍历 ──

    private suspend fun searchByWalking(query: String): WorkspaceSearchResult = withContext(Dispatchers.IO) {
        val files = runCatching {
            fileAccess.listFilesRecursive(WorkspacePathMapper.CONTAINER_ROOT, FALLBACK_MAX_DEPTH)
                .filterNot { rel -> rel.split('/').any { it == ".git" } }
        }.getOrElse { error ->
            FileLogger.w(TAG, "遍历工作区失败", error)
            emptyList()
        }

        val hits = mutableListOf<WorkspaceSearchHit>()
        hits += matchFileNames(files, query, MAX_HITS)

        var scanned = 0
        var truncated = files.size > FALLBACK_SCAN_FILES
        for (relative in files) {
            if (hits.size >= MAX_HITS || scanned >= FALLBACK_SCAN_FILES) {
                truncated = true
                break
            }
            val name = relative.substringAfterLast('/')
            if (name.substringAfterLast('.', "").lowercase() in BINARY_EXTS) continue
            val path = "${WorkspacePathMapper.CONTAINER_ROOT}/$relative"
            val size = runCatching { fileAccess.fileSize(path) }.getOrDefault(0L)
            if (size <= 0L || size > FALLBACK_MAX_FILE_BYTES) continue
            scanned++
            val lines = runCatching { fileAccess.readLines(path) }.getOrNull() ?: continue
            var inFile = 0
            var lineNumber = 0
            for (text in lines) {
                lineNumber++
                if (inFile >= MAX_HITS_PER_FILE || hits.size >= MAX_HITS) break
                val column = text.indexOf(query, ignoreCase = true)
                if (column < 0) continue
                inFile++
                hits += hit(relative, lineNumber, text.trimEnd(), column + 1, query.length)
            }
        }

        WorkspaceSearchResult(hits.take(MAX_HITS), truncated, ENGINE_WALK)
    }

    // ── 公共小件 ──

    /** 文件名/相对路径的客户端匹配：名字先于路径命中，便于「找文件」时把真正的同名文件排在前面。 */
    private fun matchFileNames(relativePaths: List<String>, query: String, limit: Int): List<WorkspaceSearchHit> {
        val lowered = query.lowercase()
        val byName = mutableListOf<WorkspaceSearchHit>()
        val byPath = mutableListOf<WorkspaceSearchHit>()
        for (relative in relativePaths) {
            val clean = relative.removePrefix("./")
            if (clean.isEmpty()) continue
            val name = clean.substringAfterLast('/')
            when {
                name.lowercase().contains(lowered) -> byName += hit(clean)
                clean.lowercase().contains(lowered) -> byPath += hit(clean)
                else -> continue
            }
            if (byName.size >= limit) break
        }
        return (byName + byPath).take(limit)
    }

    private fun hit(
        relative: String,
        line: Int = 0,
        text: String = "",
        column: Int = 0,
        length: Int = 0
    ) = WorkspaceSearchHit(
        path = "${WorkspacePathMapper.CONTAINER_ROOT}/$relative",
        name = relative.substringAfterLast('/'),
        directory = relative.substringBeforeLast('/', ""),
        line = line,
        text = text,
        column = column,
        length = length
    )
}
