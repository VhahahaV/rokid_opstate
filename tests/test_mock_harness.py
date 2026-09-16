from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import unittest
import sys
from pathlib import Path

from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("mock_harness", ROOT / "code/server-contract/mock_harness.py")
harness = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = harness
spec.loader.exec_module(harness)


class MockHarnessTests(unittest.TestCase):
    def setUp(self):
        harness.seen.clear()
        self.client = TestClient(harness.app)
        self.addCleanup(self.client.close)
        self.image = b"local-contract-test-image"
        self.request = json.loads((ROOT / "tests/fixtures/sample_inference_request.json").read_text())
        self.request["current_image_sha256"] = hashlib.sha256(self.image).hexdigest()

    def post(self, data=None, image=None):
        data = self.request if data is None else data
        return self.client.post(
            "/v1/evaluate", data={"request": json.dumps(data)},
            files={"image": ("current.jpg", self.image if image is None else image, "image/jpeg")},
            headers={"Idempotency-Key": data.get("request_id", "missing")},
        )

    def test_health(self):
        self.assertEqual(self.client.get("/health").json(), {"ok": True, "model_id": "mock-not-for-paper"})

    def test_service_and_retry_match_response_schema(self):
        response = self.post()
        self.assertEqual(response.status_code, 200, response.text)
        data = response.json()
        Draft202012Validator(json.loads((ROOT / "schemas/inference_response.schema.json").read_text())).validate(data)
        self.assertEqual(data["decision"], "SERVICE")
        self.assertEqual(data["evidence_observation_ids"], ["obs-message-move"])
        self.assertEqual(self.post().json(), data)

    def test_no_service_omits_how(self):
        self.request["observations"] = []
        response = self.post()
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["decision"], "NO_SERVICE")
        self.assertTrue({"title", "body", "service_mode"}.isdisjoint(data))

    def test_malformed_json_is_client_error(self):
        response = self.client.post("/v1/evaluate", data={"request": "{"}, files={"image": ("current.jpg", self.image)})
        self.assertEqual(response.status_code, 400)

    def test_invalid_contract_is_client_error(self):
        for data in ({}, [], dict(self.request, gold="must not enter model request")):
            with self.subTest(data=data):
                response = self.client.post("/v1/evaluate", data={"request": json.dumps(data)}, files={"image": ("current.jpg", self.image)})
                self.assertEqual(response.status_code, 422)

    def test_hash_is_checked_even_on_retry(self):
        self.assertEqual(self.post().status_code, 200)
        self.assertEqual(self.post(image=b"different").status_code, 400)

    def test_reusing_request_id_for_different_data_is_rejected(self):
        self.assertEqual(self.post().status_code, 200)
        changed = copy.deepcopy(self.request)
        changed["observations"] = []
        self.assertEqual(self.post(changed).status_code, 409)


if __name__ == "__main__":
    unittest.main()
