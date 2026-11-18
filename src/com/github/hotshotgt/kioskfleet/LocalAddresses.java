// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** This device's own addresses, so a candidate kiosk entry can be recognised as us. */
final class LocalAddresses implements FleetPayload.LocalCheck {

    private final Set<String> addresses = new HashSet<String>();

    LocalAddresses() {
        refresh();
    }

    void refresh() {
        Set<String> found = new HashSet<String>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    found.add(address.getHostAddress());
                }
            }
        } catch (SocketException ignored) {
            // Keep whatever was found before; an unreachable match only means
            // this device is identified from the settings file instead.
        }
        synchronized (addresses) {
            addresses.clear();
            addresses.addAll(found);
        }
        addresses.add("127.0.0.1");
    }

    @Override
    public boolean isLocal(String host) {
        synchronized (addresses) {
            return addresses.contains(host);
        }
    }

    Set<String> snapshot() {
        synchronized (addresses) {
            return new HashSet<String>(addresses);
        }
    }
}
