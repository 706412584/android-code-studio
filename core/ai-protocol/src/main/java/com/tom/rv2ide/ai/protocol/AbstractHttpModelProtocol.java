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

import com.tom.rv2ide.ai.protocol.ModelCompletionException;
import com.tom.rv2ide.ai.protocol.ModelCancellationToken;
import com.tom.rv2ide.ai.tool.api.ErrorLog;
import com.tom.rv2ide.ai.protocol.ErrorLogRedactor;
import com.tom.rv2ide.ai.protocol.AiBehaviorSettings;
import com.tom.rv2ide.ai.protocol.UrlPolicy;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.json.JSONObject;

abstract class AbstractHttpModelProtocol implements ModelProtocol {
    protected interface SseEventHandler {
        void onEvent(String eventType, String data) throws Exception;
    }

    protected static final class SseStreamCompleteException extends Exception {
    }

    protected static final int REASONING_BUDGET_LOW = 1024;
    protected static final int REASONING_BUDGET_HIGH = 8192;
    protected static final int REASONING_BUDGET_MAX = 16000;
    protected static final int REASONING_BUDGET_DEFAULT = 4096;

    /** 流空闲看门狗阈值：90s 无任何 SSE 数据视为卡死（借鉴 cc-haha streamWatchdog 默认值）。 */
    protected static final long STREAM_IDLE_TIMEOUT_MS = 90_000L;

    protected int thinkingBudget(String effort) {
        if (AiBehaviorSettings.REASONING_LOW.equals(effort)) {
            return REASONING_BUDGET_LOW;
        }
        if (AiBehaviorSettings.REASONING_HIGH.equals(effort)) {
            return REASONING_BUDGET_HIGH;
        }
        if (AiBehaviorSettings.REASONING_MAX.equals(effort)) {
            return REASONING_BUDGET_MAX;
        }
        return REASONING_BUDGET_DEFAULT;
    }

    protected String postJson(String url, JSONObject body, Map<String, String> headers) throws ModelCompletionException {
        return postJson(url, body, headers, null);
    }

