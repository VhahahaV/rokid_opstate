#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-adb}"
MODE="${1:-}"
SERIAL="${2:-}"
APP="ai.opstate.rokid.glasses"
SYSTEM_HOME="com.rokid.os.sprite.launcher/com.rokid.os.sprite.launcher.main.SpriteMainActivity"

if [[ "$MODE" != apply && "$MODE" != restore || -z "$SERIAL" ]]; then
    echo "Usage: $0 apply|restore GLASSES_SERIAL" >&2
    exit 2
fi

"$ADB" -s "$SERIAL" get-state >/dev/null
STATE="$ROOT/.local/glasses-opstate-home-$SERIAL.disabled"

if [[ "$MODE" == apply ]]; then
    "$ADB" -s "$SERIAL" shell "printf '\n'; pm path $APP" >/dev/null
    mkdir -p "$(dirname "$STATE")"
    touch "$STATE"
    while IFS= read -r package; do
        [[ -z "$package" || "$package" == "$APP" ]] && continue
        if ! grep -Fxq "$package" "$STATE"; then printf '%s\n' "$package" >> "$STATE"; fi
        "$ADB" -s "$SERIAL" shell "printf '\n'; am force-stop $package" </dev/null
        "$ADB" -s "$SERIAL" shell "printf '\n'; pm disable-user --user 0 $package" </dev/null
    done < <("$ADB" -s "$SERIAL" shell "printf '\n'; pm list packages -3 -e" | tr -d '\r' | sed -n 's/^package://p')
    "$ADB" -s "$SERIAL" shell "printf '\n'; cmd package set-home-activity $SYSTEM_HOME"
    "$ADB" -s "$SERIAL" shell "printf '\n'; am start -n $APP/.MainActivity"
    echo "OPSTATE opened; disabled user apps are recorded in $STATE"
else
    "$ADB" -s "$SERIAL" shell "printf '\n'; cmd package set-home-activity $SYSTEM_HOME"
    if [[ -f "$STATE" ]]; then
        while IFS= read -r package; do
            [[ -z "$package" ]] || "$ADB" -s "$SERIAL" shell "printf '\n'; pm enable --user 0 $package" </dev/null
        done < "$STATE"
        rm "$STATE"
    fi
    "$ADB" -s "$SERIAL" shell "printf '\n'; am start -a android.intent.action.MAIN -c android.intent.category.HOME"
    echo "Restored the Rokid launcher and previously enabled user apps."
fi
