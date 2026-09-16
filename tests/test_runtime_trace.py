import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class RuntimeTraceTests(unittest.TestCase):
    def validate(self, path):
        return subprocess.run([sys.executable, str(ROOT / "tools/validate_runtime_trace.py"), str(path)], capture_output=True, text=True)

    def test_missing_export_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.validate(Path(directory) / "missing")
            self.assertEqual(result.returncode, 1)
            self.assertEqual(json.loads(result.stdout)["status"], "FAIL")

    def test_fixture_passes_and_future_evidence_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "session"
            shutil.copytree(ROOT / "tests/fixtures/export_session", target)
            self.assertEqual(self.validate(target).returncode, 0)
            path = target / "observations.jsonl"
            observation = json.loads(path.read_text())
            observation["available_at_epoch_ms"] = 9999999999999
            path.write_text(json.dumps(observation) + "\n")
            result = self.validate(target)
            self.assertEqual(result.returncode, 1)
            self.assertIn("future evidence", result.stdout)

    def test_malformed_json_reports_file_and_line(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "session"
            shutil.copytree(ROOT / "tests/fixtures/export_session", target)
            (target / "observations.jsonl").write_text("{bad json}\n")
            result = self.validate(target)
            self.assertEqual(result.returncode, 1)
            self.assertIn("observations.jsonl:1", result.stdout)


if __name__ == "__main__":
    unittest.main()
