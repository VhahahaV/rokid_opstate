"""Frozen V2 model gateway. Launch with uvicorn frozen_harness:app --factory --workers 1."""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import time
import uuid

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import Response
from jsonschema import Draft202012Validator
from referencing import Registry, Resource
from starlette.datastructures import UploadFile
import httpx

from harness_store import HarnessStore
from model_provider import ModelProfile, ProviderFailure, output_text, request_body

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def sha(value: bytes):
    return hashlib.sha256(value).hexdigest()


def strict_json(value):
    def pairs(items):
        result = {}
        for key, item in items:
            if key in result:
                raise ValueError("duplicate JSON key")
            result[key] = item
        return result
    def constant(_):
        raise ValueError("non-finite JSON value")
    return json.loads(value, object_pairs_hook=pairs, parse_constant=constant)


def create_app(profile: ModelProfile, *, api_key: str, bearer_token: str, database: Path, upstream_transport=None):
    if not api_key or len(bearer_token) < 16:
        raise ValueError("provider API key and a Harness token of at least 16 characters are required")
    registry = Registry()
    for path in (ROOT / "schemas").glob("*.schema.json"):
        document = json.loads(path.read_text())
        registry = registry.with_resource(document["$id"], Resource.from_contents(document))
    request_schema = ROOT / "schemas/inference_request_v2.schema.json"
    response_schema = ROOT / "schemas/inference_response_v2.schema.json"
    request_validator = Draft202012Validator(json.loads(request_schema.read_text()), registry=registry)
    response_validator = Draft202012Validator(json.loads(response_schema.read_text()), registry=registry)
    schema = json.loads((HERE / "model_output.schema.json").read_text())
    model_validator = Draft202012Validator(schema)
    prompt = (HERE / "frozen_decision_prompt.md").read_text() + "\nWrite service titles and bodies in " + profile.response_language + ".\n"
    public = profile.public()
    model_config_hash = sha(canonical(public).encode())
    preprocess = {"version": "rokid-v5-causal-full-history-v1", "image": "exact-jpeg-bytes-high-detail",
        "history": "all supplied causal observations and committed receipts; reject future or oversize input; no truncation",
        "model_config_sha256": model_config_hash, "model_schema_sha256": sha(canonical(schema).encode())}
    contract = {"protocol_version": "opstate-online-v2", "model_id": profile.model_id,
        "prompt_hash": sha(prompt.encode()), "preprocess_hash": sha(canonical(preprocess).encode()),
        "parser_hash": sha(response_schema.read_bytes() + Path(__file__).read_bytes() + (HERE / "model_provider.py").read_bytes()),
        "request_schema_sha256": sha(request_schema.read_bytes()), "response_schema_sha256": sha(response_schema.read_bytes()),
        "mock": False, "mock_mode": None, "not_for_paper": True, "strict_contract": True,
        "model_config": {**public, "config_sha256": model_config_hash}}
    contract_hash = sha(canonical(contract).encode())
    pending = {}
    semaphore = asyncio.Semaphore(1)

    @asynccontextmanager
    async def lifespan(application):
        store = HarnessStore(database)
        application.state.store = store
        store.event("__service__", "FROZEN_CONFIGURATION", {"contract": contract, "contract_sha256": contract_hash})
        async with httpx.AsyncClient(transport=upstream_transport, timeout=profile.timeout_ms/1000,
                                    follow_redirects=False, trust_env=False) as client:
            application.state.client = client
            try:
                yield
            finally:
                jobs = list(pending.values())
                for job in jobs:
                    job.cancel()
                if jobs:
                    await asyncio.gather(*jobs, return_exceptions=True)
                store.close()

    application = FastAPI(title="OPSTATE Frozen Model Harness", lifespan=lifespan)
    application.state.contract = contract

    def authorize(value):
        if not secrets.compare_digest(value or "", "Bearer " + bearer_token):
            raise HTTPException(401, "invalid Harness token", headers={"WWW-Authenticate": "Bearer"})

    @application.get("/v1/health")
    async def health():
        return {"ok": True, "model_id": profile.model_id, "mock": False}

    @application.get("/v1/contract")
    async def frozen_contract(authorization: str = Header(default="")):
        authorize(authorization)
        return contract

    async def infer(data, jpeg, visible, text):
        rid = data["request_id"]
        store = application.state.store
        started = time.monotonic()
        status, body = 503, canonical({"detail": {"code": "HARNESS_CANCELLED", "request_id": rid}})
        try:
            async def invoke():
                async with semaphore:
                    payload = request_body(profile, prompt, text, jpeg, schema)
                    store.event(rid, "MODEL_INPUT", {"anchor_id": data["anchor_id"], "model_id": profile.model_id,
                        "contract_sha256": contract_hash, "input_sha256": sha(text.encode()), "image_sha256": sha(jpeg),
                        "visible_evidence_ids": sorted(visible), "prompt_hash": contract["prompt_hash"]})
                    for attempt in range(profile.transport_retries + 1):
                        try:
                            async with application.state.client.stream("POST", profile.endpoint,
                                    headers={"Authorization": "Bearer " + api_key, "X-Request-ID": rid}, json=payload) as response:
                                chunks = bytearray()
                                async for chunk in response.aiter_bytes():
                                    chunks.extend(chunk)
                                    if len(chunks) > 1024 * 1024:
                                        raise ProviderFailure("PROVIDER_RESPONSE_TOO_LARGE")
                                raw_text = bytes(chunks).decode("utf-8")
                                # Preserve every received body before inspecting status, parsing, or returning a decision.
                                stored = raw_text.replace(api_key, "[REDACTED]")
                                store.event(rid, "RAW_PROVIDER_RESPONSE", {"attempt": attempt, "http_status": response.status_code,
                                    "body": stored, "raw_sha256": sha(bytes(chunks)), "stored_sha256": sha(stored.encode()), "redacted": stored != raw_text,
                                    "latency_ms": round((time.monotonic()-started)*1000, 3)})
                                if response.status_code in {429, 500, 502, 503, 504} and attempt < profile.transport_retries:
                                    await asyncio.sleep(0.2)
                                    continue
                                if response.status_code != 200:
                                    raise ProviderFailure("PROVIDER_HTTP_" + str(response.status_code))
                                raw = strict_json(raw_text)
                                store.event(rid, "PROVIDER_USAGE", {"model_id": raw.get("model"), "usage": raw.get("usage")})
                                if raw.get("model") and raw["model"] != profile.model_id:
                                    raise ProviderFailure("PROVIDER_MODEL_MISMATCH")
                                parsed = strict_json(output_text(profile, raw))
                                model_validator.validate(parsed)
                                refs = parsed["evidence_refs"]
                                if len(refs) != len(set(refs)) or not set(refs) <= visible:
                                    raise ProviderFailure("INVALID_EVIDENCE")
                                if (parsed["decision"] == "NO_SERVICE") != (parsed["service"] is None):
                                    raise ProviderFailure("DECISION_SERVICE_MISMATCH")
                                now = int(time.time()*1000)
                                result = {"request_id": rid, "decision_id": "decision-" + str(uuid.uuid4()), **parsed,
                                    "produced_at_epoch_ms": now, "expires_at_epoch_ms": now + profile.decision_ttl_ms}
                                response_validator.validate(result)
                                return result
                        except (httpx.ConnectError, httpx.ConnectTimeout):
                            store.event(rid, "TRANSPORT_RETRY", {"attempt": attempt, "code": "PROVIDER_CONNECT_FAILED"})
                            if attempt >= profile.transport_retries:
                                raise ProviderFailure("PROVIDER_CONNECT_FAILED")
                            await asyncio.sleep(0.2)
            result = await asyncio.wait_for(invoke(), profile.timeout_ms/1000)
            status, body = 200, canonical(result)
            store.event(rid, "MODEL_DECISION", {"decision": result, "latency_ms": round((time.monotonic()-started)*1000, 3), "parser_result": "VALID"})
        except asyncio.CancelledError:
            store.event(rid, "TECHNICAL_FAILURE", {"code": "HARNESS_CANCELLED"})
            raise
        except Exception as error:
            code = error.code if isinstance(error, ProviderFailure) else "PROVIDER_TIMEOUT" if isinstance(error, (asyncio.TimeoutError, httpx.TimeoutException)) else "MODEL_OUTPUT_INVALID"
            status = 504 if code == "PROVIDER_TIMEOUT" else 502
            body = canonical({"detail": {"code": code, "request_id": rid}})
            store.event(rid, "TECHNICAL_FAILURE", {"code": code, "error_type": type(error).__name__})
        finally:
            store.finish(rid, status, body)
        return status, body

    @application.post("/v1/evaluate")
    async def evaluate(request: Request, authorization: str = Header(default=""), idempotency_key: str = Header(default="", alias="Idempotency-Key")):
        authorize(authorization)
        async with request.form(max_files=1, max_fields=1, max_part_size=8*1024*1024) as form:
            source, image = form.get("request"), form.get("image")
            if not isinstance(source, str) or not isinstance(image, UploadFile) or len(form.multi_items()) != 2:
                raise HTTPException(422, "expected request text and image file")
            jpeg = await image.read(8*1024*1024+1)
        if len(jpeg) > 8*1024*1024:
            raise HTTPException(413, "image exceeds 8 MiB")
        if not jpeg.startswith(b"\xff\xd8") or not jpeg.endswith(b"\xff\xd9"):
            raise HTTPException(422, "expected JPEG image bytes")
        try:
            data = strict_json(source)
            request_validator.validate(data)
        except Exception:
            raise HTTPException(422, "invalid V2 request")
        rid = data["request_id"]
        if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,160}", rid) or idempotency_key != rid:
            raise HTTPException(400, "Idempotency-Key must match a valid request_id")
        mismatches = [key for key in ("protocol_version", "model_id", "prompt_hash", "preprocess_hash", "parser_hash") if data.get(key) != contract[key]]
        if mismatches:
            raise HTTPException(409, {"code": "FROZEN_CONTRACT_MISMATCH", "fields": mismatches})
        if data["current_image_sha256"].lower() != sha(jpeg):
            raise HTTPException(400, "image hash mismatch")
        at = data["anchor_time_epoch_ms"]
        obs, receipts = data["observations"], data["receipts"]
        ids = [o["observation_id"] for o in obs] + [r["receipt_id"] for r in receipts]
        if len(ids) != len(set(ids)) or any(o["occurred_at_epoch_ms"] > o["available_at_epoch_ms"] or o["available_at_epoch_ms"] > at for o in obs):
            raise HTTPException(422, "non-causal or duplicate observation/receipt IDs")
        if any(r["visible_at_epoch_ms"] > at or r.get("visible_ack_frame_count", 0) < 2 for r in receipts):
            raise HTTPException(422, "receipt requires a causal visible ACK")
        if not any(o.get("asset_id") == data["current_asset_id"] and o["kind"] == "IMAGE" for o in obs):
            raise HTTPException(422, "current image requires a causal observation")
        text = canonical({"anchor_id": data["anchor_id"], "anchor_time_epoch_ms": at, "current_asset_id": data["current_asset_id"],
            "candidate": data["candidate"], "observations": obs, "receipts": receipts, "visible_evidence_ids": sorted(ids)})
        if len(text.encode()) > profile.input_budget_bytes:
            raise HTTPException(413, {"code": "MODEL_INPUT_BUDGET_EXCEEDED", "limit_bytes": profile.input_budget_bytes})
        fingerprint = sha(contract_hash.encode() + canonical(data).encode() + jpeg)
        state, cached = application.state.store.claim(rid, fingerprint)
        if state == "CONFLICT":
            raise HTTPException(409, "request_id already used with different input/configuration")
        if state == "DONE":
            return Response(cached[1], status_code=cached[0], media_type="application/json")
        if state == "NEW":
            job = asyncio.create_task(infer(data, jpeg, set(ids), text))
            pending[rid] = job
            job.add_done_callback(lambda finished: pending.pop(rid, None))
        job = pending.get(rid)
        if job is None:
            raise HTTPException(503, {"code": "INCOMPLETE_REQUEST_STATE"})
        status, body = await asyncio.shield(job)
        return Response(body, status_code=status, media_type="application/json")

    return application


def app():
    profile = ModelProfile.load(Path(os.environ["OPSTATE_FROZEN_CONFIG"]))
    return create_app(profile, api_key=os.environ[profile.api_key_env], bearer_token=os.environ["OPSTATE_HARNESS_TOKEN"],
        database=Path(os.environ["OPSTATE_HARNESS_DB"]))
