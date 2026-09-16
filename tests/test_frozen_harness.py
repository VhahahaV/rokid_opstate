from __future__ import annotations

import asyncio
import copy
import hashlib
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

import httpx
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "code/server-contract"))
from frozen_harness import create_app
from model_provider import ModelProfile, request_body
from harness_store import HarnessStore


class FrozenHarnessTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "harness.sqlite3"
        self.headers = {"Authorization": "Bearer fixture-harness-access-token"}
        self.jpeg = b"\xff\xd8explicit-wire-test-fixture\xff\xd9"
        self.calls = []
        self.mode = "valid"
        self.label = "SERVICE"
        self.profile = ModelProfile("fixture-profile", "fixture-model", "https://model.example/v1/responses", "FIXTURE_KEY",
                                    "responses", {"max_output_tokens": 512, "reasoning_effort": "high"}, timeout_ms=1000)

    async def upstream(self, request):
        body = json.loads(request.content)
        self.calls.append(body)
        if self.mode == "slow":
            await asyncio.sleep(2)
        if self.mode == "retry" and len(self.calls) == 1:
            return httpx.Response(503, json={"error": "fixture"})
        value = {"decision": self.label, "service": {"mode": "PREPARATION", "title": "Ready", "body": "Prepare the equipment."} if self.label == "SERVICE" else None,
                 "evidence_refs": ["obs-image"]}
        if self.mode == "future":
            value["evidence_refs"] = ["future-observation"]
        if self.mode == "mismatch":
            value.update(decision="NO_SERVICE", service={"mode": "PREPARATION", "title": "bad", "body": "bad"})
        text = "{" if self.mode == "malformed" else json.dumps(value)
        raw = {"model": self.profile.model_id, "status": "completed", "usage": {"input_tokens": 20, "output_tokens": 12},
               "output": [{"type": "message", "content": [{"type": "output_text", "text": text}]}]}
        if self.mode == "incomplete":
            raw["status"] = "incomplete"
        return httpx.Response(200, json=raw)

    def app(self):
        return create_app(self.profile, api_key="fixture-provider-key", bearer_token="fixture-harness-access-token",
                          database=self.path, upstream_transport=httpx.MockTransport(self.upstream))

    def input(self, client, request_id="request-fixture"):
        contract = client.get("/v1/contract", headers=self.headers).json()
        data = json.loads((ROOT / "tests/fixtures/sample_inference_request.json").read_text())
        data.update({k: contract[k] for k in ("protocol_version", "model_id", "prompt_hash", "preprocess_hash", "parser_hash")})
        data.update(request_id=request_id, current_asset_id="asset-fixture", current_image_sha256=hashlib.sha256(self.jpeg).hexdigest())
        at = data["anchor_time_epoch_ms"]
        data["observations"] = [{"observation_id": "obs-image", "kind": "IMAGE", "occurred_at_epoch_ms": at,
            "available_at_epoch_ms": at, "content": "Current test image", "asset_id": "asset-fixture", "episode_handle": None, "material_revision": None}]
        data["receipts"] = []
        return data

    def post(self, client, data):
        return client.post("/v1/evaluate", data={"request": json.dumps(data)}, files={"image": ("image.jpg", self.jpeg)},
                           headers={**self.headers, "Idempotency-Key": data["request_id"]})

    def events(self):
        with sqlite3.connect(self.path) as database:
            return [(kind, json.loads(payload)) for kind, payload in database.execute("SELECT event_type,payload FROM events ORDER BY sequence")]

    def test_actual_wire_payload_raw_before_parse_and_durable_retry(self):
        with TestClient(self.app()) as client:
            data = self.input(client)
            response = self.post(client, data)
            self.assertEqual(response.status_code, 200, response.text)
            self.assertEqual(self.post(client, data).text, response.text)
            self.assertEqual(len(self.calls), 1)
            payload = self.calls[0]
            self.assertEqual(payload["reasoning"], {"effort": "high"})
            self.assertNotIn("temperature", payload)
            self.assertFalse(payload["store"])
            self.assertEqual(payload["text"]["format"]["type"], "json_schema")
            text = json.loads(payload["input"][0]["content"][0]["text"])
            self.assertEqual(text["visible_evidence_ids"], ["obs-image"])
            self.assertNotIn("prompt_hash", text)
            changed = copy.deepcopy(data)
            changed["observations"][0]["content"] = "changed"
            self.assertEqual(self.post(client, changed).status_code, 409)
        with TestClient(self.app()) as client:
            self.assertEqual(self.post(client, data).text, response.text)
            self.assertEqual(len(self.calls), 1)
        kinds = [kind for kind, _ in self.events()]
        self.assertLess(kinds.index("RAW_PROVIDER_RESPONSE"), kinds.index("MODEL_DECISION"))
        self.assertIn("PROVIDER_USAGE", kinds)

    def test_invalid_outputs_and_timeout_are_technical_failures(self):
        with TestClient(self.app()) as client:
            for mode, code in [("future", "INVALID_EVIDENCE"), ("mismatch", "DECISION_SERVICE_MISMATCH"),
                               ("malformed", "MODEL_OUTPUT_INVALID"), ("incomplete", "PROVIDER_RESPONSE_INCOMPLETE"), ("slow", "PROVIDER_TIMEOUT")]:
                with self.subTest(mode=mode):
                    self.mode = mode
                    response = self.post(client, self.input(client, "request-" + mode))
                    self.assertIn(response.status_code, {502, 504}, response.text)
                    self.assertEqual(response.json()["detail"]["code"], code)
                    self.assertNotIn("decision", response.json())
            self.assertEqual(sum(kind == "MODEL_DECISION" for kind, _ in self.events()), 0)

    def test_auth_frozen_config_and_future_inputs_stop_before_model(self):
        with TestClient(self.app()) as client:
            self.assertEqual(client.get("/v1/contract").status_code, 401)
            data = self.input(client)
            data["observations"][0]["available_at_epoch_ms"] += 1
            self.assertEqual(self.post(client, data).status_code, 422)
            data = self.input(client)
            data["gold"] = "forbidden"
            self.assertEqual(self.post(client, data).status_code, 422)
            data = self.input(client)
            data["parser_hash"] = "wrong"
            self.assertEqual(self.post(client, data).status_code, 409)
            self.assertFalse(self.calls)

    def test_no_service_null_and_bounded_transport_retry(self):
        self.label = "NO_SERVICE"
        self.mode = "retry"
        with TestClient(self.app()) as client:
            response = self.post(client, self.input(client))
            self.assertEqual(response.status_code, 200, response.text)
            self.assertIsNone(response.json()["service"])
            self.assertEqual(len(self.calls), 2)
            raw = [payload for kind, payload in self.events() if kind == "RAW_PROVIDER_RESPONSE"]
            self.assertEqual([r["http_status"] for r in raw], [503, 200])

    def test_chat_payload_and_profile_are_explicitly_frozen(self):
        parameters = {"max_output_tokens": 512, "temperature": 0, "top_p": 1, "seed": 7, "enable_thinking": False}
        profile = ModelProfile("chat", "model", "https://model.example/v1/chat/completions", "KEY", "chat_completions", parameters)
        parameters["temperature"] = 1
        body = request_body(profile, "prompt", "context", self.jpeg, {"type": "object"})
        self.assertEqual(body["temperature"], 0)
        self.assertEqual(body["max_tokens"], 512)
        self.assertFalse(body["enable_thinking"])
        self.assertEqual(body["response_format"]["type"], "json_schema")
        self.assertNotIn("reasoning", body)
        with self.assertRaises(TypeError):
            profile.parameters["temperature"] = 1

    def test_recovery_never_reexecutes_an_incomplete_paid_request(self):
        store = HarnessStore(self.path)
        self.assertEqual(store.claim("unfinished", "fingerprint")[0], "NEW")
        with self.assertRaises(RuntimeError):
            HarnessStore(self.path)
        store.close()
        recovered = HarnessStore(self.path)
        try:
            state, cached = recovered.claim("unfinished", "fingerprint")
            self.assertEqual(state, "DONE")
            self.assertEqual(cached[0], 503)
            self.assertEqual(json.loads(cached[1])["detail"]["code"], "HARNESS_RESTARTED")
        finally:
            recovered.close()


if __name__ == "__main__":
    unittest.main()
