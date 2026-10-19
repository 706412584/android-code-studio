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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.tool;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import com.tom.rv2ide.artificial.agent.a11y.AcsAccessibilityService;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 无障碍工具共用的辅助逻辑（两个工具：只读 {@link PhoneA11yTool} 与写 {@link PhoneA11yWriteTool}）。
 *
 * <p>抽出来是因为「节点描述格式」必须两边一致：Agent 先 dump 拿到节点行、再据此点击，
 * 两处格式若各写一份，改了 dump 忘了点就会出现「id 对不上」这类难查的问题。
 *
 * <p>移植自 Aharou `A11yTool` 的对应私有方法（describeNode / bitmapToJpegBase64 /
 * appLabel / currentActivity），逻辑照搬。
 */
final class PhoneA11ySupport {

  /** 节点树最多返回的节点数（Aharou 同值：400）。 */
  static final int MAX_NODES = 400;

  /** 节点树最大深度（Aharou 同值：25）。 */
  static final int MAX_DEPTH = 25;

  /** 截图返回时的最长边（Aharou 同值：1440）。 */
  static final int MAX_IMAGE_EDGE = 1440;

  /** 截图 JPEG 质量（Aharou 同值：75）。 */
  static final int JPEG_QUALITY = 75;

  private PhoneA11ySupport() {}

  /** 服务未开启时的统一错误文案——必须给可操作路径，而不是一句「失败」。 */
  static String notEnabledMessage() {
    return "无障碍服务未开启：请到「系统设置 → 无障碍」开启「AndroidCS 无障碍」后重试"
        + "（或在「设置 → AI 助手 → 工具 → 无障碍」里点一下跳过去）。";
  }

  /**
   * 把节点格式化成一行描述。
   *
   * <p>格式：`[id] ClassName text="..." desc="..." bounds=[l,t][r,b] flags`
   * ——与 Aharou 完全一致，Agent 靠 id 引用节点、靠 bounds 判断位置。
   */
  static String describeNode(AccessibilityNodeInfo node, String id) {
    String className = node.getClassName() == null
        ? "?"
        : node.getClassName().toString();
    int dot = className.lastIndexOf('.');
    if (dot >= 0) {
      className = className.substring(dot + 1);
    }
    Rect rect = new Rect();
    node.getBoundsInScreen(rect);
    StringBuilder flags = new StringBuilder();
    if (node.isClickable()) flags.append(" clickable");
    if (node.isScrollable()) flags.append(" scrollable");
    if (node.isEditable()) flags.append(" editable");
    if (!node.isEnabled()) flags.append(" disabled");

    CharSequence textRaw = node.getText();
    CharSequence descRaw = node.getContentDescription();

    StringBuilder sb = new StringBuilder();
    sb.append('[').append(id).append("] ").append(className);
    if (textRaw != null && textRaw.length() > 0) {
      sb.append(" text=\"").append(truncate(textRaw.toString(), 60)).append('"');
    }
    if (descRaw != null && descRaw.length() > 0) {
      sb.append(" desc=\"").append(truncate(descRaw.toString(), 60)).append('"');
    }
    sb.append(" bounds=[")
        .append(rect.left)
        .append(',')
        .append(rect.top)
        .append("][")
        .append(rect.right)
        .append(',')
        .append(rect.bottom)
        .append(']');
    sb.append(flags);
    return sb.toString();
  }

  /** 遍历所有根节点，输出带 id 的树（Aharou dumpTree 的等价物）。 */
  static String dumpTree(AcsAccessibilityService service) {
    StringBuilder sb = new StringBuilder();
    int[] count = {0};
    for (AccessibilityNodeInfo root : service.rootNodes()) {
      walk(service, root, 0, sb, count);
    }
    if (count[0] == 0) {
      return "（界面为空——无障碍服务可能未就绪）";
    }
    return sb.toString();
  }

  private static void walk(
      AcsAccessibilityService service,
      AccessibilityNodeInfo node,
      int depth,
      StringBuilder sb,
      int[] count) {
    if (node == null || depth > MAX_DEPTH || count[0] >= MAX_NODES) {
      return;
    }
    String id = service.getNodeRegistry().put(node);
    for (int i = 0; i < depth; i++) {
      sb.append("  ");
    }
    sb.append(describeNode(node, id)).append('\n');
    count[0]++;
    for (int i = 0; i < node.getChildCount(); i++) {
      walk(service, node.getChild(i), depth + 1, sb, count);
    }
  }

