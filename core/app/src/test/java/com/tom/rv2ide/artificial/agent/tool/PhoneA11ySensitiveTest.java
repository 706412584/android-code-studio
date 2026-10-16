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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.tool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * a11y 写工具的敏感档判定（{@link PhoneA11yWriteTool#isSensitiveCall}）。
 *
 * <p><b>为什么这些断言值钱</b>：无障碍的 tap 可以点任意应用的任意位置——包括用户
 * 正在用的支付/转账界面。判定失灵的后果是「模型静默按下付款按钮」，而它在代码上
 * 只表现为一个 boolean 返回错值，测试是唯一能钉住它的地方。
 *
 * <p>另一个同类工具 {@link VdTool}（影子屏）已接入同一词表；本测试保证两者的
 * 保护强度一致——只测 a11y 侧，因为词表本身由 {@code ScreenActionSemantics} 负责
 * （它有自己的调用方，不在这里重复覆盖）。
 */
public final class PhoneA11ySensitiveTest {

  private static JSONObject args(String action, String label) throws Exception {
    JSONObject o = new JSONObject();
    o.put("action", action);
    if (label != null) {
      o.put("label", label);
    }
    return o;
  }

  private static final PhoneA11yWriteTool TOOL = new PhoneA11yWriteTool();

  // ---------------------------------------------------------------------------
  // 命中敏感词 → 敏感档
  // ---------------------------------------------------------------------------

  @Test
  public void tapWithPaymentLabelIsSensitive() throws Exception {
    // 「确认支付」是词表里最典型的词：按下后钱就出去了，退不回来。
    assertTrue(TOOL.isSensitiveCall(args("tap", "确认支付")));
  }

  @Test
  public void tapWithDeleteLabelIsSensitive() throws Exception {
    assertTrue(TOOL.isSensitiveCall(args("tap", "删除")));
  }

  @Test
  public void setTextWithTransferLabelIsSensitive() throws Exception {
    // setText 的 label 语义与 tap 相同（目标输入框的标签）。
    assertTrue(TOOL.isSensitiveCall(args("setText", "转账")));
  }

  @Test
  public void labelIsCaseInsensitiveForEnglishWords() throws Exception {
    // 词表含英文变体（pay now / delete / log out），大小写不敏感。
    assertTrue(TOOL.isSensitiveCall(args("tap", "PAY NOW")));
    assertTrue(TOOL.isSensitiveCall(args("tap", "Log Out")));
  }

  @Test
  public void sensitiveWordAsSubstringStillHits() throws Exception {
    // 按钮文字常带前后缀（「立即删除此条」），子串匹配才实用。
    assertTrue(TOOL.isSensitiveCall(args("tap", "立即删除此条记录")));
  }

  @Test
  public void actionIsCaseInsensitive() throws Exception {
    // 模型可能给 "TAP" 或 "Tap"；判定必须与 execute 的归一化一致（都用小写）。
    assertTrue(TOOL.isSensitiveCall(args("TAP", "确认支付")));
    assertTrue(TOOL.isSensitiveCall(args("SetText", "转账")));
  }

  // ---------------------------------------------------------------------------
  // 非敏感路径
  // ---------------------------------------------------------------------------

  @Test
  public void tapWithOrdinaryLabelIsNotSensitive() throws Exception {
    assertFalse(TOOL.isSensitiveCall(args("tap", "设置")));
    assertFalse(TOOL.isSensitiveCall(args("tap", "打开侧边栏")));
  }

  @Test
  public void tapWithoutLabelIsNotSensitive() throws Exception {
    // 未传 label 不构成敏感——但执行结果里会附一条告警（由 withLabelNote 负责），
    // 那是「提醒补 label」而不是「提级确认」。
    assertFalse(TOOL.isSensitiveCall(args("tap", null)));
    assertFalse(TOOL.isSensitiveCall(args("tap", "")));
  }

  @Test
  public void nonLabelActionsAreNeverSensitive() throws Exception {
    // swipe / key 没有「手指下是什么」这回事，即使传了 label 也不评估——
    // 与 VdTool 的 LABEL_ACTIONS 分流一致。
    assertFalse(TOOL.isSensitiveCall(args("swipe", "确认支付")));
    assertFalse(TOOL.isSensitiveCall(args("key", "删除")));
  }

  @Test
  public void nullArgsIsNotSensitive() {
    // 执行器在参数解析失败时会传 null；判定不能因此抛异常。
    assertFalse(TOOL.isSensitiveCall(null));
  }

  // ---------------------------------------------------------------------------
  // 契约
  // ---------------------------------------------------------------------------

  @Test
  public void toolStillNeedsConfirmation() {
    // 敏感档是**加强**确认，不是替代：普通确认必须保留（needsConfirmation=true），
    // 否则非敏感调用会变成无确认执行。
    assertTrue(TOOL.needsConfirmation());
  }

  @Test
  public void writeToolIsNotAllowedInReadonlyMode() {
    // 写工具在只读模式必须被拒（isSensitiveCall 只是确认强度，不是放行开关）。
    assertFalse(TOOL.isAllowedInReadonlyMode());
  }
}
