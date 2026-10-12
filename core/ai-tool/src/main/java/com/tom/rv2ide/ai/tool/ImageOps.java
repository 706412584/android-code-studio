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

package com.tom.rv2ide.ai.tool;

/**
 * 图像处理纯函数算法集（ARGB_8888 打包像素 {@code a<<24 | r<<16 | g<<8 | b}）。
 *
 * <p>算法语义完整移植自 FrameBaker（sprite_lab/imaging/{chroma,luma,color}.py），
 * 逐式保留（含边界与钳制）。本类零 Android 依赖、可 JVM 单测。
 *
 * <p><b>纯函数纪律</b>：所有方法不修改入参数组（内部 clone 或新建输出数组）。
 *
 * <p><b>取消纪律</b>：所有像素级函数最后一个参数是 {@link java.util.function.BooleanSupplier}
 * cancelled，循环内每 65536 像素检查一次，取消时返回 null（调用方转 error「已取消」）。
 */
public final class ImageOps {

  private ImageOps() {}

  // ---------------------------------------------------------------------
  // §3.1 chroma_key（移植 chroma.py: chroma_key_frame，逐式保留）
  // ---------------------------------------------------------------------

  /**
   * 色键抠图（移植 FrameBaker chroma_key_frame，语义逐式保留）。
   *
   * @param pixels   ARGB_8888 打包像素（不修改）
   * @param width    宽
   * @param height   高
   * @param keyR/keyG/keyB 背景色
   * @param threshold    距离 ≤ threshold → alpha 0
   * @param softness     0 → 硬边（> threshold 即 255）；>0 → threshold~threshold+softness 线性渐隐
   * @param despillStrength 去溢色强度（上游钳到 [0, 2.5]）
   * @param haloPixels   >0 时对 alpha 做 (2*halo+1) 最小值滤波（腐蚀）
   * @param cancelled    取消信号；为 null 时视为永不取消
   * @return 新的 ARGB_8888 数组；被取消时返回 null
   */
  public static int[] chromaKey(
      int[] pixels, int width, int height,
      int keyR, int keyG, int keyB,
      int threshold, int softness, float despillStrength, int haloPixels,
      java.util.function.BooleanSupplier cancelled) {
    int[] out = pixels.clone();
    // --- 参数钳制：对齐 FrameBaker 上游（server.py:1607-1623）---
    float despill = Math.max(0f, Math.min(2.5f, despillStrength));
    int halo = Math.max(0, haloPixels);
    int n = out.length;

    // Python: max_distance = max(threshold, 1) if softness <= 0 else threshold + softness
    int maxDistance = softness <= 0 ? Math.max(threshold, 1) : threshold + softness;

    for (int i = 0; i < n; i++) {
      if ((i & 0xFFFF) == 0 && cancelled != null && cancelled.getAsBoolean()) {
        return null; // 已取消（§3.0 取消纪律）
      }
      int p = pixels[i];
      int r = (p >> 16) & 0xFF;
      int g = (p >> 8) & 0xFF;
      int b = p & 0xFF;

      int dr = r - keyR, dg = g - keyG, db = b - keyB;
      // Python: dist = math.sqrt(dr**2 + dg**2 + db**2)
      double dist = Math.sqrt((double) (dr * dr + dg * dg + db * db));

      // Python: if dist <= threshold: alpha=0
      //         elif softness <= 0 or dist >= max_distance: alpha=255
      //         else: alpha = int(((dist - threshold) / softness) * 255)
      int alpha;
      if (dist <= threshold) {
        alpha = 0;
      } else if (softness <= 0 || dist >= maxDistance) {
        alpha = 255;
      } else {
        alpha = (int) (((dist - threshold) / softness) * 255.0);
      }

      // Python: max_rb = max(r, b); spill = max(0, g - max_rb)
      // 注：这是"绿幕溢色"的专用式——绿通道超出 r/b 最大值的部分。
      // 蓝幕素材此式恒为 0（不生效），与上游行为一致，不扩展。
      int maxRb = Math.max(r, b);
      int spill = Math.max(0, g - maxRb);
      // Python: closeness = max(0.0, 1.0 - min(dist / max_distance, 1.0))
      double closeness = Math.max(0.0, 1.0 - Math.min(dist / maxDistance, 1.0));
      // Python: reduction = int(spill * despill_strength * max(closeness, 1.0 - (alpha / 255.0)))
      // 全程 double 计算（复审 n2）：消除 int×float 的中间精度损失。
      int reduction = (int) ((double) spill * despill
          * Math.max(closeness, 1.0 - (alpha / 255.0)));

      int newG = Math.max(0, g - reduction);
      // 源 alpha 被键控结果覆盖（Python 同样如此：输出 alpha 只看色距，不看原 alpha）。
      // 这是上游语义，不要"修正"。
      out[i] = ((alpha & 0xFF) << 24) | (r << 16) | (newG << 8) | b;
    }

    // Python: if halo_pixels > 0: filter_size = halo*2+1; alpha = alpha.filter(MinFilter(size))
    if (halo > 0) {
      out = erodeAlpha(out, width, height, halo, cancelled);
      if (out == null) {
        return null; // 已取消
      }
    }
    return out;
  }

