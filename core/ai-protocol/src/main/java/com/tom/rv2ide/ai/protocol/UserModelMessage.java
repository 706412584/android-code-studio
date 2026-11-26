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

public final class UserModelMessage extends ModelMessage {
    private final String displayContent;
    private final boolean userAuthored;

    public UserModelMessage(String content) {
        this(content, "", content);
    }

    public UserModelMessage(String content, String rawInputJson) {
        this(content, rawInputJson, content);
    }

    public UserModelMessage(String content, String rawInputJson, String displayContent) {
        this(content, rawInputJson, displayContent, true);
    }

    public UserModelMessage(
            String content, String rawInputJson, String displayContent, boolean userAuthored) {
        super(content, "", rawInputJson);
        this.displayContent = displayContent == null ? content : displayContent;
        this.userAuthored = userAuthored;
    }

    public String getDisplayContent() {
        return displayContent;
    }

    /** 消息的协议角色与来源分离；模型投递不能授予用户权限。 */
    public boolean isUserAuthored() {
        return userAuthored;
    }

    @Override
    public String getRole() {
        return "user";
    }
}
