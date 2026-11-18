// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Relays one WebSocket to a kiosk, rewriting the text messages that carry the
 * fleet roster.
 *
 * <p>The roster arrives twice, in an HTTP response and again over this socket,
 * and the socket copy is the one the app uses. Without the relay a switched
 * kiosk sends the browser back to a raw address.
 *
 * <p>Compression is refused on the way up: a permessage-deflate payload is
 * binary and could not be rewritten.
 */
final class WebSocketRelay {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int OP_TEXT = 0x1;
    private static final int OP_CLOSE = 0x8;

    private final String target;
    private final String origin;
    private final Registry registry;
    private final LocalAddresses local;
    private final HttpProxyServer.Logger log;

    WebSocketRelay(String target, String origin, Registry registry, LocalAddresses local, HttpProxyServer.Logger log) {
        this.target = target;
        this.origin = origin;
        this.registry = registry;
        this.local = local;
        this.log = log;
    }

    void relay(Socket clientSocket, HttpProxyServer.Request request, InputStream clientIn, OutputStream clientOut,
               String pathAndQuery) throws IOException {
        String key = request.headers.first("Sec-WebSocket-Key");
        if (key == null || key.isEmpty()) {
            return;
        }
        Socket upstream = new Socket();
        try {
            upstream.connect(address(target), 10000);
            upstream.setSoTimeout(0);
        } catch (IOException unreachable) {
            log.log("websocket to " + target + ": " + unreachable.getMessage());
            refuse(clientOut);
            return;
        }
        InputStream upIn = upstream.getInputStream();
        OutputStream upOut = upstream.getOutputStream();
        try {
            upOut.write(handshake(request, pathAndQuery, key).getBytes("ISO-8859-1"));
            upOut.flush();
            HttpProxyServer.Response response = HttpProxyServer.readResponse(upIn);
            if (response == null || response.status != 101) {
                log.log("websocket upgrade refused: " + (response == null ? "no response" : response.status));
                refuse(clientOut);
                return;
            }
            clientOut.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + acceptKey(key) + "\r\n\r\n")
                    .getBytes("ISO-8859-1"));
            clientOut.flush();
            Thread down = new Thread(new Runnable() {
                @Override
                public void run() {
                    pump(upIn, clientOut, false);
                }
            }, "kiosk-fleet-proxy-ws-down");
            down.setDaemon(true);
            down.start();
            pump(clientIn, upOut, true);
            down.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            HttpProxyServer.closeQuietly(upstream);
            HttpProxyServer.closeQuietly(clientSocket);
        }
    }

    private static void refuse(OutputStream out) throws IOException {
        List<String[]> headers = new ArrayList<String[]>();
        headers.add(new String[] {"Content-Length", "0"});
        HttpProxyServer.writeHead(out, 502, "Bad Gateway", headers);
    }

    /** Reads frames until the socket ends, rewriting text messages on the way. */
    private void pump(InputStream in, OutputStream out, boolean masked) {
        ByteArrayOutputStream pending = null;
        try {
            while (true) {
                Frame frame = readFrame(in);
                if (frame == null) {
                    return;
                }
                if (frame.opcode >= OP_CLOSE) {
                    writeFrame(out, frame.fin, frame.opcode, frame.payload, masked);
                    if (frame.opcode == OP_CLOSE) {
                        return;
                    }
                    continue;
                }
                if (frame.opcode == 0x0 && pending != null) {
                    pending.write(frame.payload);
                    if (frame.fin) {
                        writeFrame(out, true, OP_TEXT, rewrite(pending.toByteArray()), masked);
                        pending = null;
                    }
                    continue;
                }
                if (frame.opcode == OP_TEXT && !frame.fin) {
                    pending = new ByteArrayOutputStream();
                    pending.write(frame.payload);
                    continue;
                }
                writeFrame(out, frame.fin, frame.opcode,
                        frame.opcode == OP_TEXT ? rewrite(frame.payload) : frame.payload, masked);
            }
        } catch (IOException gone) {
            // Either side closing is the normal end of a socket.
        }
    }

    private byte[] rewrite(byte[] payload) {
        if (payload.length == 0) {
            return payload;
        }
        try {
            String text = new String(payload, "UTF-8");
            String rewritten = FleetPayload.rewrite(text, origin, registry, local);
            return rewritten == text ? payload : rewritten.getBytes("UTF-8");
        } catch (IOException never) {
            return payload;
        }
    }

    private String handshake(HttpProxyServer.Request request, String pathAndQuery, String key) {
        StringBuilder head = new StringBuilder(256);
        head.append("GET ").append(pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery)
                .append(" HTTP/1.1\r\nHost: ").append(target).append("\r\n")
                .append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: ").append(key).append("\r\nSec-WebSocket-Version: 13\r\n");
        for (String[] header : request.headers.all()) {
            String name = header[0].toLowerCase(Locale.US);
            if (name.equals("host") || name.equals("connection") || name.equals("upgrade")
                    || name.equals("accept-encoding") || name.equals("origin") || name.startsWith("sec-websocket-")) {
                continue;
            }
            head.append(header[0]).append(": ").append(header[1]).append("\r\n");
        }
        return head.append("\r\n").toString();
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
            return base64(MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes("ISO-8859-1")));
        } catch (Exception impossible) {
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
            frame.write(mask, 0, 4);
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

    /** Base64 by hand: java.util.Base64 needs API 26 and android.util is a stub off-device. */
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
