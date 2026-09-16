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

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Locale;

/**
 * 全局出网代理持有者。
 *
 * <p>HTTP 出口（SimpleHttpClient、模型协议）在 openConnection 时经 {@link #proxyFor(String)}
 * 获取显式 Proxy；localhost/127.0.0.1/10.0.2.2 永远直连，避免本地服务被代理自锁。</p>
 */
public final class AppProxy {

    private static volatile String host = "";
    private static volatile int port = 0;

    private AppProxy() {
    }

    /** 应用（或清除）代理。host 为空/端口非法时清除。 */
    public static void apply(String proxyHost, int proxyPort) {
        if (proxyHost == null || proxyHost.trim().isEmpty() || proxyPort <= 0 || proxyPort > 65535) {
            host = "";
            port = 0;
            return;
        }
        host = proxyHost.trim();
        port = proxyPort;
    }

    public static boolean isEnabled() {
        return host.length() > 0 && port > 0;
    }

    public static String host() {
        return host;
    }

    public static int port() {
        return port;
    }

    /** 代理 URL（http://host:port）；未启用返回空串。 */
    public static String proxyUrl() {
        return isEnabled() ? "http://" + host + ":" + port : "";
    }

    /**
     * 按 URL host 决定连接用 Proxy：目标为本地回环时 {@link Proxy#NO_PROXY}，
     * 其余在代理启用时返回 HTTP 代理。
     */
    public static Proxy proxyFor(String urlHost) {
        if (!isEnabled() || isLocalHost(urlHost)) {
            return Proxy.NO_PROXY;
        }
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
    }

    /** localhost / 127.0.0.1 / ::1 / 10.0.2.2 视为本地。 */
    public static boolean isLocalHost(String urlHost) {
        if (urlHost == null) {
            return false;
        }
        String value = urlHost.toLowerCase(Locale.ROOT);
        return "localhost".equals(value)
                || "127.0.0.1".equals(value)
                || "::1".equals(value)
                || "10.0.2.2".equals(value);
    }
}
