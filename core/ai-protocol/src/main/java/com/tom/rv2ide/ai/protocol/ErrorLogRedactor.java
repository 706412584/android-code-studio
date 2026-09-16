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

import java.util.regex.Pattern;

public final class ErrorLogRedactor {
    /**
     * 单次脱敏的安全输入上限。超过该长度的日志内容（如嵌入大段 base64 的模型响应体）
     * 会在前缀脱敏后截断，避免 {@code Matcher.replaceAll} 对几十 MB 字符串反复复制导致 OOM。
     */
    private static final int MAX_SAFE_LENGTH = 1 << 20;

    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)(Authorization\\s*[:=]\\s*)(Bearer\\s+)?[^\\r\\n,}]+?");
    private static final Pattern API_KEY_HEADER = Pattern.compile("(?i)((x-api-key|api-key)\\s*[:=]\\s*)[^\\r\\n,}]+?");
    private static final Pattern JSON_SECRET = Pattern.compile("(?i)(\\\"(?:api[_-]?key|authorization|access[_-]?token|refresh[_-]?token|password|secret|private[_-]?key)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")");
    private static final Pattern LONG_BASE64 = Pattern.compile("(?i)(data:image/[^;]+;base64,)[A-Za-z0-9+/=]{80,}");
    private static final Pattern B64_JSON = Pattern.compile("(?i)(\\\"b64_json\\\"\\s*:\\s*\\\")[^\\\"]{80,}(\\\")");

    private static final String TRUNCATED_SUFFIX = "\n... [REDACTED_TRUNCATED]";

    private ErrorLogRedactor() {
    }

    public static String redact(String value) {
        if (value == null || value.length() == 0) {
            return "";
        }
        String safe = value;
        boolean truncated = false;
        if (value.length() > MAX_SAFE_LENGTH) {
            safe = value.substring(0, MAX_SAFE_LENGTH);
            truncated = true;
        }
        String redacted = AUTHORIZATION.matcher(safe).replaceAll("$1$2[REDACTED]");
        redacted = API_KEY_HEADER.matcher(redacted).replaceAll("$1[REDACTED]");
        redacted = JSON_SECRET.matcher(redacted).replaceAll("$1[REDACTED]$2");
        redacted = LONG_BASE64.matcher(redacted).replaceAll("$1[BASE64_REDACTED]");
        redacted = B64_JSON.matcher(redacted).replaceAll("$1[BASE64_REDACTED]$2");
        return truncated ? redacted + TRUNCATED_SUFFIX : redacted;
    }
}
