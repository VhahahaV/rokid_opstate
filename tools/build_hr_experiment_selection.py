#!/usr/bin/env python3
"""Freeze short HR replay clips from the current local, audited experiment data."""
from __future__ import annotations

import collections
import hashlib
import json
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "reports/latest/two-day-history/data"
OUT = ROOT / "reports/latest/hr_experiment_selection_20260923.json"


def lines(path: Path) -> list[dict]:
    return [json.loads(value) for value in path.read_text().splitlines() if value.strip()]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    expanded = lines(DATA / "expanded-photo-order.jsonl")
    photos = lines(DATA / "current-photos.jsonl")
    service = lines(DATA / "current-services-audit.jsonl")
    contexts = lines(DATA.parent / "synthetic-draft.jsonl")
    by_observation = {
        source["observation_id"]: photo
        for photo in photos for source in photo.get("source_refs", [])
        if source.get("observation_id")
    }
    by_sha = collections.defaultdict(list)
    for item in expanded:
        by_sha[item["photo_sha256"]].append(item)
    matched = {}
    for decision in service:
        frames = [by_observation[ref] for ref in decision.get("evidence_observation_ids", [])
                  if ref in by_observation]
        if frames:
            matched.setdefault(frames[0]["sha256"], []).append(decision)

    pairs = [(a, b) for a, b in zip(expanded, expanded[1:])
             if a["day"] == b["day"] and a["scene_id"] == b["scene_id"]
             and a["photo_sha256"] != b["photo_sha256"]]
    chosen: list[tuple[dict, dict, str]] = []
    used_scenes: set[str] = set()
    selected_titles: set[str] = set()
    for day in [1, 2]:
        taken = 0
        for first, second in pairs:
            decisions = matched.get(first["photo_sha256"], []) + matched.get(second["photo_sha256"], [])
            if first["day"] != day or not decisions or first["scene_id"] in used_scenes:
                continue
            title = next((d.get("title") for d in decisions if d.get("title") not in selected_titles), None)
            if not title:
                continue
            chosen.append((first, second, "historical_decision_scene"))
            used_scenes.add(first["scene_id"])
            selected_titles.add(title)
            taken += 1
            if taken == 3:
                break
        if taken != 3:
            raise RuntimeError(f"Only {taken} decision scenes on day {day}")
    for day in [1, 2]:
        taken = 0
        for first, second in pairs:
            if first["day"] != day or first["scene_id"] in used_scenes or \
                    first["photo_sha256"] in matched or second["photo_sha256"] in matched:
                continue
            chosen.append((first, second, "no_historical_decision_scene"))
            used_scenes.add(first["scene_id"])
            taken += 1
            if taken == 3:
                break
        if taken != 3:
            raise RuntimeError(f"Only {taken} non-decision scenes on day {day}")
    if len(chosen) != 12:
        raise RuntimeError(f"Only {len(chosen)} distinct eligible scenes")

    clips = []
    for index, (first, second, selection_reason) in enumerate(chosen, 1):
        frames = []
        for row in [first, second]:
            photo_path = DATA.parent / row["photo_path"]
            if not photo_path.is_file() or digest(photo_path) != row["photo_sha256"]:
                raise RuntimeError(f"Missing or altered photo {row['order']}")
            frames.append({
                "order": row["order"],
                "virtual_at_epoch_ms": row["virtual_at_epoch_ms"],
                "source_at_epoch_ms": row["source_at_epoch_ms"],
                "sha256": row["photo_sha256"],
                "relative_path": row["photo_path"],
            })
        old_decisions = []
        for frame in frames:
            old_decisions.extend({
                "id": decision["id"], "decision": decision["decision"],
                "delivery_status": decision["delivery_status"],
                "title": decision.get("title"), "body": decision.get("body"),
                "original_at_epoch_ms": decision["original_at_epoch_ms"],
            } for decision in matched.get(frame["sha256"], []))
        clips.append({
            "clip": index, "day": first["day"], "scene_id_for_sampling_only": first["scene_id"],
            "selection_reason_for_sampling_only": selection_reason,
            "frames": frames, "historical_results_for_review_only": old_decisions,
        })

    # These are experiment annotations. A model input builder must use only `frames` and
    # public context `title`/`body` with time <= the packet; no scene/decision labels.
    public_contexts = [{
        "id": row["id"], "at_epoch_ms": int(datetime.fromisoformat(row["at"]).timestamp() * 1000),
        "type": row["type"], "title": row["title"], "body": row["body"],
    } for row in contexts]
    report = {
        "schema": "opstate_hr_experiment_selection_v1",
        "sources": {name: digest(DATA / name) for name in
                    ["expanded-photo-order.jsonl", "current-photos.jsonl", "current-services-audit.jsonl"]},
        "context_source_sha256": digest(DATA.parent / "synthetic-draft.jsonl"),
        "source_photo_count": len(photos), "timeline_rows": len(expanded),
        "historical_decisions": dict(collections.Counter(d["decision"] for d in service)),
        "historical_deliveries": dict(collections.Counter(d["delivery_status"] for d in service)),
        "historical_results_matched_to_real_photos": sum(bool([by_observation[ref] for ref in d.get("evidence_observation_ids", [])
                                                             if ref in by_observation]) for d in service),
        "caution": "Historical decisions used their original sessions and context; they are failure examples, not paired A/B outcomes for the curated two-day context.",
        "public_contexts": public_contexts,
        "clips": clips,
    }
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"output": str(OUT), "clips": len(clips), "frames": sum(len(c["frames"]) for c in clips),
                      "historical_matched": report["historical_results_matched_to_real_photos"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
