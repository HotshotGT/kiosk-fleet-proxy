// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Serves every kiosk in the fleet from this one origin.
 *
 * <p>{@code /} redirects to this device's own id and {@code /<id>/...} goes to
 * that kiosk's admin server. Responses lose {@code X-Frame-Options} and gain a
 * {@code frame-ancestors} policy, which is the whole point: the app answers
 * {@code SAMEORIGIN} and no browser lets a dashboard iframe override that.
 *
 * <p>Two things keep switching quick. Connections stay open in both directions,
 * so a page of ninety files is not ninety handshakes. And the app's versioned
 * static files are held in memory under their {@code ?v=} hash, so a kiosk you
 * have never opened is served from the leader rather than over the network.
 */
final class HttpProxyServer {

    /** Where status lines go. */
    interface Logger {
        void log(String message);
    }

    private static final Pattern ROUTE = Pattern.compile("^/([0-9a-fA-F]{6,40})(/.*)?$");
    private static final String LINE_BREAK = "" + (char) 13 + (char) 10;
    private static final int MAX_LINE = 8192;
    private static final int MAX_HEADERS = 100;
    private static final int BUFFER = 16384;
    private static final int IDLE_TIMEOUT_MS = 20000;
    private static final int MAX_IDLE_PER_TARGET = 8;
    private static final int MAX_IDLE_TOTAL = 32;
    private static final long MAX_CACHE_ENTRY = 4L * 1024 * 1024;
    private static final long MAX_CACHE_BYTES = 24L * 1024 * 1024;

    private final Registry registry;
    private final LocalAddresses local;
    private final Logger log;
    private final String ancestor;
    private final int port;

    private final Map<String, Deque<Upstream>> idle = new HashMap<String, Deque<Upstream>>();
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<String, Cached>(64, 0.75f, true);
    private long cachedBytes;
    private int idleCount;

    private DiskCache disk;
    private ServerSocket server;
    private ExecutorService workers;
    private volatile boolean running;

    HttpProxyServer(Registry registry, LocalAddresses local, String ancestor, int port, Logger log) {
        this.registry = registry;
        this.local = local;
        this.ancestor = ancestor == null ? "" : ancestor.trim();
        this.port = port;
        this.log = log;
    }

    int port() {
        return port;
    }

