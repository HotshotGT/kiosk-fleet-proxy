// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.util.Collections;

/** Loads the plugin and checks its lifecycle without a device. */
public final class FleetProxyPluginTest {

    public static void main(String[] args) throws Exception {
        final FleetProxyPlugin plugin = new FleetProxyPlugin();
        check(!plugin.isRunning(), "a new plugin is stopped");
        // A null host is the harshest case: start() must not depend on one.
        plugin.start(null, Collections.<String, Object>emptyMap());
        check(plugin.isRunning(), "start() marks it running");
        plugin.stop();
        check(!plugin.isRunning(), "stop() marks it stopped");
        plugin.stop();
        check(!plugin.isRunning(), "stop() twice is safe");
        System.out.println("FleetProxyPluginTest ok");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
