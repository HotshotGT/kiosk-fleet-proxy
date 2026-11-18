# Kiosk Fleet Proxy (plugin)

Serve a whole Kiosk Satellite fleet from one origin, so the app's built-in device
switcher works inside a Home Assistant panel.

**Status: pre-alpha.** The plugin loads, registers and reports its version. The
proxy listener is not ported from the Go build yet, so this release serves no
traffic. Do not install it expecting the fleet switcher to work.

## Why

The app serves its remote admin at `http://<device>:2324` with
`X-Frame-Options: SAMEORIGIN`. When the fleet switcher navigates to another kiosk,
Home Assistant's iframe is refused by the browser, because the new page is a
different origin and names no allowed ancestor. No wrapper page can fix that: the
header is enforced by the browser before any of your content runs.

The Go build of this project (`kiosk-fleet-proxy`) solved it with a reverse proxy
on the fleet leader: every kiosk at `/<id>/` on one origin, with
`frame-ancestors` added on the way out. This repository is that proxy as an SDK 1
plugin, so it needs no adb, no root and no write to `/system`, and a fleet leader
can push it to its followers.

## Install

### Plugin Manager

Once a release exists, **Settings > Plugin Manager > Add plugin**, paste
`https://github.com/HotshotGT/kiosk-fleet-proxy`, **Preview**, then **Trust and
install**.

### From a build you already have

`tools/install.py` uses the admin API, which is the same path the UI takes. It
needs only the admin password.

```sh
export KS_ADMIN_PASSWORD=...                     # or pass --password
./tools/install.py --host http://192.0.2.165:2324 --zip dist/kiosk-fleet-proxy.zip
```

This works with a ZIP built anywhere, including a Forgejo pipeline, because the
app's `installPlugin` command takes the package itself rather than a repository
URL. Add `--no-enable` to install it switched off.

### From a GitHub release

```sh
./tools/install.py --host http://192.0.2.165:2324 \
  --url https://github.com/HotshotGT/kiosk-fleet-proxy --install
```

That runs `previewPluginRepository` and then `installPluginRepository`, the two
commands behind the UI's **Trust and install**. Without `--install` it stops after
the preview and prints what it reviewed.

## Automated updates

A pipeline can build the ZIP and push it to the leader with `installPlugin`,
which needs no GitHub at all. Only the store path, the preview and the update
check, requires a GitHub repository whose release assets were built by GitHub
Actions; hand-attached assets are rejected. Updates installed from a ZIP are not
checked automatically, so a pipeline has to push them.

## Build

The build needs a JDK and an Android SDK with build-tools 35, which is what the
release workflow uses.

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35 --version 0.1.0
```

`dist/` then holds the ZIP, its SHA-256 and the release manifest.

Releases are built by `.github/workflows/release.yml` when a release is published,
and the assets it attaches are the ones the app accepts.

## Layout

```
kiosk-satellite-plugin.json   manifest: id, version, entry class, capabilities
src/                          the plugin
tests/                        compile-and-run tests, no device needed
sdk/                          SDK 1, from jxlarrea/kiosk-satellite-plugins
tools/                        build, test and install
```

## License

Apache-2.0. The SDK in `sdk/` comes from the Kiosk Satellite plugins repository
under the same license, with its plugin exception.
