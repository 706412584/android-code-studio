/*
 * This file is part of AndroidCodeStudio.
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

package com.tom.rv2ide.ai.agent.conversation;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;

/**
 * 一个会话的 append-only JSONL 日志。
 *
 * <p><b>核心不变量</b>：文件只追加，永不改写。所有"变更"（重命名、压缩）都是新条目。
 * 因此崩溃最多损坏最后一行，不会破坏已写入的历史。
 *
 * <p>本类实现了四个针对真实问题的机制：
 * <ul>
 *   <li><b>残缺尾部</b>：只解析到最后一个换行符。写入中途崩溃留下的半行不会被当成条目，
 *       也不会阻塞后续追加（下次追加前先补换行）。
 *   <li><b>字节偏移</b>：每条条目记录起始字节，可按偏移 seek，不必重解析全文。
 *   <li><b>增量水位线</b>：{@code indexedBytes} 记录已解析到的位置，恢复时只读新增尾部。
 *   <li><b>指纹判变</b>：对已索引区间末尾一个窗口做 sha256。若窗口内容变了，说明文件被
 *       重写或截断（而非追加），此时水位线失效，必须整体重建。
 * </ul>
 */
public final class ConversationLog {

  /**
   * 指纹窗口大小。取 64KB，与参考实现一致——窗口越大，越不容易被"同长度改写"骗过。
   */
  static final int FINGERPRINT_WINDOW_BYTES = 64 * 1024;

  /** 读取缓冲区大小。 */
  private static final int READ_BUFFER_BYTES = 64 * 1024;

  private final File file;

  public ConversationLog(File file) {
    this.file = file;
  }

  public File getFile() {
    return file;
  }

  /**
   * 追加一条条目。必要时先补上残缺尾部的换行，保证新条目自成一行。
   *
   * @return 本条条目的起始字节偏移
   */
  public long append(ConversationEntry entry) throws IOException {
    String line;
    try {
      line = ConversationCodec.toLine(entry);
    } catch (JSONException e) {
      throw new IOException("条目无法序列化: " + e.getMessage(), e);
    }
    byte[] payload = (line + "\n").getBytes(StandardCharsets.UTF_8);

    File parent = file.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
      throw new IOException("无法创建目录: " + parent);
    }

