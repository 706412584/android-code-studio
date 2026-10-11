/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.projects

import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent
import com.tom.rv2ide.eventbus.events.file.FileDeletionEvent
import com.tom.rv2ide.eventbus.events.file.FileRenameEvent
import com.tom.rv2ide.progress.ProgressManager
import com.tom.rv2ide.projects.models.ActiveDocument
import java.io.BufferedReader
import java.io.InputStream
import java.net.URI
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory

/**
 * Manages active documents.
 *
 * @author Akash Yadav
 */
object FileManager {

  private val log = LoggerFactory.getLogger(FileManager::class.java)
  private val activeDocuments = ConcurrentHashMap<Path, ActiveDocument>()

  fun isActive(uri: URI): Boolean {
    return isActive(Paths.get(uri))
  }

  fun isActive(file: Path): Boolean {
    return this.activeDocuments.containsKey(file.normalize())
  }

  fun getActiveDocument(file: Path): ActiveDocument? {
    return this.activeDocuments[file.normalize()]
  }

  fun getActiveDocumentCount(): Int {
    return this.activeDocuments.size
  }

  fun getDocumentContents(file: Path): String {
    val document = getActiveDocument(file)
    if (document != null && !isDiskNewer(file, document)) {
      return document.content
    }

    return getFileContents(file)
  }

  fun getLastModified(file: Path): Instant {
    val document = getActiveDocument(file)
    if (document != null) {
      // 打开的文件默认以内存为准，但**磁盘更新时必须以磁盘为准**。
      //
      // 背景：AI 工具（以及 git checkout / archive 解压等任何外部写入）只写磁盘，
      // 不会更新编辑器缓冲。旧实现无条件返回 `document.modified`（内存时间），于是
      // 语言服务器把「上一次编辑器改动时间」当作文件版本——磁盘上的新改动既不会
      // 让它重新分析（缓存命中），分析时读到的又是编辑器里的**旧内容**
      // （见 SourceFileObject → getDocumentContents）。
      //
      // 实测表现：AI 改完一个**正在编辑器里打开**的文件，LSP 仍按旧内容报错
      // （如「缺少返回语句」，而方法里明明已有 return），且每次编辑都复现。
      return if (isDiskModifiedAfter(file, document.modified)) {
        getLastModifiedFromDisk(file)
      } else {
        document.modified
      }
    }

    return getLastModifiedFromDisk(file)
  }

  fun getReader(file: Path): BufferedReader {
    val document = getActiveDocument(file)
    if (document != null && !isDiskNewer(file, document)) {
      return document.reader()
    }

    return createFileReader(file)
  }

  fun getInputStream(file: Path): InputStream {
    val document = getActiveDocument(file)
    if (document != null && !isDiskNewer(file, document)) {
      return document.inputStream()
    }

    return createFileInputStream(file)
  }

  /**
   * 磁盘上的文件是否比编辑器缓冲更新（即存在未同步到编辑器的外部写入）。
   *
   * <p>文件在磁盘上不存在（如新建未保存的文件）时返回 false——此时以内存为准。
   * 任何异常都按「以内存为准」处理，避免因文件系统抖动把编辑器内容判丢。
   */
  private fun isDiskNewer(file: Path, document: ActiveDocument): Boolean {
    return isDiskModifiedAfter(file, document.modified)
  }

  private fun isDiskModifiedAfter(file: Path, since: Instant): Boolean {
    return try {
      Files.getLastModifiedTime(file).toInstant().isAfter(since)
    } catch (e: Exception) {
      false
    }
  }

  fun onDocumentOpen(event: DocumentOpenEvent) {
    activeDocuments[event.openedFile.normalize()] = createDocument(event)
  }

  fun onDocumentContentChange(event: DocumentChangeEvent) {
    val document = activeDocuments[event.changedFile.normalize()]

    if (document == null) {
      // create document if not already created
      // this should not happen under normal circumstances
      activeDocuments[event.changedFile.normalize()] = createDocument(event)
      log.warn("Document change event received before open event for file {}", event.changedFile)
      return
    }

    document.version = event.version
    document.modified = Instant.now()
    document.content = event.newText ?: document.content
  }

  fun onDocumentClose(event: DocumentCloseEvent) {
    activeDocuments.remove(event.closedFile.normalize())
  }

  fun onFileRenamed(event: FileRenameEvent) {
    val document = activeDocuments.remove(event.file.toPath().normalize())
    if (document != null) {
      activeDocuments[event.newFile.toPath().normalize()] = document
    }
  }

  fun onFileDeleted(event: FileDeletionEvent) {
    // If the file was an active document, remove the document cache
    activeDocuments.remove(event.file.toPath().normalize())
  }

  private fun createDocument(event: DocumentOpenEvent): ActiveDocument {
    return ActiveDocument(
        file = event.openedFile,
        version = event.version,
        modified = Instant.now(),
        content = event.text,
    )
  }

  private fun createDocument(event: DocumentChangeEvent): ActiveDocument {
    val initialContent = event.newText ?: getFileContents(event.changedFile)
    return ActiveDocument(
        file = event.changedFile,
        version = event.version,
        modified = Instant.now(),
        content = initialContent,
    )
  }

  private fun createFileReader(file: Path): BufferedReader {
    return try {
      Files.newBufferedReader(file)
    } catch (noFile: java.nio.file.NoSuchFileException) {
      log.warn("No such file", noFile)
      "".reader().buffered()
    } catch (cancelled: CancellationException) {
      "".reader().buffered()
    }
  }

  private fun createFileInputStream(file: Path): InputStream {
    return try {
      Files.newInputStream(file)
    } catch (noFile: java.nio.file.NoSuchFileException) {
      log.warn("No such file", noFile)
      "".byteInputStream()
    } catch (cancelled: CancellationException) {
      "".byteInputStream()
    }
  }

  private fun getLastModifiedFromDisk(file: Path): Instant {
    return Files.getLastModifiedTime(file).toInstant()
  }

  private fun getFileContents(file: Path): String {
    return try {
      ProgressManager.abortIfCancelled()
      FileUtils.readFileToString(file.toFile(), Charset.defaultCharset())
    } catch (noFile: java.nio.file.NoSuchFileException) {
      log.warn("No such file", noFile)
      ""
    } catch (cancelled: CancellationException) {
      ""
    }
  }
}
