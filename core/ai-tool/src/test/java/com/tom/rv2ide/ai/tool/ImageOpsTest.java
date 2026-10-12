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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ImageOps} 的黄金值测试。
 *
 * <p>黄金值来源：FrameBaker 原 Python 实现（sprite_lab/imaging/*.py）的实测输出
 * （计划 §3.10 表），直接抄作期望——这是「移植保真」的最强证据。
 * 复跑命令（FrameBaker 仓库根）：
 * <pre>
 * .venv-matting/Scripts/python -c "import sys; sys.path.insert(0,'release/framebaker-win/resources/sprite'); ..."
 * </pre>
 */
class ImageOpsTest {

  private static int argb(int a, int r, int g, int b) {
    return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
  }

  private static int alphaOf(int p) {
    return (p >>> 24) & 0xFF;
  }

  // ---------------------------------------------------------------------
  // chromaKey
  // ---------------------------------------------------------------------

  @Test
  void chromaKeyMatchesFrameBakerGolden() {
    // 4x1：纯绿、浅绿（softness 渐变带）、红（保留）、近绿
    int[] input = {
        argb(255, 0, 255, 0),
        argb(255, 0, 240, 0),
        argb(255, 200, 50, 50),
        argb(255, 0, 250, 0),
    };
    int[] out = ImageOps.chromaKey(input, 4, 1, 0, 255, 0, 10, 20, 1.0f, 0, null);
    assertNotNull(out);
    // 期望（FrameBaker 实测）：[(0,0,0,0),(0,60,0,63),(200,50,50,255),(0,0,0,0)]
    assertEquals(argb(0, 0, 0, 0), out[0], "纯绿 → 全透明");
    assertEquals(63, alphaOf(out[1]), "浅绿在渐变带（FrameBaker 实测 alpha=63）");
    assertEquals(60, (out[1] >> 8) & 0xFF, "despill 把 g 从 240 降到 60");
    assertEquals(argb(255, 200, 50, 50), out[2], "红色保留不透明");
    assertEquals(0, alphaOf(out[3]), "近绿 → 全透明");
  }

  @Test
  void chromaKeyHaloErodesAlpha() {
    // 3x3 中心红其余绿；halo=1 → MinFilter(3) 全图最小 alpha=0 扩散到全图
    int[] input = new int[9];
    for (int i = 0; i < 9; i++) {
      input[i] = argb(255, 0, 255, 0);
    }
    input[4] = argb(255, 200, 50, 50);
    int[] out = ImageOps.chromaKey(input, 3, 3, 0, 255, 0, 10, 20, 0f, 1, null);
    assertNotNull(out);
    for (int i = 0; i < 9; i++) {
      assertEquals(0, alphaOf(out[i]), "halo 腐蚀后全图 alpha=0（index " + i + "）");
    }
  }

  @Test
  void chromaKeySoftnessZeroIsHardEdge() {
    int[] input = {
        argb(255, 0, 255, 0),   // dist=0 ≤ 10 → alpha 0
        argb(255, 100, 100, 100), // dist 远大于 10 → alpha 255
    };
    int[] out = ImageOps.chromaKey(input, 2, 1, 0, 255, 0, 10, 0, 0f, 0, null);
    assertNotNull(out);
    assertEquals(0, alphaOf(out[0]));
    assertEquals(255, alphaOf(out[1]), "softness=0 硬边");
  }

  @Test
  void chromaKeyDespillReducesGreen() {
    // 边缘像素：绿稍多于 rb（spill>0），dist 在渐变带
    int[] input = {argb(255, 50, 120, 50)};
    int[] out = ImageOps.chromaKey(input, 1, 1, 0, 255, 0, 10, 200, 1.0f, 0, null);
    assertNotNull(out);
    int g = (out[0] >> 8) & 0xFF;
    assertEquals(50, (out[0] >> 16) & 0xFF, "r 不变");
    assertEquals(50, out[0] & 0xFF, "b 不变");
    assertTrue(g <= 120, "g 被 despill 降低（或不变），实际 " + g);
  }

  @Test
  void chromaKeyDoesNotMutateInput() {
    int[] input = {argb(255, 0, 255, 0), argb(255, 200, 50, 50)};
    int[] copy = input.clone();
    ImageOps.chromaKey(input, 2, 1, 0, 255, 0, 10, 20, 1.0f, 1, null);
    assertArrayEquals(copy, input, "纯函数纪律：输入不被修改");
  }

  @Test
  void chromaKeyCancellationReturnsNull() {
    int[] input = new int[200_000];
    int[] out = ImageOps.chromaKey(input, 200_000, 1, 0, 255, 0, 10, 20, 0f, 0, () -> true);
    assertNull(out, "取消信号为 true 时返回 null");
  }

  // ---------------------------------------------------------------------
  // lumaKey
  // ---------------------------------------------------------------------

