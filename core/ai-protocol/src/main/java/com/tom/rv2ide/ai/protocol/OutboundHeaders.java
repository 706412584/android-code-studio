/*
 * This file is part of AndroidCodeStudio.
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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.protocol;

import java.util.Map;

/**
 * 把「出站 User-Agent」与「额外自定义请求头」应用到模型请求的 header 表。
 *
 * <p>为什么需要：一些中转/网关会校验固定 UA（或要求带特定头）。协议层原本只设鉴权头，
 * UA 走运行时默认，无法通过配置改变。这里统一注入，且在协议各自的鉴权头构造之后调用，
 * 使 {@link ModelConfig#getExtraHeaders()} 里的同名键可以覆盖默认（含 UA）。
 */
public final class OutboundHeaders {

    private OutboundHeaders() { }

    public static void apply(Map<String, String> headers, ModelConfig config) {
        if (headers == null || config == null) {
            return;
        }
        String userAgent = config.getUserAgent();
        if (userAgent != null
                && !userAgent.isEmpty()
                && !containsIgnoreCase(headers, "User-Agent")) {
            headers.put("User-Agent", userAgent);
        }
        for (Map.Entry<String, String> entry : config.getExtraHeaders().entrySet()) {
            String key = entry.getKey();
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            headers.put(key.trim(), entry.getValue() == null ? "" : entry.getValue());
        }
    }

    private static boolean containsIgnoreCase(Map<String, String> headers, String key) {
        for (String existing : headers.keySet()) {
            if (existing != null && existing.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }
}
