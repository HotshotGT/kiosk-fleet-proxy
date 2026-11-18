// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tests that need no device: JSON, the roster rewrite, frames and a live proxy round trip. */
public final class FleetProxyPluginTest {

    public static void main(String[] args) throws Exception {
        jsonKeepsWholeNumbers();
        jsonRoundTripsEscapes();
        rosterLinksBecomePaths();
        selfIsDecidedByAddress();
        routePattern();
        base64AndAcceptKey();
        framesRoundTrip();
        proxyRewritesJsonAndHeaders();
        postBodyReachesUpstream();
        proxyUnknownKiosk();
        proxyRedirectsRoot();
        System.out.println("FleetProxyPluginTest ok");
    }

    private static void jsonKeepsWholeNumbers() {
        Object parsed = Json.parse("{\"port\":2324,\"ratio\":0.5,\"off\":false,\"none\":null}");
        String text = Json.stringify(parsed);
        check(text.contains("\"port\":2324"), "whole numbers stay whole: " + text);
        check(text.contains("\"ratio\":0.5"), "fractions survive: " + text);
        check(text.contains("\"off\":false"), "booleans survive: " + text);
    }

    private static void jsonRoundTripsEscapes() {
        String original = "{\"name\":\"Amzn \\\"Echo\\\" Show\",\"path\":\"a\\\\b\",\"nl\":\"x\\ny\"}";
        Object parsed = Json.parse(original);
        Object again = Json.parse(Json.stringify(parsed));
        check(Json.stringify(again).contains("Amzn \\\"Echo\\\" Show"), "quotes escape once");
        check(Json.parseOrNull("not json") == null, "non JSON is rejected, not thrown");
    }

    private static void rosterLinksBecomePaths() {
        String body = "{\"followers\":[{\"id\":\"1111111111111111\",\"address\":\"192.0.2.163\","
                + "\"port\":2324,\"self\":true,\"url\":\"http://192.0.2.163:2324\",\"name\":\"Kitchen kiosk\"}]}";
        Registry registry = new Registry();
        String rewritten = FleetPayload.rewrite(body, "https://proxy.example", registry, noLocal());
        check(rewritten.contains("\"url\":\"https://proxy.example/1111111111111111\""), "link rewritten: " + rewritten);
        check(rewritten.contains("\"name\":\"Kitchen kiosk\""), "other fields kept: " + rewritten);
        check("192.0.2.163:2324".equals(registry.get("1111111111111111")), "address learned");
        check(registry.self().isEmpty(), "a remote kiosk cannot rename this device");
    }

    private static void selfIsDecidedByAddress() {
        String body = "{\"id\":\"2222222222222222\",\"address\":\"192.0.2.165\",\"port\":2324,"
                + "\"self\":true,\"url\":\"http://192.0.2.165:2324\"}";
        Registry registry = new Registry();
        FleetPayload.rewrite(body, "https://proxy.example", registry, new FleetPayload.LocalCheck() {
            @Override
            public boolean isLocal(String host) {
                return "192.0.2.165".equals(host);
            }
        });
        check("2222222222222222".equals(registry.self()), "our own address decides self");
        String untouched = "{\"id\":\"1111111111111111\",\"address\":\"192.0.2.163\",\"port\":2324,"
                + "\"url\":\"http://192.0.2.163:9999\"}";
        Registry second = new Registry();
        String same = FleetPayload.rewrite(untouched, "https://proxy.example", second, noLocal());
        check(same.equals(untouched), "an unfamiliar link is left alone");
    }

    private static void routePattern() {
        check(java.util.regex.Pattern.matches("^/([0-9a-fA-F]{6,40})(/.*)?$", "/1111111111111111/"), "id alone");
        check(java.util.regex.Pattern.matches("^/([0-9a-fA-F]{6,40})(/.*)?$", "/1111111111111111/#dashboard"),
                "id with fragment");
        check(!java.util.regex.Pattern.matches("^/([0-9a-fA-F]{6,40})(/.*)?$", "/nothex/"), "wrong shape rejected");
    }

