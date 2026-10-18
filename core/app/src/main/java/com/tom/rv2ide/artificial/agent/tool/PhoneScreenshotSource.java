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

import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.ToolInvoker;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 复用 {@code phone_screenshot} 截图，而不是自己重写截图逻辑。
 *
 * <p><b>为什么走 {@link ToolInvoker} 而不是直接取工具调用</b>：截图工具虽无害，
 * 但直接调用 {@code BaseTool.execute} 会绕过执行器的权限判定。统一走调用入口后，
 * 截图与场景/连拍里的其它子调用行为一致——权限规则对它们一视同仁。
 *
 * <p>好处是截图能力只有一份实现：未来它换实现/换路径都不影响这里——
 * 只要工具名与「结果文本是 PNG 路径」的契约不变。
 *
 * <p><b>两种来源（{@code display}）</b>：
 * <ul>
 *   <li>{@code device}（默认）— {@code phone_screenshot}，截用户当前看到的物理屏
 *   <li>{@code vscreen} — {@code vscreen action=shot inline=false}，截影子屏（虚拟显示屏）。
 *       跑自动化时不占用用户主屏，也不受用户操作干扰。需先用
 *       {@code vscreen action=start} 建屏、{@code launch} 把目标 App 开进去。
 * </ul>
 * 两者的返回契约完全一致（尺寸字段 + 单独成行的路径），因此基线保存、回归对比、
 * 动作连拍、OCR 都只需透传 {@code display} 参数，无需各自分叉。
 */
final class PhoneScreenshotSource {

  private static final Logger log = LoggerFactory.getLogger(PhoneScreenshotSource.class);

  /** 截物理屏（用户当前看到的画面）。 */
  static final String DISPLAY_DEVICE = "device";
  /** 截影子屏（vscreen 创建的虚拟显示屏）。 */
  static final String DISPLAY_VSCREEN = "vscreen";

  private PhoneScreenshotSource() {}

  /**
   * 把调用方给的 {@code display} 参数归一成两种来源之一。
   *
   * <p>空值/未知值一律回落 {@code device}：工具参数由模型生成，拼错不该让整次调用失败，
   * 而「截物理屏」是用户最可能的本意。
   */
  static String normalizeDisplay(String raw) {
    if (raw == null) {
      return DISPLAY_DEVICE;
    }
    String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
    return DISPLAY_VSCREEN.equals(value) ? DISPLAY_VSCREEN : DISPLAY_DEVICE;
  }

  /** 一次截图的结果。 */
  static final class Shot {
    final boolean ok;
    final File file;
    final String error;
    /** 屏幕尺寸（物理像素）；解析不到时为 0。 */
    final int screenWidth;
    final int screenHeight;
    /** 落盘图片尺寸；解析不到时为 0。maxDimension=0 且未触发内联体积收缩时与屏幕尺寸相同。 */
    final int outputWidth;
    final int outputHeight;

    Shot(boolean ok, File file, String error) {
      this(ok, file, error, 0, 0, 0, 0);
    }

    Shot(boolean ok, File file, String error,
        int screenWidth, int screenHeight, int outputWidth, int outputHeight) {
      this.ok = ok;
      this.file = file;
      this.error = error == null ? "" : error;
      this.screenWidth = screenWidth;
      this.screenHeight = screenHeight;
      this.outputWidth = outputWidth;
      this.outputHeight = outputHeight;
    }
  }

  /**
   * 调用 {@code phone_screenshot} 截图。
   *
   * @param invoker 工具调用入口（走执行器，含权限判定）；为 {@code null} 时明确报错
   * @param context 工具上下文
   * @return 截图结果；调用入口缺失、工具未注册或调用失败时 {@code ok=false}
   */
  static Shot capture(ToolInvoker invoker, ToolContext context) {
    return capture(invoker, context, null);
  }

