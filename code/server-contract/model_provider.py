"""One immutable model profile per harness process; no provider selection from inference input."""
from __future__ import annotations

import base64
from dataclasses import dataclass, field
import json
from pathlib import Path
from types import MappingProxyType
from urllib.parse import urlparse


class ProviderFailure(Exception):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


@dataclass(frozen=True)
class ModelProfile:
    profile_id: str
    model_id: str
    endpoint: str
    api_key_env: str
    wire_api: str
    parameters: dict = field(default_factory=dict)
    response_language: str = "zh-CN"
    timeout_ms: int = 11000
    decision_ttl_ms: int = 20000
    transport_retries: int = 1
    input_budget_bytes: int = 2 * 1024 * 1024

    def __post_init__(self):
        object.__setattr__(self, "parameters", MappingProxyType(dict(self.parameters)))
        endpoint = urlparse(self.endpoint)
        if endpoint.username or endpoint.password or endpoint.query or endpoint.fragment:
            raise ValueError("endpoint must not contain credentials, query or fragment")
        if endpoint.scheme != "https" and not (endpoint.scheme == "http" and endpoint.hostname in {"127.0.0.1", "localhost"}):
            raise ValueError("provider endpoint must use HTTPS (loopback test server excepted)")
        allowed = {"max_output_tokens", "reasoning_effort"} if self.wire_api == "responses" else {"max_output_tokens", "temperature", "top_p", "seed", "enable_thinking"}
        if self.wire_api not in {"responses", "chat_completions"} or not self.parameters.keys() <= allowed:
            raise ValueError("invalid wire API or parameters; only effective parameters may be configured")
        if not self.model_id or not self.profile_id or not self.api_key_env:
            raise ValueError("model profile identity is required")
        if not 100 <= self.timeout_ms <= 12000 or not 0 <= self.transport_retries <= 1 or not 1 <= self.decision_ttl_ms <= 60000:
            raise ValueError("invalid timeout, retry or decision TTL")
        if not 1024 <= self.input_budget_bytes <= 8 * 1024 * 1024:
            raise ValueError("invalid input byte budget")
        if not isinstance(self.parameters.get("max_output_tokens"), int) or not 64 <= self.parameters["max_output_tokens"] <= 16384:
            raise ValueError("max_output_tokens must be 64–16384")

    @classmethod
    def load(cls, path: Path):
        return cls(**json.loads(path.read_text()))

    def public(self):
        return {"profile_id": self.profile_id, "model_id": self.model_id, "endpoint": self.endpoint,
                "wire_api": self.wire_api, "parameters": dict(self.parameters), "response_language": self.response_language,
                "timeout_ms": self.timeout_ms, "decision_ttl_ms": self.decision_ttl_ms,
                "transport_retries": self.transport_retries, "input_budget_bytes": self.input_budget_bytes}


def request_body(profile: ModelProfile, prompt: str, text: str, jpeg: bytes, schema: dict):
    url = "data:image/jpeg;base64," + base64.b64encode(jpeg).decode("ascii")
    params = dict(profile.parameters)
    maximum = params.pop("max_output_tokens")
    structured = {"name": "opstate_when_how", "strict": True, "schema": schema}
    if profile.wire_api == "responses":
        body = {"model": profile.model_id, "instructions": prompt, "store": False,
                "input": [{"role": "user", "content": [{"type": "input_text", "text": text},
                    {"type": "input_image", "image_url": url, "detail": "high"}]}],
                "max_output_tokens": maximum, "text": {"format": {"type": "json_schema", **structured}}}
        if "reasoning_effort" in params:
            body["reasoning"] = {"effort": params["reasoning_effort"]}
        return body
    return {"model": profile.model_id, "messages": [{"role": "system", "content": prompt},
            {"role": "user", "content": [{"type": "text", "text": text},
                {"type": "image_url", "image_url": {"url": url, "detail": "high"}}]}],
            "max_tokens": maximum, **params, "response_format": {"type": "json_schema", "json_schema": structured}}


def output_text(profile: ModelProfile, raw: dict) -> str:
    if profile.wire_api == "responses":
        if raw.get("status") != "completed":
            raise ProviderFailure("PROVIDER_RESPONSE_INCOMPLETE")
        parts = [part for item in raw.get("output", []) if item.get("type") == "message" for part in item.get("content", [])]
        if any(part.get("type") == "refusal" for part in parts):
            raise ProviderFailure("PROVIDER_REFUSAL")
        texts = [p.get("text") for p in parts if p.get("type") == "output_text"]
    else:
        choices = raw.get("choices", [])
        if len(choices) != 1 or choices[0].get("finish_reason") != "stop":
            raise ProviderFailure("PROVIDER_RESPONSE_INCOMPLETE")
        message = choices[0].get("message", {})
        if message.get("refusal"):
            raise ProviderFailure("PROVIDER_REFUSAL")
        texts = [message.get("content")]
    if len(texts) != 1 or not isinstance(texts[0], str) or not texts[0].strip():
        raise ProviderFailure("PROVIDER_TEXT_MISSING")
    return texts[0]
