// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** This device's own addresses, so a roster entry can be recognised as us. */
final class LocalAddresses implements FleetPayload.LocalCheck {

    private final Set<String> addresses = new HashSet<String>();

    LocalAddresses() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    addresses.add(address.getHostAddress());
                }
            }
        } catch (Exception ignored) {
            // Without NIC details this device is identified from its settings file.
        }
        addresses.add("127.0.0.1");
    }

    @Override
    public boolean isLocal(String host) {
        synchronized (addresses) {
            return addresses.contains(host);
        }
    }
}
