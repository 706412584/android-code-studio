/*
 * This file is part of AndroidCodeStudio.
 *
 * Ported from LineCode Pro (https://github.com/LangLang03/LineCodePro),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

package com.tom.rv2ide.ai.protocol;

/**
 * 流空闲看门狗：纯状态机，判断一条 SSE 流是否已经卡死。
 *
 * <p><b>为什么需要它（{@code readTimeout} 为什么不够）</b>：{@code setReadTimeout} 约束的是
 * 「两次 socket 读取之间」的间隔。某些中转（relay）会稳定地每几秒吐一个 thinking delta
 * 却永远不给出最终结果——每个 chunk 都落在超时窗口内，readTimeout 一次都不会触发，
 * 用户于是面对一个永远转圈的界面。另一类更隐蔽：连接建立后一个字节都不发，
 * 此时阻塞在 {@code readLine} 上，只有首个 token 的独立预算能发现它。
 * 所以判定依据不是「socket 是否活着」，而是「<b>内容是否在推进</b>」。</p>
 *
 * <p><b>为什么分相位而不是一个统一阈值</b>：prefill 阶段的正常耗时与流中两个 chunk
 * 的正常间隔差一个数量级——长上下文 + 慢中转的首 token 等 60s 是常态，而流中间隔
 * 60s 基本可以断定已经断了。用同一个阈值必然在两者中选错一个：要么误杀正常的慢首包，
 * 要么让断流卡住用户一分半。</p>
 *
 * <p><b>与 cc-haha 的取值差异（有意为之）</b>：cc-haha 跑在桌面、固定宽带，
 * 首 token 与流中空闲都取 90s。本实现流中空闲收紧到 30s，理由是 Android 的网络环境：
 * 移动端切换基站/进出电梯/Wi-Fi 与蜂窝互切都会造成数秒到数十秒的黑洞，断流本身更频繁；
 * 而用户在手机上是「盯着屏幕等」的，卡 90 秒的体感远差于桌面后台任务。
 * 首 token 预算则保持 90s 不动——prefill 耗时取决于服务端算力与上下文长度，
 * 与客户端网络无关，收紧它只会误杀正常的长上下文请求。
 * 两个值都可注入，接入方仍可按场景覆盖。</p>
 *
 * <p><b>为什么「工具调用已完成」之后永久禁止重发</b>：这是本类最重要的一条不变量。
 * 重发一次纯文本请求，最坏结果是用户看到重复的回复；而重发一个已经越过工具调用边界的
 * 请求，会<b>把工具再执行一遍</b>——重复写文件、重复执行 shell 命令、重复发网络请求。
 * 后者是不可逆的副作用，比「重试失败」严重得多。所以边界一旦越过就不再回退，
 * 即使后续收到的是明确的「流断开」也不允许重试，只能把已有内容提交并终止。
 * 注意判定用的是「工具调用块已完成」而非「开始出现工具调用」：块可能被分片传输，
 * 只有整个块收完才代表参数已完整、工具即将被执行。</p>
 *
 * <p><b>为什么不用 {@link StreamWatchdog}</b>：同目录的 {@code StreamWatchdog} 是
 * 线程版实现（自带守护线程 + {@code System.currentTimeMillis()}），它解决的是「阻塞读
 * 怎么被唤醒」，代价是无法在单测里推进时间、也无法表达相位与副作用边界。
 * 本类不碰线程与真实时钟，由调用方喂 {@code nowMs}，两者职责互补而非替代。</p>
 */
public final class StreamIdleWatchdog {

    /** 首 token 预算默认值：与 cc-haha 一致，prefill 慢是服务端特性，不该由客户端惩罚。 */
    public static final long DEFAULT_FIRST_TOKEN_BUDGET_MS = 90_000L;

    /** 流中空闲预算默认值：有意短于 cc-haha 的 90s，理由见类注释。 */
    public static final long DEFAULT_STREAM_IDLE_BUDGET_MS = 30_000L;

    /** 相位：决定用哪个预算，也决定超时后调用方能否安全重发。 */
    public enum Phase {
        /** 连接已建立，但一个事件都还没收到——典型表现是服务端挂住了请求。 */
        BEFORE_FIRST_EVENT,
        /** 收到过事件（含纯 thinking / 心跳），但还没产出正文。 */
        BEFORE_CONTENT,
        /** 已收到正文，正常流式输出中。 */
        MID_STREAM
    }

    /** 超时类型。区分首包与流中，是为了让调用方能给出不同的用户提示与重试策略。 */
    public enum TimeoutType {
        /** 首包迟迟不来（BEFORE_FIRST_EVENT 相位超时）。 */
        FIRST_TOKEN_TIMEOUT,
        /** 流已建立但空闲过久（BEFORE_CONTENT / MID_STREAM 相位超时）。 */
        IDLE_TIMEOUT
    }

    private final long firstTokenBudgetMs;
    private final long streamIdleBudgetMs;

    /** BEFORE_CONTENT 阶段的总预算，见构造器里的说明。 */
    private final long contentBudgetMs;

