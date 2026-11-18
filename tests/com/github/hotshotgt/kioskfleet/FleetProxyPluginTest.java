// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

/** Tests that need no device: JSON, the roster rewrite, frames and a live proxy round trip. */
public final class FleetProxyPluginTest {

    /** A CR LF pair, built without escapes so edits cannot mangle it. */
    private static final String EOL = "" + (char) 13 + (char) 10;

    public static void main(String[] args) throws Exception {
        jsonKeepsWholeNumbers();
        jsonRoundTripsEscapes();
        rosterLinksBecomePaths();
        selfIsDecidedByAddress();
        routePattern();
        base64AndAcceptKey();
        framesRoundTrip();
        proxyRewritesJsonAndHeaders();
        gzippedJsonIsDecoded();
        gzippedAssetsPassThrough();
        keepAliveReusesSockets();
        staticAssetsServedFromCache();
        cacheKeepsEncodingVariantsApart();
        htmlGetsOriginStaticPathsAndPrefetch();
        gzippedHtmlMatchesItsHeader();
        staticPathsServeWithoutAKioskId();
        learnedBuildServesItsOwnAssets();
        rangeRequestsSkipTheCache();
        websocketFramesAreRewritten();
        unreachableKioskIsAnswered();
        compressedJsonPassesThrough();
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

    /** The roster arrives gzipped when the browser asks for it; it still gets rewritten. */
    private static void gzippedJsonIsDecoded() throws Exception {
        String body = "{\"followers\":[{\"id\":\"1111111111111111\",\"address\":\"192.0.2.163\","
                + "\"port\":2324,\"url\":\"http://192.0.2.163:2324\"}]}";
        byte[] compressed = gzip(body);
        ServerSocket upstream = serveOnce("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                + "Content-Encoding: gzip\r\nContent-Length: " + compressed.length + "\r\n\r\n", compressed);

        Registry registry = new Registry();
        registry.set("1111111111111111", "127.0.0.1:" + upstream.getLocalPort());
        HttpProxyServer server = new HttpProxyServer(registry, new LocalAddresses(), "", freePort(), quiet());
        server.start();
        try {
            Response response = get(server.port(), "/1111111111111111/api/fleet", "Accept-Encoding: gzip\r\n");
            check(response.status == 200, "status: " + response.status);
            check(response.header("content-encoding") == null, "gzipped json comes back plain");
            check(!response.body.contains("http://192.0.2.163:2324"), "roster inside gzip is rewritten");
            check(response.body.contains("http://127.0.0.1:" + server.port() + "/1111111111111111"),
                    "rewritten link: " + response.body);
        } finally {
            server.stop();
            upstream.close();
        }
    }

    /** Assets stay compressed: passing them through saves the bandwidth they were sent with. */
    private static void gzippedAssetsPassThrough() throws Exception {
        byte[] compressed = gzip("const catalog = [" + repeat("\"item\",", 400) + "\"end\"];");
        ServerSocket upstream = serveOnce("HTTP/1.1 200 OK\r\nContent-Type: application/javascript\r\n"
                + "Content-Encoding: gzip\r\nContent-Length: " + compressed.length + "\r\n\r\n", compressed);

        Registry registry = new Registry();
        registry.set("1111111111111111", "127.0.0.1:" + upstream.getLocalPort());
        HttpProxyServer server = new HttpProxyServer(registry, new LocalAddresses(), "", freePort(), quiet());
        server.start();
        try {
            Response response = get(server.port(), "/1111111111111111/static/catalogs.js", "Accept-Encoding: gzip\r\n");
            check(response.status == 200, "status: " + response.status);
            check("gzip".equals(response.header("content-encoding")), "compression kept: "
                    + response.header("content-encoding"));
            check(response.bytes.length == compressed.length, "bytes untouched: forwarded "
                    + response.bytes.length + " of " + compressed.length);
        } finally {
            server.stop();
            upstream.close();
        }
    }

    private static ServerSocket serveOnce(final String head, final byte[] body) throws Exception {
        final ServerSocket upstream = new ServerSocket(0);
        Thread responder = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket socket = upstream.accept();
                    HttpProxyServer.readRequest(socket.getInputStream());
                    OutputStream out = socket.getOutputStream();
                    out.write(head.getBytes("ISO-8859-1"));
                    out.write(body);
                    out.flush();
                    socket.close();
                } catch (Exception ignored) {
                    // Asserted in the test body.
                }
            }
        });
        responder.setDaemon(true);
        responder.start();
        return upstream;
    }

    private static byte[] gzip(String text) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(raw);
        gzip.write(text.getBytes("UTF-8"));
        gzip.close();
        return raw.toByteArray();
    }

    private static String repeat(String text, int times) {
        StringBuilder out = new StringBuilder(text.length() * times);
        for (int i = 0; i < times; i++) {
            out.append(text);
        }
        return out.toString();
    }

    /** A page of ninety files should not be ninety handshakes, on either side. */
    private static void keepAliveReusesSockets() throws Exception {
        final ServerSocket upstream = new ServerSocket(0);
        final AtomicInteger accepted = new AtomicInteger();
        Thread responder = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        Socket socket = upstream.accept();
                        accepted.incrementAndGet();
                        InputStream in = socket.getInputStream();
                        OutputStream out = socket.getOutputStream();
                        while (true) {
                            HttpProxyServer.Request request = HttpProxyServer.readRequest(in);
                            if (request == null) {
                                break;
                            }
                            String body = "{\"ok\":true}";
                            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                    + body.length() + "\r\n\r\n" + body).getBytes("ISO-8859-1"));
                            out.flush();
                        }
                        socket.close();
                    }
                } catch (Exception stopped) {
                    // The test closes the server.
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
            Socket client = new Socket("127.0.0.1", server.port());
            client.setSoTimeout(5000);
            OutputStream out = client.getOutputStream();
            for (int i = 0; i < 3; i++) {
                out.write("GET /1111111111111111/api/ping HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
                        .getBytes("ISO-8859-1"));
                out.flush();
            }
            StringBuilder seen = new StringBuilder();
            byte[] buffer = new byte[4096];
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && count("HTTP/1.1 200", seen.toString()) < 3) {
                int read = client.getInputStream().read(buffer);
                if (read < 0) {
                    break;
                }
                seen.append(new String(buffer, 0, read, "ISO-8859-1"));
            }
            check(count("HTTP/1.1 200", seen.toString()) == 3,
                    "three responses on one client connection, saw " + count("HTTP/1.1 200", seen.toString()));
            check(accepted.get() == 1, "one upstream connection for three requests, saw " + accepted.get());
            client.close();
        } finally {
            server.stop();
            upstream.close();
        }
    }

    /** One copy of a versioned asset serves every kiosk running that build. */
    private static void staticAssetsServedFromCache() throws Exception {
        final String asset = "console.log('" + repeat("kiosk-satellite-", 200) + "');";
        final byte[] body = asset.getBytes("UTF-8");
        final ServerSocket upstream = new ServerSocket(0);
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicInteger requests = new AtomicInteger();
        Thread responder = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        Socket socket = upstream.accept();
                        accepted.incrementAndGet();
                        InputStream in = socket.getInputStream();
                        OutputStream out = socket.getOutputStream();
                        while (true) {
                            HttpProxyServer.Request request = HttpProxyServer.readRequest(in);
                            if (request == null) {
                                break;
                            }
                            requests.incrementAndGet();
                            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/javascript\r\n"
                                    + "Cache-Control: public, max-age=31536000, immutable\r\n"
                                    + "Content-Length: " + body.length + "\r\n\r\n").getBytes("ISO-8859-1"));
                            out.write(body);
                            out.flush();
                        }
                        socket.close();
                    }
                } catch (Exception stopped) {
                    // The test closes the server.
                }
            }
        });
        responder.setDaemon(true);
        responder.start();

        Registry registry = new Registry();
        registry.set("aaaaaaaaaaaa1111", "127.0.0.1:" + upstream.getLocalPort());
        registry.set("bbbbbbbbbbbb2222", "127.0.0.1:" + upstream.getLocalPort());
        HttpProxyServer server = new HttpProxyServer(registry, new LocalAddresses(), "https://dash.example",
                freePort(), quiet());
        server.start();
        try {
            Response first = get(server.port(), "/aaaaaaaaaaaa1111/static/app.js?v=abc123", null);
            Response second = get(server.port(), "/bbbbbbbbbbbb2222/static/app.js?v=abc123", null);
            check(first.status == 200 && second.status == 200, "both answered: " + first.status + "/" + second.status);
            check(first.body.equals(second.body), "same asset either way");
            check(requests.get() == 1, "second kiosk served from cache, upstream requests: " + requests.get());
            check(second.header("content-security-policy") != null
                    && second.header("content-security-policy").contains("https://dash.example"),
                    "cached response still carries frame-ancestors");
            Response other = get(server.port(), "/aaaaaaaaaaaa1111/static/app.js?v=different", null);
            check(other.status == 200 && requests.get() == 2, "a different build is fetched: " + requests.get());
            check(accepted.get() >= 1, "the upstream was reached at all");
        } finally {
            server.stop();
            upstream.close();
        }
    }


    /** A client that cannot take gzip must not be handed gzipped bytes from the cache. */
    private static void cacheKeepsEncodingVariantsApart() throws Exception {
        final String asset = "console.log('" + repeat("variant-", 60) + "');";
        final byte[] plain = asset.getBytes("UTF-8");
        final byte[] zipped = gzip(asset);
        final ServerSocket upstream = new ServerSocket(0);
        final AtomicInteger requests = new AtomicInteger();
        Thread responder = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        Socket socket = upstream.accept();
                        InputStream in = socket.getInputStream();
                        OutputStream out = socket.getOutputStream();
                        while (true) {
                            HttpProxyServer.Request request = HttpProxyServer.readRequest(in);
                            if (request == null) {
                                break;
                            }
                            requests.incrementAndGet();
                            String accept = request.headers.first("Accept-Encoding");
                            boolean gzipWanted = accept != null && accept.contains("gzip");
                            byte[] body = gzipWanted ? zipped : plain;
                            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/javascript\r\n"
                                    + (gzipWanted ? "Content-Encoding: gzip\r\n" : "") 
                                    + "Cache-Control: public, max-age=31536000, immutable\r\n"
                                    + "Content-Length: " + body.length + "\r\n\r\n").getBytes("ISO-8859-1"));
                            out.write(body);
                            out.flush();
                        }
                        socket.close();
                    }
                } catch (Exception stopped) {
                    // The test closes the server.
                }
            }
        });
        responder.setDaemon(true);
        responder.start();

        Registry registry = new Registry();
        registry.set("aaaaaaaaaaaa1111", "127.0.0.1:" + upstream.getLocalPort());
        registry.set("bbbbbbbbbbbb2222", "127.0.0.1:" + upstream.getLocalPort());
        HttpProxyServer server = new HttpProxyServer(registry, new LocalAddresses(), "", freePort(), quiet());
        server.start();
        try {
            Response gz = get(server.port(), "/aaaaaaaaaaaa1111/static/app.js?v=zzz1", "Accept-Encoding: gzip" + EOL);
            check(gz.status == 200 && "gzip".equals(gz.header("content-encoding")), "gzip variant served as gzip");
            Response otherKiosk = get(server.port(), "/bbbbbbbbbbbb2222/static/app.js?v=zzz1",
                    "Accept-Encoding: gzip" + EOL);
            check(otherKiosk.status == 200 && requests.get() == 1,
                    "same encoding reuses the entry, requests: " + requests.get());
            Response plainWanted = get(server.port(), "/aaaaaaaaaaaa1111/static/app.js?v=zzz1", null);
            check(plainWanted.status == 200, "plain variant answered: " + plainWanted.status);
            check(plainWanted.header("content-encoding") == null,
                    "plain client is not handed gzip, got: " + plainWanted.header("content-encoding"));
            check(plainWanted.body.contains("variant-variant"), "plain body decoded: " + plainWanted.body.length());
            check(requests.get() == 2, "plain variant was fetched, requests: " + requests.get());
        } finally {
            server.stop();
            upstream.close();
        }
    }


    /** The page's files move to one origin level path so every kiosk shares them. */
    private static void htmlGetsOriginStaticPathsAndPrefetch() throws Exception {
        String html = "<html><head><title>KS</title></head><body>"
                + "<script type=\"module\" src=\"static/main.js?v=abc123\"></script>"
                + "<link rel=\"stylesheet\" href=\"./static/app.css?v=abc123\">"
                + "</body></html>";
        byte[] body = html.getBytes("UTF-8");
        ServerSocket upstream = serveOnce("HTTP/1.1 200 OK" + EOL + "Content-Type: text/html; charset=utf-8" + EOL
                + "Cache-Control: no-store" + EOL + "Content-Length: " + body.length + EOL + EOL, body);

        Registry registry = new Registry();
        registry.set("aaaaaaaaaaaa1111", "127.0.0.1:" + upstream.getLocalPort());
        registry.set("bbbbbbbbbbbb2222", "127.0.0.1:9");
        registry.setSelf("aaaaaaaaaaaa1111");
        HttpProxyServer server = new HttpProxyServer(registry, new LocalAddresses(), "https://dash.example",
                freePort(), quiet());
        server.start();
        try {
            Response response = get(server.port(), "/aaaaaaaaaaaa1111/", null);
            check(response.status == 200, "page served: " + response.status);
            check(response.body.contains("src=\"/static/main.js?v=abc123\""),
                    "script url became origin level: " + response.body);
            check(response.body.contains("href=\"/static/app.css?v=abc123\""), "stylesheet too");
            check(response.body.contains("<link rel=\"prefetch\" href=\"/bbbbbbbbbbbb2222/\">"),
                    "other kiosk prefetched: " + response.body);
            check("private, max-age=5".equals(response.header("cache-control")),
                    "a few seconds of cache instead of no-store: " + response.header("cache-control"));
            check(response.header("content-security-policy") != null, "frame ancestors still added");
        } finally {
            server.stop();
            upstream.close();
        }
    }


    /** A page that goes in gzipped must come out gzipped, and actually be gzipped. */
    private static void gzippedHtmlMatchesItsHeader() throws Exception {
        String html = "<html><head></head><body><script src=\"static/main.js?v=abc123\"></script></body></html>";
        byte[] compressed = gzip(html);
        ServerSocket upstream = serveOnce("HTTP/1.1 200 OK" + EOL + "Content-Type: text/html; charset=utf-8" + EOL
                + "Content-Encoding: gzip" + EOL + "Cache-Control: no-store" + EOL
                + "Content-Length: " + compressed.length + EOL + EOL, compressed);

        Registry registry = new Registry();
        registry.set("aaaaaaaaaaaa1111", "127.0.0.1:" + upstream.getLocalPort());
        registry.set("bbbbbbbbbbbb2222", "127.0.0.1:9");
        HttpProxyServer server = new HttpProxyServer(registry, new LocalAddresses(), "", freePort(), quiet());
        server.start();
        try {
            Response response = get(server.port(), "/aaaaaaaaaaaa1111/", "Accept-Encoding: gzip" + EOL);
            check(response.status == 200, "status: " + response.status);
            check("gzip".equals(response.header("content-encoding")), "still gzipped: "
                    + response.header("content-encoding"));
            java.io.InputStream in = new java.util.zip.GZIPInputStream(
                    new ByteArrayInputStream(response.bytes));
            ByteArrayOutputStream plain = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                plain.write(buffer, 0, read);
            }
            String page = plain.toString("UTF-8");
            check(page.contains("/static/main.js?v=abc123"), "the header and the body agree: " + page);
            check(page.contains("rel=\"prefetch\""), "prefetch survived recompression: " + page);
        } finally {
            server.stop();
            upstream.close();
        }
    }


    /** A page names its build; that build's files must come from that kiosk. */
    private static void learnedBuildServesItsOwnAssets() throws Exception {
        final ServerSocket leader = fakeKiosk("aaaaaaaa", "the-leader-app");
        final ServerSocket other = fakeKiosk("bbbbbbbb", "the-other-app");
        Registry registry = new Registry();
        registry.set("aaaaaaaaaaaa1111", "127.0.0.1:" + leader.getLocalPort());
        registry.set("bbbbbbbbbbbb2222", "127.0.0.1:" + other.getLocalPort());
        registry.setSelf("aaaaaaaaaaaa1111");
        HttpProxyServer server = new HttpProxyServer(registry, new LocalAddresses(), "", freePort(), quiet());
        server.start();
        try {
            server.learnBuildsNow();
            Response mine = get(server.port(), "/static/main.js?v=aaaaaaaa", null);
            check(mine.body.contains("the-leader-app"), "leader's own files: " + mine.body);
            Response theirs = get(server.port(), "/static/main.js?v=bbbbbbbb", null);
            check(theirs.body.contains("the-other-app"), "another build is not answered with this device's app: "
                    + theirs.body);
        } finally {
            server.stop();
            leader.close();
            other.close();
        }
    }


    /** A cached whole file must not be handed to a request that asked for a slice. */
    private static void rangeRequestsSkipTheCache() throws Exception {
        final ServerSocket kiosk = fakeKiosk("cccccccc", "0123456789");
        Registry registry = new Registry();
        registry.set("aaaaaaaaaaaa1111", "127.0.0.1:" + kiosk.getLocalPort());
        HttpProxyServer server = new HttpProxyServer(registry, new LocalAddresses(), "", freePort(), quiet());
        server.start();
        try {
            get(server.port(), "/aaaaaaaaaaaa1111/", null);
            check(get(server.port(), "/static/main.js?v=cccccccc", null).status == 200, "cached once");
            Response ranged = get(server.port(), "/static/main.js?v=cccccccc",
                    "Range: bytes=0-3" + EOL + "Accept-Encoding: identity" + EOL);
            check(ranged.status == 206, "the range reached the kiosk instead of the cache: " + ranged.status);
        } finally {
            server.stop();
            kiosk.close();
        }
    }


    /** The roster also arrives over a socket, and those links need rewriting too. */
    private static void websocketFramesAreRewritten() throws Exception {
        final ServerSocket upstream = new ServerSocket(0);
        Thread hub = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket socket = upstream.accept();
                    InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream();
                    HttpProxyServer.Request request = HttpProxyServer.readRequest(in);
                    String key = request.headers.first("Sec-WebSocket-Key");
                    out.write(("HTTP/1.1 101 Switching Protocols" + EOL + "Upgrade: websocket" + EOL
                            + "Connection: Upgrade" + EOL + "Sec-WebSocket-Accept: "
                            + WebSocketRelay.acceptKey(key) + EOL + EOL).getBytes("ISO-8859-1"));
                    String roster = "{\"fleet\":[{\"id\":\"aaaaaaaaaaaa1111\",\"address\":\"192.0.2.5\","
                            + "\"port\":2324,\"url\":\"http://192.0.2.5:2324\",\"self\":true}]}";
                    WebSocketRelay.writeFrame(out, true, 1, roster.getBytes("UTF-8"), false);
                    out.flush();
                    Thread.sleep(2000);
                    socket.close();
                } catch (Exception finished) {
                    // The test closes the socket.
                }
            }
        });
        hub.setDaemon(true);
        hub.start();

        Registry registry = new Registry();
        registry.set("aaaaaaaaaaaa1111", "127.0.0.1:" + upstream.getLocalPort());
        HttpProxyServer server = new HttpProxyServer(registry, new LocalAddresses(), "https://dash.example",
                freePort(), quiet());
        server.start();
        Socket client = null;
        try {
            client = new Socket("127.0.0.1", server.port());
            OutputStream out = client.getOutputStream();
            InputStream in = new BufferedInputStream(client.getInputStream());
            out.write(("GET /aaaaaaaaaaaa1111/ws HTTP/1.1" + EOL + "Host: dash.example" + EOL
                    + "X-Forwarded-Proto: https" + EOL + "Upgrade: websocket" + EOL + "Connection: Upgrade" + EOL
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" + EOL + "Sec-WebSocket-Version: 13" + EOL + EOL)
                    .getBytes("ISO-8859-1"));
            out.flush();
            HttpProxyServer.Response upgraded = HttpProxyServer.readResponse(in);
            check(upgraded != null && upgraded.status == 101,
                    "upgraded: " + (upgraded == null ? "no response" : upgraded.status));
            WebSocketRelay.Frame frame = WebSocketRelay.readFrame(in);
            check(frame != null, "a frame arrived");
            String roster = new String(frame.payload, "UTF-8");
            check(roster.contains("https://dash.example/aaaaaaaaaaaa1111"),
                    "the roster link points at the proxy: " + roster);
            check(roster.contains("192.0.2.5"), "the address is still there for the registry: " + roster);
        } finally {
            if (client != null) {
                client.close();
            }
            server.stop();
            upstream.close();
        }
    }


    /** A kiosk that refuses the connection still gets an answer, naming itself. */
    private static void unreachableKioskIsAnswered() throws Exception {
        ServerSocket closed = new ServerSocket(0);
        int port = closed.getLocalPort();
        closed.close();
        Registry registry = new Registry();
        registry.set("dddddddddddd3333", "127.0.0.1:" + port);
        HttpProxyServer server = new HttpProxyServer(registry, new LocalAddresses(), "", freePort(), quiet());
        server.start();
        try {
            Response response = get(server.port(), "/dddddddddddd3333/", null);
            check(response.status == 502, "a dead kiosk is a bad gateway, not a dropped socket: " + response.status);
            check(response.body.contains("dddddddddddd3333"), "the answer names the kiosk: " + response.body);
            check(response.body.contains("Kiosk Satellite"), "it is a page, not a bare line: " + response.body);
            check(response.body.contains("Kiosk unreachable"), "and it says what is wrong: " + response.body);
            check("text/html; charset=utf-8".equals(response.header("content-type")),
                    "served as html: " + response.header("content-type"));
            check("no-store".equals(response.header("cache-control")), "never cached: "
                    + response.header("cache-control"));
        } finally {
            server.stop();
        }
    }

    private static ServerSocket fakeKiosk(final String build, final String assetBody) throws Exception {
        final ServerSocket kiosk = new ServerSocket(0);
        Thread responder = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        Socket socket = kiosk.accept();
                        InputStream in = socket.getInputStream();
                        OutputStream out = socket.getOutputStream();
                        HttpProxyServer.Request request = HttpProxyServer.readRequest(in);
                        if (request == null) {
                            socket.close();
                            continue;
                        }
                        boolean page = "/".equals(request.path());
                        byte[] body = (page
                                ? "<html><head></head><body><script src=\"static/main.js?v=" + build
                                        + "\"></script></body></html>"
                                : assetBody).getBytes("UTF-8");
                        if (!page && request.headers.first("Range") != null) {
                            out.write(("HTTP/1.1 206 Partial Content" + EOL + "Content-Type: application/javascript"
                                    + EOL + "Content-Range: bytes 0-3/10" + EOL + "Content-Length: 4" + EOL
                                    + "Connection: close" + EOL + EOL).getBytes("ISO-8859-1"));
                            out.write("0123".getBytes("UTF-8"));
                            out.flush();
                            socket.close();
                            continue;
                        }
                        out.write(("HTTP/1.1 200 OK" + EOL + "Content-Type: "
                                + (page ? "text/html; charset=utf-8" : "application/javascript") + EOL
                                + "Cache-Control: " + (page ? "no-store"
                                        : "public, max-age=31536000, immutable") + EOL
                                + "Content-Length: " + body.length + EOL + "Connection: close" + EOL + EOL)
                                .getBytes("ISO-8859-1"));
                        out.write(body);
                        out.flush();
                        socket.close();
                    }
                } catch (Exception stopped) {
                    // The test closes the kiosk.
                }
            }
        });
        responder.setDaemon(true);
        responder.start();
        return kiosk;
    }

    /** After learning the build, /static/ is answered without a kiosk id. */
    private static void staticPathsServeWithoutAKioskId() throws Exception {
        String html = "<html><head></head><body><script src=\"static/main.js?v=abc123\"></script></body></html>";
        final String asset = "console.log('" + repeat("shared-", 50) + "');";
        final ServerSocket upstream = new ServerSocket(0);
        final AtomicInteger requests = new AtomicInteger();
        Thread responder = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        Socket socket = upstream.accept();
                        InputStream in = socket.getInputStream();
                        OutputStream out = socket.getOutputStream();
                        while (true) {
                            HttpProxyServer.Request request = HttpProxyServer.readRequest(in);
                            if (request == null) {
                                break;
                            }
                            requests.incrementAndGet();
                            boolean page = "/".equals(request.path());
                            byte[] body = page ? html.getBytes("UTF-8") : asset.getBytes("UTF-8");
                            out.write(("HTTP/1.1 200 OK" + EOL
                                    + "Content-Type: " + (page ? "text/html; charset=utf-8" : "application/javascript") + EOL
                                    + (page ? "Cache-Control: no-store" + EOL
                                            : "Cache-Control: public, max-age=31536000, immutable" + EOL)
                                    + "Content-Length: " + body.length + EOL + EOL).getBytes("ISO-8859-1"));
                            out.write(body);
                            out.flush();
                        }
                        socket.close();
                    }
                } catch (Exception stopped) {
                    // The test closes the server.
                }
            }
        });
        responder.setDaemon(true);
        responder.start();

        Registry registry = new Registry();
        registry.set("aaaaaaaaaaaa1111", "127.0.0.1:" + upstream.getLocalPort());
        registry.setSelf("aaaaaaaaaaaa1111");
        HttpProxyServer server = new HttpProxyServer(registry, new LocalAddresses(), "", freePort(), quiet());
        server.start();
        try {
            check(get(server.port(), "/aaaaaaaaaaaa1111/", null).status == 200, "page first, to learn the build");
            Response assetResponse = get(server.port(), "/static/main.js?v=abc123", null);
            check(assetResponse.status == 200, "origin level asset served: " + assetResponse.status);
            check(assetResponse.body.contains("shared-shared"), "asset body: " + assetResponse.body.length());
            int afterFirst = requests.get();
            Response again = get(server.port(), "/static/main.js?v=abc123", null);
            check(again.status == 200 && requests.get() == afterFirst,
                    "second origin level request came from cache, requests: " + requests.get());
            Response unknownBuild = get(server.port(), "/static/main.js?v=ffffeeee1111", null);
            check(unknownBuild.status == 200, "an unknown build falls back to this kiosk: " + unknownBuild.status);
        } finally {
            server.stop();
            upstream.close();
        }
    }

    /** A JSON body with no roster link is sent on still compressed. */
    private static void compressedJsonPassesThrough() throws Exception {
        String body = "{\"cpu\":12,\"temperature\":37,\"name\":\"a kiosk with a long enough name\"}";
        byte[] compressed = gzip(body);
        ServerSocket upstream = serveOnce("HTTP/1.1 200 OK" + EOL + "Content-Type: application/json" + EOL
                + "Content-Encoding: gzip" + EOL + "Content-Length: " + compressed.length + EOL + EOL, compressed);

        Registry registry = new Registry();
        registry.set("aaaaaaaaaaaa1111", "127.0.0.1:" + upstream.getLocalPort());
        HttpProxyServer server = new HttpProxyServer(registry, new LocalAddresses(), "", freePort(), quiet());
        server.start();
        try {
            Response response = get(server.port(), "/aaaaaaaaaaaa1111/api/status", "Accept-Encoding: gzip" + EOL);
            check(response.status == 200, "status: " + response.status);
            check("gzip".equals(response.header("content-encoding")), "kept compressed: "
                    + response.header("content-encoding"));
            check(response.bytes.length == compressed.length, "bytes untouched: " + response.bytes.length
                    + " of " + compressed.length);
        } finally {
            server.stop();
            upstream.close();
        }
    }

    private static int count(String needle, String haystack) {
        int seen = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            seen++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return seen;
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

        byte[] bytes;
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
        response.bytes = received.toByteArray();
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
