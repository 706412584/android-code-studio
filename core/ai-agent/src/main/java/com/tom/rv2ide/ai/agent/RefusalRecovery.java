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

package com.tom.rv2ide.ai.agent;

import com.tom.rv2ide.ai.protocol.ModelConfig;

/**
 * 「模型拒绝 → 原样重发」的编排器。
 *
 * <p><b>职责边界</b>:本类只管**运行之间**的决策——上一次结果是否为拒绝、
 * 要不要重跑。单次运行内部的工具循环仍由 {@link AgentSession}
 * 负责,传输层重试(429/超时)仍由协议层 {@code RetryPolicy} 负责。三层各管
 * 一段,互不侵入。
 *
 * <p><b>策略:原地重发</b>。拒绝翻盘主要靠系统提示词(宿主已内置执行约束),
 * 工具层不做任何「策略升级」——不换模型、不改提示词,重试就是用**同样的参数**
 * 再请求一次(采样随机性本身就有机会翻盘)。预算上限见 {@link Policy}。
 * 每档跑完用 {@link RefusalDetector} 复检;仍拒绝且预算耗尽时,原样返回
 * 最后一次结果,<b>不吞掉模型的实际回复</b>。
 *
 * <p>纯 Java、无 Android 依赖;宿主持有并注入 {@link AttemptRunner}
 * (ConversationRunner / AgentOrchestrator 各自实现一次),可用脚本化 runner 单测。
 *
 * <p><b>已知局限</b>:触发器是「输出命中拒绝短语」的文本匹配,**不区分**「模型在打太极」
 * 与「模型在拒绝一件真正越界的事」。对窃取凭据、破坏系统、写恶意载荷等本该拒绝的危险
 * 请求,本机制同样会重试,直到输出不再命中拒绝短语。这是宿主选择接入本功能时承担的
 * 刻意取舍;宿主侧的 {@code refusal_recovery_enabled} 设置开关一旦关闭,本类退化为
 * 「只首跑一次」,上述重试**完全不发生**,行为回到接入前。
 */
public final class RefusalRecovery {

  /**
   * 一次「按策略跑一轮完整会话」的实现,由宿主注入。
   *
   * <p>宿主在自己的实现里负责:新建 AgentSession、按 config 构建系统提示词、
   * 挂监听器、跑 {@code session.run},并把结果原样返回。事件监听复用首跑那一个
   * (重试期间的进度/流式增量走同一条事件流,UI 与持久化无需感知「这是第几次尝试」)。
   */
  public interface AttemptRunner {
    /**
     * @param config 本轮使用的模型配置(原地重发:恒等于首跑的 config)
     * @param listener 事件监听(可为 null)
     * @return 本轮运行的结果
     */
    AgentRunResult run(ModelConfig config, AgentEvent.Listener listener);
  }

  /** 策略与预算。字段均可保持默认;宿主持有并注入。 */
  public static final class Policy {
    /**
     * 总尝试次数上限(含首跑)。默认 3,最小 1;上界 5 由宿主读设置时钳制
     * (设置项的可选范围 1-5,防止外部改写 prefs 绕过 UI 约束)。
     */
    public int maxAttempts = 3;

    /** 是否播报重试进度(默认开;关掉时静默重试)。 */
    public boolean announceProgress = true;
  }

  private final RefusalDetector detector;
  private final AttemptRunner runner;
  private final Policy policy;

  public RefusalRecovery(RefusalDetector detector, AttemptRunner runner, Policy policy) {
    this.detector = detector == null ? new RefusalDetector() : detector;
    this.runner = runner;
    this.policy = policy == null ? new Policy() : policy;
  }

  /** 便捷构造:默认检测器 + 默认策略。 */
  public static RefusalRecovery defaultWith(AttemptRunner runner) {
    return new RefusalRecovery(new RefusalDetector(), runner, new Policy());
  }

  /** 关闭:首跑即返回,行为等价于未接入本功能。 */
  public static RefusalRecovery disabled(AttemptRunner runner) {
    return new RefusalRecovery(RefusalDetector.disabled(), runner, new Policy());
  }

  /**
   * 执行带拒绝恢复的运行。
   *
   * @param config 模型配置(全部尝试共用——原地重发)
   * @param listener 事件监听;可为 null(静默执行、不播报)
   * @return 最终一次运行的结果(可能是拒绝文本——预算耗尽时如实返回)
   */
  public AgentRunResult execute(ModelConfig config, AgentEvent.Listener listener) {
    AgentRunResult result = runner.run(config, listener);
    int attempts = 1;
    RefusalDetector.Verdict verdict = detector.detect(result);

    int limit = Math.max(1, policy.maxAttempts);
    boolean announced = false;
    while (verdict.isRefused() && attempts < limit) {
      // 只在**首次**检测到拒绝时播报一条(不带次数):逐次 "n/N" 会在 UI 堆出
      // 一串提示行,用户要的反馈是「正在重试」,而新尝试的流式正文本身就是进展。
      if (!announced) {
        announce(listener, "检测到模型拒绝(" + verdict.getReason() + "),原地重试中…");
        announced = true;
      }
      result = runner.run(config, listener);
      attempts++;
      verdict = detector.detect(result);
    }

    if (verdict.isRefused()) {
      announce(listener, "已重试 " + (attempts - 1) + " 次,模型仍拒绝执行,返回最后一次回复");
    }
    return result;
  }

  private void announce(AgentEvent.Listener listener, String message) {
    if (listener != null && policy.announceProgress) {
      listener.onEvent(AgentEvent.progress(message));
    }
  }
}
