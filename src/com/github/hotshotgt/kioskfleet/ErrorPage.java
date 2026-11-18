// SPDX-License-Identifier: Apache-2.0
package com.github.hotshotgt.kioskfleet;

/**
 * The page a dead or unknown kiosk answers with.
 *
 * <p>Colours, type and the logo are the ones the app's own login card uses, taken
 * from the build that was live when this was written ({@code app.css?v=790526ee564f}),
 * so a failure looks like part of Kiosk Satellite rather than a bare gateway line.
 * The theme follows the app's own {@code ks_theme} preference.
 *
 * <p>Google Sans is embedded as a font in the app's stylesheet and is not carried
 * here, so the type falls back to the system face.
 */
final class ErrorPage {

    private static final String LOGO =
            "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCA5MjQuNDQgOTU5LjU1Ij48cGF0aCBmaWxsPSIjZmZmIiBzdHJva2U9IiM1ZGEzYTYiIHN0cm9rZS1saW5lam9pbj0icm91bmQiIHN0cm9rZS13aWR0aD0iNTIuMTUiIGQ9Ik0zOTAuMTYsNTAuNzVjNDIuNDEtMzIuODksMTAxLjcxLTMyLjg5LDE0NC4xMiwwbDMxMi44OSwyMjQuNzFjMzEuOTQsMjEuODUsNTEuMDksNTguMDEsNTEuMiw5Ni43MXY0NDMuNzNjMCw2NC45My01Mi42NCwxMTcuNTctMTE3LjU3LDExNy41N0gxNDMuNjRjLTY0LjkzLDAtMTE3LjU3LTUyLjY0LTExNy41Ny0xMTcuNTd2LTQ0My43M2MuMTEtMzguNywxOS4yNi03NC44Nyw1MS4yLTk2LjcxTDM5MC4xNiw1MC43NVoiLz48cGF0aCBmaWxsPSIjMThiY2YyIiBkPSJNMjQyLjI1LDUxMS41NWMwLTIyLjUyLDE4LjI1LTQwLjc3LDQwLjc3LTQwLjc3czQwLjc3LDE4LjI1LDQwLjc3LDQwLjc3djE3MC42N2MwLDIyLjUyLTE4LjI1LDQwLjc3LTQwLjc3LDQwLjc3cy00MC43Ny0xOC4yNS00MC43Ny00MC43N3YtMTcwLjY3aDBaIi8+PHBhdGggZmlsbD0iI2YyYjcwNSIgZD0iTTM2MS43Miw1MzMuMzVjLjAyLTIyLjUyLDE4LjI4LTQwLjc2LDQwLjgtNDAuNzQsMjIuNS4wMiw0MC43MywxOC4yNSw0MC43NCw0MC43NHYxMjcuMDVjLS4wMiwyMi41Mi0xOC4yOCw0MC43Ni00MC44LDQwLjc0LTIyLjUtLjAxLTQwLjczLTE4LjI1LTQwLjc0LTQwLjc0di0xMjcuMDVaIi8+PHBhdGggZmlsbD0iIzNmYmY1ZiIgZD0iTTQ4MS4xOSw0MzUuNjljLjAyLTIyLjUyLDE4LjI4LTQwLjc2LDQwLjgtNDAuNzQsMjIuNS4wMiw0MC43MywxOC4yNSw0MC43NCw0MC43NHYzMjIuMzdjLS4wMiwyMi41Mi0xOC4yOCw0MC43Ni00MC44LDQwLjc0LTIyLjUtLjAxLTQwLjczLTE4LjI1LTQwLjc0LTQwLjc0di0zMjIuMzdaIi8+PHBhdGggZmlsbD0iI2U4NjA0YyIgZD0iTTYwMC42NSw1MzAuNTFjLjAyLTIyLjUyLDE4LjI4LTQwLjc2LDQwLjgtNDAuNzQsMjIuNS4wMiw0MC43MywxOC4yNSw0MC43NCw0MC43NHYxMzIuNzRjLS4wMiwyMi41Mi0xOC4yOCw0MC43Ni00MC44LDQwLjc0LTIyLjUtLjAxLTQwLjczLTE4LjI1LTQwLjc0LTQwLjc0di0xMzIuNzRaIi8+PC9zdmc+";

