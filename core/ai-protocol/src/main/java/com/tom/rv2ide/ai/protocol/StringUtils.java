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

public final class StringUtils {

    private StringUtils() {
    }

    /**
     * Checks if all characters in {@code text} are either a-z, A-Z, 0-9,
     * or one of the characters in {@code allowedSpecialChars}.
     */
    public static boolean isAllowedIdentifier(String text, String allowedSpecialChars) {
        if (text == null || text.length() == 0) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!isAllowedChar(text.charAt(i), allowedSpecialChars)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if a single character is either a-z, A-Z, 0-9,
     * or one of the characters in {@code allowedSpecialChars}.
     */
    public static boolean isAllowedChar(char ch, String allowedSpecialChars) {
        if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) {
            return true;
        }
        if (allowedSpecialChars != null) {
            for (int i = 0; i < allowedSpecialChars.length(); i++) {
                if (ch == allowedSpecialChars.charAt(i)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 把 Java 风格的 Unicode 转义 {@code \\uXXXX} 解码为对应字符。
     * 用于修复部分模型/服务端把中文错误信息序列化为 {@code \\uXXXX}，
     * 直接展示到聊天里变成一串转义符的问题。
     *
     * <p>仅解码严格匹配 {@code \\u} 后接 4 位十六进制（0-9a-fA-F）的片段；
     * 其它文本（包括不完整的 {@code \\u}、Windows 路径 {@code \\Users} 等）原样保留，
     * 避免误伤正常内容。</p>
     */
    public static String decodeUnicodeEscapes(String text) {
        if (text == null || text.length() < 6) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch == '\\' && i + 5 < text.length() && text.charAt(i + 1) == 'u') {
                int hex = parseHex4(text, i + 2);
                if (hex >= 0) {
                    out.append((char) hex);
                    i += 6;
                    continue;
                }
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    private static int parseHex4(String text, int index) {
        int value = 0;
        for (int j = 0; j < 4; j++) {
            char c = text.charAt(index + j);
            int digit;
            if (c >= '0' && c <= '9') {
                digit = c - '0';
            } else if (c >= 'a' && c <= 'f') {
                digit = c - 'a' + 10;
            } else if (c >= 'A' && c <= 'F') {
                digit = c - 'A' + 10;
            } else {
                return -1;
            }
            value = (value << 4) | digit;
        }
        return value;
    }
}
