"""Deterministic integration harness. Mock decisions are never model-quality evidence."""
from __future__ import annotations

import asyncio
import hashlib
import json
import os
import secrets
import time
import threading
from concurrent.futures import Future
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, Header, HTTPException, Request
from starlette.datastructures import UploadFile
from fastapi.responses import Response
from jsonschema import Draft202012Validator
from referencing import Registry, Resource

ROOT=Path(__file__).resolve().parents[2]
SCHEMAS=ROOT/"schemas"
registry=Registry()
for path in SCHEMAS.glob("*.schema.json"):
    schema=json.loads(path.read_text())
    registry=registry.with_resource(schema["$id"],Resource.from_contents(schema))
request_validator=Draft202012Validator(json.loads((SCHEMAS/"inference_request.schema.json").read_text()),registry=registry)
request_validator_v2=Draft202012Validator(json.loads((SCHEMAS/"inference_request_v2.schema.json").read_text()),registry=registry)
response_validator_v2=Draft202012Validator(json.loads((SCHEMAS/"inference_response_v2.schema.json").read_text()),registry=registry)
response_validator=Draft202012Validator(json.loads((SCHEMAS/"inference_response.schema.json").read_text()),registry=registry)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


@dataclass(frozen=True)
class MockOptions:
    mode: str="heuristic"
    delay_ms: int=0
    ttl_ms: int=20_000
    script: tuple[str,...]=("NO_SERVICE","SERVICE","SERVICE","SERVICE")
    dataset: Optional[Path]=None
    log_path: Optional[Path]=None
    bearer_token: Optional[str]=None
    strict_contract: bool=False

    @classmethod
    def environment(cls):
        return cls(mode=os.getenv("OPSTATE_MOCK_MODE","heuristic"),delay_ms=int(os.getenv("OPSTATE_MOCK_DELAY_MS","0")),
            ttl_ms=int(os.getenv("OPSTATE_MOCK_TTL_MS","20000")),script=tuple(os.getenv("OPSTATE_MOCK_SCRIPT","NO_SERVICE,SERVICE,SERVICE,SERVICE").split(",")),
            dataset=Path(os.environ["OPSTATE_MOCK_DATASET"]) if os.getenv("OPSTATE_MOCK_DATASET") else None,
            log_path=Path(os.environ["OPSTATE_HARNESS_LOG"]) if os.getenv("OPSTATE_HARNESS_LOG") else None,
            bearer_token=os.getenv("OPSTATE_HARNESS_TOKEN"),strict_contract=os.getenv("OPSTATE_STRICT_CONTRACT","0")=="1")


@dataclass(frozen=True)
class Reply:
    body: str
    status: int=200

    def response(self):
        return Response(self.body,status_code=self.status,media_type="application/json")


