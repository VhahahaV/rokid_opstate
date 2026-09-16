#!/usr/bin/env python3
"""Safely inventory and optionally sanitize a private Android/Rokid ZIP.

Privacy properties:
- never prints source-file contents;
- secret matches are reported only as rule names and file paths;
- dry-run is the default;
- rejects archive path traversal and oversized members;
- emits a private provenance manifest with source/output hashes.

This tool is intentionally conservative. Review the plan before using --apply.
"""
from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import os
import re
import shutil
import tempfile
import zipfile
from dataclasses import dataclass, asdict
from pathlib import Path, PurePosixPath
from typing import Iterable

TEXT_EXT = {".kt", ".kts", ".java", ".xml", ".json", ".toml", ".gradle", ".properties", ".md", ".txt", ".pro"}
SECRET_RULES = {
    "generic_api_key": re.compile(r"(?i)(api[_-]?key|client[_-]?secret|access[_-]?token)\s*[:=]\s*['\"]?[^\s'\"]{12,}"),
    "bearer_token": re.compile(r"(?i)bearer\s+[A-Za-z0-9._~+/=-]{16,}"),
    "private_key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "aws_access_key": re.compile(r"AKIA[0-9A-Z]{16}"),
    "url_credentials": re.compile(r"https?://[^/\s:@]+:[^/\s@]+@"),
}

@dataclass
class Entry:
    source_path: str
    size: int
    sha256: str
    action: str
    reason: str
    output_path: str | None = None
    output_sha256: str | None = None
    secret_rules: list[str] | None = None


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def safe_member(name: str) -> bool:
    p = PurePosixPath(name)
    return not p.is_absolute() and ".." not in p.parts and "\x00" not in name


def matches(path: str, patterns: Iterable[str]) -> bool:
    normalized = path.replace("\\", "/")
    return any(fnmatch.fnmatch(normalized, pat) or fnmatch.fnmatch("/" + normalized, pat) for pat in patterns)


def classify(path: str, policy: dict) -> tuple[str, str]:
    low = path.lower().replace("\\", "/")
    if matches(path, policy.get("drop_globs", [])):
        return "DROP", "drop_glob"
    if any(keyword.lower() in low for keyword in policy.get("drop_path_keywords", [])):
        return "DROP", "unrelated_path_keyword"
    if matches(path, policy.get("keep_globs", [])):
        if matches(path, policy.get("rewrite_globs", [])):
            return "KEEP_REWRITE", "rokid_or_android_core"
        return "KEEP", "rokid_or_android_core"
    if Path(path).suffix.lower() in TEXT_EXT and policy.get("copy_unknown_text_files", False):
        return "REVIEW", "unknown_text"
    return "DROP", "not_allowlisted"


def rewrite_text(text: str, replacements: dict[str, str]) -> str:
    for old, new in replacements.items():
        text = text.replace(old, new)
    return text


def detect_secret_rules(data: bytes) -> list[str]:
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        return []
    return [name for name, regex in SECRET_RULES.items() if regex.search(text)]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("--policy", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--apply", action="store_true", help="Copy allowlisted files. Default is dry-run.")
    args = parser.parse_args()

    policy = json.loads(args.policy.read_text(encoding="utf-8"))
    max_size = int(policy.get("max_member_bytes", 50 * 1024 * 1024))
    entries: list[Entry] = []
    args.output.mkdir(parents=True, exist_ok=True)
    args.report.parent.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(args.archive) as zf:
        for info in sorted(zf.infolist(), key=lambda x: x.filename):
            if info.is_dir():
                continue
            if not safe_member(info.filename):
                entries.append(Entry(info.filename, info.file_size, "", "REJECT", "unsafe_archive_path"))
                continue
            if info.file_size > max_size:
                entries.append(Entry(info.filename, info.file_size, "", "DROP", "oversized_member"))
                continue
            data = zf.read(info)
            action, reason = classify(info.filename, policy)
            secrets = detect_secret_rules(data)
            if secrets:
                action, reason = "QUARANTINE", "potential_secret"
            entry = Entry(
                source_path=info.filename,
                size=len(data),
                sha256=sha256(data),
                action=action,
                reason=reason,
                secret_rules=secrets or None,
            )
            if args.apply and action in {"KEEP", "KEEP_REWRITE"}:
                relative = PurePosixPath(info.filename)
                # Strip one common archive root directory.
                parts = relative.parts[1:] if len(relative.parts) > 1 else relative.parts
                out = args.output.joinpath(*parts)
                out.parent.mkdir(parents=True, exist_ok=True)
                out_data = data
                if action == "KEEP_REWRITE":
                    try:
                        text = data.decode("utf-8")
                        out_data = rewrite_text(text, policy.get("rename_text", {})).encode("utf-8")
                    except UnicodeDecodeError:
                        entry.action = "QUARANTINE"
                        entry.reason = "rewrite_target_not_utf8"
                        entries.append(entry)
                        continue
                out.write_bytes(out_data)
                entry.output_path = out.relative_to(args.output).as_posix()
                entry.output_sha256 = sha256(out_data)
            entries.append(entry)

    summary = {}
    for e in entries:
        summary[e.action] = summary.get(e.action, 0) + 1
    report = {
        "schema_version": "private_repo_sanitization_report_v1",
        "archive_name": args.archive.name,
        "archive_sha256": sha256(args.archive.read_bytes()),
        "dry_run": not args.apply,
        "policy_sha256": sha256(args.policy.read_bytes()),
        "summary": summary,
        "entries": [asdict(e) for e in entries],
        "privacy_note": "No source contents or secret values are included in this report.",
    }
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"dry_run": not args.apply, "summary": summary, "report": str(args.report)}, ensure_ascii=False))
    return 2 if any(e.action in {"REJECT", "QUARANTINE"} for e in entries) else 0


if __name__ == "__main__":
    raise SystemExit(main())
