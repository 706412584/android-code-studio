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

import com.tom.rv2ide.ai.protocol.ModelApiError;

public final class ModelCompletionException extends Exception {
    private int httpStatus = -1;
    private ModelApiError.Kind kind = ModelApiError.Kind.UNKNOWN;
    private long retryAfterMs = 0;
    private String partialText;
    private String partialReasoning;
    private boolean crossedToolBoundary;
    private boolean watchdogFired;

    public ModelCompletionException(String message) {
        super(message);
    }

    public ModelCompletionException(String message, Throwable cause) {
        super(message, cause);
    }

    public int httpStatus() {
        return httpStatus;
    }

    public ModelCompletionException withHttpStatus(int status) {
        this.httpStatus = status;
        return this;
    }

    public ModelApiError.Kind kind() {
        return kind;
    }

    public ModelCompletionException withKind(ModelApiError.Kind kind) {
        this.kind = kind;
        return this;
    }

    public long retryAfterMs() {
        return retryAfterMs;
    }

    public ModelCompletionException withRetryAfterMs(long retryAfterMs) {
        this.retryAfterMs = retryAfterMs;
        return this;
    }

    public String partialText() {
        return partialText;
    }

    public String partialReasoning() {
        return partialReasoning;
    }

    public ModelCompletionException withPartial(String text, String reasoning, boolean crossedToolBoundary) {
        this.partialText = text == null ? "" : text;
        this.partialReasoning = reasoning == null ? "" : reasoning;
        this.crossedToolBoundary = crossedToolBoundary;
        return this;
    }

    public boolean hasPartial() {
        return (partialText != null && partialText.length() > 0)
                || (partialReasoning != null && partialReasoning.length() > 0);
    }

    public boolean crossedToolBoundary() {
        return crossedToolBoundary;
    }

    public boolean watchdogFired() {
        return watchdogFired;
    }

    public ModelCompletionException withWatchdogFired(boolean fired) {
        this.watchdogFired = fired;
        return this;
    }
}
