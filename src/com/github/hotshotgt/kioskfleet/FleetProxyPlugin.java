// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.io.IOException;
import java.util.Map;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Kiosk Fleet Proxy.
 *
 * <p>Serves the whole fleet from one origin so the app's device switcher keeps
 * working inside a dashboard iframe. See {@link HttpProxyServer}.
 *
 * <p>One setting: {@code frameAncestors}, the space separated origins allowed to
 * frame the page. Empty means only this origin may frame it.
 */
public final class FleetProxyPlugin implements KioskPlugin {

    static final String VERSION = "0.5.1";
    static final int PORT = 8790;

    private final Registry registry = new Registry();
    private final LocalAddresses local = new LocalAddresses();

    private PluginHost host;
    private HttpProxyServer server;
    private String ancestor = "";

    @Override
    public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        ancestor = ancestorOf(settings, "");
        int known = AppSettings.seed(registry, "127.0.0.1:" + 2324);
        open();
        report("Kiosk Fleet Proxy " + VERSION + " on :" + PORT + ", " + known + " fleet entries"
                + (registry.self().isEmpty() ? ", no id yet" : ", this kiosk is " + registry.self()));
    }

    @Override
    public void configure(Map<String, Object> settings) {
        String wanted = ancestorOf(settings, ancestor);
        if (!wanted.equals(ancestor)) {
            ancestor = wanted;
            close();
            open();
        }
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        // No commands.
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        // No subscriptions.
    }

    @Override
    public void stop() {
        close();
        host = null;
    }

    private static String ancestorOf(Map<String, Object> settings, String fallback) {
        Object value = settings == null ? null : settings.get("frameAncestors");
        return value == null ? fallback : String.valueOf(value).trim();
    }

    private void open() {
        HttpProxyServer candidate = new HttpProxyServer(registry, local, ancestor, PORT, new HttpProxyServer.Logger() {
            @Override
            public void log(String message) {
                report(message);
            }
        });
        try {
            candidate.start();
            server = candidate;
            candidate.learnBuildsAsync();
        } catch (IOException busy) {
            server = null;
            report("Kiosk Fleet Proxy could not listen on :" + PORT + ": " + busy.getMessage(), true);
        }
    }

    private void close() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private void report(String message) {
        report(message, false);
    }

    private void report(String message, boolean error) {
        final PluginHost h = host;
        if (h == null) {
            return;
        }
        try {
            h.status(message, error);
        } catch (RuntimeException olderHost) {
            // Status is cosmetic: a host without SDK 1 must not break start.
        }
    }
}