    synchronized void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port), 64);
        workers = Executors.newCachedThreadPool();
        disk = DiskCache.open();
        restore();
        running = true;
        Thread accept = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "kiosk-fleet-proxy");
        accept.setDaemon(true);
        accept.start();
    }

    synchronized void stop() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // Closing twice is fine.
            }
            server = null;
        }
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
        synchronized (idle) {
            for (Deque<Upstream> pool : idle.values()) {
                while (!pool.isEmpty()) {
                    closeQuietly(pool.poll().socket);
                }
            }
            idle.clear();
            idleCount = 0;
        }
        synchronized (cache) {
            cache.clear();
            cachedBytes = 0;
        }
    }

    private void acceptLoop() {
        while (running) {
            final Socket socket;
            try {
                socket = server.accept();
            } catch (IOException stop) {
                return;
            }
            try {
                workers.execute(new Runnable() {
                    @Override
                    public void run() {
                        serve(socket);
                    }
                });
            } catch (RuntimeException busy) {
                closeQuietly(socket);
            }
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setSoTimeout(IDLE_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream(), BUFFER);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), BUFFER);
            while (running) {
                Request request;
                try {
                    request = readRequest(in);
                } catch (SocketTimeoutException idleForTooLong) {
                    return;
                }
                if (request == null) {
                    return;
                }
                boolean reuse = route(socket, in, out, request);
                out.flush();
                if (!reuse) {
                    return;
                }
            }
        } catch (IOException gone) {
            // A browser that navigated away mid-response is not an error.
        } finally {
            closeQuietly(socket);
        }
    }

    /** Handles one request. Returns whether the client connection can carry another. */
    private boolean route(Socket socket, InputStream in, OutputStream out, Request request) throws IOException {
        boolean keepAlive = wantsKeepAlive(request);
        String path = request.path();
        if (path == null || !path.startsWith("/")) {
            return text(out, 400, "bad request", keepAlive);
        }
        String query = request.query();
        if ("/".equals(path)) {
            String self = registry.self();
            if (self.isEmpty()) {
                return text(out, 503, "waiting for this kiosk to report its id", keepAlive);
            }
            List<String[]> headers = new ArrayList<String[]>();
            headers.add(new String[] {"Location", "/" + self + "/" + (query.isEmpty() ? "" : "?" + query)});
            headers.add(new String[] {"Content-Length", "0"});
            writeHead(out, 302, "Found", headers, keepAlive);
            return keepAlive;
        }
        if (path.startsWith("/static/")) {
            String build = query.startsWith("v=") ? query.substring(2) : "";
            String owner = registry.kioskForBuild(build);
            if (owner == null) {
                owner = registry.self().isEmpty() ? registry.anyKiosk() : registry.self();
            }
            String ownerTarget = owner == null ? null : registry.get(owner);
            if (ownerTarget == null) {
                return text(out, 404, "no kiosk serves this build yet", keepAlive);
            }
            String staticKey = ranged(request) ? null
                    : cacheKeyOf(request.method, path, query, takesGzip(request));
            if (staticKey != null) {
                Cached hit = cached(staticKey);
                if (hit != null) {
                    writeHead(out, hit.status, hit.reason, hit.headers, keepAlive);
                    out.write(hit.body);
                    out.flush();
                    return keepAlive;
                }
            }
            return proxy(request, in, owner, path, ownerTarget, originOf(request), out, keepAlive, staticKey);
        }
        Matcher matcher = ROUTE.matcher(path);
        if (!matcher.matches()) {
            return text(out, 404, "not found", keepAlive);
        }
        String id = matcher.group(1);
        String rest = (matcher.group(2) == null ? "/" : matcher.group(2)) + (query.isEmpty() ? "" : "?" + query);
        String target = registry.get(id);
        if (target == null) {
            log.log("no address known for " + id);
            return text(out, 502, "unknown kiosk", keepAlive);
        }
        String origin = originOf(request);
        if ("websocket".equalsIgnoreCase(request.headers.first("Upgrade"))) {
            new WebSocketRelay(target, origin, registry, local, log).relay(socket, request, in, out, rest);
            return false;
        }
        int split = rest.indexOf('?');
        String restPath = split < 0 ? rest : rest.substring(0, split);
        String restQuery = split < 0 ? "" : rest.substring(split + 1);
        String cacheKey = ranged(request) ? null
                : cacheKeyOf(request.method, restPath, restQuery, takesGzip(request));
        if (cacheKey != null) {
            Cached hit = cached(cacheKey);
            if (hit != null) {
                writeHead(out, hit.status, hit.reason, hit.headers, keepAlive);
                out.write(hit.body);
                out.flush();
                return keepAlive;
            }
        }
        return proxy(request, in, id, rest, target, origin, out, keepAlive, cacheKey);
    }

    private boolean proxy(Request request, InputStream clientIn, String id, String path, String target, String origin,
                          OutputStream out, boolean keepAlive, String cacheKey) throws IOException {
        boolean hasBody = hasBody(request);
        Upstream upstream = null;
        Response response = null;
        for (int attempt = 0; response == null; attempt++) {
            upstream = acquire(target);
            try {
                upstream.out.write(requestHead(request, path, target).getBytes("ISO-8859-1"));
                if (hasBody) {
                    copyRequestBody(request, clientIn, upstream.out);
                }
                upstream.out.flush();
                response = readResponse(upstream.in);
            } catch (IOException failed) {
                release(upstream, false);
                upstream = null;
                // A pooled socket the kiosk already dropped is worth one retry,
                // but only for a request without a body: that one cannot replay.
                if (attempt == 0 && !hasBody) {
                    continue;
                }
                log.log(request.method + " " + request.path() + " -> " + target + ": " + failed.getMessage());
                return text(out, 502, "kiosk unreachable", keepAlive);
            }
            if (response == null) {
                // The same dead socket, closed cleanly instead of reset.
                release(upstream, false);
                upstream = null;
                if (attempt == 0 && !hasBody) {
                    continue;
                }
                return text(out, 502, "kiosk sent no response", keepAlive);
            }
        }

        String contentType = response.headers.first("Content-Type");
        String contentEncoding = response.headers.first("Content-Encoding");
        boolean gzipped = contentEncoding != null && contentEncoding.toLowerCase(Locale.US).contains("gzip");
        boolean json = contentType != null && contentType.toLowerCase(Locale.US).startsWith("application/json");
        boolean html = contentType != null && contentType.toLowerCase(Locale.US).startsWith("text/html");
        boolean head = "HEAD".equals(request.method);

        List<String[]> headers = new ArrayList<String[]>();
        for (String[] header : response.headers.all()) {
            String name = header[0].toLowerCase(Locale.US);
            if (name.equals("x-frame-options") || name.equals("content-security-policy")
                    || name.equals("transfer-encoding") || name.equals("connection")
                    || name.equals("content-length") || name.equals("keep-alive")
                    || (html && (name.equals("cache-control") || name.equals("content-encoding")))
                    || (json && name.equals("content-encoding"))) {
                continue;
            }
            String value = header[1];
            if (name.equals("location") && value.startsWith("/")) {
                value = "/" + id + value;
            }
            headers.add(new String[] {header[0], value});
        }
        headers.add(new String[] {"Content-Security-Policy", frameAncestors()});

        try {
            if (head) {
                String length = response.headers.first("Content-Length");
                if (length != null) {
                    headers.add(new String[] {"Content-Length", length});
                }
                boolean reusable = length != null;
                writeHead(out, response.status, response.reason, headers, keepAlive && reusable);
                release(upstream, reusable);
                return keepAlive && reusable;
            }
            if (html) {
                byte[] body = readBody(upstream.in, response);
                String page = new String(gzipped ? gunzip(body) : body, "UTF-8");
                byte[] rewritten = HtmlRewriter.rewrite(page, id, registry).getBytes("UTF-8");
                if (gzipped) {
                    // Same encoding out as in, so the page still crosses the wifi small.
                    rewritten = gzip(rewritten);
                    headers.add(new String[] {"Content-Encoding", contentEncoding});
                }
                // The app sends no-store. A few seconds lets the prefetched copies
                // of the other kiosks survive until the click, and the page still
                // names its assets by build hash.
                headers.add(new String[] {"Cache-Control", "private, max-age=5"});
                headers.add(new String[] {"Content-Length", String.valueOf(rewritten.length)});
                writeHead(out, response.status, response.reason, headers, keepAlive);
                out.write(rewritten);
                release(upstream, true);
                return keepAlive;
            }
            if (json) {
                byte[] body = readBody(upstream.in, response);
                String text = new String(gzipped ? gunzip(body) : body, "UTF-8");
                String rewritten = FleetPayload.rewrite(text, origin, registry, local);
                if (gzipped && rewritten.equals(text)) {
                    // Nothing to change, so send upstream's bytes still compressed.
                    headers.add(new String[] {"Content-Encoding", contentEncoding});
                    headers.add(new String[] {"Content-Length", String.valueOf(body.length)});
                    writeHead(out, response.status, response.reason, headers, keepAlive);
                    out.write(body);
                    release(upstream, true);
                    return keepAlive;
                }
                byte[] bytes = rewritten.getBytes("UTF-8");
                headers.add(new String[] {"Content-Length", String.valueOf(bytes.length)});
                writeHead(out, response.status, response.reason, headers, keepAlive);
                out.write(bytes);
                release(upstream, true);
                return keepAlive;
            }
            if (cacheKey != null && response.status == 200 && storable(response)) {
                byte[] body = readBody(upstream.in, response);
                headers.add(new String[] {"Content-Length", String.valueOf(body.length)});
                store(cacheKey, response.status, response.reason, headers, body);
                writeHead(out, response.status, response.reason, headers, keepAlive);
                out.write(body);
                release(upstream, true);
                return keepAlive;
            }
            if (response.chunked) {
                headers.add(new String[] {"Transfer-Encoding", "chunked"});
                writeHead(out, response.status, response.reason, headers, keepAlive);
                copyChunked(upstream.in, out);
                release(upstream, true);
                return keepAlive;
            }
            if (response.contentLength >= 0) {
                headers.add(new String[] {"Content-Length", String.valueOf(response.contentLength)});
                writeHead(out, response.status, response.reason, headers, keepAlive);
                copyExactly(upstream.in, out, response.contentLength);
                release(upstream, true);
                return keepAlive;
            }
            // No length and no chunking: a stream that ends with the socket, which
            // is what the camera feeds look like. Nothing can follow on this
            // connection, because the end of the body is the end of the socket.
            writeHead(out, response.status, response.reason, headers, false);
            copyUntilEnd(upstream.in, out);
            release(upstream, false);
            return false;
        } catch (IOException broken) {
            release(upstream, false);
            throw broken;
        }
    }

    private String frameAncestors() {
        return ancestor.isEmpty() ? "frame-ancestors 'self'" : "frame-ancestors 'self' " + ancestor;
    }

    private static String originOf(Request request) {
        String forwarded = request.headers.first("X-Forwarded-Proto");
        String scheme = forwarded != null && forwarded.equalsIgnoreCase("https") ? "https" : "http";
        String host = request.headers.first("Host");
        return scheme + "://" + (host == null || host.isEmpty() ? "localhost" : host);
    }

    // -----------------------------------------------------------------------
    // Static cache. The app versions every asset with ?v=<hash> and serves it
    // immutable, so one copy serves every kiosk running that build.

    /**
     * A range request must reach the kiosk, because a cached whole file would be
     * sent back as a 200 to a client that asked for a slice.
     */
    private static boolean ranged(Request request) {
        return request.headers.first("Range") != null;
    }

    private static boolean takesGzip(Request request) {
        String acceptEncoding = request.headers.first("Accept-Encoding");
        return acceptEncoding != null && acceptEncoding.toLowerCase(Locale.US).contains("gzip");
    }

    private static String cacheKeyOf(String method, String path, String query, boolean gzipOk) {
        if (path == null || !path.startsWith("/static/") || !query.contains("v=") || !"GET".equals(method)) {
            return null;
        }
        // The stored bytes are whichever encoding upstream chose, so the variant
        // is part of the key: a client that cannot take gzip must not be handed
        // a gzipped body.
        return path + "?" + query + (gzipOk ? "|gzip" : "|plain");
    }

    private static boolean storable(Response response) {
        String cacheControl = response.headers.first("Cache-Control");
        if (cacheControl == null || !cacheControl.toLowerCase(Locale.US).contains("immutable")) {
            return false;
        }
        return response.contentLength >= 0 && response.contentLength <= MAX_CACHE_ENTRY;
    }

    private Cached cached(String key) {
        synchronized (cache) {
            return cache.get(key);
        }
    }

    private void store(String key, int status, String reason, List<String[]> headers, byte[] body) {
        synchronized (cache) {
            Cached previous = cache.put(key, new Cached(status, reason, headers, body));
            cachedBytes += body.length;
            if (previous != null) {
                cachedBytes -= previous.body.length;
            }
            while (cachedBytes > MAX_CACHE_BYTES && !cache.isEmpty()) {
                String eldest = cache.keySet().iterator().next();
                Cached dropped = cache.remove(eldest);
                cachedBytes -= dropped.body.length;
                if (disk != null) {
                    disk.remove(eldest);
                }
            }
        }
        if (disk != null) {
            disk.write(key, status, reason, headers, body);
        }
    }

    /**
     * Reads each kiosk's page once, in the background, to learn its build hash.
     *
     * <p>Origin level asset paths are answered by a kiosk that serves the build
     * asked for. After a restart that map is empty, and a request would fall back
     * to this device, which may be on another build: a page paired with someone
     * else's JavaScript. One small request per kiosk closes that hole.
     */
    void learnBuildsAsync() {
        Thread learner = new Thread(new Runnable() {
            @Override
            public void run() {
                learnBuildsNow();
            }
        }, "kiosk-fleet-proxy-builds");
        learner.setDaemon(true);
        learner.start();
    }

    void learnBuildsNow() {
        {
            {
                for (final String id : registry.ids()) {
                    String target = registry.get(id);
                    if (target == null) {
                        continue;
                    }
                    try {
                        Socket socket = new Socket();
                        socket.connect(address(target), 5000);
                        socket.setSoTimeout(5000);
                        OutputStream out = socket.getOutputStream();
                        InputStream in = new BufferedInputStream(socket.getInputStream(), BUFFER);
                        out.write(("GET / HTTP/1.1" + LINE_BREAK + "Host: " + target + LINE_BREAK
                                + "Accept-Encoding: gzip" + LINE_BREAK + "Connection: close" + LINE_BREAK
                                + LINE_BREAK).getBytes("ISO-8859-1"));
                        out.flush();
                        Response response = readResponse(in);
                        if (response != null && response.status == 200) {
                            byte[] body = readBody(in, response);
                            String encoding = response.headers.first("Content-Encoding");
                            String page = new String(encoding != null && encoding.contains("gzip") ? gunzip(body) : body,
                                    "UTF-8");
                            HtmlRewriter.rewrite(page, id, registry);
                        }
                        closeQuietly(socket);
                    } catch (Exception unreachable) {
                        // An offline kiosk keeps its asset paths unusable until it answers.
                    }
                }
            }
        }
    }

    /** Entries kept on disk by a previous run, newest first until the cap. */
    private void restore() {
        if (disk == null) {
            return;
        }
        for (Map.Entry<String, Cached> entry : disk.load()) {
            synchronized (cache) {
                if (cachedBytes + entry.getValue().body.length > MAX_CACHE_BYTES) {
                    return;
                }
                cache.put(entry.getKey(), entry.getValue());
                cachedBytes += entry.getValue().body.length;
            }
        }
    }

    static final class Cached {
        final int status;
        final String reason;
        final List<String[]> headers;
        final byte[] body;

        Cached(int status, String reason, List<String[]> headers, byte[] body) {
            this.status = status;
            this.reason = reason;
            this.headers = headers;
            this.body = body;
        }
    }

    // -----------------------------------------------------------------------
    // Upstream connections, a small pool per kiosk.

    private static final class Upstream {
        final String target;
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        final long created;

        Upstream(String target, Socket socket) throws IOException {
            this.target = target;
            this.socket = socket;
            this.socket.setSoTimeout(IDLE_TIMEOUT_MS);
            this.in = new BufferedInputStream(socket.getInputStream(), BUFFER);
            this.out = new BufferedOutputStream(socket.getOutputStream(), BUFFER);
            this.created = System.currentTimeMillis();
        }
    }

    private Upstream acquire(String target) throws IOException {
        synchronized (idle) {
            Deque<Upstream> pool = idle.get(target);
            while (pool != null && !pool.isEmpty()) {
                Upstream reused = pool.poll();
                idleCount--;
                if (!reused.socket.isClosed() && System.currentTimeMillis() - reused.created < IDLE_TIMEOUT_MS) {
                    return reused;
                }
                closeQuietly(reused.socket);
            }
        }
        Socket socket = new Socket();
        socket.connect(address(target), 10000);
        socket.setTcpNoDelay(true);
        return new Upstream(target, socket);
    }

    private void release(Upstream upstream, boolean reusable) {
        if (upstream == null) {
            return;
        }
        if (!reusable) {
            closeQuietly(upstream.socket);
            return;
        }
        synchronized (idle) {
            Deque<Upstream> pool = idle.get(upstream.target);
            if (pool == null) {
                pool = new ArrayDeque<Upstream>();
                idle.put(upstream.target, pool);
            }
            if (pool.size() >= MAX_IDLE_PER_TARGET || idleCount >= MAX_IDLE_TOTAL) {
                closeQuietly(upstream.socket);
                return;
            }
            pool.addLast(upstream);
            idleCount++;
        }
    }

    // -----------------------------------------------------------------------
    // HTTP

    static final class Headers {
        private final List<String[]> entries = new ArrayList<String[]>();

        void add(String name, String value) {
            entries.add(new String[] {name, value});
        }

        List<String[]> all() {
            return entries;
        }

        String first(String name) {
            for (String[] entry : entries) {
                if (entry[0].equalsIgnoreCase(name)) {
                    return entry[1];
                }
            }
            return null;
        }
    }

    static final class Request {
        String method;
        String target;
        final Headers headers = new Headers();

        String path() {
            if (target == null) {
                return null;
            }
            int query = target.indexOf('?');
            return query < 0 ? target : target.substring(0, query);
        }

        String query() {
            int query = target == null ? -1 : target.indexOf('?');
            return query < 0 ? "" : target.substring(query + 1);
        }
    }

    static final class Response {
        int status;
        String reason = "";
        final Headers headers = new Headers();
        boolean chunked;
        long contentLength = -1;
    }

    static Request readRequest(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null || line.isEmpty()) {
            return null;
        }
        String[] parts = line.split(" ");
        if (parts.length < 3) {
            return null;
        }
        Request request = new Request();
        request.method = parts[0];
        request.target = parts[1];
        readHeaders(in, request.headers);
        return request;
    }

    static Response readResponse(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null) {
            return null;
        }
        String[] parts = line.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            return null;
        }
        Response response = new Response();
        response.status = Integer.parseInt(parts[1]);
        response.reason = parts.length > 2 ? parts[2] : "";
        readHeaders(in, response.headers);
        String transferEncoding = response.headers.first("Transfer-Encoding");
        response.chunked = transferEncoding != null && transferEncoding.toLowerCase(Locale.US).contains("chunked");
        String length = response.headers.first("Content-Length");
        if (length != null) {
            try {
                response.contentLength = Long.parseLong(length.trim());
            } catch (NumberFormatException ignored) {
                response.contentLength = -1;
            }
        }
        return response;
    }

    private static void readHeaders(InputStream in, Headers headers) throws IOException {
        for (int i = 0; i < MAX_HEADERS; i++) {
            String line = readLine(in);
            if (line == null || line.isEmpty()) {
                return;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.add(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
    }

    /** A CRLF terminated line, or null at end of stream. */
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        while (buffer.size() < MAX_LINE) {
            int b = in.read();
            if (b < 0) {
                return buffer.size() == 0 ? null : buffer.toString("ISO-8859-1");
            }
            if (b == '\n') {
                String line = buffer.toString("ISO-8859-1");
                return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            }
            buffer.write(b);
        }
        throw new IOException("header line too long");
    }

    private static boolean wantsKeepAlive(Request request) {
        String connection = request.headers.first("Connection");
        return connection == null || !connection.toLowerCase(Locale.US).contains("close");
    }

    private static boolean hasBody(Request request) {
        String transferEncoding = request.headers.first("Transfer-Encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase(Locale.US).contains("chunked")) {
            return true;
        }
        String length = request.headers.first("Content-Length");
        return length != null && !"0".equals(length.trim());
    }

    private static void copyRequestBody(Request request, InputStream clientIn, OutputStream upOut) throws IOException {
        String transferEncoding = request.headers.first("Transfer-Encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase(Locale.US).contains("chunked")) {
            copyChunked(clientIn, upOut);
            return;
        }
        String length = request.headers.first("Content-Length");
        if (length != null) {
            copyExactly(clientIn, upOut, Long.parseLong(length.trim()));
        }
    }

    private static String requestHead(Request request, String path, String target) {
        StringBuilder head = new StringBuilder(256);
        head.append(request.method).append(' ').append(path.startsWith("/") ? path : "/" + path)
                .append(" HTTP/1.1\r\n");
        head.append("Host: ").append(target).append("\r\n");
        for (String[] header : request.headers.all()) {
            String name = header[0].toLowerCase(Locale.US);
            if (name.equals("host") || name.equals("connection") || name.equals("keep-alive")
                    || name.equals("proxy-connection") || name.equals("te") || name.equals("trailer")
                    || name.equals("upgrade") || name.equals("accept-encoding")
                    || name.startsWith("sec-websocket-")) {
                continue;
            }
            head.append(header[0]).append(": ").append(header[1]).append("\r\n");
        }
        String acceptEncoding = request.headers.first("Accept-Encoding");
        boolean clientTakesGzip = acceptEncoding != null
                && acceptEncoding.toLowerCase(Locale.US).contains("gzip");
        head.append("Accept-Encoding: ").append(clientTakesGzip ? "gzip" : "identity")
                .append("\r\nConnection: keep-alive\r\n\r\n");
        return head.toString();
    }

    private static InetSocketAddress address(String hostAndPort) {
        int colon = hostAndPort.lastIndexOf(':');
        if (colon <= 0) {
            return new InetSocketAddress(hostAndPort, 80);
        }
        return new InetSocketAddress(hostAndPort.substring(0, colon),
                Integer.parseInt(hostAndPort.substring(colon + 1)));
    }

    private static byte[] readBody(InputStream in, Response response) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (response.chunked) {
            while (true) {
                int size = chunkSize(in);
                if (size <= 0) {
                    readLine(in);
                    break;
                }
                copyExactly(in, body, size);
                readLine(in);
            }
        } else if (response.contentLength >= 0) {
            copyExactly(in, body, response.contentLength);
        } else {
            copyUntilEnd(in, body);
        }
        return body.toByteArray();
    }

    private static int chunkSize(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null) {
            return 0;
        }
        int semicolon = line.indexOf(';');
        try {
            return Integer.parseInt((semicolon < 0 ? line : line.substring(0, semicolon)).trim(), 16);
        } catch (NumberFormatException bad) {
            return 0;
        }
    }

    private static void copyExactly(InputStream in, OutputStream out, long count) throws IOException {
        byte[] buffer = new byte[BUFFER];
        long left = count;
        while (left > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (read < 0) {
                return;
            }
            out.write(buffer, 0, read);
            left -= read;
        }
        out.flush();
    }

    private static void copyChunked(InputStream in, OutputStream out) throws IOException {
        while (true) {
            int size = chunkSize(in);
            if (size <= 0) {
                out.write("0\r\n\r\n".getBytes("ISO-8859-1"));
                out.flush();
                readLine(in);
                return;
            }
            out.write((Integer.toHexString(size) + "\r\n").getBytes("ISO-8859-1"));
            copyExactly(in, out, size);
            out.write("\r\n".getBytes("ISO-8859-1"));
            out.flush();
            readLine(in);
        }
    }

    private static void copyUntilEnd(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER];
        while (true) {
            int read = in.read(buffer);
            if (read < 0) {
                return;
            }
            out.write(buffer, 0, read);
            out.flush();
        }
    }

    private static byte[] gzip(byte[] plain) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(plain.length / 3, 1024));
        java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(buffer);
        gzip.write(plain);
        gzip.close();
        return buffer.toByteArray();
    }

    /** A gzipped body, decoded so the roster inside can be rewritten. */
    private static byte[] gunzip(byte[] compressed) throws IOException {
        InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed));
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(compressed.length * 4, 1024));
        copyUntilEnd(in, out);
        return out.toByteArray();
    }

    static void writeHead(OutputStream out, int status, String reason, List<String[]> headers, boolean keepAlive)
            throws IOException {
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason == null ? "" : reason).append("\r\n");
        for (String[] header : headers) {
            head.append(header[0]).append(": ").append(header[1]).append("\r\n");
        }
        head.append(keepAlive ? "Connection: keep-alive\r\n\r\n" : "Connection: close\r\n\r\n");
        out.write(head.toString().getBytes("ISO-8859-1"));
        out.flush();
    }

    private static boolean text(OutputStream out, int status, String body, boolean keepAlive) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        List<String[]> headers = new ArrayList<String[]>();
        headers.add(new String[] {"Content-Type", "text/plain; charset=utf-8"});
        headers.add(new String[] {"Content-Length", String.valueOf(bytes.length)});
        writeHead(out, status, status == 200 ? "OK" : String.valueOf(status), headers, keepAlive);
        out.write(bytes);
        out.flush();
        return keepAlive;
    }

    static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }
}
