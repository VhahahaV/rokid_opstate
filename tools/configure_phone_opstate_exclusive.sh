#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-adb}"
MODE="${1:-}"
SERIAL="${2:-}"
COMPANION="com.rokid.sprite.aiapp"

if [[ "$MODE" != apply && "$MODE" != restore || -z "$SERIAL" ]]; then
    echo "Usage: $0 apply|restore PHONE_SERIAL" >&2
    exit 2
fi

"$ADB" -s "$SERIAL" get-state >/dev/null
if [[ "$MODE" == apply ]]; then
    "$ADB" -s "$SERIAL" shell am force-stop "$COMPANION"
    "$ADB" -s "$SERIAL" shell pm disable-user --user 0 "$COMPANION"
    echo "Rokid companion disabled so OPSTATE owns the CXR phone connection."
else
    "$ADB" -s "$SERIAL" shell pm enable --user 0 "$COMPANION"
    echo "Rokid companion enabled."
fi
