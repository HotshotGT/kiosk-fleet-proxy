// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Keeps the asset cache across restarts.
 *
 * <p>The cache lives in the app's own cache directory, reached from inside the
 * app's process, so an app update or a restart does not throw away a warm copy of
 * the fleet's static files. Android may clear that directory under storage
 * pressure, which is exactly the right lifetime for a cache.
 *
 * <p>One file per entry, named by the SHA-1 of the cache key, holding a JSON
 * header line and then the body.
 */
final class DiskCache {

    private static final String SUFFIX = ".bin";

    private final File directory;

    private DiskCache(File directory) {
        this.directory = directory;
    }

    /** Null when the directory cannot be reached or created. */
    static DiskCache open() {
        File directory = null;
        try {
            Object application = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            File base = (File) application.getClass().getMethod("getCacheDir").invoke(application);
            directory = new File(base, "kiosk-fleet-proxy");
        } catch (Throwable unavailable) {
            return null;
        }
        if (!directory.isDirectory() && !directory.mkdirs()) {
            return null;
        }
        return new DiskCache(directory);
    }

    /** Entries in newest first order, which is the order they are worth keeping. */
    List<Map.Entry<String, HttpProxyServer.Cached>> load() {
        List<Map.Entry<String, HttpProxyServer.Cached>> loaded =
                new ArrayList<Map.Entry<String, HttpProxyServer.Cached>>();
        File[] files = directory.listFiles();
        if (files == null) {
            return loaded;
        }
        final File[] ordered = files.clone();
        Arrays.sort(ordered, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return Long.compare(right.lastModified(), left.lastModified());
            }
        });
        for (File file : ordered) {
            if (!file.getName().endsWith(SUFFIX)) {
                continue;
            }
            Map.Entry<String, HttpProxyServer.Cached> entry = read(file);
            if (entry != null) {
                loaded.add(entry);
            }
        }
        return loaded;
    }

    private Map.Entry<String, HttpProxyServer.Cached> read(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            String header = HttpProxyServer.readLine(in);
            if (header == null) {
                return null;
            }
            Object parsed = Json.parseOrNull(header);
            if (!(parsed instanceof Map)) {
                return null;
            }
            Map<?, ?> meta = (Map<?, ?>) parsed;
            Object key = meta.get("k");
            Object status = meta.get("s");
            if (!(key instanceof String) || !(status instanceof Number)) {
                return null;
            }
            List<String[]> headers = new ArrayList<String[]>();
            Object rawHeaders = meta.get("h");
            if (rawHeaders instanceof List) {
                for (Object item : (List<?>) rawHeaders) {
                    if (item instanceof List && ((List<?>) item).size() == 2) {
                        headers.add(new String[] {
                                String.valueOf(((List<?>) item).get(0)),
                                String.valueOf(((List<?>) item).get(1))});
                    }
                }
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream((int) file.length());
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) {
                body.write(buffer, 0, read);
            }
            Object reason = meta.get("r");
            HttpProxyServer.Cached cached = new HttpProxyServer.Cached(
                    (int) ((Number) status).doubleValue(),
                    reason == null ? "" : String.valueOf(reason),
                    headers,
                    body.toByteArray());
            return new java.util.AbstractMap.SimpleEntry<String, HttpProxyServer.Cached>((String) key, cached);
        } catch (Exception unreadable) {
            file.delete();
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    void write(String key, int status, String reason, List<String[]> headers, byte[] body) {
        File target = new File(directory, sha1(key) + SUFFIX);
        File temporary = new File(directory, target.getName() + ".tmp");
        FileOutputStream out = null;
        try {
            List<Object> headerPairs = new ArrayList<Object>();
            for (String[] header : headers) {
                List<Object> pair = new ArrayList<Object>(2);
                pair.add(header[0]);
                pair.add(header[1]);
                headerPairs.add(pair);
            }
            Map<String, Object> meta = new java.util.LinkedHashMap<String, Object>();
            meta.put("k", key);
            meta.put("s", Integer.valueOf(status));
            meta.put("r", reason);
            meta.put("h", headerPairs);
            out = new FileOutputStream(temporary);
            out.write((Json.stringify(meta) + "\n").getBytes("UTF-8"));
            out.write(body);
            out.flush();
            out.close();
            out = null;
            if (!temporary.renameTo(target)) {
                target.delete();
                temporary.renameTo(target);
            }
        } catch (Exception unwritable) {
            temporary.delete();
        } finally {
            closeQuietly(out);
        }
    }

    void remove(String key) {
        new File(directory, sha1(key) + SUFFIX).delete();
    }

    private static String sha1(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(key.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xf, 16));
                hex.append(Character.forDigit(value & 0xf, 16));
            }
            return hex.toString();
        } catch (Exception impossible) {
            return Integer.toHexString(key.hashCode());
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }
}
