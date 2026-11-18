// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The slice of JSON this plugin needs. Deliberately not org.json: that comes
 * from android.jar, whose method bodies are stubs on a desktop JVM, and these
 * classes have to be testable without a device.
 *
 * <p>Values are Map (String keys, insertion ordered), List, String, Double,
 * Boolean or null.
 */
final class Json {

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        Json parser = new Json(text);
        Object value = parser.value();
        parser.space();
        if (parser.at != parser.text.length()) {
            throw new IllegalArgumentException("trailing content at " + parser.at);
        }
        return value;
    }

    /** The parsed value, or null when the text is not JSON. */
    static Object parseOrNull(String text) {
        if (text == null) {
            return null;
        }
        try {
            return parse(text);
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    private Object value() {
        space();
        if (at >= text.length()) {
            throw new IllegalArgumentException("unexpected end");
        }
        char c = text.charAt(at);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                literal("true");
                return Boolean.TRUE;
            case 'f':
                literal("false");
                return Boolean.FALSE;
            case 'n':
                literal("null");
                return null;
            default:
                return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        at++;
        space();
        if (peek() == '}') {
            at++;
            return map;
        }
        while (true) {
            space();
            String key = string();
            space();
            expect(':');
            map.put(key, value());
            space();
            char c = peek();
            at++;
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected , or } at " + (at - 1));
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<Object>();
        at++;
        space();
        if (peek() == ']') {
            at++;
            return list;
        }
        while (true) {
            list.add(value());
            space();
            char c = peek();
            at++;
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected , or ] at " + (at - 1));
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) {
                throw new IllegalArgumentException("unterminated string");
            }
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escape = text.charAt(at++);
            switch (escape) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                    break;
                default:
                    throw new IllegalArgumentException("bad escape \\" + escape);
            }
        }
    }

    private Double number() {
        int start = at;
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
                at++;
            } else {
                break;
            }
        }
        if (start == at) {
            throw new IllegalArgumentException("expected a value at " + start);
        }
        return Double.valueOf(text.substring(start, at));
    }

    private void literal(String expected) {
        if (!text.startsWith(expected, at)) {
            throw new IllegalArgumentException("expected " + expected + " at " + at);
        }
        at += expected.length();
    }

    private void expect(char c) {
        if (at >= text.length() || text.charAt(at) != c) {
            throw new IllegalArgumentException("expected " + c + " at " + at);
        }
        at++;
    }

    private char peek() {
        if (at >= text.length()) {
            throw new IllegalArgumentException("unexpected end");
        }
        return text.charAt(at);
    }

    private void space() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                at++;
            } else {
                return;
            }
        }
    }

    static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Object entryObject : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObject;
                if (!first) {
                    out.append(',');
                }
                first = false;
                quote(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(item, out);
            }
            out.append(']');
        } else if (value instanceof Boolean) {
            out.append(((Boolean) value).booleanValue() ? "true" : "false");
        } else if (value instanceof Number) {
            // Go and dart both write 2324, never 2324.0, so keep it that way.
            double d = ((Number) value).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                out.append((long) d);
            } else {
                out.append(Double.toString(d));
            }
        } else {
            quote(value.toString(), out);
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
