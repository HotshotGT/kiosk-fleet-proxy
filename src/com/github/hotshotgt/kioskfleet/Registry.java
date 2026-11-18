// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Which address answers for which fleet id, plus this device's own id. */
final class Registry {

    private final Map<String, String> addresses = new HashMap<String, String>();
    private final Map<String, String> builds = new HashMap<String, String>();
    private String self = "";

    synchronized String get(String id) {
        return addresses.get(id);
    }

    synchronized String self() {
        return self;
    }

    synchronized void setSelf(String id) {
        if (id != null && !id.isEmpty()) {
            self = id;
        }
    }

    synchronized void set(String id, String address) {
        if (id != null && !id.isEmpty() && address != null && !address.isEmpty()) {
            addresses.put(id, address);
        }
    }

    /** The build hash a kiosk serves its static files under, learned from its HTML. */
    synchronized void build(String id, String hash) {
        if (id != null && !id.isEmpty() && hash != null && !hash.isEmpty()) {
            builds.put(id, hash);
        }
    }

    /** Any kiosk known to serve this build, so origin level asset paths can be answered. */
    synchronized String kioskForBuild(String hash) {
        for (Map.Entry<String, String> entry : builds.entrySet()) {
            if (hash.equals(entry.getValue()) && addresses.containsKey(entry.getKey())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Every fleet id this device knows an address for. */
    synchronized List<String> ids() {
        return new ArrayList<String>(addresses.keySet());
    }

    /** Some kiosk with an address, for the rare request that names none. */
    synchronized String anyKiosk() {
        return addresses.isEmpty() ? null : addresses.keySet().iterator().next();
    }
}
