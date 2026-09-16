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

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 按协议类型创建协议实现。
 *
 * <p><b>与上游的差异</b>：LineCode Pro 还注册了 {@code CodexResponsesProtocol} 与
 * {@code LocalGgufProtocol}。本移植未搬这两个实现（前者面向 OpenAI Responses API，
 * 后者是端侧 GGUF 运行时），因此只注册已搬运的两种协议。
 * 未注册的类型回退到 OpenAI 兼容协议——那是最通用的形态。
 */
public final class ModelProtocolFactory {
    private final Map<ModelProtocolType, Supplier<ModelProtocol>> registry = new HashMap<>();

    public ModelProtocolFactory() {
        register(ModelProtocolType.ANTHROPIC_MESSAGES, AnthropicMessagesProtocol::new);
        register(ModelProtocolType.OPENAI_COMPATIBLE, OpenAiCompatibleProtocol::new);
    }

    public void register(ModelProtocolType type, Supplier<ModelProtocol> supplier) {
        if (type == null || supplier == null) {
            return;
        }
        registry.put(type, supplier);
    }

    public ModelProtocol create(ModelProtocolType type) {
        Supplier<ModelProtocol> supplier = registry.get(type);
        if (supplier != null) {
            return supplier.get();
        }
        return new OpenAiCompatibleProtocol();
    }
}
