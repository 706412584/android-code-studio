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

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StringTemplate {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]+)\\}\\}");
    private final String template;

    public StringTemplate(String template) {
        this.template = template == null ? "" : template;
    }

    public String render(Map<String, String> values) {
        // Substitute the template once; placeholders inside inserted text are literal data.
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = values.containsKey(key) ? values.get(key) : matcher.group();
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement == null ? "" : replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString().trim();
    }
}
