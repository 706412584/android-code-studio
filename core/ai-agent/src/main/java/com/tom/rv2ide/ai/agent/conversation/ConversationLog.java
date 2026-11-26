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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONException;
import org.json.JSONObject;

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

  /**
   * 按文件路径的 JVM 内互斥锁（静态、跨实例共享）。
   *
   * <p>为什么需要它：`FileChannel.lock()` 在**同一 JVM 内**对同一文件重复加锁会抛
   * `OverlappingFileLockException`（不阻塞），只在**跨进程**时才阻塞等待。因此 JVM 内
   * 必须先用这把锁把同文件的并发 append 串行化，再进入 OS 文件锁——两层配合才能同时
   * 覆盖「同 JVM 多线程」「跨 Store 实例」「跨进程」三种并发。
   */
  private static final ConcurrentHashMap<String, Object> FILE_LOCKS = new ConcurrentHashMap<>();

  private static Object lockFor(File file) {
    return FILE_LOCKS.computeIfAbsent(file.getAbsolutePath(), k -> new Object());
  }

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
      line = serializeForAppend(entry);
    } catch (JSONException e) {
      throw new IOException("条目无法序列化: " + e.getMessage(), e);
    }
    byte[] payload = (line + "\n").getBytes(StandardCharsets.UTF_8);

    File parent = file.getParentFile();
    // 用 isDirectory 判定：路径存在但**是文件**时 mkdirs 不会执行，
    // 后续 RandomAccessFile 只会抛「系统找不到指定的路径」——与真实原因无关。
    if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
      throw new IOException("无法创建目录: " + parent);
    }

    try (RandomAccessFile raf = new RandomAccessFile(file, "rw");
        FileChannel channel = raf.getChannel()) {
      // 双层互斥：先用 JVM 内按文件路径的锁把同文件的并发 append 串行化（避免同 JVM
      // 触发 OverlappingFileLockException），再用 OS 文件锁挡住跨进程写入（悬浮窗
      // Service 与主 app Activity）。length→seek→write 由此原子化，杜绝同偏移覆盖。
      synchronized (lockFor(file)) {
        try (FileLock ignored = channel.lock()) {
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
    }
  }

  /**
   * 序列化条目；工具结果**截图外置**成文件，JSONL 只写 {@code imageRef}。
   *
   * <p>为什么外置：截图 base64 内联进 JSONL 会让会话文件膨胀到几十 MB，且每次续接读盘
   * 都把全部截图读进内存——影子屏连拍时的 512MB 堆 OOM 即由此而来。外置后 JSONL 只有
   * 小引用，图片在需要时才从文件懒加载（见 {@link ToolResultEntry#getImageBase64()}）。
   *
   * <p>外置失败时退回内联（照旧写 base64），绝不因写图失败而丢会话。
   */
  private String serializeForAppend(ConversationEntry entry) throws JSONException {
    if (entry instanceof ToolResultEntry) {
      ToolResultEntry result = (ToolResultEntry) entry;
      String inline = result.getInlineImageBase64();
      if (!inline.isEmpty()) {
        File imageDir = imagesDir();
        if (imageDir.isDirectory() || imageDir.mkdirs() || imageDir.isDirectory()) {
          String refName = result.getUuid() + ".b64";
          try {
            Files.write(
                new File(imageDir, refName).toPath(), inline.getBytes(StandardCharsets.UTF_8));
            JSONObject json = result.toJson();
            json.remove(ToolResultEntry.FIELD_IMAGE_BASE64);
            json.put(ToolResultEntry.FIELD_IMAGE_REF, refName);
            return json.toString();
          } catch (IOException e) {
            // 退回内联。
          }
        }
      }
    }
    return ConversationCodec.toLine(entry);
  }

  /** 该会话外置截图的目录：与 .jsonl 同级的 `<日志文件名>.images/`。 */
  private File imagesDir() {
    return new File(file.getParentFile(), file.getName() + ".images");
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
    File imageDir = imagesDir();
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
              ConversationCodec.parse(new String(lineBytes, StandardCharsets.UTF_8), imageDir);
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

  /** 历史检索上限独立于正常回放，禁止先 readAll 再裁剪。 */
  static final int HISTORY_SCAN_BYTES = 4 * 1024 * 1024;
  static final int HISTORY_LINE_BYTES = 256 * 1024;
  private static final int HISTORY_PARSE_LINES = 512;
  private static final int HISTORY_PAGE_ENTRIES = 100;

  /** 从尾部倒扫最近一个真实用户回合；beforeUuid 排除锚点。图片不水合。 */
  public HistoryPage readPage(String beforeUuid) throws IOException {
    if (!file.isFile()) {
      throw new IOException("会话不存在");
    }
    String anchor = beforeUuid == null ? "" : beforeUuid;
    boolean foundAnchor = anchor.isEmpty();
    boolean finished = false;
    boolean hasMore = false;
    String customTitle = "";
    String aiTitle = "";
    List<ConversationEntry> selected = new ArrayList<>();
    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      ReverseLines lines = new ReverseLines(raf);
      String line;
      int parsed = 0;
      while ((line = lines.next()) != null) {
        if (++parsed > HISTORY_PARSE_LINES) {
          throw new IOException("历史过大，已达到解析上限；请读取更近一页");
        }
        ConversationEntry entry = ConversationCodec.parse(line);
        if (entry == null) {
          continue;
        }
        if (entry instanceof TitleEntry) {
          if (entry.getType() == ConversationEntry.Type.CUSTOM_TITLE && customTitle.isEmpty()) {
            customTitle = ((TitleEntry) entry).getTitle();
          } else if (entry.getType() == ConversationEntry.Type.AI_TITLE && aiTitle.isEmpty()) {
            aiTitle = ((TitleEntry) entry).getTitle();
          }
        }
        if (!foundAnchor) {
          if (anchor.equals(entry.getUuid())) {
            foundAnchor = true;
          }
          continue;
        }
        boolean visible = entry instanceof AssistantMessageEntry
            || entry instanceof ToolResultEntry || entry instanceof CompactionEntry
            || (entry instanceof UserMessageEntry && !((UserMessageEntry) entry).isMeta());
        if (!visible) {
          continue;
        }
        if (finished) {
          hasMore = true;
          break;
        }
        selected.add(entry);
        finished = entry instanceof UserMessageEntry || selected.size() == HISTORY_PAGE_ENTRIES;
      }
      if (!foundAnchor) {
        throw new IOException("before_uuid 不属于该会话或已失效");
      }
      // 初始标题通常在文件头，不为取标题加载整份会话。
      int prefixLength = (int) Math.min(64 * 1024, raf.length());
      byte[] prefix = new byte[prefixLength];
      raf.seek(0);
      raf.readFully(prefix);
      String prefixText = new String(prefix, StandardCharsets.UTF_8);
      int start = 0;
      for (int count = 0; count < 32; count++) {
        int end = prefixText.indexOf('\n', start);
        if (end < 0) {
          break;
        }
        ConversationEntry entry = ConversationCodec.parse(prefixText.substring(start, end));
        if (entry instanceof TitleEntry) {
          if (customTitle.isEmpty() && entry.getType() == ConversationEntry.Type.CUSTOM_TITLE) {
            customTitle = ((TitleEntry) entry).getTitle();
          } else if (aiTitle.isEmpty() && entry.getType() == ConversationEntry.Type.AI_TITLE) {
            aiTitle = ((TitleEntry) entry).getTitle();
          }
        }
        start = end + 1;
      }
    }
    java.util.Collections.reverse(selected);
    String title = !customTitle.isEmpty() ? customTitle
        : !aiTitle.isEmpty() ? aiTitle : ConversationSummary.UNTITLED;
    return new HistoryPage(title, selected, hasMore);
  }

  public static final class HistoryPage {
    private final String title;
    private final List<ConversationEntry> entries;
    private final boolean hasMore;

    HistoryPage(String title, List<ConversationEntry> entries, boolean hasMore) {
      this.title = title;
      this.entries = java.util.Collections.unmodifiableList(new ArrayList<>(entries));
      this.hasMore = hasMore;
    }

    public String getTitle() { return title; }
    public List<ConversationEntry> getEntries() { return entries; }
    public boolean hasMore() { return hasMore; }
  }

  /** 固定块缓存倒读，单行与总扫描字节均有硬上限，残缺尾行不解析。 */
  private static final class ReverseLines {
    private final RandomAccessFile raf;
    private final byte[] buffer = new byte[READ_BUFFER_BYTES];
    private long cursor;
    private long blockStart = -1;
    private long blockEnd = -1;
    private int scanned;

    ReverseLines(RandomAccessFile raf) throws IOException {
      this.raf = raf;
      cursor = raf.length();
      while (cursor > 0 && previousByte() != '\n') {
        // 跳过没有换行的残缺尾部。
      }
    }

    private int previousByte() throws IOException {
      if (++scanned > HISTORY_SCAN_BYTES) {
        throw new IOException("历史过大，已达到扫描上限；请读取更近一页");
      }
      cursor--;
      if (cursor < blockStart || cursor >= blockEnd) {
        blockEnd = cursor + 1;
        blockStart = Math.max(0, blockEnd - buffer.length);
        raf.seek(blockStart);
        raf.readFully(buffer, 0, (int) (blockEnd - blockStart));
      }
      return buffer[(int) (cursor - blockStart)] & 0xff;
    }

    String next() throws IOException {
      if (cursor == 0) {
        return null;
      }
      ByteArrayOutputStream reversed = new ByteArrayOutputStream();
      while (cursor > 0) {
        int value = previousByte();
        if (value == '\n') {
          break;
        }
        if (reversed.size() >= HISTORY_LINE_BYTES) {
          throw new IOException("历史单条消息过大，已达到单行上限；请读取更近一页");
        }
        reversed.write(value);
      }
      byte[] bytes = reversed.toByteArray();
      for (int left = 0, right = bytes.length - 1; left < right; left++, right--) {
        byte value = bytes[left];
        bytes[left] = bytes[right];
        bytes[right] = value;
      }
      return new String(bytes, StandardCharsets.UTF_8);
    }
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
