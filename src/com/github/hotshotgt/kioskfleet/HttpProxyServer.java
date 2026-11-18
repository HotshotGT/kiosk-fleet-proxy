// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves every kiosk in the fleet from this one origin.
 *
 * <p>{@code /} redirects to this device's own id and {@code /<id>/...} goes to
 * that kiosk's admin server. Responses lose {@code X-Frame-Options} and gain a
 * {@code frame-ancestors} policy, which is the whole reason this exists:
 * dart:io answers {@code SAMEORIGIN}, no browser lets a dashboard iframe
 * override that, and a header the browser accepts is the only fix.
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
                if (running) {
                    log.log("accept failed: " + stop.getMessage());
                }
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
            if (request == null) {
                closeQuietly(socket);
                return;
            }
            route(socket, in, out, request);
            out.flush();
        } catch (IOException gone) {
            // A browser that navigated away mid-response is not an error.
        } finally {
            closeQuietly(socket);
        }
    }

    // -----------------------------------------------------------------------
    // routing

    private void route(Socket socket, InputStream in, OutputStream out, Request request) throws IOException {
        String path = request.path();
        if (path == null || !path.startsWith("/")) {
            writeText(out, 400, "bad request");
            return;
        }
        if ("/".equals(path)) {
            String self = registry.self();
            if (self.isEmpty()) {
                waiting(out);
                return;
            }
            String location = "/" + self + "/" + (request.query().isEmpty() ? "" : "?" + request.query());
            List<String[]> headers = new ArrayList<String[]>();
            headers.add(new String[] {"Location", location});
            headers.add(new String[] {"Content-Length", "0"});
            writeHead(out, 302, "Found", headers);
            return;
        }
        Matcher matcher = ROUTE.matcher(path);
        if (!matcher.matches()) {
            writeText(out, 404, "not found");
            return;
        }
        String id = matcher.group(1);
        String rest = matcher.group(2) == null ? "/" : matcher.group(2);
        String target = registry.get(id);
        if (target == null) {
            log.log("no address known for " + id);
            writeText(out, 502, "unknown kiosk");
            return;
        }
        String origin = originOf(request);
        if ("websocket".equalsIgnoreCase(request.header("Upgrade"))) {
            String query = request.query();
            new WebSocketRelay(target, origin, registry, local, log)
                    .relay(socket, request, in, out, rest + (query.isEmpty() ? "" : "?" + query));
            throw new IOException("websocket finished");
        }
        proxy(request, in, id, rest + (request.query().isEmpty() ? "" : "?" + request.query()), target, origin, out);
    }

    private static String originOf(Request request) {
        String scheme = "http";
        String forwarded = request.header("X-Forwarded-Proto");
        if (forwarded != null && forwarded.equalsIgnoreCase("https")) {
            scheme = "https";
        }
        String host = request.header("Host");
        if (host == null || host.isEmpty()) {
            host = "localhost";
        }
        return scheme + "://" + host;
    }

    // -----------------------------------------------------------------------
    // proxying

    private void proxy(Request request, InputStream clientIn, String id, String path, String target, String origin,
                       OutputStream out) throws IOException {
        Socket upstream = new Socket();
        try {
            upstream.connect(address(target), 10000);
            upstream.setSoTimeout(0);
        } catch (IOException unreachable) {
            log.log(request.method + " " + request.path() + " -> " + target + ": " + unreachable.getMessage());
            writeText(out, 502, "kiosk unreachable");
            return;
        }
        try {
            InputStream upIn = new BufferedInputStream(upstream.getInputStream(), BUFFER);
            OutputStream upOut = new BufferedOutputStream(upstream.getOutputStream(), BUFFER);
            upOut.write(requestHead(request, path, target).getBytes("ISO-8859-1"));
            upOut.flush();
            // The admin API is all POST with a JSON body, so a dropped body turns
            // every command into a 401 or an empty request.
            String transferEncoding = request.header("Transfer-Encoding");
            boolean chunkedRequest = transferEncoding != null
                    && transferEncoding.toLowerCase(Locale.US).contains("chunked");
            String lengthHeader = request.header("Content-Length");
            if (chunkedRequest) {
                copyChunked(clientIn, upOut);
            } else if (lengthHeader != null) {
                long length = -1;
                try {
                    length = Long.parseLong(lengthHeader.trim());
                } catch (NumberFormatException bad) {
                    length = -1;
                }
                if (length > 0) {
                    copyExactly(clientIn, upOut, length);
                }
            }
            upOut.flush();

            Response response = readResponse(upIn);
            if (response == null) {
                writeText(out, 502, "kiosk sent no response");
                return;
            }
            String contentType = response.header("Content-Type");
            boolean json = contentType != null
                    && contentType.toLowerCase(Locale.US).startsWith("application/json")
                    && !response.hasHeader("Content-Encoding");

            List<String[]> headers = new ArrayList<String[]>();
            for (String[] header : response.headers) {
                String name = header[0].toLowerCase(Locale.US);
                if (name.equals("x-frame-options") || name.equals("content-security-policy")) {
                    continue;
                }
                if (name.equals("transfer-encoding") || name.equals("connection") || name.equals("content-length")) {
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
                String rewritten = FleetPayload.rewrite(new String(body, "UTF-8"), origin, registry, local);
                byte[] bytes = rewritten.getBytes("UTF-8");
                headers.add(new String[] {"Content-Length", String.valueOf(bytes.length)});
                writeHead(out, response.status, response.reason, headers);
                out.write(bytes);
                out.flush();
                return;
            }
            if (response.chunked) {
                headers.add(new String[] {"Transfer-Encoding", "chunked"});
                writeHead(out, response.status, response.reason, headers);
                out.flush();
                copyChunked(upIn, out);
                return;
            }
            if (response.contentLength >= 0) {
                headers.add(new String[] {"Content-Length", String.valueOf(response.contentLength)});
                writeHead(out, response.status, response.reason, headers);
                out.flush();
                copyExactly(upIn, out, response.contentLength);
                return;
            }
            // No length and no chunking: a stream that ends when the socket does,
            // which is what the camera feeds look like.
            writeHead(out, response.status, response.reason, headers);
            out.flush();
            copyUntilEnd(upIn, out);
        } finally {
            closeQuietly(upstream);
        }
    }

    private String frameAncestors() {
        if (ancestor.isEmpty()) {
            return "frame-ancestors 'self'";
        }
        return "frame-ancestors 'self' " + ancestor;
    }

    private static String requestHead(Request request, String path, String target) {
        StringBuilder head = new StringBuilder(256);
        head.append(request.method).append(' ').append(path.startsWith("/") ? path : "/" + path).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(target).append("\r\n");
        for (Map.Entry<String, List<String>> entry : request.headers.entrySet()) {
            String name = entry.getKey();
            if (hopByHop(name) || name.equals("host") || name.equals("accept-encoding")) {
                continue;
            }
            for (String value : entry.getValue()) {
                head.append(entry.getKey()).append(": ").append(value).append("\r\n");
            }
        }
        head.append("Accept-Encoding: identity\r\n");
        head.append("Connection: close\r\n");
        head.append("\r\n");
        return head.toString();
    }

    private static boolean hopByHop(String lowerName) {
        return lowerName.equals("connection") || lowerName.equals("keep-alive") || lowerName.equals("proxy-connection")
                || lowerName.equals("te") || lowerName.equals("trailer")
                || lowerName.equals("upgrade") || lowerName.equals("sec-websocket-key")
                || lowerName.equals("sec-websocket-version") || lowerName.equals("sec-websocket-extensions");
    }

    private static InetSocketAddress address(String hostAndPort) {
        int colon = hostAndPort.lastIndexOf(':');
        if (colon <= 0) {
            return new InetSocketAddress(hostAndPort, 80);
        }
        String host = hostAndPort.substring(0, colon);
        int port = Integer.parseInt(hostAndPort.substring(colon + 1));
        return new InetSocketAddress(host, port);
    }

    // -----------------------------------------------------------------------
    // HTTP reading and writing

    static final class Request {
        String method;
        String target;
        String version;
        final Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();

        String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.US));
            return values == null || values.isEmpty() ? null : values.get(0);
        }

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
        final List<String[]> headers = new ArrayList<String[]>();
        boolean chunked;
        long contentLength = -1;

        String header(String name) {
            for (String[] header : headers) {
                if (header[0].equalsIgnoreCase(name)) {
                    return header[1];
                }
            }
            return null;
        }

        boolean hasHeader(String name) {
            return header(name) != null;
        }
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
        request.version = parts[2];
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
        String transferEncoding = response.header("Transfer-Encoding");
        response.chunked = transferEncoding != null && transferEncoding.toLowerCase(Locale.US).contains("chunked");
        String length = response.header("Content-Length");
        if (length != null) {
            try {
                response.contentLength = Long.parseLong(length.trim());
            } catch (NumberFormatException ignored) {
                response.contentLength = -1;
            }
        }
        return response;
    }

    private static void readHeaders(InputStream in, List<String[]> headers) throws IOException {
        readHeaders(in, headers, null);
        // Headers land in one of two shapes; this keeps a single parser.
    }

    private static void readHeaders(InputStream in, List<String[]> list, Map<String, List<String>> map) throws IOException {
        for (int i = 0; i < MAX_HEADERS; i++) {
            String line = readLine(in);
            if (line == null || line.isEmpty()) {
                return;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            list.add(new String[] {name, value});
            if (map != null) {
                String key = name.toLowerCase(Locale.US);
                List<String> values = map.get(key);
                if (values == null) {
                    values = new ArrayList<String>();
                    map.put(key, values);
                }
                values.add(value);
            }
        }
    }

    private static void readHeaders(InputStream in, Map<String, List<String>> map) throws IOException {
        for (int i = 0; i < MAX_HEADERS; i++) {
            String line = readLine(in);
            if (line == null || line.isEmpty()) {
                return;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            List<String> values = map.get(key);
            if (values == null) {
                values = new ArrayList<String>();
                map.put(key, values);
            }
            values.add(value);
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
        if (response.chunked) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            while (true) {
                String sizeLine = readLine(in);
                if (sizeLine == null) {
                    break;
                }
                int semicolon = sizeLine.indexOf(';');
                String hex = (semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon)).trim();
                int size;
                try {
                    size = Integer.parseInt(hex, 16);
                } catch (NumberFormatException bad) {
                    break;
                }
                if (size == 0) {
                    readLine(in);
                    break;
                }
                copyExactly(in, body, size);
                readLine(in);
            }
            return body.toByteArray();
        }
        if (response.contentLength >= 0) {
            ByteArrayOutputStream body = new ByteArrayOutputStream((int) Math.min(response.contentLength, 1 << 20));
            copyExactly(in, body, response.contentLength);
            return body.toByteArray();
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        copyUntilEnd(in, body);
        return body.toByteArray();
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
            out.flush();
        }
    }

    private static void copyChunked(InputStream in, OutputStream out) throws IOException {
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                return;
            }
            int semicolon = sizeLine.indexOf(';');
            String hex = (semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon)).trim();
            int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (NumberFormatException bad) {
                return;
            }
            if (size == 0) {
                out.write("0\r\n\r\n".getBytes("ISO-8859-1"));
                out.flush();
                readLine(in);
                return;
            }
            out.write((hex + "\r\n").getBytes("ISO-8859-1"));
            copyExactly(in, out, size);
            out.write("\r\n".getBytes("ISO-8859-1"));
            out.flush();
            readLine(in);
        }
    }

    private static void copyUntilEnd(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER];
        while (true) {
            int read;
            try {
                read = in.read(buffer);
            } catch (IOException closed) {
                return;
            }
            if (read < 0) {
                return;
            }
            out.write(buffer, 0, read);
            out.flush();
        }
    }

    static void writeHead(OutputStream out, int status, String reason, List<String[]> headers) throws IOException {
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason == null || reason.isEmpty() ? "" : reason)
                .append("\r\n");
        for (String[] header : headers) {
            head.append(header[0]).append(": ").append(header[1]).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes("ISO-8859-1"));
        out.flush();
    }

    private void writeText(OutputStream out, int status, String body) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        List<String[]> headers = new ArrayList<String[]>();
        headers.add(new String[] {"Content-Type", "text/plain; charset=utf-8"});
        headers.add(new String[] {"Content-Length", String.valueOf(bytes.length)});
        writeHead(out, status, status == 200 ? "OK" : String.valueOf(status), headers);
        out.write(bytes);
        out.flush();
    }

    private void waiting(OutputStream out) throws IOException {
        String page = "<!doctype html><meta http-equiv=\"refresh\" content=\"2\">"
                + "<title>Kiosk Fleet Proxy</title><p>Waiting for this kiosk to report its id.</p>";
        byte[] bytes = page.getBytes("UTF-8");
        List<String[]> headers = new ArrayList<String[]>();
        headers.add(new String[] {"Content-Type", "text/html; charset=utf-8"});
        headers.add(new String[] {"Content-Length", String.valueOf(bytes.length)});
        headers.add(new String[] {"Retry-After", "2"});
        headers.add(new String[] {"Cache-Control", "no-store"});
        writeHead(out, 503, "Service Unavailable", headers);
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

    static byte[] latin1(String text) {
        try {
            return text.getBytes("ISO-8859-1");
        } catch (UnsupportedEncodingException impossible) {
            return text.getBytes();
        }
    }
}