def create_app(options: Optional[MockOptions]=None) -> FastAPI:
    options=options or MockOptions.environment()
    modes={"heuristic","always_service","always_no_service","scripted","delay","malformed","timeout","expired","dataset"}
    if options.mode not in modes or not options.script or any(label not in {"SERVICE","NO_SERVICE"} for label in options.script):
        raise ValueError("invalid mock policy")
    if options.delay_ms<0 or options.ttl_ms<=0: raise ValueError("invalid timing policy")
    gold={}; how={}
    if options.mode=="dataset":
        if options.dataset is None: raise ValueError("dataset mode requires OPSTATE_MOCK_DATASET")
        manifests=list(options.dataset.rglob("RELEASE_MANIFEST.json"))
        if len(manifests)!=1: raise ValueError("expected one verified dataset release")
        release=manifests[0].parent
        gold={row["anchor_id"]:row for row in map(json.loads,(release/"evaluator/service_gold.jsonl").read_text().splitlines())}
        how={row["anchor_id"]:row for row in map(json.loads,(release/"evaluator/how_gold.jsonl").read_text().splitlines())}
    model_id="mock-january-v10-r6" if options.mode=="dataset" else "mock-not-for-paper"
    preprocess={"version":"jpeg-byte-preserving-v1","input":"exact JPEG bytes","history":"available_at <= anchor_time","receipts":"committed visible ACKs only"}
    contract={"protocol_version":"opstate-online-v2","model_id":model_id,
        "prompt_hash":sha((ROOT/"code/server-contract/decision_prompt.md").read_bytes()),
        "preprocess_hash":sha(json.dumps(preprocess,sort_keys=True,separators=(",",":")).encode()),
        "parser_hash":sha((SCHEMAS/"inference_response_v2.schema.json").read_bytes()+b"|strict-temporal-causal-v2"),
        "request_schema_sha256":sha((SCHEMAS/"inference_request_v2.schema.json").read_bytes()),
        "response_schema_sha256":sha((SCHEMAS/"inference_response_v2.schema.json").read_bytes()),
        "mock":True,"mock_mode":options.mode,"not_for_paper":True,"strict_contract":options.strict_contract}
    app=FastAPI(title="OPSTATE Frozen Harness — integration mock")
    app.state.seen={}
    app.state.pending={}
    app.state.counter=0
    app.state.contract=contract
    lock=threading.Lock()

    def log(entry):
        if options.log_path:
            options.log_path.parent.mkdir(parents=True,exist_ok=True)
            with options.log_path.open("a",encoding="utf-8") as out:
                out.write(json.dumps(entry,ensure_ascii=False,separators=(",",":"))+"\n")

    @app.get("/health")
    @app.get("/v1/health")
    def health():
        return {"ok":True,"model_id":model_id}

    @app.get("/v1/contract")
    def frozen_contract():
        return contract

    @app.post("/v1/evaluate",openapi_extra={"requestBody":{"required":True,"content":{"multipart/form-data":{"schema":{
        "type":"object","required":["request","image"],"properties":{"request":{"type":"string"},"image":{"type":"string","format":"binary"}}}}}}})
    async def evaluate(http_request: Request,
        idempotency_key: Optional[str]=Header(default=None,alias="Idempotency-Key"),
        authorization: Optional[str]=Header(default=None,alias="Authorization")):
        if options.bearer_token and not secrets.compare_digest(authorization or "","Bearer "+options.bearer_token):
            raise HTTPException(401,"invalid bearer token",headers={"WWW-Authenticate":"Bearer"})
        async with http_request.form(max_files=1,max_fields=1,max_part_size=8*1024*1024) as form:
            request=form.get("request")
            image=form.get("image")
            if not isinstance(request,str) or not isinstance(image,UploadFile) or set(form.keys())!={"request","image"}:
                raise HTTPException(422,"request must be a text form field and image must be a file")
            image_bytes=await image.read(8*1024*1024+1)
        try: data=json.loads(request)
        except ValueError: raise HTTPException(400,"request must contain valid JSON")
        validator=request_validator_v2 if isinstance(data,dict) and data.get("protocol_version")=="opstate-online-v2" else request_validator
        errors=list(validator.iter_errors(data))
        if errors: raise HTTPException(422,[{"path":list(error.path),"message":error.message} for error in errors])
        key=idempotency_key or data["request_id"]
        if key!=data["request_id"]: raise HTTPException(400,"Idempotency-Key must match request_id")
        if len(image_bytes)>8*1024*1024: raise HTTPException(413,"image exceeds 8 MiB")
        image_hash=sha(image_bytes)
        if data["current_image_sha256"].lower()!=image_hash: raise HTTPException(400,"image hash mismatch")
        if options.strict_contract:
            mismatches=[name for name in ("protocol_version","model_id","prompt_hash","preprocess_hash","parser_hash") if data.get(name)!=contract[name]]
            if mismatches: raise HTTPException(409,{"code":"FROZEN_CONTRACT_MISMATCH","fields":mismatches})
        canonical=json.dumps(data,ensure_ascii=False,sort_keys=True,separators=(",",":"))
        fingerprint=sha(canonical.encode()+image_bytes)
        with lock:
            if key in app.state.seen:
                previous,reply=app.state.seen[key]
                if previous!=fingerprint: raise HTTPException(409,"request_id already used for a different payload")
                return reply.response()
            if key in app.state.pending:
                previous,future=app.state.pending[key]
                if previous!=fingerprint: raise HTTPException(409,"request_id already in flight with a different payload")
                owner=False
            else:
                future=Future()
                app.state.pending[key]=(fingerprint,future)
                index=app.state.counter; app.state.counter+=1
                owner=True
        if not owner: return (await asyncio.shield(asyncio.wrap_future(future))).response()
        started=time.monotonic()
        try:
            delay=options.delay_ms
            if options.mode=="timeout": delay=max(delay,60_000)
            if options.mode=="delay": delay=max(delay,250)
            if delay: await asyncio.sleep(delay/1000)
            visible={row["observation_id"] for row in data["observations"] if row["available_at_epoch_ms"]<=data["anchor_time_epoch_ms"]}
            label="SERVICE" if any("moved" in row.get("content","").lower() for row in data["observations"]) else "NO_SERVICE"
            if options.mode in {"always_service","delay","expired"}: label="SERVICE"
            if options.mode=="always_no_service": label="NO_SERVICE"
            if options.mode=="scripted": label=options.script[index%len(options.script)]
            reference=None
            if options.mode=="dataset":
                reference=gold.get(data["anchor_id"])
                if reference is None: raise HTTPException(422,"anchor is not in the supplied dataset")
                label=reference["decision_gold"]
            now=int(time.time()*1000)
            body={"request_id":data["request_id"],"decision_id":"decision-"+str(uuid.uuid4()),"decision":label,
                "evidence_observation_ids":[],"produced_at_epoch_ms":now,"expires_at_epoch_ms":now+options.ttl_ms}
            if options.mode=="expired": body.update(produced_at_epoch_ms=now-20_000,expires_at_epoch_ms=now-1)
            if label=="SERVICE":
                body.update(service_mode="PREPARATION",title="Check the updated destination",body="A relevant destination changed. Review it before leaving.")
                body["evidence_observation_ids"]=[row["observation_id"] for row in data["observations"] if "moved" in row.get("content","").lower() and row["observation_id"] in visible]
                if reference:
                    how_row=how[data["anchor_id"]]
                    body.update(service_mode=how_row["primary_mode"],title={"AWARENESS":"Progress update","DECISION":"A choice to review","PREPARATION":"Prepare the next step","EXECUTION":"Ready to use"}[how_row["primary_mode"]],body=how_row["glance_text"])
                    refs=reference.get("decision_gates",{}).get("visible_evidence",{}).get("source_ids",[])
                    body["evidence_observation_ids"]=sorted(set(refs)&visible)
            response_validator.validate(body)
            if data["protocol_version"]=="opstate-online-v2":
                body={"request_id":body["request_id"],"decision_id":body["decision_id"],"decision":body["decision"],"confidence":1.0,
                    "service":{"mode":body["service_mode"],"title":body["title"],"body":body["body"]} if label=="SERVICE" else None,
                    "evidence_refs":body["evidence_observation_ids"],"produced_at_epoch_ms":body["produced_at_epoch_ms"],"expires_at_epoch_ms":body["expires_at_epoch_ms"]}
                response_validator_v2.validate(body)
            reply=Reply('{"decision":' if options.mode=="malformed" else json.dumps(body,ensure_ascii=False,separators=(",",":")))
            log({"request_id":key,"anchor_id":data["anchor_id"],"model_id":model_id,"mock_mode":options.mode,
                "prompt_hash":data["prompt_hash"],"request_sha256":sha(canonical.encode()),"image_sha256":image_hash,
                "latency_ms":round((time.monotonic()-started)*1000,3),"raw_output":reply.body,
                "parser_result":"INTENTIONALLY_MALFORMED" if options.mode=="malformed" else "VALID","token_usage":None})
            with lock:
                app.state.seen[key]=(fingerprint,reply)
                if not future.done(): future.set_result(reply)
            return reply.response()
        except BaseException:
            if not future.done(): future.cancel()
            raise
        finally:
            with lock: app.state.pending.pop(key,None)
    return app


app=create_app()
seen=app.state.seen
