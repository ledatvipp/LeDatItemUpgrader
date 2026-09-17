#!/usr/bin/env python3
"""Offline native build prerequisites only. Never downloads dependencies or creates an API stub.
Passing this check is NOT an API ABI check, a Paper compile, or a server runtime test.
"""
from __future__ import annotations
import argparse
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = ('vn/ledat/platform/api/LeDatPlatformApi.class',
            'vn/ledat/platform/api/LeDatPlatformProvider.class')


def run_version(executable: str, argument: str) -> str:
    result = subprocess.run([executable, argument], capture_output=True, text=True,
                            timeout=30, check=False)
    if result.returncode != 0:
        raise RuntimeError('version command returned non-zero exit code')
    return result.stdout + result.stderr


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api-jar', type=Path, default=ROOT/'libs/ledat-platform-api-2.10.0.jar')
    parser.add_argument('--gradle', default='gradle', help='Installed Gradle executable, not a shell command')
    args = parser.parse_args()
    failures = 0
    javac = shutil.which('javac')
    try:
        if not javac:
            raise RuntimeError('javac is missing')
        version = run_version(javac, '-version')
        match = re.search(r'javac (\d+)(?:\.\d+)*', version)
        if not match or int(match.group(1)) != 21:
            raise RuntimeError('use JDK 21 for this baseline')
        print('PASS JDK: ' + match.group(0))
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        failures += 1
        print('BLOCKED JDK: ' + str(error))
    try:
        executable = shutil.which(args.gradle)
        if not executable:
            raise RuntimeError('installed Gradle executable not found')
        version = run_version(executable, '--version')
        match = re.search(r'^Gradle (\d+\.\d+(?:\.\d+)?)', version, re.M)
        if not match:
            raise RuntimeError('cannot identify Gradle version')
        print('PASS executable: ' + match.group(0))
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        failures += 1
        print('BLOCKED Gradle: ' + str(error))
    try:
        if not args.api_jar.is_file():
            raise RuntimeError('API artifact is missing; supply --api-jar with the actual server API JAR')
        if args.api_jar.stat().st_size > 256 * 1024 * 1024:
            raise RuntimeError('artifact exceeds structural-check limit')
        with zipfile.ZipFile(args.api_jar) as archive:
            for entry in REQUIRED:
                info = archive.getinfo(entry)
                if not 8 <= info.file_size <= 4 * 1024 * 1024:
                    raise RuntimeError('invalid class entry size: ' + entry)
                with archive.open(info) as stream:
                    if stream.read(4) != b'\xca\xfe\xba\xbe':
                        raise RuntimeError('class-file signature missing: ' + entry)
        with args.api_jar.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        print('PASS API archive structure; SHA-256=' + digest)
        print('NOTE class presence does not establish artifact authenticity, API version or ABI compatibility.')
    except (OSError, KeyError, RuntimeError, zipfile.BadZipFile) as error:
        failures += 1
        print('BLOCKED API: ' + str(error))
    print('NETWORK NOT PROBED. Paper/PAPI/SnakeYAML dependency resolution remains a separate Gradle step.')
    print('No plugin compiled; no JAR generated; no native integration or live transaction tested.')
    print('RESULT blocking_prerequisites=' + str(failures))
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
