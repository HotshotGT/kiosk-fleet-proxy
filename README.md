# Kiosk Fleet Proxy

Serve a whole Kiosk Satellite fleet from one origin, so the app's built-in device
switcher works inside a dashboard iframe.

## Installation

### Community Plugins (Recommended)

Kiosk Fleet Proxy is pending review for [Community Plugins](https://kiosksatellite.com/plugins/). The button below opens it in your Kiosk Satellite whether or not the review has finished, and it installs the same release either way, checksum and all.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=HotshotGT/kiosk-fleet-proxy)

It does not appear in the directory's search until that review passes. **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite is where it will show up under `Kiosk Fleet Proxy`.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/HotshotGT/kiosk-fleet-proxy` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

A fleet leader pushes an installed plugin to its followers on its own, so one
install per fleet is enough.

### Scripted

`tools/install.py` drives the admin API, the same path the UI takes, with only the
admin password:

```sh
export KS_ADMIN_PASSWORD=...
./tools/install.py --host http://<kiosk>:2324 --url https://github.com/HotshotGT/kiosk-fleet-proxy --install
```

Without `--install` it stops after the preview, which is the step that shows the
manifest and the README. Add `--no-enable` to install it switched off. A ZIP works
too, including from a Forgejo pipeline, because the app's `installPlugin` command
takes the package itself instead of a repository URL:

```sh
./tools/install.py --host http://<kiosk>:2324 --zip dist/ks-kiosk-fleet-proxy-<version>.zip
```

## Why

The app serves its remote admin on `http://<device>:2324` with
`X-Frame-Options: SAMEORIGIN`. When the fleet switcher navigates to another kiosk,
a dashboard iframe is refused by the browser: the new page is a different origin
and names no allowed ancestor. No wrapper page can fix that, because the header is
enforced before any of your content runs.

This plugin puts one listener in front of the fleet. Every kiosk is reachable at
`/<kiosk id>/` on a single origin, and every response comes back with
`frame-ancestors` instead of `X-Frame-Options`.

## How it works

- `/<kiosk id>/...` proxies to that kiosk's admin server. `/` redirects to this
  device's own id.
- The roster the app sends is rewritten on the way through. Each entry's
  `http://<address>:<port>` link becomes `<origin>/<id>`, where the origin comes
  from the request's `Host` header. This happens in HTTP responses and in
  WebSocket text frames, because the roster arrives twice and the socket copy is
  the one the app uses.
- The same pass learns the address of every kiosk it sees. On start the plugin
  also reads the app's own fleet list from its preferences, so a restart does not
  go blind.
- `X-Frame-Options` is dropped and `Content-Security-Policy: frame-ancestors` is
  added. This device's own id is decided by its own addresses, never by the `self`
  flag in a payload, since every kiosk marks itself self in its own answer.
- WebSocket upgrades get their own handshake, and `permessage-deflate` is left out
  of the upgrade so text frames stay readable and rewritable.
- Request bodies are forwarded, which the admin API needs for every command and
  for login.
- Connections stay open in both directions. A dashboard is around ninety
  requests, and without keep-alive that is ninety handshakes to the reverse proxy
  and ninety more to the kiosk.
- The app's static files are held under their `?v=<hash>`, up to 4 MB in memory
  and mirrored into the app's cache directory so a restart or an update keeps it.
  Opening a kiosk you have never visited serves its assets from this device
  instead of pulling a megabyte and a half over the network. One build of the
  fleet's files measures about 1.3 MB compressed, so the ceiling is headroom, not
  a working set.
- Each page is rewritten on the way out so its files load from `/static/...`
  rather than `/<kiosk>/static/...`. The browser then keeps one copy for the whole
  fleet instead of one per kiosk, which is what makes a second switch instant.
  Two builds never share, because the build hash stays in the URL. To answer
  those paths the proxy has to know which kiosk serves which build, so it reads
  each kiosk's page once at start, in the background.
- That same pass adds `<link rel="prefetch">` for up to four other kiosks, and
  gives the page a five second lifetime instead of the app's `no-store`, so the
  prefetched copy is still there when the click comes.

## Data

The plugin opens no connection outside your own network. It talks to the other
kiosks' admin servers on the addresses the app already knows, and to this device's
own app. There is no telemetry, no update check of its own and no third party.

It writes one thing to disk: a copy of the fleet's static files in the app's cache
directory, so a restart does not fetch them again. Learned addresses stay in memory.

The app's own Plugin Manager checks GitHub for plugin updates, as it does for every
installed plugin. The plugin does not.

## Settings

| Key | Default | Meaning |
| --- | --- | --- |
| `frameAncestors` | empty | Space separated origins allowed to frame the page, for example `https://dashboard.example`. Empty means only this origin may frame it. |

The port is fixed at 8790. Changing the setting restarts the listener.

## Deploying

Point a reverse proxy at port 8790 on the leader and keep one hostname for the
fleet:

```
kiosk.example  ->  http://<leader address>:8790
```

The plugin needs no root, no adb and no write to `/system`, and it survives app
updates. It does need the port free: stop anything else that listens on it.

## Build and test

A JDK and an Android SDK with build-tools are enough, and no device is needed.

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 36.1 --version <version>
```

`tools/test.py` covers the roster rewrite, the route pattern, RFC 6455 vectors, the
frame codec, and a proxy round trip against a fake upstream, including a POST body.
`tools/build.py` writes the ZIP, its SHA-256 and the release manifest to `dist/`.

Releases are built by `.github/workflows/release.yml` when a release is published.
The app accepts only assets built by GitHub Actions, and only from a stable
release, so leave `prerelease` off.

## Limits worth knowing

- Every kiosk shares one origin, so a cookie set by one kiosk is visible to the
  others. The admin UI keeps its token in `localStorage` per path, so switching
  kiosks asks for that kiosk's password once.
- Learned addresses live in memory. A restart re-seeds from the app's fleet list.
- A failure answers with a page that matches the app's own login card: the kiosk's
  logo, its palette, and the kiosk id plus address that failed. It is never cached.
- The `Host` header decides the origin written into rewritten links. Behind a TLS
  reverse proxy, forward the original `Host` and `X-Forwarded-Proto: https` so
  links come back as `https`.
- A page is cached for five seconds, against the app's `no-store`. That is what
  makes a prefetch worth anything, and the small staleness it allows cannot point
  at missing files, since the page names them by build hash.
- Camera streams and other endless responses are passed through unbuffered, and
  they close the connection when they end, since the end of the body is the end
  of the socket.
- The asset cache trusts the `?v=` hash. Two kiosks running different builds ask
  for different URLs and are cached separately, which is the intent. On this app
  the hash is shared by every kiosk on the same build, and the bytes match: the
  same three files served by two kiosks hash identically.
- Cached bytes are stored as upstream sent them, so gzip and plain variants are
  separate entries. A client that does not accept gzip is never handed gzipped
  bytes out of the cache.
- Discovery reads two keys from the app's own preferences while running inside it,
  `flutter.ks.fleet.id` and `flutter.ks.fleet.followers`. It only reads them and
  never writes. They are the app's current names; if a future version renames them
  the registry starts empty and fills from the roster that passes through, which is
  also why a fleet with an empty settings list answers 502 for a while.
- There is no mDNS discovery. The fleet list from the app's settings seeds the
  registry, and the roster seen through the proxy keeps it current; a fleet whose
  settings list is empty starts answering 502 for ids until a page passes through.

## Layout

```
kiosk-satellite-plugin.json   manifest: id, version, entry class, settings
src/                          the plugin
tests/                        compile-and-run tests, no device needed
sdk/                          SDK 1, from jxlarrea/kiosk-satellite-plugins
tools/                        build, test and install
```

## License

Copyright 2026 Hotshot. Apache-2.0, see LICENSE.

Apache-2.0. The SDK in `sdk/` comes from the Kiosk Satellite plugins repository
under the same license, with its plugin exception.