  // ---------------------------------------------------------------------
  // §3.2 luma_key（移植 luma.py: luminance_alpha_mask）
  // ---------------------------------------------------------------------

  /**
   * 亮度键抠图（移植 FrameBaker luminance_alpha_mask + apply_alpha_mask 的合并语义）。
   *
   * <p>该函数只算 alpha 遮罩（L 通道），再由 apply_alpha_mask 合并回原 RGB
   * （luma.py:95-101）。移植版直接返回合并后的 ARGB。
   *
   * <p>注：本函数只移植 mask 阶段；FrameBaker luma 流水线的后置两步
   * （halo MinFilter 与 despill_alpha_edges，server.py:1504-1507）在默认参数下
   * 都是 no-op（halo 默认 0；despill_strength 缺省 0 时函数直接 return），
   * v1 不移植——与上游默认行为一致。
   */
  public static int[] lumaKey(
      int[] pixels, int width, int height,
      int blackPoint, int whitePoint, float gamma, float strength,
      int keyR, int keyG, int keyB, float keySuppression,
      java.util.function.BooleanSupplier cancelled) {
    // --- 钳制：对齐 luma.py:21-25 的**精确**语义（复审修正）---
    // Python `float(gamma or 1.0)`：只有**恰好 0**（falsy）回退 1.0；负数是 truthy，
    // 走外层 max(0.05, ...) → 钳到 0.05。写 `<=0 → 1.0` 会让 gamma∈(0,0.05) 与
    // 负数两段行为偏离上游。
    int black = Math.max(0, Math.min(254, blackPoint));
    int white = Math.max(black + 1, Math.min(255, whitePoint));
    float gammaOr = gamma == 0f ? 1.0f : gamma;             // 上游 `gamma or 1.0`
    float curveGamma = Math.max(0.05f, gammaOr);            // 上游 max(0.05, ...)
    float strengthOr = strength == 0f ? 1.0f : strength;    // 上游 `strength or 1.0`
    float curveStrength = Math.max(0f, Math.min(2f, strengthOr)); // clamp(0, 2)
    float keyStrength = Math.max(0f, Math.min(1f, keySuppression));
    int scale = white - black;

    int[] out = pixels.clone();
    for (int i = 0; i < out.length; i++) {
      if ((i & 0xFFFF) == 0 && cancelled != null && cancelled.getAsBoolean()) {
        return null; // 已取消
      }
      int p = pixels[i];
      int r = (p >> 16) & 0xFF;
      int g = (p >> 8) & 0xFF;
      int b = p & 0xFF;
      // Python: luma = int(0.2126*r + 0.7152*g + 0.0722*b)   ← Rec.709 亮度权重
      int luma = (int) (0.2126 * r + 0.7152 * g + 0.0722 * b);
      // Python: normalized = clamp_float((luma - black) / scale, 0, 1)
      double normalized = clamp((double) (luma - black) / scale, 0.0, 1.0);
      double adjusted = Math.pow(normalized, curveGamma);
      double alpha = clamp(adjusted * curveStrength, 0.0, 1.0);
      // Python: 可选 key 抑制（key_rgb 非空且 key_strength > 0）
      if (keyStrength > 0f) {
        int dkr = r - keyR, dkg = g - keyG, dkb = b - keyB;
        double dist = Math.sqrt((double) (dkr * dkr + dkg * dkg + dkb * dkb));
        // Python: closeness = 1.0 - min(dist / 180.0, 1.0); alpha *= 1.0 - (closeness**2)*key_strength
        double closeness = 1.0 - Math.min(dist / 180.0, 1.0);
        alpha *= 1.0 - (closeness * closeness * keyStrength);
      }
      int a = (int) Math.round(alpha * 255);
      // apply_alpha_mask 语义：RGB 保留，alpha 替换。
      out[i] = ((a & 0xFF) << 24) | (r << 16) | (g << 8) | b;
    }
    return out;
  }

