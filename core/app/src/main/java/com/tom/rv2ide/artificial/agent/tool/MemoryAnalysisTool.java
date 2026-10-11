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

package com.tom.rv2ide.artificial.agent.tool;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import org.json.JSONObject;

/**
 * AI 助手内存分析：一次性拉取进程内存画像 + 助手各子系统规模，供卡顿/内存排查。
 *
 * <p><b>为什么需要它</b>：用户实测过 AI 助手的 OOM 崩溃（Markdown 表格渲染 + 图片
 * base64 堆积，堆满 512MB）与多次卡顿。排查时只看 logcat 看不到「是谁在吃内存」——
 * Java 堆、native 堆、消息列表规模、Markdown 缓存、图片缓存各自多少，需要一个汇总视图。
 * 本工具把散落在 Runtime / Debug / 视图层的数字收成一份结构化报告：
 * <ul>
 *   <li>模型可直接读它定位「哪一块异常增长」，并给出修复建议
 *   <li>用户可把它贴进 issue / 让 AI 分析
 * </ul>
 *
 * <p><b>数据来源</b>：
 * <ul>
 *   <li>进程级：{@code Runtime}（Java 堆）、{@code Debug.getNativeHeapAllocatedSize()}
 *       （native，图片解码大头）、{@code ActivityManager.MemoryInfo}（系统总/可用）
 *   <li>助手级：{@link MemoryStatsRegistry}——各视图注册的画像（消息数、工具卡数、
 *       Markdown 缓存条目/权重、流式缓冲字符数、订阅者数）
 * </ul>
 *
 * <p>只读工具，无副作用；不申请任何权限（读自身进程内存无需权限）。
 */
public final class MemoryAnalysisTool extends BaseTool {

  /** 大对象判定阈值：Markdown 缓存权重超过它时在报告里提示。 */
  private static final int MARKDOWN_CACHE_WEIGHT_WARN = 500_000;

  /** 消息条目数告警阈值。 */
  private static final int MESSAGE_COUNT_WARN = 500;

  private final Context appContext;

  public MemoryAnalysisTool(Context context) {
    this.appContext = context.getApplicationContext();
  }

  @Override
  public String getName() {
    return com.tom.rv2ide.ai.tool.api.ToolNames.MEMORY_ANALYSIS;
  }

  @Override
  public String getDescription() {
    return "分析 AI 助手（本应用进程）的内存使用：Java 堆 / native 堆 / 系统可用内存，"
        + "以及各助手视图的消息数、工具卡片数、Markdown 缓存、流式缓冲、待发送队列等规模。"
        + "用于排查卡顿与内存膨胀（如长会话 OOM）。只读、无副作用。"
        + "参数：无。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.GENERIC;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    return true;
  }