    private Phase phase = Phase.BEFORE_FIRST_EVENT;
    private long lastEventMs;
    private long streamStartMs;
    private boolean toolUseCompleted;

    public StreamIdleWatchdog(long nowMs) {
        this(nowMs, DEFAULT_FIRST_TOKEN_BUDGET_MS, DEFAULT_STREAM_IDLE_BUDGET_MS);
    }

    /**
     * @param nowMs               流开始的时刻，作为首个 idle 窗口的起点
     * @param firstTokenBudgetMs  首 token 预算（BEFORE_FIRST_EVENT）
     * @param streamIdleBudgetMs  流中空闲预算（BEFORE_CONTENT / MID_STREAM）
     */
    public StreamIdleWatchdog(long nowMs, long firstTokenBudgetMs, long streamIdleBudgetMs) {
        // 预算夹到至少 1ms：0 或负数会让第一次 checkTimeout 立刻判定卡死，
        // 把一个配置笔误变成「所有流都秒断」
        this.firstTokenBudgetMs = Math.max(1L, firstTokenBudgetMs);
        this.streamIdleBudgetMs = Math.max(1L, streamIdleBudgetMs);
        // BEFORE_CONTENT 的总预算。取「首 token 预算 × 2」而不是另设一个魔数：
        // 两者的语义都是「等到正文为止能容忍多久」，量级应当同阶；
        // 而 before-content 已经证明连接是通的（收到过事件），可以比首 token 宽一档。
        this.contentBudgetMs = Math.max(1L, firstTokenBudgetMs * 2);
        this.streamStartMs = nowMs;
        this.lastEventMs = nowMs;
    }

    /**
     * 每收到一个 SSE 事件（含 thinking delta、心跳、空行）都要调用。
     *
     * <p>语义是「流仍在推进」，因此它把相位推进到至少 BEFORE_CONTENT，并重置 idle 计时。
     * 已经进入 MID_STREAM 的流不会被降级。</p>
     */
    public void onEvent(long nowMs) {
        lastEventMs = nowMs;
        if (phase == Phase.BEFORE_FIRST_EVENT) {
            phase = Phase.BEFORE_CONTENT;
        }
    }

    /** 收到正文（用户可见的内容或 reasoning 之外的正文 delta）。 */
    public void onContent(long nowMs) {
        lastEventMs = nowMs;
        phase = Phase.MID_STREAM;
    }

    /**
     * 标记「工具调用块已完整收到」。此后 {@link #safeToRetry()} 恒为 false，且不可撤销。
     * 见类注释中关于重复副作用的说明。
     */
    public void markToolUseCompleted() {
        toolUseCompleted = true;
    }

    /** 标记工具调用边界已越过（同 {@link #markToolUseCompleted()}，语义更直白的别名）。 */
    public void markToolBoundaryCrossed() {
        markToolUseCompleted();
    }

    /**
     * 判定是否卡死。
     *
     * @param nowMs 当前时刻，由调用方提供
     * @return 超时类型；未超时返回 null
     */
    public TimeoutType checkTimeout(long nowMs) {
        if (phase == Phase.BEFORE_FIRST_EVENT) {
            if (nowMs - streamStartMs >= firstTokenBudgetMs) {
                return TimeoutType.FIRST_TOKEN_TIMEOUT;
            }
            return null;
        }
        if (phase == Phase.BEFORE_CONTENT) {
            // 独立的总预算，**不能只靠 idle 判定**。
            //
            // 本类要抓的典型故障是某些中转「稳定吐 thinking delta 却永不产出正文」。
            // 每个 delta 都调用 onEvent() 从而重置 idle 计时，因此只要间隔小于
            // streamIdleBudgetMs，纯 idle 判定永远返回 null —— 用户面对的仍是
            // 永久转圈，与 readTimeout 的失效模式完全相同。
            // 这里用「从流开始到现在」的总时长兜底，不受单个事件间隔影响。
            if (nowMs - streamStartMs >= contentBudgetMs) {
                return TimeoutType.FIRST_TOKEN_TIMEOUT;
            }
        }
        if (nowMs - lastEventMs >= streamIdleBudgetMs) {
            return TimeoutType.IDLE_TIMEOUT;
        }
        return null;
    }

    /**
     * 现在是否允许重发请求。
     *
     * <p>越过工具边界后恒为 false——这条判断先于任何其他条件，因为它关乎副作用正确性
     * 而非重试效率。</p>
     */
    public boolean safeToRetry() {
        if (toolUseCompleted) {
            return false;
        }
        return phase != Phase.MID_STREAM;
    }

    public Phase phase() {
        return phase;
    }

    /** 是否已越过工具调用边界（调用方据此决定「重发」还是「提交部分内容并终止」）。 */
    public boolean toolUseCompleted() {
        return toolUseCompleted;
    }

    /** 距离上次活动的空闲时长，供 UI 展示或日志。 */
    public long idleMs(long nowMs) {
        return nowMs - lastEventMs;
    }

    public long firstTokenBudgetMs() {
        return firstTokenBudgetMs;
    }

    public long streamIdleBudgetMs() {
        return streamIdleBudgetMs;
    }
}
