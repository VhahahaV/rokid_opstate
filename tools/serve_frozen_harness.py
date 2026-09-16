#!/usr/bin/env python3
"""Launch one immutable model service without copying provider secrets into the repo or phone."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import secrets
import sys

from dotenv import dotenv_values
import uvicorn

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "code/server-contract"))
from frozen_harness import create_app
from model_provider import ModelProfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--model-env-file", type=Path)
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8767)
    parser.add_argument("--tls-cert", type=Path)
    parser.add_argument("--tls-key", type=Path)
    args = parser.parse_args()
    if bool(args.tls_cert) != bool(args.tls_key) or (args.host not in {"127.0.0.1", "localhost", "::1"} and not args.tls_cert):
        parser.error("A non-loopback listener requires both --tls-cert and --tls-key")
    profile = ModelProfile.load(args.config)
    values = dotenv_values(args.model_env_file) if args.model_env_file else {}
    api_key = os.environ.get(profile.api_key_env) or values.get(profile.api_key_env)
    if not api_key:
        parser.error("Required model credential is unset: " + profile.api_key_env)
    args.state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    token_path = args.state_dir / "harness-access-token"
    try:
        descriptor = os.open(token_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        pass
    else:
        with os.fdopen(descriptor, "w") as output:
            output.write(secrets.token_urlsafe(32) + "\n")
    token = token_path.read_text().strip()
    app = create_app(profile, api_key=api_key, bearer_token=token, database=args.state_dir / "harness.sqlite3")
    print("Frozen profile:", profile.profile_id, "| Harness token file:", token_path, flush=True)
    uvicorn.run(app, host=args.host, port=args.port, workers=1, access_log=False,
        ssl_certfile=str(args.tls_cert) if args.tls_cert else None, ssl_keyfile=str(args.tls_key) if args.tls_key else None)


if __name__ == "__main__":
    main()
