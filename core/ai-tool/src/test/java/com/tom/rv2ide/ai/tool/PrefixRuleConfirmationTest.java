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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolCall;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 执行器的双键判定：逐字全文规则与「同类命令前缀」规则并存。
 *
 * <p>钉住的语义：
 * <ul>
 *   <li>前缀规则只放行同一命令族（{@code git status} → {@code git status -sb}），
 *       不放行其他命令（{@code git push --force}）。
 *   <li>没有任何规则时一律询问，绝不默认按前缀放行。
 *   <li>后端不同（termux → root）时前缀规则不命中——防提权。
 * </ul>
 */
final class PrefixRuleConfirmationTest {

  /** 以真实工具名 shell_execute 注册的探针：记录是否真正执行。 */
  private static final class ShellProbe extends BaseTool {
    boolean executed = false;

    @Override
    public String getName() {
      return ToolNames.SHELL_EXECUTE;
    }

    @Override
    public String getDescription() {
      return "shell 探针";
    }

    @Override
    public ToolCategory getCategory() {
      return ToolCategory.SYSTEM;
    }

    @Override
    public boolean needsConfirmation() {
      return true;
    }

    @Override
    public JSONObject getParameters() {
      return new JSONObject();
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
      executed = true;
      return ok("ran");
    }
  }

  /** 持久化规则用 Set 模拟；询问一律拒绝并计数，用于区分「规则命中」与「被询问」。 */
  private static final class RuleSettings implements ToolSettingsPort {
    final Set<String> rules = new HashSet<>();
    int dangerousCalls = 0;

    @Override
    public String getPermissionMode() {
      return PERMISSION_CONFIRM;
    }

    @Override
    public boolean hasDangerousToolRule(String ruleKey) {
      return rules.contains(ruleKey);
    }

    @Override
    public boolean confirmDangerousTool(String conversationId, String toolName, String arguments) {
      dangerousCalls++;
      return false; // 没有 UI：询问即拒绝
    }
  }

  private static ToolContext contextWith(RuleSettings settings) {
    return ToolContext.builder().homePath("").settings(settings).conversationId("conv").build();
  }

  private static ToolResult run(ShellProbe probe, RuleSettings settings, String argsJson) {
    ToolRegistry registry = new ToolRegistry();
    registry.register(probe);
    ToolExecutor executor =
        new ToolExecutor(registry, new ToolPermissionService(settings, registry), null);
    return executor.execute(
        new ToolCall("c1", ToolNames.SHELL_EXECUTE, argsJson), contextWith(settings));
  }

  private static String prefixKey(String argsJson) {
    return ToolPermissionRule.prefixKeyFor(ToolNames.SHELL_EXECUTE, argsJson);
  }

  @Test
  void prefixRuleAllowsSameCommandFamily() {
    ShellProbe probe = new ShellProbe();
    RuleSettings settings = new RuleSettings();
    settings.rules.add(prefixKey("{\"command\":\"git status\"}"));

    ToolResult result = run(probe, settings, "{\"command\":\"git status -sb\"}");

    assertFalse(result.isError(), "同族命令应被前缀规则放行: " + result.getContent());
    assertTrue(probe.executed);
    assertEquals(0, settings.dangerousCalls, "命中规则不应再弹窗");
  }

  @Test
  void prefixRuleDoesNotAllowDifferentCommand() {
    ShellProbe probe = new ShellProbe();
    RuleSettings settings = new RuleSettings();
    settings.rules.add(prefixKey("{\"command\":\"git status\"}"));

    ToolResult result = run(probe, settings, "{\"command\":\"git push origin main --force\"}");

    assertTrue(result.isError(), "git status 的前缀授权不得放行 git push --force");
    assertFalse(probe.executed);
    assertEquals(1, settings.dangerousCalls, "未命中规则必须询问");
  }

  @Test
  void noRuleAlwaysAsks() {
    ShellProbe probe = new ShellProbe();
    RuleSettings settings = new RuleSettings();

    ToolResult result = run(probe, settings, "{\"command\":\"git status\"}");

    assertTrue(result.isError());
    assertEquals(1, settings.dangerousCalls, "没有规则时绝不默认按前缀放行");
  }

  @Test
  void fullTextRuleDoesNotCoverVariants() {
    ShellProbe probe = new ShellProbe();
    RuleSettings settings = new RuleSettings();
    settings.rules.add(ToolPermissionRule.keyFor(ToolNames.SHELL_EXECUTE, "{\"command\":\"git status\"}"));

    assertFalse(run(probe, settings, "{\"command\":\"git status\"}").isError(),
        "逐字相同应命中全文规则");

    ToolResult variant = run(probe, settings, "{\"command\":\"git status -sb\"}");
    assertTrue(variant.isError(), "全文规则不覆盖命令变体（这是前缀规则存在的意义）");
  }

  @Test
  void prefixRuleIsBackendScoped() {
    ShellProbe probe = new ShellProbe();
    RuleSettings settings = new RuleSettings();
    settings.rules.add(prefixKey("{\"command\":\"git status\",\"backend\":\"termux\"}"));

    ToolResult result =
        run(probe, settings, "{\"command\":\"git status\",\"backend\":\"root\"}");

    assertTrue(result.isError(), "termux 的前缀授权不得在 root 后端生效（提权路径）");
    assertFalse(probe.executed);
  }
}