  @Override
  public boolean isConcurrencySafe() {
    // 只读快照，不碰共享可变状态（registry 遍历用 COW 列表）。
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject().put("type", "object").put("properties", new JSONObject());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    if (context != null) {
      context.reportProgress("分析内存");
    }
    StringBuilder sb = new StringBuilder();

    // ── 进程级内存 ──
    Runtime runtime = Runtime.getRuntime();
    long javaTotal = runtime.totalMemory();
    long javaFree = runtime.freeMemory();
    long javaUsed = javaTotal - javaFree;
    long javaMax = runtime.maxMemory();

    long nativeAllocated = Debug.getNativeHeapAllocatedSize();
    long nativeSize = Debug.getNativeHeapSize();
    long nativeFree = Debug.getNativeHeapFreeSize();

    sb.append("== 进程内存（com.tom.rv2ide）==\n");
    sb.append("Java 堆: ").append(mb(javaUsed)).append(" 已用 / ")
        .append(mb(javaTotal)).append(" 已分配 / ")
        .append(mb(javaMax)).append(" 上限")
        .append("（").append(percent(javaUsed, javaMax)).append("%）\n");
    sb.append("Native 堆: ").append(mb(nativeAllocated)).append(" 已分配 / ")
        .append(mb(nativeSize)).append(" 预留")
        .append("（空闲 ").append(mb(nativeFree)).append("）\n");

    ActivityManager am =
        (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
    if (am != null) {
      ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
      am.getMemoryInfo(info);
      sb.append("系统内存: ").append(mb(info.totalMem)).append(" 总 / ")
          .append(mb(info.availMem)).append(" 可用")
          .append("（").append(percent(info.availMem, info.totalMem)).append("% 可用）");
      if (info.lowMemory) {
        sb.append(" ⚠ 系统报告低内存");
      }
      sb.append('\n');
    }
    sb.append("GC 计数: ").append(debugGcCount()).append('\n');

    // ── 视图级规模 ──
    java.util.List<MemoryStatsRegistry.ViewStats> views = MemoryStatsRegistry.snapshotAll();
    sb.append("\n== 助手视图（").append(views.size()).append(" 个存活）==\n");
    if (views.isEmpty()) {
      sb.append("(没有已注册的助手视图——面板未打开或已全部销毁)\n");
    }
    int totalMessages = 0;
    int totalToolCards = 0;
    int totalMarkdownWeight = 0;
    for (MemoryStatsRegistry.ViewStats v : views) {
      totalMessages += v.messageCount;
      totalToolCards += v.toolCardCount;
      totalMarkdownWeight += v.markdownCacheWeight;
      sb.append("[").append(v.viewLabel).append("]");
      if (!v.conversationId.isEmpty()) {
        sb.append(" 会话 ").append(shortId(v.conversationId));
      }
      sb.append('\n');
      sb.append("  消息条目 ").append(v.messageCount)
          .append(" · 工具卡片 ").append(v.toolCardCount)
          .append(" · 流式缓冲 ").append(v.streamingChars).append(" 字符\n");
      sb.append("  Markdown 缓存 ").append(v.markdownCacheEntries).append(" 条 / ")
          .append(v.markdownCacheWeight).append(" 权重")
          .append(" · 队列 ").append(v.queuedCount)
          .append(" · 附件 ").append(v.attachmentCount)
          .append(" · 订阅者 ").append(v.subscriberCount).append('\n');
    }

    // ── 判定与提示 ──
    sb.append("\n== 判定 ==\n");
    boolean warned = false;
    if (javaMax > 0 && javaUsed * 100L / javaMax >= 75L) {
      sb.append("⚠ Java 堆占用 ≥75%：接近上限，长会话/大图片容易触发 OOM。建议清理旧会话或压缩上下文。\n");
      warned = true;
    }
    if (nativeAllocated >= 256L * 1024 * 1024) {
      sb.append("⚠ Native 堆 ≥256MB：图片解码/位图缓存是常见大头，检查大图预览。\n");
      warned = true;
    }
    if (totalMessages > MESSAGE_COUNT_WARN) {
      sb.append("⚠ 消息条目合计 ").append(totalMessages)
          .append(" 条（> ").append(MESSAGE_COUNT_WARN).append("）：超长列表会拖慢回放与重组。\n");
      warned = true;
    }
    if (totalMarkdownWeight > MARKDOWN_CACHE_WEIGHT_WARN) {
      sb.append("⚠ Markdown 解析缓存权重合计 ").append(totalMarkdownWeight)
          .append("（> ").append(MARKDOWN_CACHE_WEIGHT_WARN).append("）：缓存偏大，考虑降低上限。\n");
      warned = true;
    }
    if (!warned) {
      sb.append("未见异常：各指标均在正常范围。\n");
    }

    return ok(sb.toString());
  }

  /** 触发一次 GC 计数读取；部分 ROM 上不可用，返回 -1 时说明。 */
  private static String debugGcCount() {
    try {
      // Debug.getRuntimeStat 需要 API 23+；minSdk 26，安全。
      String count = Debug.getRuntimeStat("art.gc.gc-count");
      String timeMs = Debug.getRuntimeStat("art.gc.gc-time");
      if (count == null) {
        return "(不可用)";
      }
      return count + " 次 · 累计 " + (timeMs == null ? "?" : timeMs) + " ms";
    } catch (Throwable t) {
      return "(读取失败: " + t.getClass().getSimpleName() + ")";
    }
  }

  private static String mb(long bytes) {
    return String.format(java.util.Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0);
  }

  private static String percent(long part, long whole) {
    if (whole <= 0) {
      return "?";
    }
    return String.valueOf(part * 100L / whole);
  }

  private static String shortId(String id) {
    return id.length() <= 8 ? id : id.substring(0, 8) + "…";
  }
}
