// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.util.HashMap;
import java.util.Map;

/** Which address answers for which fleet id, plus this device's own id. */
final class Registry {

    private final Map<String, String> addresses = new HashMap<String, String>();
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
}
