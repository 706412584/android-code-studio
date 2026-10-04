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

package com.tom.rv2ide.ai.tool.api;

/**
 * 图片解码与缩放的端口。
 *
 * <p><b>为什么是一个接口而不是直接调 BitmapFactory</b>：工具模块（{@code core/ai-tool}）是
 * 纯 Java、零 Android 依赖的 {@code java-library}（见其 build.gradle 的 {@code options.release = 8}），
 * 因此不能在工具里直接引用 {@code android.graphics}。把「缩放」这一步抽成端口，
 * 由 app 层注入 {@code BitmapFactory} 实现，工具层只负责字节层面的编排。
 *
 * <p>实现方无状态、可并发调用：单次 agent 运行里多个 {@code file_read} 可能并发读图。
 */
public interface ImageDataProvider {

  /**
   * 把原始图片字节缩放到最长边不超过 {@code maxDimensionPx} 的范围。
   *
   * <p>实现语义：
   * <ul>
   *   <li>原图最长边已经 ≤ 上限时，应原样返回（不做无谓的重编码，避免画质损失）
   *   <li>缩放后应保持宽高比
   *   <li>无法解码（文件损坏、格式不受支持）时返回 {@code null}，由调用方回退到原字节
   * </ul>
   *
   * @param raw 原始图片文件字节
   * @param maxDimensionPx 最长边像素上限，调用方保证为正
   * @return 缩放后的图片字节（编码格式由实现决定）；无法处理时返回 {@code null}
   */
  byte[] downscaleToMaxDimension(byte[] raw, int maxDimensionPx);
}
