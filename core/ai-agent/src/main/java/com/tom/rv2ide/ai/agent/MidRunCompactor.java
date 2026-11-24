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

package com.tom.rv2ide.ai.agent;

import com.tom.rv2ide.ai.protocol.ModelMessage;
import java.util.List;

/**
 * 运行中途的历史压缩器：把最旧一段历史**摘要替换**，替代「直接裁掉」。
 *
 * <p><b>与入口压缩的分工</b>：入口压缩（{@code ConversationCompaction}）在运行开始前
 * 做一次并持久化；本接口解决的是「长任务跑到中段，早期历史被逐轮裁剪无声丢失」——
 * 裁剪（{@code RunContextManager.fit}）是本地、无网络的硬约束，仍是最后兜底；
 * mid-run 压缩把「丢信息」尽量变成「摘要信息」。
 *
 * <p><b>实现约定</b>：实现方通常做一次模型调用（可与入口压缩共用摘要管线）。
 * 返回 null/空串或抛出异常都表示「这次压不了」——调用方退回原有裁剪路径，
 * 运行绝不因摘要失败而中断。实现应尽快响应取消（不要在取消后还烧完整请求）。
 */
public interface MidRunCompactor {

  /**
   * 摘要一段最旧历史。
   *
   * @param segment 待摘要的消息（按时间顺序，不含系统提示词与本次用户请求）
   * @return 摘要文本；null 或空串表示放弃本次压缩
   */
  String summarize(List<ModelMessage> segment) throws Exception;
}
