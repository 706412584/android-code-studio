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

package com.tom.rv2ide.artificial.agent.compose.compat

/**
 * Aharou domain 类型的最小映射。
 *
 * <p><b>为什么只挑这几个、不做整包搬</b>：Aharou 的 `feature.agent.domain.*` 是 Hilt/Room/Retrofit
 * 装配起来的（`SessionUseCase` 注入仓储、`RetryErrorInfo` 由 `HttpException` 推导、
 * `ImageCompressor` 依赖它的文件抽象），整包搬会把 ACS 没有的依赖一起牵进来。
 * 这里只保留**被移植文件真正读到的那几个符号**，逐个与 Aharou 同值/同形，
 * 让移植文件只改 import、不改调用点。
 *
 * <p>对应的 Aharou 出处：
 * - [SessionUseCase] ← `feature/agent/domain/session/SessionUseCase.kt`
 * - [DIRECTORY_MIME_TYPE] / [AgentImage] ← `feature/agent/presentation/AgentUiModels.kt` /
 *   `feature/agent/domain/model/AgentMessage.kt`
 * - [RetryErrorKind] / [RetryErrorInfo] ← `feature/agent/domain/provider/RetryPolicy.kt`
 * - [RetryState] ← `feature/agent/presentation/AgentUiModels.kt`
 */

/**
 * 工具运行态的**文本哨兵**。
 *
 * <p><b>为什么照抄 Aharou 的值而不是给空串</b>：Aharou 在工具消息的 `content` 前缀里塞哨兵
 * （运行中是 `[running]`，历史版本用过 ⏳/⏹）来表示运行态，渲染层据此把前缀剥掉再展示。
 * 若这里给 `""`，调用点的 `content.startsWith("")` 恒为真 → 每条工具消息都被判成运行中，
 * 是**静默的功能错误**；给同值常量则相反：ACS 的 content 里本来就不会有这些哨兵，
 * `removePrefix` 什么都不会剥掉，而运行态由显式字段 `AgentUIMessage.toolStatus` 承载。
 * 两者叠加的结果是「不会误判、也不会破坏文本」，正是想要的。
 */
object SessionUseCase {
  const val PENDING_TOOL_MARKER = "[running]"
  const val LEGACY_PENDING_TOOL_MARKER = "\u23F3"
  const val LEGACY_STOPPED_TOOL_MARKER = "\u23F9"
}

/**
 * 目录附件的类型标记（与 Aharou 同值）。目录附件不打包上传，只带路径，由 AI 自行遍历。
 */
const val DIRECTORY_MIME_TYPE = "inode/directory"

/**
 * 随消息一起发给模型的图片。
 *
 * <p>只含被读到的三个字段。ACS 侧的等价物是 `ImageInputPayload.Payload`（协议层），
 * 但那个是 Java 类、字段为 `mimeType`/`dataBase64`/`prompt`，且构造即 normalize，
 * 与这里「原样承载附件字节」的用途不同，故不互相替代。
 */
data class AgentImage(
    val mimeType: String,
    val base64Data: String,
    val path: String = ""
)

/**
 * 重试错误的归类。
 *
 * <p><b>不能用 ACS 的 `core/ai-protocol` 的 `RetryPolicy.ErrorCategory` 代替</b>：那个粒度更粗
 * （CONNECTION/TIMEOUT/RATE_LIMIT/SERVER_ERROR/STREAM_DISCONNECT/AUTH/CLIENT_ERROR/UNKNOWN），
 * 缺 DNS_FAILED / SSL_ERROR / CONNECTION_RESET / SERVER_OVERLOADED。重试气泡按 kind 出**不同文案**，
 * 换过去会让四种错误退化成同一个提示。二者只在即将接入真实重试状态时做映射，不互相替换。
 */
enum class RetryErrorKind {
  /** HTTP 429 / 服务端 rate limit 类错误。 */
  RATE_LIMIT,

  /** HTTP 503 服务过载（或流式错误码 server_is_overloaded / overloaded）。 */
  SERVER_OVERLOADED,

  /** HTTP 5xx（除 503）等服务端错误。 */
  SERVER_ERROR,

  /** 连接/读取超时（含首字节 watchdog 触发的断流）。 */
  TIMEOUT,

  /** 目标端口无服务，连接被拒绝。 */
  CONNECTION_REFUSED,

  /** DNS 解析失败（域名不存在或网络不可达）。 */
  DNS_FAILED,

  /** 连接建立后被对端/中间设备重置（流中断、unexpected end of stream 等）。 */
  CONNECTION_RESET,

  /** TLS/SSL 握手失败。 */
  SSL_ERROR,

  /** 其它网络层故障。 */
  NETWORK,

  /** 无法归类的其它错误。 */
  UNKNOWN
}

/** 触发重试的错误摘要。[statusCode] 为 HTTP 状态码，非 HTTP 错误时为 null。 */
data class RetryErrorInfo(val kind: RetryErrorKind, val statusCode: Int? = null)

/** 重试状态：第 [attempt] 次（从 1 起）、上限 [maxRetries] 次，以及触发本次重试的错误。 */
data class RetryState(val attempt: Int, val maxRetries: Int, val error: RetryErrorInfo? = null)
