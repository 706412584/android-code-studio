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

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import org.json.JSONObject;

/**
 * 读取 IDE 编辑器当前状态：当前文件、光标、选区、已打开文件、未保存修改。
 *
 * <p><b>这是 ACS 相对通用编码 agent 的独有能力</b>：通用 agent 只能假设「磁盘内容就是
 * 用户看到的内容」，而编辑器缓冲可能领先磁盘（未保存修改）——用户说「帮我改我正在看的
 * 这个方法」时，通用 agent 只能要求用户手动描述文件与位置，本工具直接给出答案。
 *
 * <p><b>为什么无参数</b>：状态是一个整体（文件 + 光标 + 选区 + 已打开列表），
 * 让模型一次拿到全貌，比逐项查询少一轮往返，也避免「查了文件忘了查修改状态」。
 *
 * <p>与 {@link EditorSessionPort} 的约定一致：端口内 0 基，本工具对外输出 1 基。
 */
public final class EditorStateTool extends BaseTool {

  @Override
  public String getName() {
    return ToolNames.EDITOR_STATE;
  }

  @Override
  public String getDescription() {
    return "读取 IDE 编辑器的当前状态：当前打开的文件路径、光标位置（行/列）、选中范围（若有）、"
        + "全部已打开文件列表、每个文件是否有未保存修改。"
        + "用于回答「我正在看的文件是哪个」「光标在哪」——用户说「帮我改我正在看的这个方法」时，"
        + "先用本工具确定文件与位置，不要要求用户手动描述。"
        + "注意：需要项目已在编辑器中打开（编辑器会话存活）；若用户在主页且编辑器已关闭，"
        + "会明确报错，此时应改用 file_read 等工具直接读文件。"
        + "行列号从 1 开始。只读，无副作用。";
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
    // 纯读取视图状态，不改任何文件，只读模式下必须放行。
    return true;
  }

  @Override
  public boolean isConcurrencySafe() {
    // 纯读；底层读取切主线程串行化，并发调用无共享写状态。
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    // 无参数：状态整体返回，让模型一次拿到全貌而不是逐项问。
    return new JSONObject().put("type", "object").put("properties", new JSONObject());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    EditorSessionPort port = context == null ? null : context.getEditorSession();
    if (port == null) {
      return error(
          "当前环境未接入编辑器会话，无法读取编辑器状态。"
              + "请改用 file_read / glob / grep 等工具直接读文件。");
    }
    String reason = port.unavailableReason();
    if (!reason.isEmpty()) {
      return error(reason);
    }
    EditorSessionPort.Snapshot snap = port.snapshot();
    if (snap.isUnavailable()) {
      // 判定与读取之间会话消失的竞态：如实报错，不返回空快照（空快照会被模型读成
      // 「编辑器开着但没有文件」，与事实不符——同 DiagnosticsPort 对静默失败的防范）。
      return error(snap.getUnavailableReason());
    }
    return ok(EditorStateMessages.format(context, snap));
  }
}