  /**
   * 同上，但可附加截图工具的其它参数（如 {@code maxDimension:0} 要原分辨率）。
   * 固定项 {@code inline=false} 不可被 extraArgs 覆盖。
   *
   * <p>{@code extraArgs} 里若带 {@code display=vscreen}，改走影子屏截图；该键会从传给
   * 子工具的参数字里剔除——{@code phone_screenshot} 不认识它。
   */
  static Shot capture(ToolInvoker invoker, ToolContext context, JSONObject extraArgs) {
    if (invoker == null) {
      return new Shot(
          false,
          null,
          "工具调用入口不可用（编排类工具需由装配方注入 ToolInvoker）。");
    }
    String display = DISPLAY_DEVICE;
    try {
      // inline=false：只要落盘路径，不要 base64 图片负载——
      // 连拍/对比只暂存文件，逐张内联的图片会被立刻丢弃，白白浪费编码开销。
      // 先放 extraArgs 的非 inline/display 键，最后强制 inline=false（不允许调用方覆盖）。
      JSONObject args = new JSONObject();
      if (extraArgs != null) {
        java.util.Iterator<String> keys = extraArgs.keys();
        while (keys.hasNext()) {
          String key = keys.next();
          if ("inline".equals(key)) {
            continue; // 固定项：由本方法强制
          }
          if ("display".equals(key)) {
            display = normalizeDisplay(extraArgs.optString("display", null));
            continue; // 来源由本方法决定，不透传给子工具
          }
          args.put(key, extraArgs.get(key));
        }
      }
      args.put("inline", false);

      ToolResult result;
      if (DISPLAY_VSCREEN.equals(display)) {
        // 影子屏只有 action=shot 一条截图路径，参数名也不同：maxDimension 等缩放项
        // 在 inline=false 下本来就不生效（直接返回原图路径），故只透传 grid/ocr 之外无关项。
        JSONObject vdArgs = new JSONObject();
        vdArgs.put("action", "shot");
        vdArgs.put("inline", false);
        if (args.has("grid")) {
          vdArgs.put("grid", args.opt("grid"));
        }
        result = invoker.invoke(ToolNames.VSCREEN, vdArgs, context);
      } else {
        result = invoker.invoke(ToolNames.PHONE_SCREENSHOT, args, context);
      }
      if (result == null) {
        return new Shot(false, null, "截图工具返回为空");
      }
      if (result.isError()) {
        return new Shot(false, null, "截图失败: " + abbreviate(result.getContent()));
      }
      String content = result.getContent();
      if (content == null || content.trim().isEmpty()) {
        return new Shot(false, null, "截图工具返回为空");
      }
      File file = findPngPath(content);
      if (file == null) {
        return new Shot(false, null, "截图工具未返回 PNG 路径: " + abbreviate(content));
      }
      int[] screen = PhoneOcrText.parseDims(content, "屏幕尺寸");
      int[] output = PhoneOcrText.parseDims(content, "输出尺寸");
      return new Shot(true, file, "", screen[0], screen[1], output[0], output[1]);
    } catch (Exception e) {
      log.warn("调用截图工具失败（display={}）", display, e);
      return new Shot(false, null, "截图失败: " + e.getMessage());
    }
  }

  /** 从结果文本里找出 PNG 绝对路径。 */
  private static File findPngPath(String content) {
    for (String line : content.split("\\r?\\n")) {
      String candidate = line.trim();
      if (candidate.isEmpty()) {
        continue;
      }
      int space = candidate.indexOf(' ');
      if (space > 0) {
        candidate = candidate.substring(0, space);
      }
      if (candidate.endsWith(".png")) {
        File file = new File(candidate);
        if (file.exists()) {
          return file;
        }
      }
    }
    // 兜底：整段文本就是一个路径
    String whole = content.trim();
    if (whole.endsWith(".png")) {
      File file = new File(whole);
      if (file.exists()) {
        return file;
      }
    }
    return null;
  }

  private static String abbreviate(String value) {
    if (value == null) {
      return "";
    }
    return value.length() <= 120 ? value : value.substring(0, 120) + "…";
  }
}
