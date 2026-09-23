#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PHONE_SERIAL="${PHONE_SERIAL:-ANYXVB4906000933}"
ADB_BIN="${ADB:-$(command -v adb)}"
OUT="$ROOT/reports/latest/proactivity-effort"
RUNNER='ai.opstate.rokid.phone.test/androidx.test.runner.AndroidJUnitRunner'
INPUT="$ROOT/reports/latest/hr_experiment_selection_20260923.json"
cd "$ROOT"

python3 - "$ADB_BIN" "$PHONE_SERIAL" <<'PY'
import sqlite3,subprocess,sys,tempfile
from pathlib import Path
adb,serial=sys.argv[1:]
with tempfile.TemporaryDirectory(prefix='opstate-effort-guard-') as tmp:
    directory=Path(tmp)
    for suffix in ('','-wal','-shm'):
        name='opstate-study.db'+suffix
        result=subprocess.run([adb,'-s',serial,'exec-out','run-as','ai.opstate.rokid.phone',
            'cat','databases/'+name],capture_output=True,check=True)
        (directory/name).write_bytes(result.stdout)
    db=sqlite3.connect('file:'+str(directory/'opstate-study.db')+'?mode=ro',uri=True)
    active=db.execute("select state from runtime_checkpoint where state not in ('STOPPED','IDLE')").fetchall()
    if active:
        print('Refusing to install while a phone session is active:',[row[0] for row in active],file=sys.stderr)
        sys.exit(2)
PY

mkdir -p "$OUT"
make -C "$ROOT" apks > "$OUT/apk-build.txt"
"$ROOT/tools/gradle.sh" --console=plain -PwithAndroid=true :phone-app:assembleDebugAndroidTest > "$OUT/test-apk-build.txt"
"$ADB_BIN" -s "$PHONE_SERIAL" install -r "$ROOT/code/phone-app/build/outputs/apk/debug/phone-app-debug.apk"
"$ADB_BIN" -s "$PHONE_SERIAL" install -r "$ROOT/code/phone-app/build/outputs/apk/androidTest/debug/phone-app-debug-androidTest.apk"
"$ADB_BIN" -s "$PHONE_SERIAL" push "$INPUT" \
    /sdcard/Android/data/ai.opstate.rokid.phone/files/hr_experiment_selection.json > "$OUT/input-staged.txt"

run_test() {
    local label="$1"
    shift
    "$ADB_BIN" -s "$PHONE_SERIAL" shell am instrument -w -r "$@" "$RUNNER" | tee "$OUT/$label.txt"
    if ! rg -q 'OK \([0-9]+ tests?\)' "$OUT/$label.txt"; then
        echo "Instrumentation did not pass: $label" >&2
        exit 1
    fi
}

run_test slider -e class ai.opstate.rokid.phone.ui.ProactivityEffortControlUiTest
run_test component -e class ai.opstate.rokid.phone.ProactivityEffortDeviceTest

for effort in RESTRAINED INCLINED GUARANTEED; do
    effort_lower="$(printf '%s' "$effort" | tr '[:upper:]' '[:lower:]')"
    run_test "model-$effort_lower" \
        -e class ai.opstate.rokid.phone.HrExistingExperimentDeviceTest \
        -e hr_existing_experiment true -e hr_arm hr -e hr_clips 1 -e hr_clip_from 1 \
        -e hr_effort "$effort"
    "$ADB_BIN" -s "$PHONE_SERIAL" pull \
        /sdcard/Android/data/ai.opstate.rokid.phone/files/hr_existing_experiment_result.json \
        "$OUT/model-$effort_lower.json" > "$OUT/pull-$effort_lower.txt"
done

if [[ "${RUN_REAL_GLASSES:-0}" == "1" ]]; then
    run_test real-glasses-ack -e class ai.opstate.rokid.phone.HrRealGlassesAckDeviceTest \
        -e hr_live_ack true
    "$ADB_BIN" -s "$PHONE_SERIAL" pull \
        /sdcard/Android/data/ai.opstate.rokid.phone/files/hr_real_glasses_ack.json \
        "$OUT/real-glasses-ack.json" > "$OUT/pull-real-glasses.txt"
fi

if [[ "${RUN_LIVE_MODEL_GLASSES:-0}" == "1" ]]; then
    run_test real-model-glasses-ack -e class ai.opstate.rokid.phone.HrGuaranteedLiveModelAckDeviceTest \
        -e hr_live_model_ack true
    "$ADB_BIN" -s "$PHONE_SERIAL" pull \
        /sdcard/Android/data/ai.opstate.rokid.phone/files/hr_live_model_glasses_ack.json \
        "$OUT/real-model-glasses-ack.json" > "$OUT/pull-real-model-glasses.txt"
fi

python3 "$ROOT/tools/summarize_proactivity_checks.py"
echo "Short checks complete: $OUT"
