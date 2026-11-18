# Kiosk Fleet Proxy

Serve a whole Kiosk Satellite fleet from one origin, so the app's built-in device
switcher works inside a dashboard iframe.

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

## Install

### Plugin Manager

**Settings > Plugin Manager > Add plugin**, paste
`https://github.com/HotshotGT/kiosk-fleet-proxy`, **Preview**, then **Trust and
install**.

### From a build you already have

`tools/install.py` drives the admin API, the same path the UI takes. It needs only
the admin password.

```sh
export KS_ADMIN_PASSWORD=...
./tools/install.py --host http://<kiosk>:2324 --zip dist/ks-kiosk-fleet-proxy-0.3.0.zip
```

A ZIP works from anywhere, including a Forgejo pipeline, because the app's
`installPlugin` command takes the package itself rather than a repository URL.

### From a GitHub release

```sh
./tools/install.py --host http://<kiosk>:2324 \
  --url https://github.com/HotshotGT/kiosk-fleet-proxy --install
```

That runs `previewPluginRepository` and then `installPluginRepository`, the two
commands behind the UI's **Trust and install**. Without `--install` it stops after
the preview. Add `--no-enable` to install it switched off.

A fleet leader pushes an installed plugin to its followers on its own, so one
install per fleet is enough.

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
python3 tools/build.py --android-platform 36.1 --version 0.3.0
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
- The `Host` header decides the origin written into rewritten links. Behind a TLS
  reverse proxy, forward the original `Host` and `X-Forwarded-Proto: https` so
  links come back as `https`.
- Camera streams and other endless responses are passed through unbuffered; only
  `application/json` bodies are buffered for rewriting.
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

Apache-2.0. The SDK in `sdk/` comes from the Kiosk Satellite plugins repository
under the same license, with its plugin exception.
