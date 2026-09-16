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

import com.tom.rv2ide.ai.protocol.AiBehaviorSettings;
import com.tom.rv2ide.ai.protocol.ModelConfig;
import com.tom.rv2ide.ai.protocol.ModelContextParser;
import java.util.Locale;

/** Maps the app's shared reasoning levels to the subset accepted by each provider. */
public final class ReasoningCompatibility {
    private ReasoningCompatibility() {
    }

    public static ModelRequestOptions adapt(ModelConfig config, ModelRequestOptions options) {
        ModelRequestOptions source = options == null ? ModelRequestOptions.defaults() : options;
        String effort = compatibleEffort(config, source.getReasoningEffort());
        if (effort.equals(source.getReasoningEffort())) {
            return source;
        }
        return new ModelRequestOptions(effort, source.isPreserveReasoning(), source.getTools());
    }

    public static String compatibleEffort(ModelConfig config, String requestedEffort) {
        String effort = AiBehaviorSettings.normalizeReasoningEffort(requestedEffort);
        if (!isGlm(config)) {
            return effort;
        }
        if (AiBehaviorSettings.REASONING_MAX.equals(effort)) {
            return AiBehaviorSettings.REASONING_MAX;
        }
        if (AiBehaviorSettings.REASONING_OFF.equals(effort)
                || AiBehaviorSettings.REASONING_LOW.equals(effort)) {
            return AiBehaviorSettings.REASONING_LOW;
        }
        return AiBehaviorSettings.REASONING_HIGH;
    }

    public static boolean isGlm(ModelConfig config) {
        if (config == null) {
            return false;
        }
        String baseUrl = lower(config.getBaseUrl());
        String provider = lower(config.getProviderLabel());
        String model = lower(ModelContextParser.apiModelId(config));
        return baseUrl.contains("bigmodel")
                || baseUrl.contains("zhipu")
                || provider.contains("zhipu")
                || provider.contains("glm")
                || provider.contains("智谱")
                || model.contains("glm");
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
