/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from LineCode Pro (https://github.com/LangLang03/LineCodePro),
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

package com.tom.rv2ide.ai.tool.api;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 协议层的错误记录入口。
 *
 * <p><b>与上游的差异</b>：LineCode Pro 的 {@code cn.lineai.log.ErrorLog} 位于其 {@code data}
 * 模块，依赖 {@code android.content.Context} 把错误写入 SQLite 数据库，并安装全局
 * UncaughtExceptionHandler。那套实现使协议层无法脱离 Android 运行（也就无法在 JVM 上做单元测试）。
 *
 * <p>这里保留完全相同的静态 API（{@link #record}），但把「写到哪里」抽成可注入的
 * {@link Sink}。默认实现只写 {@code java.util.logging}，因此本模块是纯 Java、可独立测试；
 * AndroidCodeStudio 的 app 层可在启动时通过 {@link #setSink} 接入自己的日志设施。
 *
 * <p>调用方（协议实现）代码无需改动。
 */
public final class ErrorLog {

  /** 错误落地的目标。实现方需自行保证线程安全与异常隔离。 */
  public interface Sink {
    void record(String type, String summary, Throwable throwable, String details);
  }

  private static final Logger LOGGER = Logger.getLogger(ErrorLog.class.getName());

  private static volatile Sink sink;

  private ErrorLog() {}

  /**
   * 注入自定义的日志落地实现。传 {@code null} 可恢复为默认的 java.util.logging 行为。
   *
   * <p>由 app 层在初始化时调用；协议层本身不关心具体实现。
   */
  public static void setSink(Sink newSink) {
    sink = newSink;
  }

  /**
   * 记录一条错误。签名与上游保持一致。
   *
   * @param type 错误分类，例如 {@code "api"}、{@code "parse"}
   * @param summary 简短描述
   * @param throwable 原始异常，可为 {@code null}
   * @param details 已脱敏的详细信息
   */
  public static void record(String type, String summary, Throwable throwable, String details) {
    Sink current = sink;
    if (current != null) {
      try {
        current.record(type, summary, throwable, details);
      } catch (RuntimeException ignored) {
        // 日志失败不得影响主流程；退回到默认通道
        logToFallback(type, summary, throwable, details);
      }
      return;
    }
    logToFallback(type, summary, throwable, details);
  }

  private static void logToFallback(
      String type, String summary, Throwable throwable, String details) {
    LOGGER.log(
        Level.WARNING,
        "[" + type + "] " + summary + (details == null || details.isEmpty() ? "" : " | " + details),
        throwable);
  }
}
