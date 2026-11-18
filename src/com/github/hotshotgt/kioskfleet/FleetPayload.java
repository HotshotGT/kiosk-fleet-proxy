// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.util.List;
import java.util.Map;

/**
 * Rewrites the fleet roster, in HTTP bodies and WebSocket text frames alike.
 *
 * <p>The app links each kiosk as {@code http://<address>:<port>}, which sends the
 * browser to another origin and out of the dashboard. Every such link becomes
 * {@code <origin>/<id>}. The same pass learns addresses, because the roster is
 * the only place they appear.
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
        if (parsed == null || !walk(parsed, origin, registry, local)) {
            return body;
        }
        return Json.stringify(parsed);
    }

    private static boolean walk(Object value, String origin, Registry registry, LocalCheck local) {
        boolean changed = false;
        if (value instanceof Map) {
            changed = entry((Map<?, ?>) value, origin, registry, local);
            for (Object child : ((Map<?, ?>) value).values()) {
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
     * One roster entry. Every kiosk marks itself self in its own payload, so the
     * flag only counts together with one of this device's own addresses: a
     * follower's answer must not be able to rename this device.
     */
    @SuppressWarnings("unchecked")
    private static boolean entry(Map<?, ?> map, String origin, Registry registry, LocalCheck local) {
        Object idValue = map.get("id");
        Object addressValue = map.get("address");
        Object urlValue = map.get("url");
        if (!(idValue instanceof String) || !(addressValue instanceof String) || !(urlValue instanceof String)) {
            return false;
        }
        String id = (String) idValue;
        String address = (String) addressValue;
        Object portValue = map.get("port");
        int port = portValue instanceof Number && ((Number) portValue).doubleValue() > 0
                ? (int) ((Number) portValue).doubleValue()
                : 2324;
        if (id.isEmpty() || address.isEmpty() || !("http://" + address + ":" + port).equals(urlValue)) {
            return false;
        }
        registry.set(id, address + ":" + port);
        if (Boolean.TRUE.equals(map.get("self")) && local.isLocal(address)) {
            registry.setSelf(id);
        }
        ((Map<Object, Object>) map).put("url", origin + "/" + id);
        return true;
    }
}
