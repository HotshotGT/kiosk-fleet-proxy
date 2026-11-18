// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.util.List;
import java.util.Map;

/**
 * Rewrites the fleet roster the app sends, in HTTP bodies and WebSocket text
 * frames alike.
 *
 * <p>The app builds each entry's link as {@code http://<address>:<port>}, which
 * sends the browser to another origin and out of the dashboard. Every such link
 * becomes {@code <origin>/<id>}. The same pass learns addresses and decides
 * which kiosk this device is, because the roster arrives before anything else
 * does.
 */
final class FleetPayload {

    /** Whether an address is one of this device's own. */
    interface LocalCheck {
        boolean isLocal(String host);
    }

    private FleetPayload() {
    }

    static String rewrite(String body, String origin, Registry registry, LocalCheck local) {
        Object parsed = Json.parseOrNull(body);
        if (parsed == null) {
            return body;
        }
        if (!walk(parsed, origin, registry, local)) {
            return body;
        }
        return Json.stringify(parsed);
    }

    private static boolean walk(Object value, String origin, Registry registry, LocalCheck local) {
        boolean changed = false;
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            changed = entry(map, origin, registry, local);
            for (Object child : map.values()) {
                if (child instanceof Map || child instanceof List) {
                    changed |= walk(child, origin, registry, local);
                }
            }
        } else if (value instanceof List) {
            for (Object child : (List<?>) value) {
                if (child instanceof Map || child instanceof List) {
                    changed |= walk(child, origin, registry, local);
                }
            }
        }
        return changed;
    }

    /**
     * One roster entry. Every kiosk marks itself "self" in its own payload, so a
     * follower's answer must not be able to rename this device: the flag only
     * counts together with one of this device's own addresses.
     */
    private static boolean entry(Map<?, ?> map, String origin, Registry registry, LocalCheck local) {
        Object idValue = map.get("id");
        Object addressValue = map.get("address");
        Object urlValue = map.get("url");
        if (!(idValue instanceof String) || !(addressValue instanceof String) || !(urlValue instanceof String)) {
            return false;
        }
        String id = (String) idValue;
        String address = (String) addressValue;
        if (id.isEmpty() || address.isEmpty()) {
            return false;
        }
        int port = 2324;
        Object portValue = map.get("port");
        if (portValue instanceof Number && ((Number) portValue).doubleValue() > 0) {
            port = (int) ((Number) portValue).doubleValue();
        }
        String link = "http://" + address + ":" + port;
        if (!link.equals(urlValue)) {
            return false;
        }
        registry.set(id, address + ":" + port);
        if (map.get("self") instanceof Boolean && ((Boolean) map.get("self")).booleanValue() && local.isLocal(address)) {
            registry.setSelf(id);
        }
        try {
            ((Map<Object, Object>) map).put("url", origin + "/" + id);
        } catch (RuntimeException unmodifiable) {
            return false;
        }
        return true;
    }
}