    private static final String HEAD =
            "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
            + "<meta name=\"color-scheme\" content=\"light dark\">"
            + "<title>Kiosk Satellite</title>"
            + "<link rel=\"icon\" type=\"image/svg+xml\" href=\"" + LOGO + "\">"
            + "<script>try{var p=localStorage.getItem('ks_theme');"
            + "document.documentElement.dataset.theme="
            + "p==='dark'||(p==='auto'&&matchMedia('(prefers-color-scheme: dark)').matches)?'dark':'light'}"
            + "catch(e){}</script>"
            + "<style>"
            + ":root{color-scheme:light;--bg:#f5f4f2;--surface:#fff;--text:#444;--muted:#5d6066;"
            + "--primary:#488284;--primary-fill:#488284;--primary-ink:#fff;--radius:24px}"
            + ":root[data-theme=dark]{color-scheme:dark;--bg:#202124;--surface:#2a2b2f;--text:#e8eaed;"
            + "--muted:#b6babf}"
            + "*{box-sizing:border-box}"
            + "html,body{margin:0;height:100%}"
            + "body{background:var(--bg);color:var(--text);display:grid;place-items:center;padding:24px;"
            + "font:15px/1.5 Google Sans,system-ui,-apple-system,Segoe UI,Roboto,sans-serif;"
            + "-webkit-font-smoothing:antialiased}"
            + "main{width:100%;max-width:360px;background:var(--surface);border-radius:var(--radius);"
            + "padding:32px;text-align:center}"
            + "img{display:block;margin:0 auto}"
            + "h1{font-size:20px;font-weight:700;margin:16px 0 4px}"
            + "p{margin:0 0 24px;font-size:14px;color:var(--muted)}"
            + "p.code{margin:-14px 0 24px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;"
            + "font-size:12.5px;line-height:1.45;overflow-wrap:anywhere}"
            + "a.btn{display:inline-flex;align-items:center;justify-content:center;width:100%;height:44px;"
            + "border-radius:999px;background:var(--primary-fill);color:var(--primary-ink);"
            + "font-size:14.5px;font-weight:600;text-decoration:none}"
            + "a.btn:hover{filter:brightness(1.06)}"
            + "a.link{display:inline-block;margin-top:14px;font-size:13.5px;color:var(--primary);"
            + "text-decoration:none}"
            + "</style></head><body><main>";

    private ErrorPage() {
    }

    /**
     * @param headline what went wrong, in a few words
     * @param detail   the identifier or address a reader can act on, may be empty
     */
    static String html(String headline, String detail) {
        StringBuilder page = new StringBuilder(HEAD.length() + 512);
        page.append(HEAD)
                .append("<img alt=\"\" width=\"72\" height=\"75\" src=\"").append(LOGO).append("\">")
                .append("<h1>").append(escape(headline)).append("</h1>")
                .append("<p>This kiosk is not answering through the fleet proxy.</p>");
        if (detail != null && !detail.isEmpty()) {
            page.append("<p class=\"code\">").append(escape(detail)).append("</p>");
        }
        page.append("<a class=\"btn\" href=\"\">Try again</a>")
                .append("<a class=\"link\" href=\"/\">Open the fleet</a>")
                .append("</main></body></html>");
        return page.toString();
    }

    private static String escape(String text) {
        StringBuilder safe = new StringBuilder(text.length() + 16);
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '&':
                    safe.append("&amp;");
                    break;
                case '<':
                    safe.append("&lt;");
                    break;
                case '>':
                    safe.append("&gt;");
                    break;
                case '"':
                    safe.append("&quot;");
                    break;
                default:
                    safe.append(character);
            }
        }
        return safe.toString();
    }
}
