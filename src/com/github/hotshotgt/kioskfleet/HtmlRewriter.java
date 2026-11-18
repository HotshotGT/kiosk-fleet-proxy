// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites a kiosk's admin page so its files live at one origin level path.
 *
 * <p>The page asks for {@code static/main.js?v=<build>}, which the browser
 * resolves under the kiosk's own prefix. That makes every kiosk a separate cache
 * entry for the same bytes, so switching re-downloads a megabyte. Absolute paths
 * put every kiosk on one entry, and the {@code v=} hash still keeps two builds
 * apart.
 *
 * <p>The same pass learns which build a kiosk serves and asks the browser to
 * fetch the other kiosks' pages early.
 */
final class HtmlRewriter {

    private static final Pattern BUILD = Pattern.compile("\\?v=([0-9a-fA-F]{6,64})");
    private static final int PREFETCH_LIMIT = 4;

    private HtmlRewriter() {
    }

    static String rewrite(String html, String id, Registry registry) {
        Matcher matcher = BUILD.matcher(html);
        if (matcher.find()) {
            registry.build(id, matcher.group(1));
        }
        String rewritten = html;
        for (String quote : new String[] {"\"", "'"}) {
            rewritten = rewritten.replace(quote + "./static/", quote + "/static/");
            rewritten = rewritten.replace(quote + "static/", quote + "/static/");
        }
        return injectPrefetch(rewritten, id, registry);
    }

    private static String injectPrefetch(String html, String id, Registry registry) {
        StringBuilder links = new StringBuilder(160);
        int added = 0;
        for (String other : registry.ids()) {
            if (added == PREFETCH_LIMIT) {
                break;
            }
            if (other.equals(id)) {
                continue;
            }
            links.append("<link rel=\"prefetch\" href=\"/").append(other).append("/\">");
            added++;
        }
        if (added == 0) {
            return html;
        }
        int head = indexOfHead(html);
        if (head < 0) {
            return html;
        }
        return html.substring(0, head) + links + html.substring(head);
    }

    private static int indexOfHead(String html) {
        int at = html.toLowerCase(java.util.Locale.US).indexOf("<head>");
        return at < 0 ? -1 : at + "<head>".length();
    }
}
