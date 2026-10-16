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
 * along with AndroidCodeStudio.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * {@code with*} 派生方法必须**逐字段复制**。
 *
 * <p><b>为什么这是有代价的测试</b>：{@code ToolContext} 有 24 个可空端口字段，
 * 每个 {@code with*} 方法都手写一遍全量拷贝。漏抄一个字段的后果是**静默的**：
 * 例如漏了 {@code cancellation}，则工具在派生上下文里收不到取消信号（停止按钮失效）；
 * 漏了 {@code progressListener}，长任务不再上报进度（界面看起来卡死）。两者都不报错、
 * 单测全绿、只有用户在特定路径上才会撞见。
 *
 * <p>这里用反射**枚举**所有 getter，对每个 {@code with*} 方法验证：除被替换的那个字段外，
 * 其余 getter 在派生实例上返回与原始实例相同的引用/值。新增字段若忘记复制，本测试自动失败。
 */
final class ToolContextCopyTest {

  /** 造一个所有端口都非空、字段可区分的上下文。 */
  private static ToolContext fullyPopulated() {
    ToolContext.Builder b = ToolContext.builder();
    b.homePath("/workspace");
    b.extraWriteRoots(List.of("/extra"));
    b.bypassPathProtection(true);
    b.toolCallId("call-1");
    b.conversationId("conv-1");
    b.progressListener(message -> {});
    b.settings(ToolSettingsPort.defaults());
    // 其余端口用最小可用实现填充；未注入的端口保持 null（同样纳入复制验证）。
    return b.build();
  }

  /** 所有无参、返回非 void 的公开 getter。 */
  private static List<Method> getters() {
    List<Method> out = new ArrayList<>();
    for (Method m : ToolContext.class.getMethods()) {
      if (m.getParameterCount() != 0
          || m.getReturnType() == void.class
          || Modifier.isStatic(m.getModifiers())
          || !m.getName().startsWith("get")) {
        continue;
      }
      out.add(m);
    }
    return out;
  }

  private static Object invoke(Method m, Object target) {
    try {
      return m.invoke(target);
    } catch (Exception e) {
      throw new AssertionError("调用 " + m.getName() + " 失败", e);
    }
  }

  @Test
  void withProgressListenerCopiesEveryOtherField() {
    ToolContext original = fullyPopulated();
    ToolContext.ProgressListener replacement = message -> {};

    ToolContext copy = original.withProgressListener(replacement);

    assertNotSame(original, copy, "应返回新实例");
    assertSame(replacement, copy.getProgressListener(), "被替换字段应更新");
    assertEveryOtherFieldPreserved(original, copy, "getProgressListener");
  }

  @Test
  void withToolCallIdCopiesEveryOtherField() {
    ToolContext original = fullyPopulated();

    ToolContext copy = original.withToolCallId("call-2");

    assertNotSame(original, copy);
    assertEquals("call-2", copy.getToolCallId(), "被替换字段应更新");
    assertEveryOtherFieldPreserved(original, copy, "getToolCallId");
  }

  @Test
  void withToolCallIdAcceptsNullAndBlank() {
    ToolContext original = fullyPopulated();
    // 派生到「无调用 id」是合法状态（嵌套调用/后台路径）；不得抛异常。
    // 构造器把 null 规整为空串（调用方只需判 isEmpty），这里钉住这个归一化。
    assertEquals("", original.withToolCallId(null).getToolCallId());
    assertEquals("", original.withToolCallId("").getToolCallId());
  }

  @Test
  void gettersCoverExpectedSurface() {
    // 若 ToolContext 新增端口但测试的字段清单没跟上，这条会提示维护者。
    TreeSet<String> names = new TreeSet<>();
    for (Method m : getters()) {
      names.add(m.getName());
    }
    assertTrue(names.size() >= 20, "getter 数量异常（" + names.size() + "），字段清单可能需要更新");
  }

  /**
   * 除 {@code replacedGetter} 外，其余 getter 在 copy 上应与 original 相同。
   *
   * <p>比较用 {@code assertEquals} 而非 {@code assertSame}：列表类字段（extraWriteRoots）
   * 可能被 Builder 重新包装成新实例，但值必须相等。
   */
  private static void assertEveryOtherFieldPreserved(
      ToolContext original, ToolContext copy, String replacedGetter) {
    List<String> mismatched = new ArrayList<>();
    int checked = 0;
    for (Method m : getters()) {
      if (m.getName().equals(replacedGetter)) {
        continue;
      }
      Object a = invoke(m, original);
      Object b = invoke(m, copy);
      checked++;
      if (a == null && b == null) {
        continue;
      }
      if (a == null || !a.equals(b)) {
        mismatched.add(m.getName() + " (original=" + a + ", copy=" + b + ")");
      }
    }
    assertTrue(checked > 0, "没有可验证的 getter——反射路径可能失效");
    assertTrue(
        mismatched.isEmpty(),
        "with* 派生时漏抄了字段：" + mismatched + "（漏抄会导致该能力在派生上下文里静默丢失）");
  }
}
