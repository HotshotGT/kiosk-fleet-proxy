// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.io.IOException;
import java.util.Map;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Kiosk Fleet Proxy, plugin edition.
 *
 * <p>Serves the whole fleet from one origin so the app's device switcher keeps
 * working inside a dashboard iframe, which it cannot do directly: dart:io
 * answers {@code X-Frame-Options: SAMEORIGIN} and no browser lets a page
 * override that. See {@link HttpProxyServer}.
 *
 * <p>Settings: {@code port} (default 8790) and {@code frameAncestors}, the space
 * separated origins allowed to frame this page. With no ancestor given the page
 * is only framable by itself.
 */
public final class FleetProxyPlugin implements KioskPlugin {

    static final String VERSION = "0.2.0";
    static final int DEFAULT_PORT = 8790;

    private final Registry registry = new Registry();
    private final LocalAddresses local = new LocalAddresses();

    private PluginHost host;
    private HttpProxyServer server;
    private int port = DEFAULT_PORT;
    private String ancestor = "";

    @Override
    public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        applySettings(settings);
        int seeded = AppSettings.seed(registry, "127.0.0.1:" + 2324);
        local.refresh();
        startServer();
        report("Kiosk Fleet Proxy " + VERSION + " listening on " + port + ", " + seeded
                + " fleet entries known" + (registry.self().isEmpty() ? ", no id yet" : ", this kiosk is " + registry.self()));
    }

    @Override
    public void configure(Map<String, Object> settings) {
        int previousPort = port;
        String previousAncestor = ancestor;
        applySettings(settings);
        if (port != previousPort || !ancestor.equals(previousAncestor)) {
            stopServer();
            startServer();
            report("Kiosk Fleet Proxy listening on " + port);
        }
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        if ("restart".equals(command)) {
            stopServer();
            local.refresh();
            startServer();
            report("Kiosk Fleet Proxy listening on " + port);
        }
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        // Nothing subscribes yet; the roster arrives through the proxy itself.
    }

    @Override
    public void stop() {
        stopServer();
        host = null;
    }

    private void applySettings(Map<String, Object> settings) {
        if (settings == null) {
            return;
        }
        Object portValue = settings.get("port");
        if (portValue instanceof Number) {
            int wanted = ((Number) portValue).intValue();
            if (wanted > 1024 && wanted < 65536) {
                port = wanted;
            }
        } else if (portValue != null) {
            try {
                int wanted = Integer.parseInt(String.valueOf(portValue).trim());
                if (wanted > 1024 && wanted < 65536) {
                    port = wanted;
                }
            } catch (NumberFormatException ignored) {
                // Keep the previous port.
            }
        }
        Object ancestors = settings.get("frameAncestors");
        if (ancestors != null) {
            ancestor = String.valueOf(ancestors).trim();
        }
    }

    private void startServer() {
        HttpProxyServer candidate = new HttpProxyServer(registry, local, ancestor, port, new HttpProxyServer.Logger() {
            @Override
            public void log(String message) {
                report(message);
            }
        });
        try {
            candidate.start();
            server = candidate;
        } catch (IOException busy) {
            server = null;
            report("Kiosk Fleet Proxy could not listen on " + port + ": " + busy.getMessage(), true);
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    /** True while the listener is up. Exposed for the tests and the status line. */
    boolean isRunning() {
        return server != null;
    }

    void report(String message) {
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
