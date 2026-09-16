#!/usr/bin/env python3
"""Verify a supplied R6 bundle and prepare only its public stream/anchors for the app."""
from __future__ import annotations
import argparse
import hashlib
import json
import shutil
import stat
import zipfile
from collections import Counter
from pathlib import Path, PurePosixPath

ROOT=Path(__file__).resolve().parents[1]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def rows(path: Path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def import_bundle(archive: Path, output: Path) -> dict:
    archive_hash=digest(archive)
    staging=output/"source"
    staging.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(archive) as bundle:
        members=bundle.infolist()
        if sum(item.file_size for item in members)>1024*1024*1024:
            raise ValueError("bundle exceeds the 1 GiB development import limit")
        for item in members:
            path=PurePosixPath(item.filename)
            if path.is_absolute() or ".." in path.parts or "\\" in item.filename or stat.S_ISLNK(item.external_attr>>16):
                raise ValueError("unsafe bundle member")
            if item.file_size>32*1024*1024:
                raise ValueError("oversized bundle member")
        for item in members:
            if item.is_dir(): continue
            target=staging/item.filename
            target.parent.mkdir(parents=True,exist_ok=True)
            content=bundle.read(item)
            if target.exists() and target.read_bytes()!=content:
                raise ValueError("staging already contains a different release")
            target.write_bytes(content)
    checksum_counts=[]
    for manifest in staging.rglob("SHA256SUMS.txt"):
        checked=0
        for line in manifest.read_text().splitlines():
            if not line.strip(): continue
            expected,relative=line.split(None,1)
            rel=PurePosixPath(relative.strip().lstrip("*"))
            if rel.is_absolute() or ".." in rel.parts: raise ValueError("unsafe checksum path")
            target=manifest.parent/rel
            if not target.is_file() or digest(target)!=expected:
                raise ValueError(f"checksum mismatch: {rel}")
            checked+=1
        checksum_counts.append(checked)
    if not checksum_counts: raise ValueError("bundle has no checksum manifest")
    manifests=list(staging.rglob("RELEASE_MANIFEST.json"))
    if len(manifests)!=1: raise ValueError("expected one release")
    release=manifests[0].parent
    anchors=rows(release/"benchmark_data/anchors/evaluation_anchors.jsonl")
    observations=rows(release/"benchmark_data/stream/observations.jsonl")
    gold=rows(release/"evaluator/service_gold.jsonl")
    labels=Counter(row["decision_gold"] for row in gold)
    if len({row["anchor_id"] for row in anchors})!=len(anchors): raise ValueError("duplicate anchor")
    if len({row["observation_id"] for row in observations})!=len(observations): raise ValueError("duplicate observation")
    if observations!=sorted(observations,key=lambda row:(row["available_at"],row["global_sequence_index"])):
        raise ValueError("public stream is not chronological")
    public=output/"public"
    public.mkdir(exist_ok=True)
    public_anchors=[]
    for anchor in anchors:
        asset=anchor["current_asset"]
        relative=PurePosixPath(asset["relative_path"])
        if relative.is_absolute() or ".." in relative.parts: raise ValueError("unsafe image path")
        source=release/relative
        if not source.is_file() or digest(source)!=asset["sha256"]: raise ValueError("anchor image unavailable or changed")
        destination=public/"images"/relative
        destination.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(source,destination)
        public_anchors.append({key:anchor[key] for key in ("anchor_id","anchor_index","available_at","current_asset","query","task_episode_handle")})
    public_observations=[]
    for row in observations:
        payload=row["payload"]
        allowed={"surface","text","task_episode_handle"} if row["modality"]=="digital" else {"current_asset"}
        if set(payload)-allowed: raise ValueError("unreviewed fields in public observation payload")
        public_observations.append({key:row[key] for key in ("observation_id","available_at","global_sequence_index","modality","payload")})
    for filename,records in (("anchors.jsonl",public_anchors),("observations.jsonl",public_observations)):
        (public/filename).write_text("".join(json.dumps(row,ensure_ascii=False,separators=(",",":"))+"\n" for row in records))
    available_images={str(path.relative_to(public/"images")) for path in (public/"images").rglob("*.jpg")}
    missing_history=sum(row["modality"]=="visual" and row["payload"]["current_asset"]["relative_path"] not in available_images for row in observations)
    catalog={"dataset_id":"january-v10-r6","title":"January V10 · R6","month":"2019-01","source_archive_sha256":archive_hash,
        "anchor_count":len(anchors),"observation_count":len(observations),"current_image_count":len(available_images),
        "public_anchor_sha256":digest(public/"anchors.jsonl"),"public_observation_sha256":digest(public/"observations.jsonl"),
        "profile":"BENCHMARK_REPLAY","teacher_receipts_imported":False}
    (public/"catalog.json").write_text(json.dumps(catalog,ensure_ascii=False,indent=2))
    report={**catalog,"status":"PASS","checksum_manifests_verified":checksum_counts,"evaluation_labels":dict(labels),
        "historical_visual_observations_without_image_bytes":missing_history,
        "limits":["Replay assets do not prove live-camera or wireless capture gates.",
                  "Gold, authoring metadata and teacher-forced receipts remain outside the app's public import.",
                  "Only anchor JPEGs are supplied; do not fabricate unavailable historical image pixels."]}
    (output/"import_report.json").write_text(json.dumps(report,ensure_ascii=False,indent=2))
    return report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive",type=Path)
    parser.add_argument("--output",type=Path)
    parser.add_argument("--app-assets",action="store_true",help="Install the verified public projection in the phone app assets")
    args=parser.parse_args()
    output=args.output or ROOT/".local/datasets"/digest(args.archive)[:12]
    report=import_bundle(args.archive,output)
    if args.app_assets:
        target=ROOT/"code/phone-app/src/debug/assets/datasets"/report["dataset_id"]
        if target.exists(): shutil.rmtree(target)
        shutil.copytree(output/"public",target)
    (ROOT/"reports/latest").mkdir(parents=True,exist_ok=True)
    (ROOT/"reports/latest/data_import.json").write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__=="__main__":
    main()
