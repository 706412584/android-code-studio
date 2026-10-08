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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.tom.rv2ide.resources.R

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
  val expand: String @Composable get() = stringResource(R.string.compose_bubble_expand)
  val collapse: String @Composable get() = stringResource(R.string.compose_bubble_collapse)
  val collapseAction: String @Composable get() = stringResource(R.string.compose_bubble_collapse_action)
  val copied: String @Composable get() = stringResource(R.string.compose_bubble_copied)
  val copy: String @Composable get() = stringResource(R.string.compose_bubble_copy)
  val moreOptions: String @Composable get() = stringResource(R.string.compose_bubble_more_options)
  val readAloud: String @Composable get() = stringResource(R.string.compose_bubble_read_aloud)
  val stopReadAloud: String @Composable get() = stringResource(R.string.compose_bubble_stop_read_aloud)
  val rewindTitle: String @Composable get() = stringResource(R.string.compose_bubble_rewind_title)
  val turnRunning: String @Composable get() = stringResource(R.string.compose_bubble_turn_running)
  val contextCompressed: String @Composable get() = stringResource(R.string.compose_bubble_context_compressed)
  val compactionFailed: String @Composable get() = stringResource(R.string.compose_bubble_compaction_failed)
  val bgCommandDone: String @Composable get() = stringResource(R.string.compose_bubble_bg_command_done)
  val variantPrevious: String @Composable get() = stringResource(R.string.compose_bubble_variant_previous)
  val variantNext: String @Composable get() = stringResource(R.string.compose_bubble_variant_next)
  val variantDelete: String @Composable get() = stringResource(R.string.compose_bubble_variant_delete)
  val compressingContext: String @Composable get() = stringResource(R.string.compose_bubble_compressing_context)
  val statusThinking: String @Composable get() = stringResource(R.string.compose_bubble_status_thinking)
  val statusGenerating: String @Composable get() = stringResource(R.string.compose_bubble_status_generating)
  val retryRecordsTitle: String @Composable get() = stringResource(R.string.compose_bubble_retry_records_title)
  val thinkingDone: String @Composable get() = stringResource(R.string.compose_bubble_thinking_done)
  val streamingPreviewNotice: String @Composable get() = stringResource(R.string.compose_bubble_streaming_preview_notice)
  val retryErrorRateLimit: String @Composable get() = stringResource(R.string.compose_bubble_retry_error_rate_limit)
  val retryErrorServerOverloaded: String @Composable get() = stringResource(R.string.compose_bubble_retry_error_server_overloaded)
  val retryErrorServer: String @Composable get() = stringResource(R.string.compose_bubble_retry_error_server)
  val retryErrorTimeout: String @Composable get() = stringResource(R.string.compose_bubble_retry_error_timeout)
  val retryErrorConnectionRefused: String @Composable get() = stringResource(R.string.compose_bubble_retry_error_connection_refused)
  val retryErrorDnsFailed: String @Composable get() = stringResource(R.string.compose_bubble_retry_error_dns_failed)
  val retryErrorConnectionReset: String @Composable get() = stringResource(R.string.compose_bubble_retry_error_connection_reset)
  val retryErrorSsl: String @Composable get() = stringResource(R.string.compose_bubble_retry_error_ssl)
  val retryErrorNetwork: String @Composable get() = stringResource(R.string.compose_bubble_retry_error_network)
  val retryErrorUnknown: String @Composable get() = stringResource(R.string.compose_bubble_retry_error_unknown)
  @Composable
  fun cacheHitRate(rate: Any): String = stringResource(R.string.compose_bubble_cache_hit_rate, rate)
  @Composable
  fun taskDuration(duration: Any): String = stringResource(R.string.compose_bubble_task_duration, duration)
  @Composable
  fun turnCompleted(duration: Any): String = stringResource(R.string.compose_bubble_turn_completed, duration)
  @Composable
  fun variantCounter(position: Any, count: Any): String = stringResource(R.string.compose_bubble_variant_counter, position, count)
  @Composable
  fun bgCommandsPartialFailed(total: Any, failed: Any): String = stringResource(R.string.compose_bubble_bg_commands_partial_failed, total, failed)
  @Composable
  fun bgCommandsDone(count: Any): String = stringResource(R.string.compose_bubble_bg_commands_done, count)
  @Composable
  fun retryBadge(attempt: Any, maxRetries: Any): String = stringResource(R.string.compose_bubble_retry_badge, attempt, maxRetries)
  @Composable
  fun retryBadgeDesc(attempt: Any, maxRetries: Any): String = stringResource(R.string.compose_bubble_retry_badge_desc, attempt, maxRetries)
  @Composable
  fun retryRecordLine(attempt: Any, error: Any, time: Any): String = stringResource(R.string.compose_bubble_retry_record_line, attempt, error, time)
  @Composable
  fun keySwitched(newIndex: Any, total: Any): String = stringResource(R.string.compose_bubble_key_switched, newIndex, total)
  @Composable
  fun thinkingRunningTime(seconds: Any): String = stringResource(R.string.compose_bubble_thinking_running_time, seconds)
  @Composable
  fun thinkingDoneTime(seconds: Any): String = stringResource(R.string.compose_bubble_thinking_done_time, seconds)
  @Composable
  fun retryErrorWithCode(base: Any, code: Any): String = stringResource(R.string.compose_bubble_retry_error_with_code, base, code)
}
