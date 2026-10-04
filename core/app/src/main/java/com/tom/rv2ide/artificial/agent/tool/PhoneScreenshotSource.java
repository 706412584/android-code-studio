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
 */
final class PhoneScreenshotSource {

  private static final Logger log = LoggerFactory.getLogger(PhoneScreenshotSource.class);

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
   * @param invoker 工具调用入口（走执行器，含权限判定）；为 {@code null} 时明确报错
   * @param context 工具上下文
   * @return 截图结果；调用入口缺失、工具未注册或调用失败时 {@code ok=false}
   */
  static Shot capture(ToolInvoker invoker, ToolContext context) {
    if (invoker == null) {
      return new Shot(
          false,
          null,
          "工具调用入口不可用（编排类工具需由装配方注入 ToolInvoker）。");
    }
    try {
      // inline=false：只要落盘路径，不要 base64 图片负载——
      // 连拍/对比只暂存文件，逐张内联的图片会被立刻丢弃，白白浪费编码开销。
      JSONObject args = new JSONObject().put("inline", false);
      ToolResult result = invoker.invoke(ToolNames.PHONE_SCREENSHOT, args, context);
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
      return new Shot(true, file, "");
    } catch (Exception e) {
      log.warn("调用 phone_screenshot 失败", e);
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
