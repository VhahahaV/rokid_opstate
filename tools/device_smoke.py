#!/usr/bin/env python3
"""Run the existing library code on explicitly selected phone/glasses devices."""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import ProxyHandler, build_opener

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phone", required=True, help="Phone ADB serial")
    parser.add_argument("--glasses", required=True, help="Glasses ADB serial")
    parser.add_argument("--adb", default=os.getenv("ADB") or shutil.which("adb") or str(ROOT / ".local/android-sdk/platform-tools/adb"))
    parser.add_argument("--port", type=int, default=8765, help="Running local mock harness port")
    args = parser.parse_args()
    if not args.phone or not args.glasses or args.phone == args.glasses:
        parser.error("provide two distinct device serials")
    if not 1 <= args.port <= 65535:
        parser.error("port must be between 1 and 65535")
    report_dir = ROOT / "reports/latest"
    report_dir.mkdir(parents=True, exist_ok=True)
    report = {"checked_at": datetime.now(timezone.utc).isoformat(), "status": "FAIL", "devices": {}, "errors": []}
    installed = []
    reverse_created = False

    def adb(serial, *command, check=True):
        return subprocess.run([args.adb, "-s", serial, *command], capture_output=True, text=True, check=check, timeout=90)

    try:
        with build_opener(ProxyHandler({})).open(f"http://127.0.0.1:{args.port}/health", timeout=5) as response:
            health = json.load(response)
        if health.get("model_id") != "mock-not-for-paper":
            raise RuntimeError("device smoke tests require the local mock harness (make serve)")
        for role, serial in (("phone", args.phone), ("glasses", args.glasses)):
            if adb(serial, "get-state").stdout.strip() != "device":
                raise RuntimeError(f"{role} is not ready")
            report["devices"][role] = {
                "serial": serial,
                "model": adb(serial, "shell", "getprop", "ro.product.model").stdout.strip(),
                "api": adb(serial, "shell", "getprop", "ro.build.version.sdk").stdout.strip(),
            }
        local = f"tcp:{args.port}"
        mappings = [line.split()[-2:] for line in adb(args.phone, "reverse", "--list").stdout.splitlines() if line.strip()]
        existing = [remote for port, remote in mappings if port == local]
        if existing and existing != [local]:
            raise RuntimeError(f"phone {local} already maps to a different host port")
        if not existing:
            adb(args.phone, "reverse", "--no-rebind", local, local)
            reverse_created = True
        health_on_phone = adb(args.phone, "shell", "curl", "--fail", "--silent", f"http://127.0.0.1:{args.port}/health")
        report["phone_http_health"] = json.loads(health_on_phone.stdout)

        for role, serial in (("phone", args.phone), ("glasses", args.glasses)):
            package = f"ai.opstate.rokid.{role}.librarytest"
            apk = ROOT / f"code/android-overlay/{role}/build/outputs/apk/androidTest/debug/opstate-{role}-debug-androidTest.apk"
            if not apk.is_file():
                raise RuntimeError(f"Missing {apk.name}; run make device-apks first")
            already_installed = "package:" in adb(serial, "shell", "pm", "path", package, check=False).stdout
            adb(serial, "install", "-r", "-t", str(apk))
            if not already_installed:
                installed.append((serial, package))
            command = ["shell", "am", "instrument", "-w", "-r"]
            if role == "phone":
                command += ["-e", "harnessUrl", f"http://127.0.0.1:{args.port}"]
            command += [f"{package}/androidx.test.runner.AndroidJUnitRunner"]
            result = adb(serial, *command, check=False)
            output = result.stdout + result.stderr
            (report_dir / f"{role}_device_test_output.txt").write_text(output)
            match = re.search(r"OK \((\d+) tests?\)", output)
            if result.returncode or not match or int(match.group(1)) < 2:
                raise RuntimeError(f"{role} tests failed; see reports/latest/{role}_device_test_output.txt")
            report["devices"][role]["tests_passed"] = int(match.group(1))
            print(f"PASS {role}: {match.group(1)} device tests", flush=True)
        report["status"] = "PASS"
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        report["errors"].append(str(error))
    finally:
        for serial, package in installed:
            try:
                adb(serial, "uninstall", package, check=False)
            except (OSError, subprocess.SubprocessError) as error:
                report.setdefault("cleanup_warnings", []).append(str(error))
        if reverse_created:
            try:
                adb(args.phone, "reverse", "--remove", f"tcp:{args.port}", check=False)
            except (OSError, subprocess.SubprocessError) as error:
                report.setdefault("cleanup_warnings", []).append(str(error))
        (report_dir / "device_validation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
