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
        ModelProtocol protocol = protocolFactory.create(config.getProtocolType());
        ModelRequestOptions compatibleOptions = ReasoningCompatibility.adapt(config, options);
        return protocol.stream(config, messages, callback, cancellationToken, compatibleOptions);
    }
}
