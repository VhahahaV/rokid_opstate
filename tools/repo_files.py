"""Shared source-tree boundary for validation and source manifests."""
from __future__ import annotations

import os
from pathlib import Path

GENERATED_DIRS = {".git", ".gradle", ".kotlin", ".venv", ".local", "__pycache__", "build", "node_modules", "dist", "archive", "reports"}
LOCAL_FILES = {
    ".DS_Store",
    ".environment-setup.log",
    "local.properties",
    "BUNDLE_MANIFEST.json",
    "SHA256SUMS.txt",
}


def source_files(root: Path):
    for directory, dirs, names in os.walk(root):
        base = Path(directory)
        dirs[:] = sorted(d for d in dirs if d not in GENERATED_DIRS and not (base / d).is_symlink())
        for name in sorted(names):
            path = base / name
            if name not in LOCAL_FILES and path.suffix not in {".pyc", ".pyo", ".zip"} and not path.is_symlink():
                yield path
