#!/usr/bin/env bash
# User-owned Linux environment; does not change system Java or start a server.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME="${OPSTATE_RUNTIME_DIR:-$HOME/.local/share/opstate-dev}"
PRIVATE="${OPSTATE_PRIVATE_DIR:-$HOME/.config/opstate-dev}"
[ "$(uname -sm)" = 'Linux x86_64' ] || { echo 'This installer requires Linux x86_64.' >&2; exit 1; }
mkdir -p "$RUNTIME/downloads" "$RUNTIME/android-sdk" "$ROOT/.local" "$PRIVATE"
chmod 700 "$PRIVATE"
fetch() { curl --fail --location --retry 2 --connect-timeout 15 --max-time 600 --silent --show-error "$1" -o "$2"; }

if [ ! -x "$RUNTIME/jdk-21/bin/javac" ]; then
    fetch 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse' "$RUNTIME/downloads/jdk.json"
    python3 - "$RUNTIME/downloads/jdk.json" "$RUNTIME/downloads/jdk-source.txt" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))[0]['binary']['package']
open(sys.argv[2], 'w').write(data['link'] + '\n' + data['checksum'] + '\n')
PY
    mapfile -t jdk_source < "$RUNTIME/downloads/jdk-source.txt"
    fetch "${jdk_source[0]}" "$RUNTIME/downloads/jdk.tar.gz"
    (cd "$RUNTIME/downloads" && printf '%s  jdk.tar.gz\n' "${jdk_source[1]}" | sha256sum -c -)
    mkdir -p "$RUNTIME/jdk-21"
    tar -xzf "$RUNTIME/downloads/jdk.tar.gz" --strip-components=1 -C "$RUNTIME/jdk-21"
fi
export JAVA_HOME="$RUNTIME/jdk-21"
export ANDROID_HOME="$RUNTIME/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    fetch 'https://dl.google.com/android/repository/repository2-1.xml' "$RUNTIME/downloads/android-repository.xml"
    python3 - "$RUNTIME/downloads/android-repository.xml" "$RUNTIME/downloads/android-source.txt" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for archive in root.iter('archive'):
    if archive.findtext('host-os') != 'linux': continue
    complete = archive.find('complete')
    if complete is None or complete.findtext('url') != 'commandlinetools-linux-13114758_latest.zip': continue
    open(sys.argv[2], 'w').write(complete.findtext('url') + '\n' + complete.findtext('checksum') + '\n')
    break
else: raise SystemExit('Pinned Android command-line tools missing from official repository metadata')
PY
    mapfile -t android_source < "$RUNTIME/downloads/android-source.txt"
    fetch "https://dl.google.com/android/repository/${android_source[0]}" "$RUNTIME/downloads/android-tools.zip"
    (cd "$RUNTIME/downloads" && printf '%s  android-tools.zip\n' "${android_source[1]}" | sha1sum -c -)
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    unzip -q "$RUNTIME/downloads/android-tools.zip" -d "$RUNTIME/downloads/android-unpacked"
    mv "$RUNTIME/downloads/android-unpacked/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rmdir "$RUNTIME/downloads/android-unpacked"
fi
# Setup is explicitly requested by the developer operating this workstation.
# Feed acceptance only to sdkmanager; preserve sdkmanager's actual exit status.
set +o pipefail
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses > "$RUNTIME/downloads/sdk-licenses.log"
sdk_status=${PIPESTATUS[1]}
set -o pipefail
[ "$sdk_status" -eq 0 ] || exit "$sdk_status"
sdkmanager --sdk_root="$ANDROID_HOME" 'platform-tools' 'platforms;android-35' 'build-tools;34.0.0'

if [ ! -x "$ROOT/.venv/bin/python" ]; then
    if [ -x "$HOME/.local/bin/uv" ]; then "$HOME/.local/bin/uv" venv --python python3 "$ROOT/.venv"; else python3 -m venv "$ROOT/.venv"; fi
fi
if [ -x "$HOME/.local/bin/uv" ]; then
    "$HOME/.local/bin/uv" pip install --python "$ROOT/.venv/bin/python" -r "$ROOT/requirements.txt"
else
    "$ROOT/.venv/bin/python" -m pip install -r "$ROOT/requirements.txt"
fi
{
    printf 'export OPSTATE_JAVA_HOME=%q\n' "$JAVA_HOME"
    printf 'export JAVA_HOME=%q\n' "$JAVA_HOME"
    printf 'export ANDROID_HOME=%q\n' "$ANDROID_HOME"
    printf 'export GRADLE_USER_HOME=%q\n' "$RUNTIME/gradle"
    printf 'export PATH=%q:"$PATH"\n' "$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$ROOT/.venv/bin"
    printf 'if [ -f %q ]; then export OPSTATE_DEBUG_KEYSTORE=%q; fi\n' "$PRIVATE/debug.keystore" "$PRIVATE/debug.keystore"
} > "$ROOT/.local/environment.sh"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$ROOT/local.properties"
printf '\nReady. Run: cd %q && make quick-check apks\n' "$ROOT"