    try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
      long length = raf.length();
      // 残缺尾部（崩溃留下的半行）没有换行结尾。直接追加会让两条条目粘成一行，
      // 导致两者都不可解析。补一个换行把它隔开——半行本身仍会被解析器跳过。
      if (length > 0 && !endsWithNewline(raf)) {
        raf.seek(length);
        raf.write('\n');
        length += 1;
      }
      raf.seek(length);
      raf.write(payload);
      return length;
    }
  }

  private static boolean endsWithNewline(RandomAccessFile raf) throws IOException {
    long length = raf.length();
    raf.seek(length - 1);
    return raf.read() == '\n';
  }

  /**
   * 从指定偏移读取新增条目。
   *
   * <p>只消费完整行；末尾未换行的残段作为 pending tail 报告，留给下次读取。
   */
  public ReadResult readFrom(long startOffset) throws IOException {
    if (!file.exists()) {
      return new ReadResult(new ArrayList<>(), startOffset, 0);
    }
    long size = file.length();
    if (startOffset < 0 || startOffset > size) {
      // 偏移超出文件长度说明文件被截断或重写，调用方应改用 readAll。
      return new ReadResult(new ArrayList<>(), 0, 0);
    }

    List<EntryLocation> entries = new ArrayList<>();
    long pendingTailBytes = 0;
    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      raf.seek(startOffset);
      long lineStart = startOffset;
      int ordinal = 0;
      byte[] buffer = new byte[READ_BUFFER_BYTES];
      // 跨读块拼接的半行；只有见到换行才认为一行完整。
      ByteArrayOutputStream pending = new ByteArrayOutputStream();

      while (raf.getFilePointer() < size) {
        int requested = (int) Math.min(buffer.length, size - raf.getFilePointer());
        int read = raf.read(buffer, 0, requested);
        if (read <= 0) {
          break;
        }
        int segmentStart = 0;
        for (int i = 0; i < read; i++) {
          if (buffer[i] != '\n') {
            continue;
          }
          pending.write(buffer, segmentStart, i - segmentStart);
          byte[] lineBytes = pending.toByteArray();
          pending.reset();
          int byteLength = lineBytes.length + 1; // 含换行符，偏移才与文件一致
          ConversationEntry entry =
              ConversationCodec.parse(new String(lineBytes, StandardCharsets.UTF_8));
          if (entry != null) {
            entries.add(new EntryLocation(entry, ordinal, lineStart, byteLength));
            ordinal++;
          }
          lineStart += byteLength;
          segmentStart = i + 1;
        }
        pending.write(buffer, segmentStart, read - segmentStart);
      }

      // 剩余内容没有换行结尾 → 残缺尾部。不解析、不计入水位线。
      pendingTailBytes = pending.size();
      return new ReadResult(entries, lineStart, pendingTailBytes);
    }
  }

  /** 读取全部完整条目（忽略残缺尾部）。 */
  public List<EntryLocation> readAll() throws IOException {
    return readFrom(0).getEntries();
  }

  /**
   * 计算「已索引区间末尾窗口」的指纹，用于判断文件是否被追加（水位线仍有效）
   * 还是被重写/截断（水位线失效）。
   *
   * @param indexedBytes 已索引到的字节位置
   * @return 窗口的 sha256 十六进制；{@code indexedBytes <= 0} 时返回空串
   */
  public String fingerprint(long indexedBytes) throws IOException {
    if (indexedBytes <= 0 || !file.exists()) {
      return "";
    }
    long end = Math.min(indexedBytes, file.length());
    int windowLength = (int) Math.min(FINGERPRINT_WINDOW_BYTES, end);
    long windowStart = end - windowLength;
    byte[] window = new byte[windowLength];
    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      raf.seek(windowStart);
      raf.readFully(window);
    }
    return sha256(window);
  }

  private static String sha256(byte[] bytes) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(bytes);
      StringBuilder builder = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        builder.append(Character.forDigit((b >> 4) & 0xF, 16));
        builder.append(Character.forDigit(b & 0xF, 16));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 是 JDK 必备算法，缺失属环境异常。
      throw new IOException("SHA-256 不可用", e);
    }
  }

  public boolean exists() {
    return file.exists();
  }

  public long length() throws IOException {
    return file.exists() ? file.length() : 0;
  }

  /** 删除日志文件（删除会话用）。 */
  public boolean delete() {
    return !file.exists() || file.delete();
  }

  /** 一条条目及其在文件中的位置。 */
  public static final class EntryLocation {
    private final ConversationEntry entry;
    private final int ordinal;
    private final long byteStart;
    private final int byteLength;

    EntryLocation(ConversationEntry entry, int ordinal, long byteStart, int byteLength) {
      this.entry = entry;
      this.ordinal = ordinal;
      this.byteStart = byteStart;
      this.byteLength = byteLength;
    }

    public ConversationEntry getEntry() {
      return entry;
    }

    public int getOrdinal() {
      return ordinal;
    }

    public long getByteStart() {
      return byteStart;
    }

    public int getByteLength() {
      return byteLength;
    }
  }

  /** 一次增量读取的结果。 */
  public static final class ReadResult {
    private final List<EntryLocation> entries;
    private final long nextOffset;
    private final long pendingTailBytes;

    ReadResult(List<EntryLocation> entries, long nextOffset, long pendingTailBytes) {
      this.entries = entries;
      this.nextOffset = nextOffset;
      this.pendingTailBytes = pendingTailBytes;
    }

    public List<EntryLocation> getEntries() {
      return entries;
    }

    /** 下次读取的起始偏移（仅覆盖已消费的完整行）。 */
    public long getNextOffset() {
      return nextOffset;
    }

    /** 末尾未换行的残段字节数；大于 0 表示有半行待补齐。 */
    public long getPendingTailBytes() {
      return pendingTailBytes;
    }
  }
}
