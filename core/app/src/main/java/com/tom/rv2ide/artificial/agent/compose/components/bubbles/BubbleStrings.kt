/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from Aharou (https://github.com/520huxiangli/Aharou),
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

package com.tom.rv2ide.artificial.agent.compose.components.bubbles

/**
 * 气泡区文案。
 *
 * <p><b>TODO（迁移到 ACS 字符串资源）</b>：Aharou 的这些文案取自它自己的 `R.string.*`，
 * 而 ACS 的 `core/app/res/values/strings.xml` 目前只有 41 条、没有任何一条以 chat_ 或 retry_ 开头
 * 的条目。本次移植遵守「不新造资源」的约束，先把文案以常量兜在组件同包内，
 * 因此**当前不具备多语言能力**（values-zh-rCN 等目录下没有对应条目可回落，中文硬编码写死）。
 * 待渲染层稳定后，应把这些常量逐条迁进 `core/app` 的 strings.xml 并改回 `stringResource(...)`，
 * 届时本文件可整体删除。
 *
 * <p>注意：本段注释里**不得**写出「chat_ 星号斜杠 retry_」那样的连写——`*` 紧邻 `/`
 * 会提前闭合块注释，把后面的注释文字当成代码解析（已踩过一次，全模块 77 条语法错误）。
 *
 * <p>带插值的文案定义成函数而非 `%1$s` 常量：调用点不必再记参数顺序，也省掉
 * `String.format` 的 Locale 语义（Kotlin 的 Int 插值不随地区变数字形状，
 * 对 `1/3` 这类计数反而更稳）。
 */
internal object BubbleStrings {

  // ------------------------------------------------------------ 通用动作

  /** 折叠箭头的语义（收起态 →「展开」）。 */
  const val expand = "展开"

  /** `R.string.common_collapse`：与 [expand] 相对，用于可折叠行。 */
  const val collapse = "折叠"

  /** `R.string.common_collapse_action`：整轮/压缩卡上的「收起」动作标签。 */
  const val collapseAction = "收起"

  // ------------------------------------------------------------ 消息气泡

  const val copied = "已复制"
  const val copy = "复制"
  const val moreOptions = "更多选项"
  const val readAloud = "朗读"
  const val stopReadAloud = "停止朗读"

  /** 回退入口的无障碍名（`R.string.checkpoint_rewind_title`）。 */
  const val rewindTitle = "检查点与撤销"

  const val turnRunning = "执行中"
  const val contextCompressed = "上下文已压缩"
  const val compactionFailed = "上下文压缩失败"
  const val bgCommandDone = "后台命令已完成"

  const val variantPrevious = "上一个版本"
  const val variantNext = "下一个版本"
  const val variantDelete = "删除当前版本"

  fun cacheHitRate(rate: String): String = "缓存命中率 $rate"

  fun taskDuration(duration: String): String = "本轮总耗时 $duration"

  fun turnCompleted(duration: String): String = "已完成 $duration"

  fun variantCounter(position: Int, count: Int): String = "$position/$count"

  fun bgCommandsPartialFailed(total: Int, failed: Int): String = "$total 个后台任务结束，$failed 个失败"

  fun bgCommandsDone(count: Int): String = "$count 个后台任务已完成"

  // ------------------------------------------------------------ 状态气泡

  const val compressingContext = "正在压缩上下文"
  const val statusThinking = "正在思考"
  const val statusGenerating = "正在生成"
  const val retryRecordsTitle = "本次重试记录"
  const val thinkingDone = "思考完成"
  const val streamingPreviewNotice = "生成中仅展示最近一段内容"

  fun retryBadge(attempt: Int, maxRetries: Int): String = "$attempt/$maxRetries"

  fun retryBadgeDesc(attempt: Int, maxRetries: Int): String = "自动重试第 $attempt 次，共 $maxRetries 次"

  fun retryRecordLine(attempt: Int, error: String, time: String): String = "第 $attempt 次 · $error · $time"

  fun keySwitched(newIndex: Int, total: Int): String = "已切换到第 $newIndex/$total 个 Key"

  fun thinkingRunningTime(seconds: String): String = "正在思考（耗时 $seconds 秒）"

  fun thinkingDoneTime(seconds: String): String = "思考完成（耗时 $seconds 秒）"

  // ------------------------------------------------- 重试原因（RetryErrorKind → 文案）

  const val retryErrorRateLimit = "速率限制"
  const val retryErrorServerOverloaded = "服务器负载过高"
  const val retryErrorServer = "服务端错误"
  const val retryErrorTimeout = "连接超时"
  const val retryErrorConnectionRefused = "连接被拒绝"
  const val retryErrorDnsFailed = "DNS 解析失败"
  const val retryErrorConnectionReset = "连接中断"
  const val retryErrorSsl = "SSL 握手失败"
  const val retryErrorNetwork = "网络连接断开"
  const val retryErrorUnknown = "网络异常"

  fun retryErrorWithCode(base: String, code: Int): String = "$base ($code)"
}
