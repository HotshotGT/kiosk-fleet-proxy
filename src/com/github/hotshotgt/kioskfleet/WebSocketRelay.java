// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Relays one WebSocket connection to a kiosk, rewriting the text messages that
 * carry the fleet roster.
 *
 * <p>The roster arrives twice: once in an HTTP response and again over this
 * socket. The socket copy wins in the app, so rewriting only the HTTP body sends
 * the browser straight back to a raw address. This is why the relay exists.
 *
 * <p>Compression is refused on the way up. If the kiosk agreed to
 * permessage-deflate, message payloads would be binary and could not be
 * rewritten, so the upgrade request leaves that extension out.
 */
final class WebSocketRelay {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int OP_CONTINUATION = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_BINARY = 0x2;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    private final String target;
    private final String origin;
    private final Registry registry;
    private final LocalAddresses local;
    private final HttpProxyServer.Logger log;
    private final Random random = new Random();

    WebSocketRelay(String target, String origin, Registry registry, LocalAddresses local, HttpProxyServer.Logger log) {
        this.target = target;
        this.origin = origin;
        this.registry = registry;
        this.local = local;
        this.log = log;
    }

    void relay(Socket clientSocket, HttpProxyServer.Request request, InputStream clientIn, OutputStream clientOut,
               String pathAndQuery) throws IOException {
        String key = request.header("Sec-WebSocket-Key");
        if (key == null || key.isEmpty()) {
            return;
        }
        Socket upstream = new Socket();
        try {
            upstream.connect(address(target), 10000);
            upstream.setSoTimeout(0);
        } catch (IOException unreachable) {
            log.log("websocket to " + target + ": " + unreachable.getMessage());
            HttpProxyServer.writeHead(clientOut, 502, "Bad Gateway", headers("Content-Length", "0"));
            return;
        }
        InputStream upIn = upstream.getInputStream();
        OutputStream upOut = upstream.getOutputStream();
        try {
            upOut.write(handshake(request, pathAndQuery, key).getBytes("ISO-8859-1"));
            upOut.flush();
            HttpProxyServer.Response response = HttpProxyServer.readResponse(upIn);
            if (response == null || response.status != 101) {
                int status = response == null ? 502 : response.status;
                log.log("websocket upgrade refused: " + status);
                HttpProxyServer.writeHead(clientOut, 502, "Bad Gateway", headers("Content-Length", "0"));
                return;
            }
            List<String[]> accept = new ArrayList<String[]>();
            accept.add(new String[] {"Upgrade", "websocket"});
            accept.add(new String[] {"Connection", "Upgrade"});
            accept.add(new String[] {"Sec-WebSocket-Accept", acceptKey(key)});
            StringBuilder head = new StringBuilder(160);
            head.append("HTTP/1.1 101 Switching Protocols\r\n");
            for (String[] header : accept) {
                head.append(header[0]).append(": ").append(header[1]).append("\r\n");
            }
            head.append("\r\n");
            clientOut.write(head.toString().getBytes("ISO-8859-1"));
            clientOut.flush();

            Thread upstreamToClient = new Thread(new Runnable() {
                @Override
                public void run() {
                    pump(upIn, clientOut, false);
                }
            }, "kiosk-fleet-proxy-ws-down");
            Thread clientToUpstream = new Thread(new Runnable() {
                @Override
                public void run() {
                    pump(clientIn, upOut, true);
                }
            }, "kiosk-fleet-proxy-ws-up");
            upstreamToClient.setDaemon(true);
            clientToUpstream.setDaemon(true);
            upstreamToClient.start();
            clientToUpstream.start();
            try {
                upstreamToClient.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            HttpProxyServer.closeQuietly(upstream);
            HttpProxyServer.closeQuietly(clientSocket);
        }
    }

    /** Reads frames until the socket ends, rewriting text messages on the way through. */
    private void pump(InputStream in, OutputStream out, boolean maskForServer) {
        ByteArrayOutputStream pending = null;
        int pendingOpcode = OP_CONTINUATION;
        try {
            while (true) {
                Frame frame = readFrame(in);
                if (frame == null) {
                    return;
                }
                if (frame.opcode >= OP_CLOSE) {
                    writeFrame(out, frame.fin, frame.opcode, frame.payload, maskForServer);
                    if (frame.opcode == OP_CLOSE) {
                        return;
                    }
                    continue;
                }
                if (frame.opcode == OP_CONTINUATION && pending != null) {
                    pending.write(frame.payload);
                    if (!frame.fin) {
                        continue;
                    }
                    byte[] message = pending.toByteArray();
                    pending = null;
                    int opcode = pendingOpcode;
                    pendingOpcode = OP_CONTINUATION;
                    writeFrame(out, true, opcode, maybeRewrite(opcode, message), maskForServer);
                    continue;
                }
                if (frame.opcode == OP_TEXT && !frame.fin) {
                    pending = new ByteArrayOutputStream();
                    pending.write(frame.payload);
                    pendingOpcode = OP_TEXT;
                    continue;
                }
                if (frame.opcode == OP_TEXT || frame.opcode == OP_BINARY) {
                    writeFrame(out, frame.fin, frame.opcode, maybeRewrite(frame.opcode, frame.payload), maskForServer);
                    continue;
                }
                writeFrame(out, frame.fin, frame.opcode, frame.payload, maskForServer);
            }
        } catch (IOException gone) {
            // Either side closing is the normal end of a socket.
        }
    }

    private byte[] maybeRewrite(int opcode, byte[] payload) {
        if (opcode != OP_TEXT || payload.length == 0) {
            return payload;
        }
        String text;
        try {
            text = new String(payload, "UTF-8");
        } catch (IOException never) {
            return payload;
        }
        String rewritten = FleetPayload.rewrite(text, origin, registry, local);
        if (rewritten == text) {
            return payload;
        }
        try {
            return rewritten.getBytes("UTF-8");
        } catch (IOException never) {
            return payload;
        }
    }

    private String handshake(HttpProxyServer.Request request, String pathAndQuery, String key) {
        StringBuilder head = new StringBuilder(256);
        head.append("GET ").append(pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery)
                .append(" HTTP/1.1\r\n");
        head.append("Host: ").append(target).append("\r\n");
        head.append("Upgrade: websocket\r\n");
        head.append("Connection: Upgrade\r\n");
        head.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        head.append("Sec-WebSocket-Version: 13\r\n");
        for (Map.Entry<String, List<String>> entry : request.headers.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.US);
            if (name.equals("host") || name.equals("upgrade") || name.equals("connection")
                    || name.equals("sec-websocket-key") || name.equals("sec-websocket-version")
                    || name.equals("sec-websocket-extensions") || name.equals("accept-encoding")
                    || name.equals("origin")) {
                continue;
            }
            for (String value : entry.getValue()) {
                head.append(entry.getKey()).append(": ").append(value).append("\r\n");
            }
        }
        head.append("\r\n");
        return head.toString();
    }

