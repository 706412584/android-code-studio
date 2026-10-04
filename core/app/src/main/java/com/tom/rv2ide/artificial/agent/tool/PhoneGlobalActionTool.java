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
import com.tom.rv2ide.ai.tool.ShellBackendRegistry;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

/**
 * 发送全局按键（{@code input keyevent}）：返回 / 主页 / 最近任务 / 回车 / 删除等。
 *
 * <p><b>为什么必须限定白名单</b>：{@code input keyevent} 能发送任意键码，包括
 * {@code KEYCODE_POWER}（关机键）、{@code KEYCODE_CAMERA}、厂商私有键，
 * 甚至组合触发系统级对话框。一个自动化的 UI 测试工具不应具备「关机」「拍屏」
 * 这类与被测应用无关、且会干扰用户设备的能力。因此这里<b>只放行导航与文本编辑
 * 相关的一组键</b>，其余一律拒绝并列出可用键。
 *
 * <p>接受键名（大小写不敏感，如 {@code BACK}）或数字键码，但数字键码也必须落在
 * 白名单内——否则数字形式会绕过白名单。
 *
 * <p>需要 adb 级权限（Shizuku 后端）；未启用时返回明确错误。
 */
public final class PhoneGlobalActionTool extends BaseTool {

  private static final long TIMEOUT_MS = 10_000L;

  /**
   * 白名单：键名 → keycode。
   *
   * <p>用 {@link LinkedHashMap} 固定顺序，使错误信息里的可用键列表稳定、可预期。
   * 只收录「导航 + 文本编辑 + 音量/唤醒」，不收录 POWER/CAMERA 等设备级按键。
   */
  private static final Map<String, Integer> WHITELIST = new LinkedHashMap<>();

  static {
    WHITELIST.put("BACK", 4);
    WHITELIST.put("HOME", 3);
    WHITELIST.put("APP_SWITCH", 187);
    WHITELIST.put("MENU", 82);
    WHITELIST.put("ENTER", 66);
    WHITELIST.put("DEL", 67);
    WHITELIST.put("FORWARD_DEL", 112);
    WHITELIST.put("TAB", 61);
    WHITELIST.put("ESCAPE", 111);
    WHITELIST.put("DPAD_UP", 19);
    WHITELIST.put("DPAD_DOWN", 20);
    WHITELIST.put("DPAD_LEFT", 21);
    WHITELIST.put("DPAD_RIGHT", 22);
    WHITELIST.put("DPAD_CENTER", 23);
    WHITELIST.put("MOVE_HOME", 122);
    WHITELIST.put("MOVE_END", 123);
    WHITELIST.put("PAGE_UP", 92);
    WHITELIST.put("PAGE_DOWN", 93);
    WHITELIST.put("VOLUME_UP", 24);
    WHITELIST.put("VOLUME_DOWN", 25);
    WHITELIST.put("VOLUME_MUTE", 164);
    WHITELIST.put("WAKEUP", 224);
  }

  private final ShellBackendRegistry registry;

  public PhoneGlobalActionTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.PHONE_GLOBAL_ACTION;
  }

  @Override
  public String getDescription() {
    return "发送全局按键。用于返回上一页、回主页、打开最近任务、提交（回车）、删除字符等。"
        + "只允许一组白名单按键："
        + String.join("/", WHITELIST.keySet())
        + "。传键名（大小写不敏感）或键码数字均可；白名单之外的按键会被拒绝。"
        + "需要 adb 级权限（Shizuku 后端）。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.SYSTEM;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.PHONE_CONTROL;
  }

  @Override
  public boolean needsConfirmation() {
    // 与 shell_execute 同级：执行 adb 级命令（input keyevent），需经确认门。
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "key",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "按键名（如 BACK / HOME / APP_SWITCH / ENTER / DEL）"
                                + "或白名单内的键码数字。可用键："
                                + String.join(", ", WHITELIST.keySet()))))
        .put("required", new org.json.JSONArray().put("key"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String key = input.optString("key", "").trim();
    if (key.isEmpty()) {
      return error("key 不能为空。可用键：" + String.join(", ", WHITELIST.keySet()));
    }

    String resolved = resolveKey(key);
    if (resolved == null) {
      return error(
          "不支持或不在白名单内的按键: "
              + key
              + "。可用键："
              + String.join(", ", WHITELIST.keySet()));
    }

    if (context != null) {
      context.reportProgress("按键: " + resolved);
    }

    PhoneUiSelector.Exec exec =
        PhoneUiSelector.exec(registry, "input keyevent " + resolved, TIMEOUT_MS);
    if (!exec.ok) {
      return error(exec.error);
    }
    return ok("已发送按键 " + resolved + "（keycode " + WHITELIST.get(resolved) + "）。");
  }

  /**
   * 把输入解析为白名单内的规范键名。
   *
   * <p>接受键名或数字键码；两者都必须命中白名单。命中返回规范名，否则返回 null。
   */
  static String resolveKey(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      return null;
    }
    String trimmed = raw.trim();
    String upper = trimmed.toUpperCase(Locale.ROOT);
    if (WHITELIST.containsKey(upper)) {
      return upper;
    }
    // 数字键码：仅当它对应白名单内的某个键时才放行，避免用数字绕过白名单。
    try {
      int code = Integer.parseInt(trimmed);
      for (Map.Entry<String, Integer> entry : WHITELIST.entrySet()) {
        if (entry.getValue() == code) {
          return entry.getKey();
        }
      }
    } catch (NumberFormatException ignored) {
      // 不是数字，按未知键名处理
    }
    return null;
  }

  /** 供测试/文档使用：白名单键名列表（副本）。 */
  static List<String> allowedKeys() {
    return new ArrayList<>(WHITELIST.keySet());
  }
}
