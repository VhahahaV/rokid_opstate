#!/usr/bin/env bash
set -euo pipefail
PHONE_PACKAGE="${PHONE_PACKAGE:-ai.opstate.rokid.phone}"
PHONE_ACTIVITY="${PHONE_ACTIVITY:-.MainActivity}"
HARNESS_PORT="${HARNESS_PORT:-8765}"
PHONE_SERIAL="${1:-${PHONE_SERIAL:-}}"
ADB="${ADB:-adb}"
if ! command -v "$ADB" >/dev/null 2>&1; then
    echo 'Install Android SDK platform-tools and add adb to PATH, or set ADB=/path/to/adb.' >&2
    exit 1
fi
ADB_COMMAND=("$ADB")
if [ -n "$PHONE_SERIAL" ]; then
    ADB_COMMAND+=(-s "$PHONE_SERIAL")
fi

"${ADB_COMMAND[@]}" get-state >/dev/null
if ! "${ADB_COMMAND[@]}" shell pm path "$PHONE_PACKAGE" | grep -q '^package:'; then
    echo "Package $PHONE_PACKAGE is not installed. Run make apks and install the host APK first." >&2
    exit 1
fi
"${ADB_COMMAND[@]}" reverse "tcp:${HARNESS_PORT}" "tcp:${HARNESS_PORT}"
"${ADB_COMMAND[@]}" shell am force-stop "$PHONE_PACKAGE" || true
"${ADB_COMMAND[@]}" shell am start -n "${PHONE_PACKAGE}/${PHONE_ACTIVITY}"
printf 'Phone debug endpoint: http://127.0.0.1:%s\n' "$HARNESS_PORT"
printf 'Logcat filter: adb logcat -s OPSTATE:R RokidTransport:R StudyRuntime:R *:S\n'