  // ---------------------------------------------------------------------
  // §3.5 自动取色（移植 color.py: auto_key_color，修掉上游越界 bug）
  // ---------------------------------------------------------------------

  /**
   * 角块均值自动取色（移植 auto_key_color，修正小图越界：sample 收缩 + 角块去重）。
   * 取四角各 sample×sample 区域 RGB 均值的总均值。
   *
   * <p>去重实现（复审 m3 修订）：逐像素判是否落在任一更早的 box 内——
   * 与 seen[][] 语义等价、显然正确、零额外内存。
   */
  public static int[] autoKeyColor(int[] pixels, int width, int height) {
    int sample = Math.max(4, Math.min(width, height) / 16);
    sample = Math.min(sample, Math.max(1, Math.min(width, height))); // 修正①：不超过短边
    int[][] boxes = {
        {0, 0, sample, sample},
        {width - sample, 0, width, sample},
        {0, height - sample, sample, height},
        {width - sample, height - sample, width, height},
    };
    long[] totals = new long[3];
    int count = 0;
    for (int bi = 0; bi < boxes.length; bi++) {
      int[] box = boxes[bi];
      for (int y = box[1]; y < box[3]; y++) {
        for (int x = box[0]; x < box[2]; x++) {
          // 修正②：若该像素落在任一更早的 box 内，则已统计过，跳过。
          // （小图时角块重叠只统计一次；大图不重叠时与 Python 原版逐像素一致。）
          boolean dup = false;
          for (int bj = 0; bj < bi; bj++) {
            int[] prev = boxes[bj];
            if (x >= prev[0] && x < prev[2] && y >= prev[1] && y < prev[3]) {
              dup = true;
              break;
            }
          }
          if (dup) {
            continue;
          }
          int p = pixels[y * width + x];
          totals[0] += (p >> 16) & 0xFF;
          totals[1] += (p >> 8) & 0xFF;
          totals[2] += p & 0xFF;
          count++;
        }
      }
    }
    if (count <= 0) {
      return new int[] {0, 255, 0}; // Python 同款兜底（color.py:38-39）
    }
    return new int[] {
        (int) (totals[0] / count), (int) (totals[1] / count), (int) (totals[2] / count)
    };
  }

  // ---------------------------------------------------------------------
  // §3.6 halo 腐蚀（PIL MinFilter → 自写 NxN 最小值滤波）
  // ---------------------------------------------------------------------

