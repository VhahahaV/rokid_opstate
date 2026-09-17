#!/usr/bin/env bash
# User-owned macOS environment; does not change system Java or start a server.
# Mirrors tools/setup_linux.sh, but reuses toolchains that are already on the
# machine (a JDK 17-23 and an Android SDK) instead of downloading duplicates.
# Requires bash 3.2 (system bash) compatibility.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME="${OPSTATE_RUNTIME_DIR:-$HOME/.local/share/opstate-dev}"
PRIVATE="${OPSTATE_PRIVATE_DIR:-$HOME/.config/opstate-dev}"
case "$(uname -sm)" in
    'Darwin arm64') JDK_ARCH='aarch64' ;;
    'Darwin x86_64') JDK_ARCH='x64' ;;
    *) echo 'This installer requires macOS (arm64 or x86_64).' >&2; exit 1 ;;
esac
mkdir -p "$RUNTIME/downloads" "$ROOT/.local" "$PRIVATE"
chmod 700 "$PRIVATE"
fetch() { curl --fail --location --retry 5 --retry-all-errors --retry-delay 3 --connect-timeout 15 --max-time 900 --silent --show-error "$1" -o "$2"; }

# Gradle 8.11.1 runs on JDK 17-23; outside that range it fails, so only reuse what fits.
supports_gradle() {
    local version
    [ -n "${1:-}" ] && [ -x "$1/bin/java" ] || return 1
    version="$("$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
    [ -n "$version" ] && [ "$version" -ge 17 ] && [ "$version" -le 23 ]
}
sdk_ready() {
    [ -n "${1:-}" ] && [ -d "$1/platforms/android-35" ] && [ -d "$1/build-tools/34.0.0" ] && [ -x "$1/platform-tools/adb" ]
}

# Reuse an existing JDK before downloading Temurin 21.
JAVA_HOME_RESOLVED=''
for candidate in "${OPSTATE_JAVA_HOME:-}" "${JAVA_HOME:-}" \
        "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home" \
        "$RUNTIME/jdk-21"; do
    if supports_gradle "$candidate"; then JAVA_HOME_RESOLVED="$candidate"; break; fi
done
if [ -z "$JAVA_HOME_RESOLVED" ] && [ -x /usr/libexec/java_home ]; then
    for version in 21 17 23; do
        candidate="$(/usr/libexec/java_home -v "$version" 2>/dev/null || true)"
        if supports_gradle "$candidate"; then JAVA_HOME_RESOLVED="$candidate"; break; fi
    done
fi
if [ -z "$JAVA_HOME_RESOLVED" ]; then
    echo 'No JDK 17-23 found; installing Temurin 21.' >&2
    fetch "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=${JDK_ARCH}&image_type=jdk&os=mac&vendor=eclipse" "$RUNTIME/downloads/jdk.json"
    python3 - "$RUNTIME/downloads/jdk.json" "$RUNTIME/downloads/jdk-source.txt" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))[0]['binary']['package']
open(sys.argv[2], 'w').write(data['link'] + '\n' + data['checksum'] + '\n')
PY
    fetch "$(sed -n '1p' "$RUNTIME/downloads/jdk-source.txt")" "$RUNTIME/downloads/jdk.tar.gz"
    (cd "$RUNTIME/downloads" && printf '%s  jdk.tar.gz\n' "$(sed -n '2p' "$RUNTIME/downloads/jdk-source.txt")" | shasum -a 256 -c -)
    # macOS JDK archives nest the runtime under Contents/Home; strip down to it.
    rm -rf "$RUNTIME/jdk-21"; mkdir -p "$RUNTIME/jdk-21"
    tar -xzf "$RUNTIME/downloads/jdk.tar.gz" --strip-components=3 -C "$RUNTIME/jdk-21"
    JAVA_HOME_RESOLVED="$RUNTIME/jdk-21"
fi
export JAVA_HOME="$JAVA_HOME_RESOLVED"

# Reuse an existing Android SDK before downloading one.
ANDROID_HOME_RESOLVED=''
for candidate in "${ANDROID_HOME:-}" "$ROOT/.local/android-sdk" "$RUNTIME/android-sdk"; do
    if sdk_ready "$candidate"; then ANDROID_HOME_RESOLVED="$candidate"; break; fi
done
if [ -z "$ANDROID_HOME_RESOLVED" ]; then
    echo 'No Android SDK with platform 35 and build-tools 34.0.0 found; installing.' >&2
    ANDROID_HOME_RESOLVED="$RUNTIME/android-sdk"
fi
export ANDROID_HOME="$ANDROID_HOME_RESOLVED"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    fetch 'https://dl.google.com/android/repository/repository2-1.xml' "$RUNTIME/downloads/android-repository.xml"
    python3 - "$RUNTIME/downloads/android-repository.xml" "$RUNTIME/downloads/android-source.txt" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for archive in root.iter('archive'):
    if archive.findtext('host-os') != 'macosx': continue
    complete = archive.find('complete')
    if complete is None or complete.findtext('url') != 'commandlinetools-mac-13114758_latest.zip': continue
    open(sys.argv[2], 'w').write(complete.findtext('url') + '\n' + complete.findtext('checksum') + '\n')
    break
else: raise SystemExit('Pinned Android command-line tools missing from official repository metadata')
PY
    fetch "https://dl.google.com/android/repository/$(sed -n '1p' "$RUNTIME/downloads/android-source.txt")" "$RUNTIME/downloads/android-tools.zip"
    (cd "$RUNTIME/downloads" && printf '%s  android-tools.zip\n' "$(sed -n '2p' "$RUNTIME/downloads/android-source.txt")" | shasum -a 1 -c -)
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    unzip -q "$RUNTIME/downloads/android-tools.zip" -d "$RUNTIME/downloads/android-unpacked"
    mv "$RUNTIME/downloads/android-unpacked/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rmdir "$RUNTIME/downloads/android-unpacked"
fi
if ! sdk_ready "$ANDROID_HOME"; then
    # Setup is explicitly requested by the developer operating this workstation.
    # Feed acceptance only to sdkmanager; preserve sdkmanager's actual exit status.
    set +o pipefail
    yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses > "$RUNTIME/downloads/sdk-licenses.log"
    sdk_status=${PIPESTATUS[1]}
    set -o pipefail
    [ "$sdk_status" -eq 0 ] || exit "$sdk_status"
    sdkmanager --sdk_root="$ANDROID_HOME" 'platform-tools' 'platforms;android-35' 'build-tools;34.0.0'
fi

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
    printf 'export GRADLE_USER_HOME=%q\n' "${GRADLE_USER_HOME:-$HOME/.gradle}"
    printf 'export PATH=%q:"$PATH"\n' "$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$ROOT/.venv/bin"
    printf 'if [ -f %q ]; then export OPSTATE_DEBUG_KEYSTORE=%q; fi\n' "$PRIVATE/debug.keystore" "$PRIVATE/debug.keystore"
} > "$ROOT/.local/environment.sh"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$ROOT/local.properties"
printf '\nReady. Run: cd %q && make quick-check apks\n' "$ROOT"