    protected String postJson(
            String url,
            JSONObject body,
            Map<String, String> headers,
            ModelCancellationToken cancellationToken
    ) throws ModelCompletionException {
        HttpURLConnection connection = null;
        String response = "";
        StringBuilder sseLog = new StringBuilder();
        int code = -1;
        try {
            connection = openJsonPost(url, body, headers, "application/json");
            HttpURLConnection activeConnection = connection;
            if (cancellationToken != null) {
                cancellationToken.onCancel(activeConnection::disconnect);
            }

            code = connection.getResponseCode();
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                return "";
            }
            response = readAll(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (code < 200 || code >= 300) {
                ModelCompletionException exception = new ModelCompletionException("HTTP " + code + ": " + response);
                logHttpError("http", url, headers, body, code, response, exception);
                throw exception;
            }
            return response;
        } catch (ModelCompletionException e) {
            throw e;
        } catch (IOException e) {
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                return "";
            }
            logHttpError("http_io", url, headers, body, code, response, e);
            throw new ModelCompletionException("Model communication failed: " + e.getMessage(), e);
        } catch (Exception e) {
            logHttpError("http_error", url, headers, body, code, response, e);
            throw new ModelCompletionException("Model communication failed: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    protected void postJsonSse(
            String url,
            JSONObject body,
            Map<String, String> headers,
            ModelCancellationToken cancellationToken,
            SseEventHandler handler
    ) throws ModelCompletionException {
        HttpURLConnection connection = null;
        String response = "";
        StringBuilder sseLog = new StringBuilder();
        int code = -1;
        // 流空闲看门狗：90s 无数据（含首 token）视为卡死，disconnect 解除阻塞读
        java.util.concurrent.atomic.AtomicBoolean watchdogFired = new java.util.concurrent.atomic.AtomicBoolean(false);
        com.tom.rv2ide.ai.protocol.StreamWatchdog watchdog = new com.tom.rv2ide.ai.protocol.StreamWatchdog(STREAM_IDLE_TIMEOUT_MS);
        try {
            connection = openJsonPost(url, body, headers, "text/event-stream");
            HttpURLConnection activeConnection = connection;
            if (cancellationToken != null) {
                cancellationToken.onCancel(activeConnection::disconnect);
            }

            code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                response = readAll(connection.getErrorStream());
                String retryAfter = connection.getHeaderField("Retry-After");
                ModelCompletionException exception = new ModelCompletionException("HTTP " + code + ": " + response)
                        .withHttpStatus(code)
                        .withKind(com.tom.rv2ide.ai.protocol.ModelApiError.fromThrowable(
                                new Exception("HTTP " + code + ": " + response)).kind())
                        .withRetryAfterMs(parseRetryAfterSeconds(retryAfter));
                logHttpError("sse_http", url, headers, body, code, response, exception);
                throw exception;
            }

            watchdog.start(reason -> {
                watchdogFired.set(true);
                activeConnection.disconnect();
            });
            readSse(connection.getInputStream(), cancellationToken, handler, sseLog, watchdog);
        } catch (SseStreamCompleteException e) {
            return;
        } catch (ModelCompletionException e) {
            throw e;
        } catch (IOException e) {
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                return;
            }
            response = sseLog.toString();
            logHttpError("sse_io", url, headers, body, code, response, e);
            ModelCompletionException exception = new ModelCompletionException("Model stream communication failed: " + e.getMessage(), e)
                    .withKind(com.tom.rv2ide.ai.protocol.ModelApiError.fromThrowable(e, watchdogFired.get()).kind())
                    .withWatchdogFired(watchdogFired.get());
            throw exception;
        } catch (Exception e) {
            response = sseLog.toString();
            logHttpError("sse_error", url, headers, body, code, response, e);
            throw new ModelCompletionException("Model stream communication failed: " + e.getMessage(), e);
        } finally {
            watchdog.stop();
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Retry-After header（秒）→ 毫秒；缺失/非数字返回 0。 */
    private static long parseRetryAfterSeconds(String value) {
        if (value == null || value.length() == 0) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim()) * 1000L;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    protected String endpoint(String baseUrl, String suffix) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith(suffix)) {
            return base;
        }
        return base + suffix;
    }

    private HttpURLConnection openJsonPost(String url, JSONObject body, Map<String, String> headers, String accept) throws Exception {
        java.net.URL target = new URL(UrlPolicy.requireHttpOrLocalCleartextUrl(url, "Model API URL"));
        HttpURLConnection connection = (HttpURLConnection) target.openConnection(
                com.tom.rv2ide.ai.protocol.AppProxy.proxyFor(target.getHost()));
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(600000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("Connection", "close");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getValue() != null && header.getValue().length() > 0) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
        }

        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8));
        writer.write(com.tom.rv2ide.ai.protocol.StableJson.stringify(body));
        writer.flush();
        writer.close();
        return connection;
    }

    private void readSse(InputStream stream, ModelCancellationToken cancellationToken, SseEventHandler handler, StringBuilder sseLog) throws Exception {
        readSse(stream, cancellationToken, handler, sseLog, null);
    }

    private void readSse(InputStream stream, ModelCancellationToken cancellationToken, SseEventHandler handler,
                         StringBuilder sseLog, com.tom.rv2ide.ai.protocol.StreamWatchdog watchdog) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        try {
            StringBuilder data = new StringBuilder();
            String eventType = "";
            String line;
            while ((cancellationToken == null || !cancellationToken.isCancelled()) && (line = reader.readLine()) != null) {
                if (watchdog != null) {
                    watchdog.onActivity();
                }
                appendSseLogLine(sseLog, line);
                if (line.length() == 0) {
                    if (data.length() > 0) {
                        handler.onEvent(eventType, data.toString());
                        data.setLength(0);
                        eventType = "";
                    }
                    continue;
                }
                if (line.startsWith("event:")) {
                    eventType = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    String value = line.substring("data:".length());
                    data.append(value.startsWith(" ") ? value.substring(1) : value);
                }
            }
            if ((cancellationToken == null || !cancellationToken.isCancelled()) && data.length() > 0) {
                handler.onEvent(eventType, data.toString());
            }
        } finally {
            reader.close();
        }
    }

    private void appendSseLogLine(StringBuilder sseLog, String line) {
        if (sseLog == null) {
            return;
        }
        if (sseLog.length() < 200000) {
            sseLog.append(line).append('\n');
        }
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        reader.close();
        return builder.toString();
    }

    private void logHttpError(String type, String url, Map<String, String> headers, JSONObject body, int code, String response, Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        builder.append("URL: ").append(url == null ? "" : url).append('\n');
        builder.append("Status: ").append(code).append("\n\n");
        builder.append("Request headers:\n").append(headers == null ? "{}" : headers.toString()).append("\n\n");
        builder.append("Request body:\n").append(body == null ? "" : body.toString()).append("\n\n");
        builder.append("Response:\n").append(response == null ? "" : response);
        ErrorLog.record(type, throwable == null ? type : throwable.getMessage(), throwable, ErrorLogRedactor.redact(builder.toString()));
    }

    protected void logParseError(String type, String rawResponse, Throwable throwable) {
        ErrorLog.record(type, throwable == null ? type : throwable.getMessage(), throwable,
                "Raw response:\n" + (rawResponse == null ? "" : rawResponse));
    }
}
