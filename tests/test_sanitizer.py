#!/usr/bin/env python3
from __future__ import annotations
import json, subprocess, sys, tempfile, unittest, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

class SanitizerTests(unittest.TestCase):
    def test_sanitizer_fixture(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            archive = tmp / "private-fixture.zip"
            with zipfile.ZipFile(archive, "w") as zf:
                zf.writestr("project/app/src/CameraBridge.kt", "package com.private.realtopia\nclass RealTopiaCamera")
                zf.writestr("project/local.properties", "API_KEY=super_secret_value_123456789")
                zf.writestr("project/app/build/generated.txt", "drop")
            output = tmp / "out"
            report = tmp / "report.json"
            proc = subprocess.run([
                sys.executable, str(ROOT / "tools/sanitize_private_repo.py"), str(archive),
                "--policy", str(ROOT / "configs/private_repo_policy.example.json"),
                "--output", str(output), "--report", str(report), "--apply",
            ], text=True, capture_output=True)
            # Exit code 2 is expected because a potential secret is quarantined.
            if proc.returncode != 2:
                raise SystemExit(f"unexpected sanitizer return code: {proc.returncode}\n{proc.stdout}\n{proc.stderr}")
            data = json.loads(report.read_text())
            if data["summary"].get("QUARANTINE") != 1:
                raise SystemExit("secret file was not quarantined")
            copied = list(output.rglob("*CameraBridge.kt"))
            if len(copied) != 1:
                raise SystemExit("allowlisted camera file was not copied")
            text = copied[0].read_text()
            if "ai.opstate.rokid" not in text or "OpstateRokidStudyCamera" not in text:
                raise SystemExit("rename mapping was not applied")
            report_text = report.read_text()
            if "super_secret_value" in report_text:
                raise SystemExit("secret value leaked into report")


if __name__ == "__main__":
    unittest.main()
