// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves every kiosk in the fleet from this one origin.
 *
 * <p>{@code /} redirects to this device's own id and {@code /<id>/...} goes to
 * that kiosk's admin server. Responses lose {@code X-Frame-Options} and gain a
 * {@code frame-ancestors} policy, which is the whole point: the app answers
 * {@code SAMEORIGIN} and no browser lets a dashboard iframe override that.
 */
final class HttpProxyServer {

    /** Where status lines go. */
    interface Logger {
        void log(String message);
    }

    private static final Pattern ROUTE = Pattern.compile("^/([0-9a-fA-F]{6,40})(/.*)?$");
    private static final int MAX_LINE = 8192;
    private static final int MAX_HEADERS = 100;
    private static final int BUFFER = 16384;

    private final Registry registry;
    private final LocalAddresses local;
    private final Logger log;
    private final String ancestor;
    private final int port;

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
            InputStream in = new BufferedInputStream(socket.getInputStream(), BUFFER);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), BUFFER);
            Request request = readRequest(in);
            if (request != null) {
                route(socket, in, out, request);
                out.flush();
            }
        } catch (IOException gone) {
            // A browser that navigated away mid-response is not an error.
        } finally {
            closeQuietly(socket);
        }
    }

    private void route(Socket socket, InputStream in, OutputStream out, Request request) throws IOException {
        String path = request.path();
        if (path == null || !path.startsWith("/")) {
            text(out, 400, "bad request");
            return;
        }
        String query = request.query();
        if ("/".equals(path)) {
            String self = registry.self();
            if (self.isEmpty()) {
                text(out, 503, "waiting for this kiosk to report its id");
                return;
            }
            List<String[]> headers = new ArrayList<String[]>();
            headers.add(new String[] {"Location", "/" + self + "/" + (query.isEmpty() ? "" : "?" + query)});
            headers.add(new String[] {"Content-Length", "0"});
            writeHead(out, 302, "Found", headers);
            return;
        }
        Matcher matcher = ROUTE.matcher(path);
        if (!matcher.matches()) {
            text(out, 404, "not found");
            return;
        }
        String id = matcher.group(1);
        String rest = (matcher.group(2) == null ? "/" : matcher.group(2)) + (query.isEmpty() ? "" : "?" + query);
        String target = registry.get(id);
        if (target == null) {
            log.log("no address known for " + id);
            text(out, 502, "unknown kiosk");
            return;
        }
        String origin = originOf(request);
        if ("websocket".equalsIgnoreCase(request.headers.first("Upgrade"))) {
            new WebSocketRelay(target, origin, registry, local, log).relay(socket, request, in, out, rest);
            return;
        }
        proxy(request, in, id, rest, target, origin, out);
    }

    private static String originOf(Request request) {
        String forwarded = request.headers.first("X-Forwarded-Proto");
        String scheme = forwarded != null && forwarded.equalsIgnoreCase("https") ? "https" : "http";
        String host = request.headers.first("Host");
        return scheme + "://" + (host == null || host.isEmpty() ? "localhost" : host);
    }

    private void proxy(Request request, InputStream clientIn, String id, String path, String target, String origin,
                       OutputStream out) throws IOException {
        Socket upstream = new Socket();
        try {
            upstream.connect(address(target), 10000);
            upstream.setSoTimeout(0);
        } catch (IOException unreachable) {
            log.log(request.method + " " + request.path() + " -> " + target + ": " + unreachable.getMessage());
            text(out, 502, "kiosk unreachable");
            return;
        }
        try {
            InputStream upIn = new BufferedInputStream(upstream.getInputStream(), BUFFER);
            OutputStream upOut = new BufferedOutputStream(upstream.getOutputStream(), BUFFER);
            upOut.write(requestHead(request, path, target).getBytes("ISO-8859-1"));
            // The admin API is all POST with a body, so dropping it turns every
            // command into an empty request.
            String transferEncoding = request.headers.first("Transfer-Encoding");
            String lengthHeader = request.headers.first("Content-Length");
            if (transferEncoding != null && transferEncoding.toLowerCase(Locale.US).contains("chunked")) {
                copyChunked(clientIn, upOut);
            } else if (lengthHeader != null && !"0".equals(lengthHeader.trim())) {
                copyExactly(clientIn, upOut, Long.parseLong(lengthHeader.trim()));
            }
            upOut.flush();

            Response response = readResponse(upIn);
            if (response == null) {
                text(out, 502, "kiosk sent no response");
                return;
            }
            String contentType = response.headers.first("Content-Type");
            boolean json = contentType != null
                    && contentType.toLowerCase(Locale.US).startsWith("application/json")
                    && response.headers.first("Content-Encoding") == null;

            List<String[]> headers = new ArrayList<String[]>();
            for (String[] header : response.headers.all()) {
                String name = header[0].toLowerCase(Locale.US);
                if (name.equals("x-frame-options") || name.equals("content-security-policy")
                        || name.equals("transfer-encoding") || name.equals("connection")
                        || name.equals("content-length")) {
                    continue;
                }
                String value = header[1];
                if (name.equals("location") && value.startsWith("/")) {
                    value = "/" + id + value;
                }
                headers.add(new String[] {header[0], value});
            }
            headers.add(new String[] {"Content-Security-Policy", frameAncestors()});

            if (json) {
                byte[] body = readBody(upIn, response);
                byte[] rewritten = FleetPayload.rewrite(new String(body, "UTF-8"), origin, registry, local)
                        .getBytes("UTF-8");
                headers.add(new String[] {"Content-Length", String.valueOf(rewritten.length)});
                writeHead(out, response.status, response.reason, headers);
                out.write(rewritten);
                return;
            }
            if (response.chunked) {
                headers.add(new String[] {"Transfer-Encoding", "chunked"});
                writeHead(out, response.status, response.reason, headers);
                copyChunked(upIn, out);
                return;
            }
            if (response.contentLength >= 0) {
                headers.add(new String[] {"Content-Length", String.valueOf(response.contentLength)});
                writeHead(out, response.status, response.reason, headers);
                copyExactly(upIn, out, response.contentLength);
                return;
            }
            // No length and no chunking: a stream that ends with the socket, which
            // is what the camera feeds look like.
            writeHead(out, response.status, response.reason, headers);
            copyUntilEnd(upIn, out);
        } finally {
            closeQuietly(upstream);
        }
    }

    private String frameAncestors() {
        return ancestor.isEmpty() ? "frame-ancestors 'self'" : "frame-ancestors 'self' " + ancestor;
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
        head.append("Accept-Encoding: identity\r\nConnection: close\r\n\r\n");
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
            out.flush();
            left -= read;
        }
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

    static void writeHead(OutputStream out, int status, String reason, List<String[]> headers) throws IOException {
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason == null ? "" : reason).append("\r\n");
        for (String[] header : headers) {
            head.append(header[0]).append(": ").append(header[1]).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes("ISO-8859-1"));
        out.flush();
    }

    private static void text(OutputStream out, int status, String body) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        List<String[]> headers = new ArrayList<String[]>();
        headers.add(new String[] {"Content-Type", "text/plain; charset=utf-8"});
        headers.add(new String[] {"Content-Length", String.valueOf(bytes.length)});
        writeHead(out, status, status == 200 ? "OK" : String.valueOf(status), headers);
        out.write(bytes);
        out.flush();
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