    private static List<String[]> headers(String name, String value) {
        List<String[]> headers = new ArrayList<String[]>();
        headers.add(new String[] {name, value});
        return headers;
    }

    private static InetSocketAddress address(String hostAndPort) {
        int colon = hostAndPort.lastIndexOf(':');
        if (colon <= 0) {
            return new InetSocketAddress(hostAndPort, 80);
        }
        return new InetSocketAddress(hostAndPort.substring(0, colon),
                Integer.parseInt(hostAndPort.substring(colon + 1)));
    }

    /** RFC 6455: base64(sha1(key + GUID)). */
    static String acceptKey(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + GUID).getBytes("ISO-8859-1"));
            return base64(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static final class Frame {
        final boolean fin;
        final int opcode;
        final byte[] payload;

        Frame(boolean fin, int opcode, byte[] payload) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    static Frame readFrame(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) {
            return null;
        }
        int second = readByte(in);
        boolean fin = (first & 0x80) != 0;
        int opcode = first & 0x0f;
        boolean masked = (second & 0x80) != 0;
        long length = second & 0x7f;
        if (length == 126) {
            length = (readByte(in) << 8) | readByte(in);
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | readByte(in);
            }
        }
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(in, mask);
        }
        byte[] payload = new byte[(int) length];
        readFully(in, payload);
        if (mask != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ mask[i & 3]);
            }
        }
        return new Frame(fin, opcode, payload);
    }

    static void writeFrame(OutputStream out, boolean fin, int opcode, byte[] payload, boolean masked)
            throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 14);
        frame.write((fin ? 0x80 : 0) | (opcode & 0x0f));
        int length = payload.length;
        int maskBit = masked ? 0x80 : 0;
        if (length < 126) {
            frame.write(maskBit | length);
        } else if (length <= 0xffff) {
            frame.write(maskBit | 126);
            frame.write((length >> 8) & 0xff);
            frame.write(length & 0xff);
        } else {
            frame.write(maskBit | 127);
            for (int i = 7; i >= 0; i--) {
                frame.write((int) (((long) length >> (8 * i)) & 0xff));
            }
        }
        if (masked) {
            byte[] mask = new byte[4];
            new Random().nextBytes(mask);
            frame.write(mask[0]);
            frame.write(mask[1]);
            frame.write(mask[2]);
            frame.write(mask[3]);
            for (int i = 0; i < payload.length; i++) {
                frame.write(payload[i] ^ mask[i & 3]);
            }
        } else {
            frame.write(payload, 0, payload.length);
        }
        out.write(frame.toByteArray());
        out.flush();
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new IOException("socket closed mid frame");
        }
        return value;
    }

    private static void readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                throw new IOException("socket closed mid frame");
            }
            offset += read;
        }
    }

    /** Base64, so no Android-only or API 26+ helper is needed. */
    static String base64(byte[] data) {
        final char[] alphabet =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder out = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xff;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xff : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xff : 0;
            out.append(alphabet[b0 >> 2]);
            out.append(alphabet[((b0 & 0x03) << 4) | (b1 >> 4)]);
            out.append(i + 1 < data.length ? alphabet[((b1 & 0x0f) << 2) | (b2 >> 6)] : '=');
            out.append(i + 2 < data.length ? alphabet[b2 & 0x3f] : '=');
        }
        return out.toString();
    }
}
