#!/usr/bin/env python3
from __future__ import annotations
import argparse, hashlib, json
from pathlib import Path
from repo_files import source_files

parser = argparse.ArgumentParser()
parser.add_argument("root", type=Path)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
root = args.root.resolve()
output = args.output.resolve()
rows = []
for path in sorted(source_files(root)):
    if not path.is_file() or path.resolve() == output:
        continue
    data = path.read_bytes()
    rows.append({
        "path": path.relative_to(root).as_posix(),
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
    })
manifest = {"schema_version": "bundle_manifest_v1", "file_count": len(rows), "files": rows}
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"wrote {output} with {len(rows)} files")