  /**
   * alpha 通道最小值滤波（腐蚀），移植 PIL ImageFilter.MinFilter(size) 的边界语义：
   * 窗口越界时收缩到图像内（越界像素不参与），非 clamp 边缘。
   *
   * <p>分离式实现：先水平后垂直（O(n·2r) 而非 O(n·(2r+1)²)）。
   *
   * @param radius 半径；对应 PIL 的 filter_size = radius*2+1
   * @return 新的 ARGB 数组（radius ≤ 0 时返回入参引用——调用方不修改返回值时安全）；被取消时返回 null
   */
  public static int[] erodeAlpha(int[] pixels, int width, int height, int radius,
      java.util.function.BooleanSupplier cancelled) {
    if (radius <= 0) {
      return pixels;
    }
    int n = pixels.length;
    int[] alpha = new int[n];
    for (int i = 0; i < n; i++) {
      if ((i & 0xFFFF) == 0 && cancelled != null && cancelled.getAsBoolean()) {
        return null; // 已取消
      }
      alpha[i] = (pixels[i] >>> 24) & 0xFF;
    }

    // 水平趟（窗口收缩，O(width·radius)；取消检查按行计——每行 65536 像素量级）
    int[] tmp = new int[n];
    for (int y = 0; y < height; y++) {
      if ((y & 0xFF) == 0 && cancelled != null && cancelled.getAsBoolean()) {
        return null; // 已取消
      }
      int row = y * width;
      for (int x = 0; x < width; x++) {
        int lo = Math.max(0, x - radius);        // ← 收缩窗口（非 clamp）
        int hi = Math.min(width - 1, x + radius);
        int min = 255;
        for (int xx = lo; xx <= hi; xx++) {
          int v = alpha[row + xx];
          if (v < min) {
            min = v;
          }
        }
        tmp[row + x] = min;
      }
    }
    // 垂直趟
    for (int y = 0; y < height; y++) {
      if ((y & 0xFF) == 0 && cancelled != null && cancelled.getAsBoolean()) {
        return null; // 已取消
      }
      for (int x = 0; x < width; x++) {
        int lo = Math.max(0, y - radius);
        int hi = Math.min(height - 1, y + radius);
        int min = 255;
        for (int yy = lo; yy <= hi; yy++) {
          int v = tmp[yy * width + x];
          if (v < min) {
            min = v;
          }
        }
        alpha[y * width + x] = min;
      }
    }
    int[] out = pixels.clone();
    for (int i = 0; i < n; i++) {
      if ((i & 0xFFFF) == 0 && cancelled != null && cancelled.getAsBoolean()) {
        return null; // 已取消
      }
      out[i] = (alpha[i] << 24) | (out[i] & 0x00FFFFFF);
    }
    return out;
  }

  // ---------------------------------------------------------------------
  // §3.8 crop / scaleNearest
  // ---------------------------------------------------------------------

  /** crop 的结果：收敛后的像素与尺寸（left/top 为实际生效的裁剪框原点）。 */
  public static final class CropResult {
    public final int[] pixels;
    public final int width;
    public final int height;
    public final int left;
    public final int top;

    public CropResult(int[] pixels, int width, int height, int left, int top) {
      this.pixels = pixels;
      this.width = width;
      this.height = height;
      this.left = left;
      this.top = top;
    }
  }

  /**
   * 收敛式裁剪（语义对齐 PhoneScreenshotTool 的 parseRegion/crop）：
   * 越界部分收敛到图像内；收敛后无交集返回 null（调用方报「rect 与图像无交集」）。
   */
  public static CropResult crop(int[] pixels, int width, int height,
      int x, int y, int rectWidth, int rectHeight) {
    int left = Math.min(Math.max(0, x), width);
    int top = Math.min(Math.max(0, y), height);
    int right = Math.min(x + rectWidth, width);
    int bottom = Math.min(y + rectHeight, height);
    int w = right - left, h = bottom - top;
    if (w <= 0 || h <= 0) {
      return null;
    }
    int[] out = new int[w * h];
    for (int row = 0; row < h; row++) {
      System.arraycopy(pixels, (top + row) * width + left, out, row * w, w);
    }
    return new CropResult(out, w, h, left, top);
  }

