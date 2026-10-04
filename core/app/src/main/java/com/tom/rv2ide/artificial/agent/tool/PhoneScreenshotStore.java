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

package com.tom.rv2ide.artificial.agent.tool;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 动作级截图回归的暂存与比对。
 *
 * <p><b>核心约束</b>：一次动作后连拍的 N 张原图<b>绝不逐张塞进上下文</b>。
 * 它们只在本类的会话暂存里落地为 PNG，随后被合成成<b>一张</b>对比图
 * （并排 + 差异红框）连同数字指标返回给模型。
 *
 * <p><b>为什么需要暂存而不是一次返回</b>：连拍的意义在于「动作发生后画面如何演变」，
 * 单张截图给不出这个信息；但把 t0/t+300/t+800 三张原图都返回，会一次性吃掉大量
 * 上下文且模型很难自己对齐比较。折中方案：工具内部连拍并暂存，只回一张合成图 +
 * 指标，必要时再调对比工具深入。
 *
 * <p><b>基线存哪</b>：项目内 {@code .acs/baseline/}（相对工作区根目录）。
 * 放项目内而非应用私有目录，是为了让基线随项目走、可被 git 管理、可人工替换。
 *
 * <p>本类不注册为工具，仅作为 {@link PhoneScreenshotCompareTool} /
 * {@link PhoneActionCaptureTool} 的共享存储。
 */
final class PhoneScreenshotStore {

  private static final Logger log = LoggerFactory.getLogger(PhoneScreenshotStore.class);

  /** 会话暂存目录名（应用缓存下）。 */
  private static final String SESSION_DIR = "acs-captures";

  /** 项目内基线目录（相对工作区根）。 */
  static final String BASELINE_DIR = ".acs/baseline";

  /** 对比图单栏宽度（像素）。控制合成图整体尺寸。 */
  private static final int PANEL_WIDTH = 320;

  /** 栏间距。 */
  private static final int PANEL_GAP = 8;

  /** 顶部标签栏高度。 */
  private static final int HEADER_HEIGHT = 22;

  /** 返回给模型的对比图最长边上限，避免 base64 负载过大。 */
  private static final int MAX_OUTPUT_DIMENSION = 1080;

  private static final Map<String, List<Frame>> SESSIONS = new ConcurrentHashMap<>();
  private static final AtomicInteger SEQ = new AtomicInteger();
  private static volatile String latestSessionId = "";

  private PhoneScreenshotStore() {}

  /** 一帧暂存的截图。 */
  static final class Frame {
    final long offsetMs;
    final String label;
    final File file;

    Frame(long offsetMs, String label, File file) {
      this.offsetMs = offsetMs;
      this.label = label == null ? "" : label;
      this.file = file;
    }
  }

  // ---------------------------------------------------------------------------
  // 会话暂存
  // ---------------------------------------------------------------------------

  /** 开启一个新的连拍会话，返回会话 id。 */
  static String newSession() {
    String id = "cap" + SEQ.incrementAndGet() + "-" + System.currentTimeMillis();
    SESSIONS.put(id, new ArrayList<>());
    latestSessionId = id;
    return id;
  }

  /** 最近一次连拍会话 id；没有则为空串。 */
  static String latestSession() {
    return latestSessionId;
  }