    private static void base64AndAcceptKey() {
        check("aGVsbG8=".equals(WebSocketRelay.base64("hello".getBytes())), "base64 pads correctly");
        // RFC 6455 section 1.3 worked example.
        check("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=".equals(WebSocketRelay.acceptKey("dGhlIHNhbXBsZSBub25jZQ==")),
                "accept key matches the RFC example");
    }

    private static void framesRoundTrip() throws Exception {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        WebSocketRelay.writeFrame(encoded, true, 0x1, "{\"a\":1}".getBytes("UTF-8"), true);
        WebSocketRelay.Frame frame = WebSocketRelay.readFrame(new ByteArrayInputStream(encoded.toByteArray()));
        check(frame != null && frame.fin && frame.opcode == 1, "frame header survives");
        check("{\"a\":1}".equals(new String(frame.payload, "UTF-8")), "masked payload decodes");
        ByteArrayOutputStream big = new ByteArrayOutputStream();
        byte[] payload = new byte[70000];
        WebSocketRelay.writeFrame(big, true, 0x2, payload, false);
        WebSocketRelay.Frame large = WebSocketRelay.readFrame(new ByteArrayInputStream(big.toByteArray()));
        check(large != null && large.payload.length == payload.length, "64 bit length survives");
    }

    private static void proxyRewritesJsonAndHeaders() throws Exception {
        ServerSocket upstream = new ServerSocket(0);
        Thread responder = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket socket = upstream.accept();
                    HttpProxyServer.Request request = HttpProxyServer.readRequest(socket.getInputStream());
                    String body = "{\"followers\":[{\"id\":\"1111111111111111\",\"address\":\"192.0.2.163\","
                            + "\"port\":2324,\"url\":\"http://192.0.2.163:2324\"}]}";
                    String head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                            + "X-Frame-Options: SAMEORIGIN\r\nContent-Length: " + body.length() + "\r\n\r\n";
                    OutputStream out = socket.getOutputStream();
                    out.write(head.getBytes("ISO-8859-1"));
                    out.write(body.getBytes("UTF-8"));
                    out.flush();
                    socket.close();
                } catch (Exception ignored) {
                    // The test fails on the assertion, not here.
                }
            }
        });
        responder.setDaemon(true);
        responder.start();

        Registry registry = new Registry();
        registry.set("1111111111111111", "127.0.0.1:" + upstream.getLocalPort());
        registry.setSelf("1111111111111111");
        HttpProxyServer server = new HttpProxyServer(registry, new LocalAddresses(), "https://dash.example",
                freePort(), quiet());
        server.start();
        try {
            Response response = get(server.port(), "/1111111111111111/api/fleet", null);
            check(response.status == 200, "status relayed: " + response.status);
            check(response.header("content-security-policy") != null
                    && response.header("content-security-policy").contains("frame-ancestors 'self' https://dash.example"),
                    "frame ancestors added");
            check(response.header("x-frame-options") == null, "X-Frame-Options dropped");
            check(!response.body.contains("http://192.0.2.163:2324"), "no raw device link left");
            check(response.body.contains("http://127.0.0.1:" + server.port() + "/1111111111111111"),
                    "link rewritten to this origin: " + response.body);
        } finally {
            server.stop();
            upstream.close();
        }
    }

    private static void postBodyReachesUpstream() throws Exception {
        ServerSocket upstream = new ServerSocket(0);
        Thread responder = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket socket = upstream.accept();
                    HttpProxyServer.Request request = HttpProxyServer.readRequest(socket.getInputStream());
                    int length = Integer.parseInt(request.headers.first("Content-Length").trim());
                    byte[] payload = new byte[length];
                    int offset = 0;
                    while (offset < length) {
                        int read = socket.getInputStream().read(payload, offset, length - offset);
                        if (read < 0) {
                            break;
                        }
                        offset += read;
                    }
                    String seen = request.method + " " + new String(payload, "UTF-8");
                    String body = "{\"seen\":" + Json.stringify(seen) + "}";
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + body.length() + "\r\n\r\n").getBytes("ISO-8859-1"));
                    out.write(body.getBytes("UTF-8"));
                    out.flush();
                    socket.close();
                } catch (Exception ignored) {
                    // Asserted below.
                }
            }
        });
        responder.setDaemon(true);
        responder.start();

        Registry registry = new Registry();
        registry.set("1111111111111111", "127.0.0.1:" + upstream.getLocalPort());
        HttpProxyServer server = new HttpProxyServer(registry, new LocalAddresses(), "", freePort(), quiet());
        server.start();
        try {
            Response response = post(server.port(), "/1111111111111111/api/login", "{\"password\":\"hunter2\"}");
            check(response.status == 200, "post status: " + response.status);
            check(response.body.contains("POST {\\\"password\\\":\\\"hunter2\\\"}"),
                    "body and method forwarded: " + response.body);
        } finally {
            server.stop();
            upstream.close();
        }
    }

    private static void proxyUnknownKiosk() throws Exception {
        Registry registry = new Registry();
        HttpProxyServer server = new HttpProxyServer(registry, new LocalAddresses(), "", freePort(), quiet());
        server.start();
        try {
            Response response = get(server.port(), "/1111111111111111/", null);
            check(response.status == 502, "unknown kiosk is a bad gateway, got " + response.status);
            Response root = get(server.port(), "/", null);
            check(root.status == 503, "no id yet means a waiting page, got " + root.status);
        } finally {
            server.stop();
        }
    }

    private static void proxyRedirectsRoot() throws Exception {
        Registry registry = new Registry();
        registry.setSelf("1111111111111111");
        HttpProxyServer server = new HttpProxyServer(registry, new LocalAddresses(), "", freePort(), quiet());
        server.start();
        try {
            Response response = get(server.port(), "/", null);
            check(response.status == 302, "root redirects, got " + response.status);
            check(("/1111111111111111/").equals(response.header("location")), "to this kiosk: "
                    + response.header("location"));
            Response withHost = get(server.port(), "/1111111111111111/x",
                    "Host: fleet.example\r\nX-Forwarded-Proto: https\r\n");
            check(withHost.status == 502, "host header does not break routing");
        } finally {
            server.stop();
        }
    }

    // -----------------------------------------------------------------------
    // helpers

    private static final class Response {
        int status;
        final Map<String, String> headers = new LinkedHashMap<String, String>();
        String body = "";

        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    private static Response get(int port, String path, String extraHeaders) throws Exception {
        return request(port, "GET", path, null, extraHeaders);
    }

    private static Response post(int port, String path, String body) throws Exception {
        return request(port, "POST", path, body, null);
    }

    private static Response request(int port, String method, String path, String body, String extraHeaders)
            throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        socket.setSoTimeout(5000);
        byte[] payload = body == null ? new byte[0] : body.getBytes("UTF-8");
        String head = method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n"
                + (payload.length > 0 ? "Content-Type: application/json\r\nContent-Length: " + payload.length + "\r\n" : "")
                + (extraHeaders == null ? "" : extraHeaders) + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(head.getBytes("ISO-8859-1"));
        if (payload.length > 0) {
            out.write(payload);
        }
        out.flush();
        InputStream in = socket.getInputStream();
        Response response = new Response();
        String status = HttpProxyServer.readLine(in);
        response.status = Integer.parseInt(status.split(" ")[1]);
        while (true) {
            String line = HttpProxyServer.readLine(in);
            if (line == null || line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            response.headers.put(line.substring(0, colon).trim().toLowerCase(),
                    line.substring(colon + 1).trim());
        }
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) > 0) {
            received.write(chunk, 0, read);
        }
        response.body = received.toString("UTF-8");
        socket.close();
        return response;
    }

    private static int freePort() throws Exception {
        ServerSocket probe = new ServerSocket(0);
        int port = probe.getLocalPort();
        probe.close();
        return port;
    }

    private static HttpProxyServer.Logger quiet() {
        return new HttpProxyServer.Logger() {
            @Override
            public void log(String message) {
                // Tests assert on responses, not on the log.
            }
        };
    }

    private static FleetPayload.LocalCheck noLocal() {
        return new FleetPayload.LocalCheck() {
            @Override
            public boolean isLocal(String host) {
                return false;
            }
        };
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
