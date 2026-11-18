// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.util.Map;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Kiosk Fleet Proxy, plugin edition.
 *
 * <p>The app's fleet switcher navigates to {@code http://<device>:2324}, and the
 * admin server answers with {@code X-Frame-Options: SAMEORIGIN}, which no browser
 * lets a Home Assistant iframe override. The Go build of this project solved that
 * with a reverse proxy that serves the whole fleet from one origin and adds
 * {@code frame-ancestors} on the way out.
 *
 * <p>This is the plugin port. As a plugin it needs no adb, no root and no write to
 * {@code /system}, installs from Plugin Manager, and a fleet leader pushes it to its
 * followers.
 *
 * <p>Status: pre-alpha. The class loads, registers and reports, but it does not
 * listen yet. Everything a working version needs is the port of the Go listener:
 * path routing by kiosk id, JSON and WebSocket payload rewriting, the
 * {@code frame-ancestors} header, and address discovery.
 */
public final class FleetProxyPlugin implements KioskPlugin {

    /** Shown in the plugin's status line so an installed build is identifiable. */
    static final String VERSION = "0.1.0";

    private PluginHost host;
    private volatile boolean running;

    @Override
    public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        running = true;
        report("Kiosk Fleet Proxy " + VERSION + " loaded. The proxy listener is not ported yet.");
    }

    @Override
    public void configure(Map<String, Object> settings) {
        // No settings yet. The listener port and the allowed ancestor origin will
        // arrive with the port itself.
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        // No commands yet.
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        // No subscriptions yet.
    }

    @Override
    public void stop() {
        running = false;
        host = null;
    }

    /** True while the plugin believes it is serving. Exposed for the tests. */
    boolean isRunning() {
        return running;
    }

    private void report(String message) {
        final PluginHost h = host;
        if (h == null) {
            return;
        }
        try {
            h.status(message, false);
        } catch (RuntimeException ignored) {
            // Status is cosmetic: an older host without SDK 1 must not break start.
        }
    }
}
