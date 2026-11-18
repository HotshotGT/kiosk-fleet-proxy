// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The app's fleet settings, read from inside the app's own process.
 *
 * <p>No root and no file parsing: a plugin runs in the kiosk's process, so
 * {@code ActivityThread.currentApplication()} hands over the preferences the app
 * itself writes. If they cannot be read the registry starts empty and fills from
 * the roster that passes through the proxy.
 */
final class AppSettings {

    static final String FLEET_ID = "flutter.ks.fleet.id";
    static final String FOLLOWERS = "flutter.ks.fleet.followers";

    private AppSettings() {
    }

    /** Seeds the registry: this device's id, then every follower already known. */
    static int seed(Registry registry, String selfAddress) {
        Map<String, String> values = read();
        int known = 0;
        String id = values.get(FLEET_ID);
        if (id != null && !id.isEmpty()) {
            registry.setSelf(id);
            registry.set(id, selfAddress);
            known++;
        }
        Object parsed = Json.parseOrNull(values.get(FOLLOWERS));
        if (!(parsed instanceof List)) {
            return known;
        }
        for (Object item : (List<?>) parsed) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> entry = (Map<?, ?>) item;
            Object followerId = entry.get("id");
            Object address = entry.get("address");
            if (!(followerId instanceof String) || !(address instanceof String)) {
                continue;
            }
            Object portValue = entry.get("port");
            int port = portValue instanceof Number && ((Number) portValue).doubleValue() > 0
                    ? (int) ((Number) portValue).doubleValue()
                    : 2324;
            registry.set((String) followerId, address + ":" + port);
            known++;
        }
        return known;
    }

    static Map<String, String> read() {
        Map<String, String> values = new HashMap<String, String>();
        try {
            Object application = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            Object shared = application.getClass()
                    .getMethod("getSharedPreferences", String.class, int.class)
                    .invoke(application, "FlutterSharedPreferences", 0);
            Object all = shared.getClass().getMethod("getAll").invoke(shared);
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) all).entrySet()) {
                if (entry.getValue() != null) {
                    values.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        } catch (Throwable unreadable) {
            // An empty map is fine: the roster learned through the proxy fills it.
        }
        return values;
    }
}
