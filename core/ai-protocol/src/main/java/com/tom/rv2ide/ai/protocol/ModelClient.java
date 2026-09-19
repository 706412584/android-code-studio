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

import java.util.List;

/**
 * 模型调用门面：按配置选择协议实现并转发请求。
 *
 * <p>调用方（agent 循环）只需依赖本类，不必了解具体协议。
 */
public final class ModelClient {
    private final ModelProtocolFactory protocolFactory;

    /** 使用默认协议工厂（按配置中的协议类型分派到具体实现）。 */
    public ModelClient() {
        this(new ModelProtocolFactory());
    }

    /**
     * 注入自定义协议工厂。
     *
     * <p>主要用于测试：可以注册一个返回固定响应的假协议，从而在不发起网络请求的情况下
     * 验证 agent 循环的多轮工具调用行为。
     */
    public ModelClient(ModelProtocolFactory protocolFactory) {
        this.protocolFactory = protocolFactory == null ? new ModelProtocolFactory() : protocolFactory;
    }

    /**
     * 该模型是否支持协议原生的工具调用。
     *
     * <p>不支持时，调用方不应把工具定义放进请求，而应依靠提示词描述工具、
     * 再从模型正文里解析调用（见 {@code ToolCallTextParser}）。
     */
    public boolean supportsNativeTools(ModelConfig config) {
        if (config == null) {
            return false;
        }
        return protocolFactory.create(config.getProtocolType()).supportsNativeTools(config);
    }

    public ModelCompletionResponse complete(ModelConfig config, List<ModelMessage> messages) throws ModelCompletionException {
        ModelProtocol protocol = protocolFactory.create(config.getProtocolType());
        return protocol.complete(config, messages);
    }

    public ModelCompletionResponse stream(
            ModelConfig config,
            List<ModelMessage> messages,
            ModelStreamCallback callback,
            ModelCancellationToken cancellationToken
    ) throws ModelCompletionException {
        return stream(config, messages, callback, cancellationToken, ModelRequestOptions.defaults());
    }

    public ModelCompletionResponse stream(
            ModelConfig config,
            List<ModelMessage> messages,
            ModelStreamCallback callback,
            ModelCancellationToken cancellationToken,
            ModelRequestOptions options
    ) throws ModelCompletionException {
        return stream(config, messages, callback, cancellationToken, options, null);
    }

    /**
     * 带断流重试的流式请求。
     *
     * <p><b>为什么重试放在这一层，而不是 postJsonSse</b>：协议实现里累积的
     * {@code text}/{@code reasoning}/{@code commitBuffer} 都是在**调用协议方法之前**
     * 创建的局部状态。在 postJsonSse 内重试只会重发 HTTP，那些 StringBuilder 不会重置，
     * 两次尝试的内容会**拼接**成一段乱码。放在这里重试，每次都是一次全新的
     * {@code protocol.stream(...)} 调用，状态自然重建。
     *
     * <p><b>只有「未越过副作用边界」才重发</b>：一旦模型已经产出完整工具调用，
     * 重发会让工具被**重复执行**（重复写文件、重复跑命令）。这个判据来自异常上的
     * {@link ModelCompletionException#crossedToolBoundary()}，由协议层的
     * {@code AssistantCommitBuffer} 标记。宁可把失败如实报给用户，也不重复副作用。
     *
     * @param retryListener 重试通知回调；可为 null。UI 需要它来丢弃本轮已渲染的部分输出
     */
    public ModelCompletionResponse stream(
            ModelConfig config,
            List<ModelMessage> messages,
            ModelStreamCallback callback,
            ModelCancellationToken cancellationToken,
            ModelRequestOptions options,
            RetryListener retryListener
    ) throws ModelCompletionException {
        RetryPolicy retryPolicy = new RetryPolicy();
        BackoffPolicy backoff = new BackoffPolicy();
        ModelCompletionException last = null;

        for (int attempt = 0; attempt <= retryPolicy.maxRetries(); attempt++) {
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                throw new ModelCompletionException("请求已取消");
            }
            try {
                ModelProtocol protocol = protocolFactory.create(config.getProtocolType());
                ModelRequestOptions compatibleOptions = ReasoningCompatibility.adapt(config, options);
                return protocol.stream(config, messages, callback, cancellationToken, compatibleOptions);
            } catch (ModelCompletionException e) {
                last = e;
                if (!shouldRetryStream(e, retryPolicy, attempt)) {
                    throw e;
                }
                long delayMs = backoff.delayMs(attempt + 1, e.retryAfterMs());
                if (retryListener != null) {
                    retryListener.onRetrying(attempt + 1, retryPolicy.maxRetries(), delayMs, e);
                }
                sleepQuietly(delayMs, cancellationToken);
            }
        }
        throw last == null ? new ModelCompletionException("请求失败") : last;
    }

    /**
     * 本次流式失败是否该重发。
     *
     * <p>三条否决条件，任一成立都不重发：
     * <ol>
     *   <li><b>越过工具边界</b>——重发会重复执行工具，这是正确性问题，不是效率问题
     *   <li>分类不可重试（认证失败、客户端参数错误等）——重发只是把同一个错误再撞一次
     *   <li>已用完重试次数
     * </ol>
     */
    static boolean shouldRetryStream(
            ModelCompletionException e, RetryPolicy retryPolicy, int attempt) {
        if (e.crossedToolBoundary()) {
            return false;
        }
        if (attempt >= retryPolicy.maxRetries()) {
            return false;
        }
        // streamDisconnected=true：走到这里说明流已经开始读了（异常由读循环抛出），
        // 让分类器给出更精确的 STREAM_DISCONNECT 而不是泛化的 CONNECTION。
        int status = e.httpStatus();
        RetryPolicy.ErrorCategory category =
                status > 0
                        ? RetryPolicy.classifyStatus(status)
                        : RetryPolicy.classify(e, true);
        return retryPolicy.canRetry(category);
    }

    /** 退避等待。可被取消打断——不把一次取消变成完整睡眠。 */
    private static void sleepQuietly(long delayMs, ModelCancellationToken cancellationToken) {
        if (delayMs <= 0) {
            return;
        }
        long deadline = System.currentTimeMillis() + delayMs;
        while (System.currentTimeMillis() < deadline) {
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                return;
            }
            try {
                Thread.sleep(Math.min(200L, Math.max(1L, deadline - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 重试通知。UI 据此丢弃本轮已渲染的部分输出。 */
    public interface RetryListener {
        void onRetrying(int attempt, int maxAttempts, long delayMs, ModelCompletionException cause);
    }
}
