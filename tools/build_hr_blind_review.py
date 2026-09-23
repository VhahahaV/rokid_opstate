#!/usr/bin/env python3
"""Prepare an arm-blinded review sheet from the fixed two-day phone replay."""
from __future__ import annotations

import hashlib
import json
import secrets
from datetime import datetime, timezone, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LATEST = ROOT / "reports/latest"
SELECTION = LATEST / "hr_experiment_selection_20260923.json"
OLD = LATEST / "hr_existing_experiment_ab_full_20260923.json"
NEW = LATEST / "hr_existing_experiment_hr_current_full_20260923.json"
SHEET = LATEST / "hr_blind_review_20260923.md"
KEY = LATEST / "hr_blind_review_key_20260923.json"


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def slot(row: dict) -> str:
    if row["error"]:
        return f"技术失败（{row['error']}；不计为沉默）"
    if row["decision"] == "SERVICE":
        return f"**{row['title']}**\n\n{row['body']}\n\n引用：{', '.join(row['evidence_ids']) or '无'}"
    return "保持沉默"


def main() -> None:
    if SHEET.exists() or KEY.exists():
        raise SystemExit("Review sheet already exists; preserve its reviewer identity and do not reshuffle")
    selection = json.loads(SELECTION.read_text())
    old = json.loads(OLD.read_text())
    new = json.loads(NEW.read_text())
    old_by_order = {row["order"]: row for row in old["rows"] if row["arm"] == "old"}
    new_by_order = {row["order"]: row for row in new["rows"]}
    assert len(old_by_order) == len(new_by_order) == 24
    assert old["input_sha256"] == new["input_sha256"] == sha(SELECTION)
    context_by_id = {row["id"]: row for row in selection["public_contexts"]}
    lines = [
        "# 两天真实照片服务短测 · 盲审表",
        "",
        "本表隐藏旧手机单轮路径与新手机 HR 的甲/乙身份；逐张看原图、当时已公开的情境和卡片后再判断。",
        "24 个指定评估点来自 12 个短片段，素材按旧出卡和未出卡场景挑选，不是随机样本或正式 Gold。",
        "合成情境仍待用户逐条审核；照片编排时间不是原拍摄时间。技术失败不得填作沉默。",
        "每项请填写：事实支持、时机/新价值、是否与上一帧重复、是否错过可提供的帮助，并保留争议。",
        "",
        f"输入 SHA-256：`{sha(SELECTION)}`。甲/乙身份及输出哈希另存于审阅密钥，不在本表揭示。",
        "",
    ]
    mapping = {}
    for clip in selection["clips"]:
        lines += [f"## 片段 {clip['clip']}", ""]
        for frame in clip["frames"]:
            order = frame["order"]
            a, b = old_by_order[order], new_by_order[order]
            assert a["photo_sha256"] == b["photo_sha256"] == frame["sha256"]
            flip = secrets.randbelow(2) == 1
            shown = [("甲", b, "hr"), ("乙", a, "old")] if flip else [("甲", a, "old"), ("乙", b, "hr")]
            mapping[str(order)] = {label: arm for label, _, arm in shown}
            local = datetime.fromtimestamp(frame["virtual_at_epoch_ms"] / 1000,
                tz=timezone(timedelta(hours=8))).strftime("%Y-%m-%d %H:%M:%S +08:00")
            photo = ROOT / "reports/latest/two-day-history" / frame["relative_path"]
            assert photo.is_file() and sha(photo) == frame["sha256"]
            cited = {ref for row in (a, b) for ref in row["evidence_ids"]}
            relevant = [context_by_id[ref] for ref in cited if ref in context_by_id]
            recent = [row for row in selection["public_contexts"]
                if row["at_epoch_ms"] <= frame["virtual_at_epoch_ms"]]
            recent = recent[-4:]
            shown_context = {row["id"]: row for row in [*relevant, *recent]}
            lines += [f"### 画面 {order} · 编排时间 {local}", "",
                f"[查看原始 JPEG](<{photo}>) · SHA-256 `{frame['sha256']}`", "",
                "当时公开的相关/近期情境（供快速核读，完整可见前缀见冻结输入）：", ""]
            for row in shown_context.values():
                lines.append(f"- {row['id']}：{row['title']} · {row['body']}")
            if not shown_context:
                lines.append("- 无新近数字情境")
            lines += [""]
            for label, row, _ in shown:
                lines += [f"**候选 {label}**", "", slot(row), ""]
            lines += ["- 事实依据：甲 ___ / 乙 ___ / 均不足 ___ / 不确定 ___",
                "- 当前时机与新价值：甲 ___ / 乙 ___ / 均无 ___ / 不确定 ___",
                "- 与上一帧重复：甲 ___ / 乙 ___ / 均无 ___ / 不确定 ___",
                "- 是否漏掉独立可帮助机会：是 ___ / 否 ___ / 不确定 ___",
                "- 审阅说明：", "", "---", ""]
    SHEET.write_text("\n".join(lines))
    KEY.write_text(json.dumps({"schema": "opstate_hr_blind_review_key_v1",
        "input_sha256": sha(SELECTION), "old_sha256": sha(OLD), "new_sha256": sha(NEW),
        "mapping": mapping}, ensure_ascii=False, indent=2) + "\n")
    print(f"Created {SHEET} and kept the arm key separate at {KEY}")


if __name__ == "__main__":
    main()