  /** 最近邻缩放（纯数组，像素画主力场景）。 */
  public static int[] scaleNearest(int[] pixels, int width, int height,
      int targetWidth, int targetHeight) {
    int[] out = new int[targetWidth * targetHeight];
    for (int y = 0; y < targetHeight; y++) {
      int sy = (int) ((long) y * height / targetHeight);
      for (int x = 0; x < targetWidth; x++) {
        int sx = (int) ((long) x * width / targetWidth);
        out[y * targetWidth + x] = pixels[sy * width + sx];
      }
    }
    return out;
  }

  // ---------------------------------------------------------------------
  // §3.9 dominant_colors（量化直方图，确定性、可单测）
  // ---------------------------------------------------------------------

  /** dominant_colors 的结果：rows 前 filled 行有效；totalPixels 为参与统计的像素总数。 */
  public static final class DominantResult {
    public final int[][] rows;      // {r,g,b,count} × topN（前 filled 行有效）
    public final int filled;
    public final long totalPixels;

    public DominantResult(int[][] rows, int filled, long totalPixels) {
      this.rows = rows;
      this.filled = filled;
      this.totalPixels = totalPixels;
    }
  }

  /**
   * 主色提取：RGB 各通道 >> 4 量化到 4096 桶，按像素数降序取 topN。
   *
   * <p>桶内代表色取桶内实际像素的均值（而非桶中心）——避免输出 #F0F0F0 这类
   * 桶中心色，给出的都是图上真实存在的近似色。
   *
   * <p>透明像素（alpha < 8）不参与统计：抠图产物的透明区不应污染配色。
   *
   * @return DominantResult；被取消时返回 null
   */
  public static DominantResult dominantColors(
      int[] pixels, int topN, java.util.function.BooleanSupplier cancelled) {
    // 每桶 4 个 long：sumR, sumG, sumB, count
    long[] sums = new long[4096 * 4];
    long totalPixels = 0;
    int idx = 0;
    for (int p : pixels) {
      if ((idx++ & 0xFFFF) == 0 && cancelled != null && cancelled.getAsBoolean()) {
        return null; // 已取消
      }
      if (((p >>> 24) & 0xFF) < 8) {
        continue;
      }
      int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
      int bucket = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
      int base = bucket * 4;
      sums[base] += r;
      sums[base + 1] += g;
      sums[base + 2] += b;
      sums[base + 3]++;
      totalPixels++;
    }
    // 取 topN（桶数 4096，选择排序足够；topN ≤ 16）
    int[][] rows = new int[topN][4];   // {r, g, b, count}
    boolean[] used = new boolean[4096];
    int filled = 0;
    for (int k = 0; k < topN; k++) {
      int best = -1;
      long bestCount = 0;
      for (int bucket = 0; bucket < 4096; bucket++) {
        if (used[bucket]) {
          continue;
        }
        long c = sums[bucket * 4 + 3];
        if (c > bestCount) {
          bestCount = c;
          best = bucket;
        }
      }
      if (best < 0) {
        break;
      }
      used[best] = true;
      int base = best * 4;
      rows[k][0] = (int) (sums[base] / bestCount);
      rows[k][1] = (int) (sums[base + 1] / bestCount);
      rows[k][2] = (int) (sums[base + 2] / bestCount);
      rows[k][3] = (int) bestCount;
      filled++;
    }
    return new DominantResult(rows, filled, totalPixels);
  }

  // ---------------------------------------------------------------------
  // 工具
  // ---------------------------------------------------------------------

  /** 数值钳制（对应 Python 的 clamp_float）。 */
  static double clamp(double v, double lo, double hi) {
    return Math.min(hi, Math.max(lo, v));
  }
}