  /**
   * 把一帧位图写入会话暂存（PNG），并登记其时间偏移。
   *
   * <p>位图不长期驻留内存——写完即回收，避免连拍多张导致 OOM。
   *
   * @return 落地文件；失败返回 null
   */
  static File addFrame(Context context, String sessionId, long offsetMs, String label, Bitmap bitmap) {
    if (context == null || bitmap == null) {
      return null;
    }
    File dir = sessionDir(context);
    if (!dir.exists() && !dir.mkdirs()) {
      log.warn("无法创建连拍暂存目录: {}", dir);
      return null;
    }
    File file = new File(dir, sessionId + "-" + offsetMs + "ms.png");
    OutputStream out = null;
    try {
      out = new FileOutputStream(file);
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
        return null;
      }
    } catch (Exception e) {
      log.warn("暂存截图失败: {}", file, e);
      return null;
    } finally {
      if (out != null) {
        try {
          out.close();
        } catch (IOException ignored) {
          // 已关闭
        }
      }
      bitmap.recycle();
    }
    SESSIONS.computeIfAbsent(sessionId, k -> new ArrayList<>())
        .add(new Frame(offsetMs, label, file));
    return file;
  }

  /** 取会话的全部帧（按加入顺序）。 */
  static List<Frame> frames(String sessionId) {
    List<Frame> list = SESSIONS.get(sessionId);
    return list == null ? new ArrayList<>() : new ArrayList<>(list);
  }

  /** 清空会话暂存并删除其 PNG。 */
  static void clear(Context context, String sessionId) {
    List<Frame> removed = SESSIONS.remove(sessionId);
    if (removed != null) {
      for (Frame frame : removed) {
        if (frame.file != null && frame.file.exists() && !frame.file.delete()) {
          log.debug("删除暂存帧失败: {}", frame.file);
        }
      }
    }
    if (sessionId != null && sessionId.equals(latestSessionId)) {
      latestSessionId = "";
    }
    if (context != null) {
      File dir = sessionDir(context);
      File[] leftovers = dir.listFiles();
      if (leftovers != null) {
        for (File f : leftovers) {
          if (f.getName().startsWith(sessionId + "-")) {
            f.delete();
          }
        }
      }
    }
  }

  private static File sessionDir(Context context) {
    return new File(context.getCacheDir(), SESSION_DIR);
  }

  // ---------------------------------------------------------------------------
  // 基线
  // ---------------------------------------------------------------------------

  /** 基线目录：{@code <projectRoot>/.acs/baseline}。 */
  static File baselineDir(String projectRoot) {
    return new File(projectRoot, BASELINE_DIR);
  }

  /** 指定名字的基线文件。名字会被规整为安全文件名。 */
  static File baselineFile(String projectRoot, String name) {
    String safe = safeName(name);
    return new File(baselineDir(projectRoot), safe + ".png");
  }

  static String safeName(String name) {
    String value = name == null ? "" : name.trim();
    if (value.isEmpty()) {
      value = "default";
    }
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  /** 保存基线（从源 PNG 复制）。 */
  static boolean saveBaseline(File source, File baseline) {
    if (source == null || !source.exists() || baseline == null) {
      return false;
    }
    File dir = baseline.getParentFile();
    if (dir != null && !dir.exists() && !dir.mkdirs()) {
      return false;
    }
    try {
      Files.copy(source.toPath(), baseline.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      return true;
    } catch (Exception e) {
      log.warn("保存基线失败: {}", baseline, e);
      return false;
    }
  }

  /** 列出已有基线名（不含扩展名）。 */
  static List<String> listBaselines(String projectRoot) {
    List<String> names = new ArrayList<>();
    File dir = baselineDir(projectRoot);
    File[] files = dir.listFiles();
    if (files == null) {
      return names;
    }
    for (File file : files) {
      String name = file.getName();
      if (name.endsWith(".png")) {
        names.add(name.substring(0, name.length() - 4));
      }
    }
    return names;
  }

  // ---------------------------------------------------------------------------
  // 差异比对
  // ---------------------------------------------------------------------------

  /** 两张图的差异统计。 */
  static final class DiffMetrics {
    int width;
    int height;
    long comparedPixels;
    long changedPixels;
    /** 变化像素占比（百分比，保留 2 位）。 */
    double diffPercent;
    /** 平均每像素最大通道差。 */
    double meanDelta;
    int maxDelta;
    /** 变化区域的包围盒（像素坐标）；无变化时为 -1。 */
    int minX = -1;
    int minY = -1;
    int maxX = -1;
    int maxY = -1;

    boolean hasChanges() {
      return changedPixels > 0;
    }

    String boundingBox() {
      if (minX < 0) {
        return "none";
      }
      return "(" + minX + "," + minY + ")-(" + maxX + "," + maxY + ")";
    }
  }

  /**
   * 逐像素比对两张位图。
   *
   * <p>容差 {@code tolerance} 为每通道允许的最大差值（0-255）：差值超过它才算「变化像素」。
   * 这样能吸收 PNG 压缩/抗锯齿造成的极小抖动，避免把噪声当成回归。
   *
   * <p>两张图尺寸不同时，以较小尺寸为准（左上对齐）——尺寸变化本身应由调用方另行判断。
   */
  static DiffMetrics compare(Bitmap a, Bitmap b, int tolerance) {
    DiffMetrics metrics = new DiffMetrics();
    if (a == null || b == null) {
      return metrics;
    }
    int width = Math.min(a.getWidth(), b.getWidth());
    int height = Math.min(a.getHeight(), b.getHeight());
    metrics.width = width;
    metrics.height = height;
    int tol = Math.max(0, Math.min(255, tolerance));

    long sum = 0;
    long changed = 0;
    long total = (long) width * height;
    int maxDelta = 0;

    int[] rowA = new int[width];
    int[] rowB = new int[width];
    for (int y = 0; y < height; y++) {
      a.getPixels(rowA, 0, width, 0, y, width, 1);
      b.getPixels(rowB, 0, width, 0, y, width, 1);
      for (int x = 0; x < width; x++) {
        int pa = rowA[x];
        int pb = rowB[x];
        int dr = Math.abs(((pa >> 16) & 0xFF) - ((pb >> 16) & 0xFF));
        int dg = Math.abs(((pa >> 8) & 0xFF) - ((pb >> 8) & 0xFF));
        int db = Math.abs((pa & 0xFF) - (pb & 0xFF));
        int delta = Math.max(dr, Math.max(dg, db));
        sum += delta;
        if (delta > maxDelta) {
          maxDelta = delta;
        }
        if (delta > tol) {
          changed++;
          if (metrics.minX < 0) {
            metrics.minX = x;
            metrics.minY = y;
            metrics.maxX = x;
            metrics.maxY = y;
          } else {
            metrics.minX = Math.min(metrics.minX, x);
            metrics.minY = Math.min(metrics.minY, y);
            metrics.maxX = Math.max(metrics.maxX, x);
            metrics.maxY = Math.max(metrics.maxY, y);
          }
        }
      }
    }

    metrics.comparedPixels = total;
    metrics.changedPixels = changed;
    metrics.diffPercent = total == 0 ? 0 : Math.round(changed * 10000.0 / total) / 100.0;
    metrics.meanDelta = total == 0 ? 0 : Math.round(sum * 100.0 / total) / 100.0;
    metrics.maxDelta = maxDelta;
    return metrics;
  }

  /**
   * 生成单张对比图：左=参考图（基线/上一帧），中=当前图，右=差异图（变化处标红）。
   *
   * @param reference 参考图（可为 null，此时左栏显示占位）
   * @param current 当前图
   * @param tolerance 差异容差
   * @param referenceLabel 左栏标签
   * @param currentLabel 中栏标签
   * @return 合成位图；失败返回 null
   */
  static Bitmap renderComparison(
      Bitmap reference,
      Bitmap current,
      int tolerance,
      String referenceLabel,
      String currentLabel) {
    if (current == null) {
      return null;
    }
    Bitmap source = reference == null ? current : reference;
    int srcW = Math.max(1, source.getWidth());
    float scale = (float) PANEL_WIDTH / srcW;
    int panelH = Math.max(1, Math.round(source.getHeight() * scale));
    if (reference != null && current.getWidth() != reference.getWidth()) {
      // 尺寸不同：以参考图比例为准，当前图按同比例缩放
      panelH = Math.max(1, Math.round(reference.getHeight() * scale));
    }

    int panelCount = reference == null ? 2 : 3;
    int totalW = PANEL_WIDTH * panelCount + PANEL_GAP * (panelCount - 1);
    int totalH = panelH + HEADER_HEIGHT;

    Bitmap output = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(output);
    canvas.drawColor(Color.parseColor("#202020"));

    Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    labelPaint.setColor(Color.WHITE);
    labelPaint.setTextSize(14f);
    Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    borderPaint.setStyle(Paint.Style.STROKE);
    borderPaint.setColor(Color.parseColor("#606060"));

    int x = 0;
    // 左栏：参考图
    if (reference != null) {
      Bitmap scaledRef = Bitmap.createScaledBitmap(reference, PANEL_WIDTH, panelH, true);
      canvas.drawBitmap(scaledRef, x, HEADER_HEIGHT, null);
      canvas.drawRect(x, HEADER_HEIGHT, x + PANEL_WIDTH, HEADER_HEIGHT + panelH, borderPaint);
      canvas.drawText(trim(referenceLabel), x + 4, 15, labelPaint);
      recycleIfNotAliased(scaledRef, reference);
      x += PANEL_WIDTH + PANEL_GAP;
    }

    // 中栏：当前图
    Bitmap scaledCur = Bitmap.createScaledBitmap(current, PANEL_WIDTH, panelH, true);
    canvas.drawBitmap(scaledCur, x, HEADER_HEIGHT, null);
    canvas.drawRect(x, HEADER_HEIGHT, x + PANEL_WIDTH, HEADER_HEIGHT + panelH, borderPaint);
    canvas.drawText(trim(currentLabel), x + 4, 15, labelPaint);
    x += PANEL_WIDTH + PANEL_GAP;

    // 右栏：差异图（变化处红，未变处灰）
    if (reference != null) {
      Bitmap diff = renderDiffPanel(reference, current, PANEL_WIDTH, panelH, tolerance);
      if (diff != null) {
        canvas.drawBitmap(diff, x, HEADER_HEIGHT, null);
        canvas.drawRect(x, HEADER_HEIGHT, x + PANEL_WIDTH, HEADER_HEIGHT + panelH, borderPaint);
        canvas.drawText("diff (red=changed)", x + 4, 15, labelPaint);
        diff.recycle();
      }
    }

    recycleIfNotAliased(scaledCur, current);

    // 若超过输出上限则整体缩小
    int longest = Math.max(totalW, totalH);
    if (longest > MAX_OUTPUT_DIMENSION) {
      float shrink = (float) MAX_OUTPUT_DIMENSION / longest;
      Bitmap small =
          Bitmap.createScaledBitmap(
              output,
              Math.max(1, Math.round(totalW * shrink)),
              Math.max(1, Math.round(totalH * shrink)),
              true);
      output.recycle();
      return small;
    }
    return output;
  }

  /** 渲染差异面板：未变像素转灰，变化像素标红。 */
  private static Bitmap renderDiffPanel(
      Bitmap reference, Bitmap current, int panelW, int panelH, int tolerance) {
    try {
      Bitmap refScaled = Bitmap.createScaledBitmap(reference, panelW, panelH, true);
      Bitmap curScaled = Bitmap.createScaledBitmap(current, panelW, panelH, true);
      Bitmap out = Bitmap.createBitmap(panelW, panelH, Bitmap.Config.ARGB_8888);
      int tol = Math.max(0, Math.min(255, tolerance));
      int[] refRow = new int[panelW];
      int[] curRow = new int[panelW];
      int[] outRow = new int[panelW];
      for (int y = 0; y < panelH; y++) {
        refScaled.getPixels(refRow, 0, panelW, 0, y, panelW, 1);
        curScaled.getPixels(curRow, 0, panelW, 0, y, panelW, 1);
        for (int x = 0; x < panelW; x++) {
          int pa = refRow[x];
          int pb = curRow[x];
          int dr = Math.abs(((pa >> 16) & 0xFF) - ((pb >> 16) & 0xFF));
          int dg = Math.abs(((pa >> 8) & 0xFF) - ((pb >> 8) & 0xFF));
          int db = Math.abs((pa & 0xFF) - (pb & 0xFF));
          int delta = Math.max(dr, Math.max(dg, db));
          if (delta > tol) {
            outRow[x] = Color.RED;
          } else {
            int gray = ((pa >> 16) & 0xFF) / 4 + ((pa >> 8) & 0xFF) / 4 + (pa & 0xFF) / 4;
            outRow[x] = Color.rgb(gray, gray, gray);
          }
        }
        out.setPixels(outRow, 0, panelW, 0, y, panelW, 1);
      }
      recycleIfNotAliased(refScaled, reference);
      recycleIfNotAliased(curScaled, current);
      return out;
    } catch (Exception e) {
      log.warn("渲染差异面板失败", e);
      return null;
    }
  }

  private static String trim(String label) {
    if (label == null) {
      return "";
    }
    return label.length() <= 24 ? label : label.substring(0, 24);
  }

  /**
   * 回收缩放副本，但避免误回收与源同一对象的情况。
   *
   * <p>{@code Bitmap.createScaledBitmap} 在目标尺寸与源完全相同时会<b>直接返回源对象</b>
   * （不新建）。若此时无条件 recycle，就会把调用方仍要用的源位图释放掉——
   * 后续再读该位图会抛 {@code RuntimeException: Canvas: trying to use a recycled bitmap}。
   */
  private static void recycleIfNotAliased(Bitmap scaled, Bitmap source) {
    if (scaled != null && scaled != source) {
      scaled.recycle();
    }
  }

  // ---------------------------------------------------------------------------
  // PNG 编解码
  // ---------------------------------------------------------------------------

  /** 返回给模型的合成图最长边上限，与 {@code phone_screenshot} 默认值一致。 */
  private static final int INLINE_MAX_DIMENSION = 1568;

  /** 内联图片负载的字节上限（base64 前）。超过时继续收缩，仍超则退化为 JPEG。 */
  private static final long INLINE_MAX_BYTES = 4L * 1024L * 1024L;

  /**
   * 把合成图编码为 base64，并施加与 {@code phone_screenshot} 相同的缩放/字节上限，
   * 避免一张大图撑爆上下文。
   *
   * <p>策略：先按最长边 1568 缩放；PNG 仍超字节上限时改用 JPEG(85) 重试。
   * 调用方仍负责回收传入的位图。
   *
   * @return base64（不含 data URL 前缀）；失败返回空串
   */
  static String encodeBase64Bounded(Bitmap bitmap) {
    if (bitmap == null) {
      return "";
    }
    Bitmap current = bitmap;
    try {
      int longest = Math.max(current.getWidth(), current.getHeight());
      if (longest > INLINE_MAX_DIMENSION) {
        float ratio = (float) INLINE_MAX_DIMENSION / longest;
        Bitmap scaled =
            Bitmap.createScaledBitmap(
                current,
                Math.max(1, Math.round(current.getWidth() * ratio)),
                Math.max(1, Math.round(current.getHeight() * ratio)),
                true);
        recycleIfNotAliased(scaled, current);
        current = scaled;
      }
      byte[] png = compress(current, Bitmap.CompressFormat.PNG, 100);
      if (png != null && png.length <= INLINE_MAX_BYTES) {
        return Base64.encodeToString(png, Base64.NO_WRAP);
      }
      byte[] jpeg = compress(current, Bitmap.CompressFormat.JPEG, 85);
      if (jpeg != null) {
        return Base64.encodeToString(jpeg, Base64.NO_WRAP);
      }
      return png == null ? "" : Base64.encodeToString(png, Base64.NO_WRAP);
    } catch (Exception e) {
      log.warn("编码合成图失败", e);
      return "";
    } finally {
      recycleIfNotAliased(current, bitmap);
    }
  }

  private static byte[] compress(Bitmap bitmap, Bitmap.CompressFormat format, int quality) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    return bitmap.compress(format, quality, out) ? out.toByteArray() : null;
  }

  /** 读取 PNG 文件为位图；失败返回 null。 */
  static Bitmap decode(File file) {
    if (file == null || !file.exists()) {
      return null;
    }
    try (InputStream in = Files.newInputStream(file.toPath())) {
      return BitmapFactory.decodeStream(in);
    } catch (Exception e) {
      log.warn("解码 PNG 失败: {}", file, e);
      return null;
    }
  }

  /** 把指标格式化成给模型看的数字块。 */
  static String formatMetrics(DiffMetrics metrics, int tolerance) {
    if (metrics == null) {
      return "(无指标)";
    }
    return String.format(
        Locale.ROOT,
        "  尺寸: %dx%d\n  容差: %d\n  变化像素: %d / %d (%.2f%%)\n"
            + "  平均通道差: %.2f, 最大通道差: %d\n  变化区域包围盒: %s\n",
        metrics.width,
        metrics.height,
        tolerance,
        metrics.changedPixels,
        metrics.comparedPixels,
        metrics.diffPercent,
        metrics.meanDelta,
        metrics.maxDelta,
        metrics.boundingBox());
  }
}
