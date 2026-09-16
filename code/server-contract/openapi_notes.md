# Frozen harness HTTP contract

## `POST /v1/evaluate`

Content type: `multipart/form-data`.

Parts:

- `request`: UTF-8 JSON matching `schemas/inference_request.schema.json`;
- `image`: the exact JPEG referenced by `current_image_sha256`.

Headers:

- `Authorization: Bearer ...`;
- `Idempotency-Key: <request_id>`.

The server must return a response matching
`schemas/inference_response.schema.json`. In paper mode, a timeout or model error
is a technical failure. It must not silently switch model, prompt or provider.

## Optional `POST /v1/observe`

This endpoint may create a caption or trigger-key summary for a stored minute
observation. Its result becomes visible only at the response's actual
`available_at`; it cannot retroactively affect an earlier anchor. It is optional
because synthetic context supplies most history in the controlled study.