  /** 按文字/描述查找节点（Aharou findNodes 的等价物），最多 50 个。 */
  static String findNodes(AcsAccessibilityService service, String query) {
    StringBuilder out = new StringBuilder();
    int[] found = {0};
    for (AccessibilityNodeInfo root : service.rootNodes()) {
      findWalk(service, root, query, out, found);
    }
    if (found[0] == 0) {
      return "没有找到包含「" + query + "」的节点";
    }
    return out.toString();
  }

  private static void findWalk(
      AcsAccessibilityService service,
      AccessibilityNodeInfo node,
      String query,
      StringBuilder out,
      int[] found) {
    if (node == null || found[0] >= 50) {
      return;
    }
    String text = node.getText() == null ? "" : node.getText().toString();
    String desc = node.getContentDescription() == null
        ? ""
        : node.getContentDescription().toString();
    if (containsIgnoreCase(text, query) || containsIgnoreCase(desc, query)) {
      String id = service.getNodeRegistry().put(node);
      out.append(describeNode(node, id)).append('\n');
      found[0]++;
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      findWalk(service, node.getChild(i), query, out, found);
    }
  }

  /** 窗口列表 → 文本行（Aharou windows 动作的等价物）。 */
  static String formatWindows(AcsAccessibilityService service) {
    List<Map<String, Object>> infos = service.windowInfos();
    if (infos.isEmpty()) {
      return "（没有可读的窗口）";
    }
    StringBuilder sb = new StringBuilder();
    for (Map<String, Object> info : infos) {
      List<String> parts = new ArrayList<>();
      for (Map.Entry<String, Object> e : info.entrySet()) {
        parts.add(e.getKey() + "=" + e.getValue());
      }
      sb.append(String.join(", ", parts)).append('\n');
    }
    return sb.toString();
  }

  /**
   * 前台应用（包名 + 类名）。
   *
   * <p>类名优先取最近的 WINDOW_STATE_CHANGED 事件——窗口根的类名常是容器
   * （如 `android.widget.FrameLayout`），事件里的才是真正的 Activity。
   * 拿不到时退回窗口根类名，并滤掉系统视图类。
   */
  static String currentActivity(AcsAccessibilityService service) {
    List<AcsAccessibilityService.RecordedEvent> events = service.snapshotEvents();
    for (int i = events.size() - 1; i >= 0; i--) {
      AcsAccessibilityService.RecordedEvent e = events.get(i);
      if (e.getType().contains("WINDOW_STATE_CHANGED")
          && e.getClassName() != null
          && !e.getClassName().isEmpty()) {
        return e.getClassName();
      }
    }
    String cls = service.foregroundPackage().getSecond();
    if (cls != null
        && !cls.isEmpty()
        && !cls.startsWith("android.")
        && !cls.startsWith("com.android.")) {
      return cls;
    }
    return null;
  }

  /** 截图 → base64 JPEG（按最长边缩放；Aharou bitmapToJpegBase64 的等价物）。 */
  static String bitmapToJpegBase64(Bitmap bitmap) {
    int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
    Bitmap scaled = bitmap;
    boolean created = false;
    try {
      if (longest > MAX_IMAGE_EDGE) {
        float ratio = (float) MAX_IMAGE_EDGE / longest;
        scaled =
            Bitmap.createScaledBitmap(
                bitmap,
                Math.max(1, (int) (bitmap.getWidth() * ratio)),
                Math.max(1, (int) (bitmap.getHeight() * ratio)),
                true);
        // createScaledBitmap 在目标尺寸与源相同时返回同一实例，只有真的新建了才回收。
        created = scaled != bitmap;
      }
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos);
      return android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
    } finally {
      // 缩放副本只用于编码：不回收的话每张截图都留下一份 1440px 位图（约 8MB），
      // 连拍几次就足以把 512MB 堆推高。调用方负责回收原始 bitmap。
      if (created && !scaled.isRecycled()) {
        scaled.recycle();
      }
    }
  }

  private static boolean containsIgnoreCase(String haystack, String needle) {
    if (haystack == null || needle == null) {
      return false;
    }
    return haystack.toLowerCase().contains(needle.toLowerCase());
  }

  private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }
}
