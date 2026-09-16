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
 * 流空闲看门狗（借鉴 cc-haha streamWatchdog）：守护线程周期轮询最近活动时间，
 * 首个 token 前与收到数据后的空闲阈值相同（默认 90s，由调用方传入）。
 * 触发后由调用方 disconnect 连接，使阻塞中的 readLine 抛 SocketException。
 */
public final class StreamWatchdog {

    public enum Reason { FIRST_TOKEN, IDLE }

    public interface Listener {
        void onWatchdogFired(Reason reason);
    }

    private final long idleTimeoutMs;
    private final Object lock = new Object();
    private volatile long lastActivityMs;
    private volatile boolean firstTokenSeen;
    private volatile boolean fired;
    private volatile boolean stopped;
    private volatile Reason firedReason;
    private Thread thread;

    public StreamWatchdog(long idleTimeoutMs) {
        this.idleTimeoutMs = Math.max(1000L, idleTimeoutMs);
        this.lastActivityMs = System.currentTimeMillis();
    }

    /** 启动看门狗（幂等：重复调用忽略）。 */
    public void start(final Listener listener) {
        if (listener == null || thread != null) {
            return;
        }
        thread = new Thread(() -> {
            while (!stopped) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    return;
                }
                if (stopped || fired) {
                    return;
                }
                long idle = System.currentTimeMillis() - lastActivityMs;
                if (idle >= idleTimeoutMs) {
                    fired = true;
                    firedReason = firstTokenSeen ? Reason.IDLE : Reason.FIRST_TOKEN;
                    listener.onWatchdogFired(firedReason);
                    return;
                }
            }
        }, "lineai-stream-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    /** readSse 每读到一行调用（含事件行与空行）。 */
    public void onActivity() {
        lastActivityMs = System.currentTimeMillis();
        firstTokenSeen = true;
    }

    /** 请求结束必须调用；已触发后再 stop 不影响 fired 状态读取。 */
    public void stop() {
        stopped = true;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    public boolean fired() {
        return fired;
    }

    public Reason firedReason() {
        return firedReason;
    }
}