  @Test
  void lumaKeyMatchesFrameBakerGolden() {
    int[] input = {argb(255, 128, 128, 128), argb(255, 0, 0, 0), argb(255, 235, 235, 235)};
    int[] out = ImageOps.lumaKey(input, 3, 1, 8, 235, 1.0f, 1.0f, 0, 255, 0, 0f, null);
    assertNotNull(out);
    assertEquals(135, alphaOf(out[0]), "中灰 → 135（FrameBaker 实测）");
    assertEquals(0, alphaOf(out[1]), "黑 → 0");
    assertEquals(255, alphaOf(out[2]), "白 → 255");
  }

  @Test
  void lumaKeyGammaCurveMatches() {
    int[] input = {argb(255, 128, 128, 128), argb(255, 0, 0, 0), argb(255, 235, 235, 235)};
    int[] out = ImageOps.lumaKey(input, 3, 1, 8, 235, 2.0f, 1.0f, 0, 255, 0, 0f, null);
    assertNotNull(out);
    assertEquals(71, alphaOf(out[0]), "gamma=2 → 71（FrameBaker 实测）");
    assertEquals(0, alphaOf(out[1]));
    assertEquals(255, alphaOf(out[2]));
  }

  @Test
  void lumaKeySuppressionMatches() {
    int[] input = {argb(255, 255, 255, 255), argb(255, 0, 255, 0)};
    // 默认其余参数：black=8, white=235, gamma=1, strength=1, key_suppression=0.95
    int[] out = ImageOps.lumaKey(input, 2, 1, 8, 235, 1.0f, 1.0f, 0, 255, 0, 0.95f, null);
    assertNotNull(out);
    assertEquals(255, alphaOf(out[0]), "白像素保留");
    assertEquals(10, alphaOf(out[1]), "绿像素被 key 抑制到 10（FrameBaker 实测）");
  }

  @Test
  void lumaKeyClampsParams() {
    // black=300 / white=0 / gamma=-5 / strength=-3：越界输入按钳制处理，不抛异常
    int[] input = {argb(255, 128, 128, 128)};
    int[] out = ImageOps.lumaKey(input, 1, 1, 300, 0, -5f, -3f, 0, 255, 0, 2f, null);
    assertNotNull(out);
    // gamma/strength ≤0 回落 1.0（镜像上游 or 语义）；black 钳 254、white 抬到 255
    // luma(128) = 128；normalized = (128-254)/(255-254) → clamp 0 → alpha 0
    assertEquals(0, alphaOf(out[0]), "black=254 时中灰全透明");
  }

  @Test
  void lumaKeyStrengthZeroFallsBackToOne() {
    // 复审 m7：strength=0 回落 1.0（上游 or 语义）——不是整图全透明
    int[] input = {argb(255, 128, 128, 128)};
    int[] out = ImageOps.lumaKey(input, 1, 1, 8, 235, 1.0f, 0f, 0, 255, 0, 0f, null);
    assertNotNull(out);
    assertEquals(135, alphaOf(out[0]), "strength=0 回落 1.0 → 与默认相同（135）");
  }

  // ---------------------------------------------------------------------
  // erodeAlpha
  // ---------------------------------------------------------------------

  @Test
  void erodeAlphaShrinksWindowAtEdges() {
    // 5x1 [255,255,0,255,255] + r=1 → [255,0,0,0,255]（PIL MinFilter 收缩窗口语义）
    int[] input = {
        argb(255, 0, 0, 0), argb(255, 0, 0, 0), argb(0, 0, 0, 0),
        argb(255, 0, 0, 0), argb(255, 0, 0, 0),
    };
    int[] out = ImageOps.erodeAlpha(input, 5, 1, 1, null);
    assertNotNull(out);
    int[] expected = {255, 0, 0, 0, 255};
    for (int i = 0; i < 5; i++) {
      assertEquals(expected[i], alphaOf(out[i]), "index " + i);
    }
  }

  @Test
  void erodeAlphaZeroRadiusIsIdentity() {
    int[] input = {argb(255, 1, 2, 3), argb(0, 4, 5, 6)};
    int[] out = ImageOps.erodeAlpha(input, 2, 1, 0, null);
    assertArrayEquals(input, out, "radius=0 按内容等于输入（允许返回引用或 clone）");
  }

  // ---------------------------------------------------------------------
  // crop / scaleNearest
  // ---------------------------------------------------------------------

  @Test
  void cropClampsToBounds() {
    // 4x4 全红，crop rect (-2,-2,4,4) → 收敛到 (0,0,2,2)
    int[] input = new int[16];
    for (int i = 0; i < 16; i++) {
      input[i] = argb(255, 255, 0, 0);
    }
    ImageOps.CropResult r = ImageOps.crop(input, 4, 4, -2, -2, 4, 4);
    assertNotNull(r);
    assertEquals(2, r.width);
    assertEquals(2, r.height);
    assertEquals(0, r.left);
    assertEquals(0, r.top);
  }

