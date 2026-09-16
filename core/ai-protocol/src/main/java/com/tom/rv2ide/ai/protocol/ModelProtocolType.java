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

public enum ModelProtocolType {
    OPENAI_COMPATIBLE("OpenAI", true),
    CODEX_RESPONSES("Codex", true),
    ANTHROPIC_MESSAGES("Anthropic", false),
    LOCAL_GGUF("Local", false);

    private final String label;
    private final boolean dedicatedCompression;

    ModelProtocolType(String label, boolean dedicatedCompression) {
        this.label = label;
        this.dedicatedCompression = dedicatedCompression;
    }

    public String getLabel() {
        return label;
    }

    /** 该协议类型是否支持独立压缩模型。 */
    public boolean supportsDedicatedCompression() {
        return dedicatedCompression;
    }

    public static ModelProtocolType fromStorage(String value) {
        if (value == null) {
            return OPENAI_COMPATIBLE;
        }
        String normalized = value.trim();
        if ("openai".equalsIgnoreCase(normalized) || "openai_compatible".equalsIgnoreCase(normalized)) {
            return OPENAI_COMPATIBLE;
        }
        if ("codex".equalsIgnoreCase(normalized) || "codex_responses".equalsIgnoreCase(normalized)) {
            return CODEX_RESPONSES;
        }
        if ("anthropic".equalsIgnoreCase(normalized) || "claude".equalsIgnoreCase(normalized)
                || "anthropic_messages".equalsIgnoreCase(normalized)) {
            return ANTHROPIC_MESSAGES;
        }
        if ("local".equalsIgnoreCase(normalized) || "gguf".equalsIgnoreCase(normalized)
                || "local_gguf".equalsIgnoreCase(normalized)) {
            return LOCAL_GGUF;
        }
        for (ModelProtocolType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return OPENAI_COMPATIBLE;
    }
}
