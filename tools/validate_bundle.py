#!/usr/bin/env python3
"""Validate authored files and host-side code; make check-all includes Android."""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

from repo_files import source_files

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    errors: list[str] = []
    report_dir = ROOT / "reports/latest"
    report_dir.mkdir(parents=True, exist_ok=True)
    files = list(source_files(ROOT))
    json_docs = {}
    for path in files:
        if path.suffix != ".json":
            continue
        try:
            json_docs[path] = json.loads(path.read_text(encoding="utf-8"))
        except (ValueError, OSError) as exc:
            errors.append(f"JSON {path.relative_to(ROOT)}: {exc}")

    registry = Registry()
    for path in sorted((ROOT / "schemas").glob("*.json")):
        schema = json_docs.get(path)
        try:
            Draft202012Validator.check_schema(schema)
            resource = Resource.from_contents(schema)
            registry = registry.with_resource(path.as_uri(), resource)
            if "$id" in schema:
                registry = registry.with_resource(schema["$id"], resource)
        except Exception as exc:
            errors.append(f"schema {path.name}: {exc}")

    mapping = json_docs.get(ROOT / "tests/schema_fixture_map.json")
    if not isinstance(mapping, dict):
        errors.append("missing or invalid tests/schema_fixture_map.json")
        mapping = {}
    for schema_rel, fixture_rels in mapping.items():
        for fixture_rel in fixture_rels:
            try:
                validator = Draft202012Validator(json_docs[ROOT / schema_rel], registry=registry)
                for err in validator.iter_errors(json_docs[ROOT / fixture_rel]):
                    location = "/".join(map(str, err.path))
                    errors.append(f"schema {fixture_rel} at {location}: {err.message}")
            except Exception as exc:
                errors.append(f"schema fixture {fixture_rel}: {exc}")

    html_re = re.compile(r"<(?:table|br|colgroup|tr|td|th)(?:\s|>|/)", re.I)
    for path in files:
        rel = path.relative_to(ROOT).as_posix()
        if path.suffix == ".md" and html_re.search(path.read_text(encoding="utf-8")):
            errors.append(f"HTML residue in Markdown: {rel}")
        if path.suffix.lower() in {".jks", ".keystore", ".p12", ".pem"} or path.name.startswith(".env"):
            errors.append(f"forbidden secret-bearing source file: {rel}")
        if "realtopia" in path.name.lower():
            errors.append(f"unexpected private repository artifact: {rel}")
        if path.suffix == ".py":
            try:
                compile(path.read_bytes(), str(path), "exec")
            except SyntaxError as exc:
                errors.append(f"Python syntax {rel}: {exc}")

    checks = {
        "core": ["bash", str(ROOT / "code/core/run_tests.sh")],
        "protocol": ["bash", str(ROOT / "tools/gradle.sh"), "--console=plain", ":opstate-protocol:check"],
        "runtime_trace": [sys.executable, str(ROOT / "tools/validate_runtime_trace.py"), str(ROOT / "tests/fixtures/export_session")],
        "python": [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-p", "test_*.py", "-v"],
    }
    results = {}
    for name, command in checks.items():
        print(f"Running {name} checks...", flush=True)
        try:
            proc = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
            output, returncode = proc.stdout + proc.stderr, proc.returncode
        except OSError as exc:
            output, returncode = str(exc), 127
        (report_dir / f"{name}_test_output.txt").write_text(output, encoding="utf-8")
        results[name] = {"returncode": returncode, "output_sha256": hashlib.sha256(output.encode()).hexdigest()}
        if returncode:
            errors.append(f"{name} checks failed; see reports/latest/{name}_test_output.txt")
            print(output, file=sys.stderr)

    report = {
        "schema_version": "static_validation_v2",
        "status": "PASS" if not errors else "FAIL",
        "errors": errors,
        "json_document_count": len(json_docs),
        "checks": results,
        "android": "Not included in host checks. Run make android or make check-all with an Android SDK.",
    }
    (report_dir / "STATIC_VALIDATION.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
