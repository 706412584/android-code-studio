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

import com.tom.rv2ide.ai.protocol.ReasoningRequestContext;
import com.tom.rv2ide.ai.protocol.ReasoningRequestStrategy;
import org.json.JSONObject;

public final class KimiReasoningStrategy implements ReasoningRequestStrategy {
    @Override
    public boolean matches(String baseUrl, String modelId) {
        return baseUrl.contains("moonshot") || baseUrl.contains("kimi") || modelId.contains("kimi")
                || modelId.contains("moonshot");
    }

    @Override
    public void apply(JSONObject body, ReasoningRequestContext context) throws Exception {
        JSONObject thinking = new JSONObject().put("type", context.isEnabled() ? "enabled" : "disabled");
        if (context.isPreserveReasoning()) {
            thinking.put("keep", "all");
        }
        body.put("thinking", thinking);
        // Kimi 官方要求 temperature 必须 >= 1.0，低于该值会报错。
        body.put("temperature", Math.max(1.0, body.optDouble("temperature", 0.2)));
    }
}
