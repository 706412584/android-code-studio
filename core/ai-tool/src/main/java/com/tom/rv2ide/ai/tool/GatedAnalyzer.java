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

import java.util.concurrent.atomic.AtomicReference;

/**
 * 给一次可能长时间阻塞的诊断加**真正的**硬超时。
 *
 * <p><b>为什么不能靠协程超时</b>：语言服务器的 {@code analyze} 实现内部是
 * {@code thread.start(); thread.join()}（见 {@code JavaDiagnosticProvider.analyze}），
 * **没有任何挂起点**。协程取消是协作式的，只有执行到挂起点才会生效，因此
 * {@code withTimeoutOrNull} 对它是形同虚设——它只会在分析跑完之后才「超时」。
 * 编辑搭车诊断号称 1.5s 有界、结果却可能阻塞十几秒，正是这个原因。
 *
 * <p><b>做法</b>：把分析放到一个**可被中断的独立线程**上跑，调用方在**自己的线程**上
 * 有界等待（{@link Thread#join(long)}）。超时后 {@link Thread#interrupt()} 并立刻返回，
 * 不等它跑完：
 * <ul>
 *   <li>若分析实现响应中断（检查 {@code isInterrupted}），线程会尽快退出
 *   <li>若不响应（当前的 join 式实现），线程会继续跑完，但因为已经不再是调用方的等待
 *       对象，**不会拖慢调用方**；它结束时自行在 {@code finally} 里释放编译器资源
 * </ul>
 * 因此这里用守护线程：进程退出时不会被它们挂住。
 *
 * <p>线程名带 {@code "gated-analyze"} 前缀，便于在 logcat 里辨认。
 */
public final class GatedAnalyzer {

  /** 待执行的阻塞任务。 */
  public interface BlockingTask<T> {
    T run() throws Exception;
  }

  /** 超时（含线程被中断）时返回该标记。 */
  public static final Object TIMED_OUT = new Object() {
    @Override
    public String toString() {
      return "GatedAnalyzer.TIMED_OUT";
    }
  };

  /**
   * 在独立线程上执行 {@code task}，最多等 {@code timeoutMs}。
   *
   * @return 任务返回值；超时或被中断时返回 {@link #TIMED_OUT}；任务抛异常时返回 null
   */
  public static Object run(BlockingTask<?> task, long timeoutMs) {
    if (task == null) {
      return null;
    }
    if (timeoutMs <= 0) {
      // 无预算：不执行，直接当作超时（调用方据此报失败）。
      return TIMED_OUT;
    }
    final AtomicReference<Object> result = new AtomicReference<>();
    final AtomicReference<Throwable> error = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              try {
                result.set(task.run());
              } catch (Throwable t) {
                error.set(t);
              }
            },
            "gated-analyze");
    worker.setDaemon(true);
    worker.start();
    try {
      worker.join(timeoutMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      worker.interrupt();
      return TIMED_OUT;
    }
    if (worker.isAlive()) {
      // 没能在预算内结束：主动请求中断，并放弃等待（线程可能仍会跑完，但已不影响调用方）。
      worker.interrupt();
      return TIMED_OUT;
    }
    if (error.get() != null) {
      return null;
    }
    return result.get();
  }

  private GatedAnalyzer() {}
}
