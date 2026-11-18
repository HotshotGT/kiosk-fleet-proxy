// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.io.File;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The app's own settings, read from inside the app's process.
 *
 * <p>A plugin runs in the kiosk's process, so the Flutter preferences are already
 * there: no root, no second copy, and no need to parse a file on disk when the
 * preferences can be asked directly. The file is only a fallback for the case
 * where reflection is refused.
 */
final class AppSettings {

    static final String FLEET_ID = "flutter.ks.fleet.id";
    static final String FOLLOWERS = "flutter.ks.fleet.followers";

    private static final String PACKAGE = "me.jxl.kiosk_satellite";

    private AppSettings() {
    }

    /** Seeds the registry: this device's own id, then every follower it knows. */
    static int seed(Registry registry, String selfAddress) {
        Map<String, String> values = read();
        int known = 0;
        String id = trim(values.get(FLEET_ID));
        if (!id.isEmpty()) {
            registry.setSelf(id);
            if (selfAddress != null && !selfAddress.isEmpty()) {
                registry.set(id, selfAddress);
            }
            known++;
        }
        for (Map.Entry<String, String> follower : followers(values.get(FOLLOWERS)).entrySet()) {
            registry.set(follower.getKey(), follower.getValue());
            known++;
        }
        return known;
    }

    /** id to address:port, as the fleet settings list them. */
    static Map<String, String> followers(String json) {
        Map<String, String> followers = new LinkedHashMap<String, String>();
        Object parsed = Json.parseOrNull(json);
        if (!(parsed instanceof List)) {
            return followers;
        }
        for (Object item : (List<?>) parsed) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> entry = (Map<?, ?>) item;
            Object id = entry.get("id");
            Object address = entry.get("address");
            if (!(id instanceof String) || !(address instanceof String)) {
                continue;
            }
            int port = 2324;
            Object portValue = entry.get("port");
            if (portValue instanceof Number && ((Number) portValue).doubleValue() > 0) {
                port = (int) ((Number) portValue).doubleValue();
            }
            if (!((String) id).isEmpty() && !((String) address).isEmpty()) {
                followers.put((String) id, address + ":" + port);
            }
        }
        return followers;
    }

    static Map<String, String> read() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Method current = thread.getMethod("currentApplication");
            Object application = current.invoke(null);
            if (application != null) {
                Method preferences = application.getClass()
                        .getMethod("getSharedPreferences", String.class, int.class);
                Object shared = preferences.invoke(application, "FlutterSharedPreferences", 0);
                Method all = shared.getClass().getMethod("getAll");
                Object map = all.invoke(shared);
                if (map instanceof Map) {
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) map).entrySet()) {
                        Object value = entry.getValue();
                        if (value != null) {
                            values.put(String.valueOf(entry.getKey()), String.valueOf(value));
                        }
                    }
                    return values;
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the file: same process, so its own file is readable.
        }
        values.putAll(readXml(new File("/data/data/" + PACKAGE + "/shared_prefs/FlutterSharedPreferences.xml")));
        return values;
    }

    private static Map<String, String> readXml(File file) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (!file.isFile()) {
            return values;
        }
        try {
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
            NodeList entries = document.getDocumentElement().getChildNodes();
            for (int i = 0; i < entries.getLength(); i++) {
                Node node = entries.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element element = (Element) node;
                String name = element.getAttribute("name");
                if (!name.isEmpty()) {
                    values.put(name, element.getTextContent());
                }
            }
        } catch (Exception unreadable) {
            // No settings is not fatal: the roster arrives over the wire anyway.
        }
        return values;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
