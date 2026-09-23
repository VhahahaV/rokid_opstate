#!/usr/bin/env python3
"""Hash and summarize the bounded three-effort phone checks without grading card quality."""
from __future__ import annotations

import hashlib
import json
import statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "reports/latest/proactivity-effort"


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    source = ROOT / "reports/latest/hr_experiment_selection_20260923.json"
    paths = {
        "source_selection": source,
        "phone_apk": ROOT / "code/phone-app/build/outputs/apk/debug/phone-app-debug.apk",
        "hr_step_prompt": ROOT / "code/android-overlay/phone/src/main/assets/direct_model/hr_step.md",
        "hr_final_prompt": ROOT / "code/android-overlay/phone/src/main/assets/direct_model/hr_final.md",
        "hr_guaranteed_prompt": ROOT / "code/android-overlay/phone/src/main/assets/direct_model/hr_guaranteed.md",
    }
    expected = {"restrained": "RESTRAINED", "inclined": "INCLINED", "guaranteed": "GUARANTEED"}
    runs = {}
    for name, effort in expected.items():
        path = OUT / f"model-{name}.json"
        result = json.loads(path.read_text())
        assert result["input_sha256"] == sha(source), name
        assert result["proactivity_effort"] == effort, name
        assert len(result["rows"]) == 2, name
        runs[name] = {
            "result_sha256": sha(path),
            "input_sha256": result["input_sha256"],
            "model_id": result["model_id"],
            "frames": len(result["rows"]),
            "model_calls": result["model_calls"],
            "model_silent": sum(row["step_route"] == "SILENT" for row in result["rows"]),
            "model_service": sum(row["step_route"] == "SERVICE" for row in result["rows"]),
            "final_service": sum(row["decision"] == "SERVICE" for row in result["rows"]),
            "program_fallback": sum(row.get("effort_override") is True for row in result["rows"]),
            "model_forced_service": sum(row.get("forced_model_pass") is True for row in result["rows"]),
            "technical_failures": sum(row["error"] is not None for row in result["rows"]),
            "median_latency_ms": statistics.median(row["latency_ms"] for row in result["rows"]),
        }
    tests = {}
    for name in ["slider", "component", "model-restrained", "model-inclined", "model-guaranteed"]:
        path = OUT / f"{name}.txt"
        log = path.read_text()
        tests[name] = {"sha256": sha(path), "passed": "FAILURES!!!" not in log and "OK (" in log}
    assert all(item["passed"] for item in tests.values())
    installed_path=OUT / "installed-apk-identity.json"
    model_run_path=OUT / "model-run-apk-identity.json"
    installed=json.loads(installed_path.read_text()) if installed_path.is_file() else None
    if installed is not None and installed.get("built_apk_sha256")!=sha(paths["phone_apk"]):
        installed={"matched":False,"reason":"saved device verification belongs to another APK build"}
    report = {
        "schema": "opstate_proactivity_short_check_manifest_v1",
        "scope": "same two local real photographs, virtual source time, phone direct model; no glasses delivery in model arms",
        "inputs": {key: {"sha256": sha(path), "bytes": path.stat().st_size} for key, path in paths.items()},
        "current_test_apk_sha256": sha(ROOT / "code/phone-app/build/outputs/apk/androidTest/debug/phone-app-debug-androidTest.apk"),
        "test_apk_identity_note": "Additional instrumentation tests were added after the first short-check run; this is the current test APK, not a claimed byte-for-byte identity for every earlier log.",
        "runs": runs,
        "instrumentation": tests,
        "model_run_phone_apk": json.loads(model_run_path.read_text()) if model_run_path.is_file() else None,
        "installed_phone_apk": installed,
        "real_glasses_ack": json.loads((OUT / "real-glasses-ack.json").read_text())
            if (OUT / "real-glasses-ack.json").is_file() else None,
        "real_model_glasses_ack": json.loads((OUT / "real-model-glasses-ack.json").read_text())
            if (OUT / "real-model-glasses-ack.json").is_file() else None,
        "limitations": ["not a representative hourly service rate", "no independent Gold labels", "real glasses test uses an injected HR decision"],
    }
    destination = OUT / "run-manifest.json"
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(destination)


if __name__ == "__main__":
    main()
