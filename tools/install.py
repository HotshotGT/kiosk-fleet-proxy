#!/usr/bin/env python3
"""Install or update Kiosk Fleet Proxy on a kiosk over its admin API.

Two sources, because the app supports both:

  --zip path/to/kiosk-fleet-proxy.zip   install a ZIP you built or downloaded,
                                        which is how a Forgejo pipeline ships it
  --url https://github.com/owner/repo   install the latest stable GitHub release,
                                        the same path Plugin Manager uses

The admin API takes the same two commands the UI does, so this needs nothing more
than the admin password: no adb, no root, no /system write.

    export KS_ADMIN_PASSWORD=...
    ./tools/install.py --host http://192.0.2.165:2324 --zip dist/kiosk-fleet-proxy.zip
    ./tools/install.py --host http://192.0.2.165:2324 --url https://github.com/HotshotGT/kiosk-fleet-proxy --install

Without --install, the URL mode stops after the preview and prints what it reviewed.
"""
import argparse
import base64
import json
import os
import sys
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

MAX_ZIP_BYTES = 4 * 1024 * 1024
TIMEOUT = 90


class ApiError(Exception):
    pass


def request(host, path, payload=None, token=None):
    url = host.rstrip('/') + path
    body = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(url, data=body, method='POST' if body is not None else 'GET')
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as response:
            raw = response.read()
    except urllib.error.HTTPError as error:
        detail = error.read().decode('utf-8', 'replace').strip()
        raise ApiError(f'{path}: HTTP {error.code} {detail}') from error
    except urllib.error.URLError as error:
        raise ApiError(f'{path}: {error.reason}') from error
    if not raw:
        return {}
    return json.loads(raw.decode('utf-8', 'replace'))


def command(host, token, name, payload):
    """Run a command and unwrap the {"ok": ..., "data": ...} envelope."""
    result = request(host, f'/api/commands/{name}', payload, token)
    if not result.get('ok'):
        raise ApiError(f'{name}: {result.get("error") or "refused"}')
    return result.get('data') or {}


def login(host, password):
    result = request(host, '/api/login', {'password': password})
    token = result.get('token')
    if not token:
        raise ApiError('login: no token in the answer')
    return token


def manifest_of(archive):
    with zipfile.ZipFile(archive) as package:
        with package.open('kiosk-satellite-plugin.json') as entry:
            return json.loads(entry.read().decode('utf-8'))


def install_zip(host, token, archive, enable):
    size = archive.stat().st_size
    if size > MAX_ZIP_BYTES:
        raise ApiError(f'{archive} is {size} bytes, the app accepts {MAX_ZIP_BYTES}')
    manifest = manifest_of(archive)
    data = base64.b64encode(archive.read_bytes()).decode('ascii')
    command(host, token, 'installPlugin', {'data': data, 'trusted': True})
    print(f'installed {manifest["id"]} {manifest["version"]} from {archive.name}')
    if enable:
        command(host, token, 'enablePlugin', {'id': manifest['id']})
        print(f'enabled {manifest["id"]}')
    return manifest['id']


def install_repository(host, token, url, install, enable):
    preview = command(host, token, 'previewPluginRepository', {'url': url})
    preview_id = preview.get('previewId') or preview.get('id')
    manifest = preview.get('manifest') or {}
    print(f'reviewed {manifest.get("id", "?")} {manifest.get("version", "?")} '
          f'from {url}')
    if not preview_id:
        raise ApiError('preview: no previewId in the answer')
    if not install:
        print('preview only; pass --install to go ahead')
        return manifest.get('id')
    command(host, token, 'installPluginRepository', {'previewId': preview_id, 'trusted': True})
    print('installed')
    if enable:
        command(host, token, 'enablePlugin', {'id': manifest.get('id', '')})
        print('enabled')
    return manifest.get('id')


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--host', required=True, help='admin base URL, e.g. http://192.0.2.165:2324')
    parser.add_argument('--password', default=os.environ.get('KS_ADMIN_PASSWORD', ''),
                        help='admin password, or set KS_ADMIN_PASSWORD')
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--zip', type=Path, help='plugin ZIP to install')
    source.add_argument('--url', help='public GitHub repository URL to install from')
    parser.add_argument('--install', action='store_true',
                        help='with --url, install after the preview')
    parser.add_argument('--no-enable', action='store_true',
                        help='leave the plugin installed but disabled')
    args = parser.parse_args()

    if not args.password:
        parser.error('no password: pass --password or set KS_ADMIN_PASSWORD')
    if args.zip and not args.zip.is_file():
        parser.error(f'{args.zip} is not a file')

    try:
        token = login(args.host, args.password)
        enable = not args.no_enable
        if args.zip:
            install_zip(args.host, token, args.zip, enable)
        else:
            install_repository(args.host, token, args.url, args.install, enable)
    except ApiError as error:
        print(f'failed: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
