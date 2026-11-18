#!/usr/bin/env python3
"""Compile the plugin against the SDK and run its tests without a device."""
import os
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from android_sdk import android_platform

ROOT = Path(__file__).resolve().parents[1]
JAVA_HOME = os.environ.get('JAVA_HOME')


def tool(name):
    return str(Path(JAVA_HOME) / 'bin' / name) if JAVA_HOME else name


def main():
    sdk_root = Path(os.environ.get(
        'ANDROID_HOME',
        os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'android-sdk')),
    ))
    try:
        platform = str(android_platform(sdk_root))
    except ValueError as error:
        raise SystemExit(str(error)) from error

    sources = [
        *sorted((ROOT / 'sdk/src').rglob('*.java')),
        *sorted((ROOT / 'src').rglob('*.java')),
        *sorted((ROOT / 'tests').rglob('*.java')),
    ]
    with tempfile.TemporaryDirectory(prefix='kiosk-plugin-test-') as directory:
        subprocess.run(
            [tool('javac'), '--release', '8', '-cp', platform, '-d', directory, *map(str, sources)],
            check=True,
        )
        subprocess.run(
            [tool('java'), '-ea', '-cp', os.pathsep.join([directory, platform]),
             'com.github.hotshotgt.kioskfleet.FleetProxyPluginTest'],
            check=True,
        )


if __name__ == '__main__':
    main()