  @Test
  void cropNoIntersectionReturnsNull() {
    int[] input = new int[16];
    assertNull(ImageOps.crop(input, 4, 4, 10, 10, 4, 4), "完全越界 → null");
  }

  @Test
  void scaleNearestPicksExpectedPixels() {
    // 2x2 → 4x4 最近邻：每像素扩成 2x2 块
    int[] input = {
        argb(255, 255, 0, 0), argb(255, 0, 255, 0),
        argb(255, 0, 0, 255), argb(255, 255, 255, 0),
    };
    int[] out = ImageOps.scaleNearest(input, 2, 2, 4, 4);
    assertEquals(16, out.length);
    assertEquals(argb(255, 255, 0, 0), out[0], "左上块");
    assertEquals(argb(255, 255, 0, 0), out[1]);
    assertEquals(argb(255, 0, 255, 0), out[2], "右上块");
    assertEquals(argb(255, 0, 0, 255), out[8], "左下块");
    assertEquals(argb(255, 255, 255, 0), out[10], "右下块");
  }

  // ---------------------------------------------------------------------
  // autoKeyColor
  // ---------------------------------------------------------------------

  @Test
  void autoKeyColorSmallImageDoesNotCrash() {
    // 3x3（上游 IndexError 的边界）；本实现修正后正常返回
    int[] input = new int[9];
    for (int i = 0; i < 9; i++) {
      input[i] = argb(255, 255, 255, 255);
    }
    int[] key = ImageOps.autoKeyColor(input, 3, 3);
    assertNotNull(key);
    assertEquals(255, key[0]);
    assertEquals(255, key[1]);
    assertEquals(255, key[2]);
  }

  @Test
  void autoKeyColorAveragesCorners() {
    // 8x8 白底中央 4x4 黑 → (191,191,191)（FrameBaker 实测）
    int[] input = new int[64];
    for (int y = 0; y < 8; y++) {
      for (int x = 0; x < 8; x++) {
        boolean black = x >= 2 && x < 6 && y >= 2 && y < 6;
        input[y * 8 + x] = black ? argb(255, 0, 0, 0) : argb(255, 255, 255, 255);
      }
    }
    int[] key = ImageOps.autoKeyColor(input, 8, 8);
    assertEquals(191, key[0], "FrameBaker 实测 191");
    assertEquals(191, key[1]);
    assertEquals(191, key[2]);
  }

  // ---------------------------------------------------------------------
  // dominantColors
  // ---------------------------------------------------------------------

  @Test
  void dominantColorsPicksTopBucket() {
    // 100 红 + 50 蓝 + 10 绿
    int[] input = new int[160];
    int idx = 0;
    for (int i = 0; i < 100; i++) {
      input[idx++] = argb(255, 255, 0, 0);
    }
    for (int i = 0; i < 50; i++) {
      input[idx++] = argb(255, 0, 0, 255);
    }
    for (int i = 0; i < 10; i++) {
      input[idx++] = argb(255, 0, 255, 0);
    }
    ImageOps.DominantResult r = ImageOps.dominantColors(input, 3, null);
    assertNotNull(r);
    assertEquals(3, r.filled);
    assertEquals(160, r.totalPixels);
    // top1 = 红
    assertEquals(255, r.rows[0][0]);
    assertEquals(0, r.rows[0][1]);
    assertEquals(0, r.rows[0][2]);
    assertEquals(100, r.rows[0][3]);
    // top2 = 蓝
    assertEquals(50, r.rows[1][3]);
  }

  @Test
  void dominantColorsIgnoresTransparent() {
    // 10 红（不透明）+ 100 蓝（alpha=0）→ 只有红参与
    int[] input = new int[110];
    for (int i = 0; i < 10; i++) {
      input[i] = argb(255, 255, 0, 0);
    }
    for (int i = 10; i < 110; i++) {
      input[i] = argb(0, 0, 0, 255);
    }
    ImageOps.DominantResult r = ImageOps.dominantColors(input, 8, null);
    assertNotNull(r);
    assertEquals(10, r.totalPixels, "透明像素不参与");
    assertEquals(1, r.filled);
    assertEquals(255, r.rows[0][0]);
  }

  @Test
  void dominantColorsIsDeterministic() {
    int[] input = new int[300];
    for (int i = 0; i < 300; i++) {
      input[i] = argb(255, (i * 7) & 0xFF, (i * 13) & 0xFF, (i * 29) & 0xFF);
    }
    ImageOps.DominantResult a = ImageOps.dominantColors(input, 8, null);
    ImageOps.DominantResult b = ImageOps.dominantColors(input, 8, null);
    assertEquals(a.filled, b.filled);
    for (int i = 0; i < a.filled; i++) {
      assertArrayEquals(a.rows[i], b.rows[i], "同输入两次调用逐位相等（确定性）");
    }
  }
}
