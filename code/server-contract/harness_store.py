"""Durable idempotency and raw provider audit. A process lock prohibits unsafe multi-worker recovery."""
from __future__ import annotations

import fcntl
import json
from pathlib import Path
import sqlite3
import threading
import time


class HarnessStore:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        path.touch(mode=0o600, exist_ok=True)
        path.chmod(0o600)
        self.lock_file = path.with_suffix(path.suffix + ".lock").open("a")
        path.with_suffix(path.suffix + ".lock").chmod(0o600)
        try:
            fcntl.flock(self.lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            self.lock_file.close()
            raise RuntimeError("HARNESS_DATABASE_ALREADY_IN_USE: run one worker per database")
        self.lock = threading.RLock()
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS requests (
              request_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, state TEXT NOT NULL,
              status INTEGER, body TEXT);
            CREATE TABLE IF NOT EXISTS events (
              sequence INTEGER PRIMARY KEY AUTOINCREMENT, request_id TEXT NOT NULL,
              event_type TEXT NOT NULL, event_at_ms INTEGER NOT NULL, payload TEXT NOT NULL);
        """)
        with self.db:
            for (rid,) in self.db.execute("SELECT request_id FROM requests WHERE state='RUNNING'").fetchall():
                self.event(rid, "TECHNICAL_FAILURE", {"code": "HARNESS_RESTARTED"})
                self.db.execute("UPDATE requests SET state='DONE',status=503,body=? WHERE request_id=?",
                    (json.dumps({"detail": {"code": "HARNESS_RESTARTED", "request_id": rid}}), rid))

    def claim(self, rid: str, fingerprint: str):
        with self.lock, self.db:
            row = self.db.execute("SELECT fingerprint,state,status,body FROM requests WHERE request_id=?", (rid,)).fetchone()
            if row:
                if row[0] != fingerprint:
                    return "CONFLICT", None
                return row[1], (row[2], row[3])
            self.db.execute("INSERT INTO requests VALUES(?,?,'RUNNING',NULL,NULL)", (rid, fingerprint))
            return "NEW", None

    def event(self, rid: str, kind: str, payload: dict):
        with self.lock, self.db:
            self.db.execute("INSERT INTO events(request_id,event_type,event_at_ms,payload) VALUES(?,?,?,?)",
                (rid, kind, int(time.time()*1000), json.dumps(payload, ensure_ascii=False, separators=(",", ":"))))

    def finish(self, rid: str, status: int, body: str):
        with self.lock, self.db:
            self.db.execute("UPDATE requests SET state='DONE',status=?,body=? WHERE request_id=?", (status, body, rid))

    def close(self):
        self.db.close()
        fcntl.flock(self.lock_file, fcntl.LOCK_UN)
        self.lock_file.close()
