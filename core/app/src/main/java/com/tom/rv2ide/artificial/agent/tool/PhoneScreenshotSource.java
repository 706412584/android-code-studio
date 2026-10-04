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

import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.ToolRegistry;
import java.io.File;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通过工具注册表复用 {@code phone_screenshot} 截图，而不是自己重写截图逻辑。
 *
 * <p><b>为什么用反射</b>：{@code phone_screenshot} 由 phone-observe 负责实现，
 * 其具体类名/构造方式不在本工具的编译依赖里（且边界要求不碰它的源码）。
 * 本工具只依赖稳定的工具名常量与「返回 PNG 路径」这一输出契约，
 * 用 {@link ToolRegistry#get} 取到工具后反射调用 {@code execute}。
 *
 * <p>这样做的好处：截图能力只有一份实现，未来它换实现/换路径都不影响这里——
 * 只要工具名与「结果文本是 PNG 路径」的契约不变。
 */
final class PhoneScreenshotSource {

  private static final Logger log = LoggerFactory.getLogger(PhoneScreenshotSource.class);

  /** 与 {@code ToolNames.PHONE_SCREENSHOT} 一致，但用具名字面量避免跨模块耦合。 */
  private static final String SCREENSHOT_TOOL = "phone_screenshot";

  private PhoneScreenshotSource() {}

  /** 一次截图的结果。 */
  static final class Shot {
    final boolean ok;
    final File file;
    final String error;

    Shot(boolean ok, File file, String error) {
      this.ok = ok;
      this.file = file;
      this.error = error == null ? "" : error;
    }
  }

  /**
   * 调用 {@code phone_screenshot} 截图。
   *
   * @param registry 工具注册表
   * @param context 工具上下文
   * @return 截图结果；工具未注册或调用失败时 {@code ok=false}
   */
  static Shot capture(ToolRegistry registry, ToolContext context) {
    if (registry == null) {
      return new Shot(false, null, "工具注册表不可用");
    }
    BaseTool tool = registry.get(SCREENSHOT_TOOL);
    if (tool == null) {
      return new Shot(
          false,
          null,
          "截图工具 phone_screenshot 尚未注册（由 phone-observe 提供）。"
              + "在其落地前，本工具无法自动截图；可先用 phone_screenshot 手动截图后再对比。");
    }
    try {
      // inline=false：只要落盘路径，不要 base64 图片负载——
      // 连拍/对比只暂存文件，逐张内联的图片会被立刻丢弃，白白浪费编码开销。
      JSONObject args = new JSONObject().put("inline", false);
      Object result = tool.execute(args, context);
      String content = extractContent(result);
      if (content == null || content.trim().isEmpty()) {
        return new Shot(false, null, "截图工具返回为空");
      }
      File file = findPngPath(content);
      if (file == null) {
        return new Shot(false, null, "截图工具未返回 PNG 路径: " + abbreviate(content));
      }
      return new Shot(true, file, "");
    } catch (Exception e) {
      log.warn("调用 phone_screenshot 失败", e);
      return new Shot(false, null, "截图失败: " + e.getMessage());
    }
  }

  private static String extractContent(Object result) {
    if (result == null) {
      return null;
    }
    if (result instanceof com.tom.rv2ide.ai.tool.api.ToolResult) {
      com.tom.rv2ide.ai.tool.api.ToolResult tr =
          (com.tom.rv2ide.ai.tool.api.ToolResult) result;
      return tr.isError() ? null : tr.getContent();
    }
    return String.valueOf(result);
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
