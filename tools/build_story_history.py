#!/usr/bin/env python3
"""Build phone-importable, time-bounded synthetic context from the authored story ledger.

The ledger is a day of someone's phone, not a log of what a service should do: every line is an
artifact of the carrier it claims to be - a message someone sent, a calendar entry, an email, a note
the wearer wrote to themselves - and nothing in it explains or judges the situation. The rules that
keep it that way are asserted here rather than remembered: each line is at least MIN_GAP_MINUTES from
the next, no day carries more than MAX_PER_DAY, and no line is written in the voice of a service
specification (PROSE_LINT).

`--retract-against` closes the loop on a ledger that has already been imported: every fact an earlier
pack carried and this one no longer does becomes `retract: true`, so one file both withdraws the old
lines and writes the new ones. It applies to a full build only - a prefix package must not withdraw
facts that lie after its own cutoff.
"""

import argparse
import csv
import json
import re
from collections import Counter
from datetime import date, datetime
from pathlib import Path
from zoneinfo import ZoneInfo


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "configs/synthetic_history/collection_stories.tsv"
ZONE = ZoneInfo("Asia/Shanghai")
TYPES = {"EMAIL", "CALENDAR", "MESSAGE", "NOTE"}
PHASES = {"setup", "progress", "revision", "risk", "outcome", "followup", "background"}

# The spacing a day of real notifications has: they arrive one at a time, not in a burst.
MIN_GAP_MINUTES = 15
MAX_PER_DAY = 20
MIN_PER_DAY = 6

# What only a specification or a situation report says. A note is something someone sent or wrote, so
# none of this belongs in one: telling the model what a service ought to do is telling it the answer.
PROSE_LINT = re.compile(r"服务|提醒时|不应|不得|待核对|以[^，。]{0,10}为准|复盘|重复提醒|不能仅凭|不算完成|自动当作")


def load_rows():
    with SOURCE.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    ids = set()
    facts = set()
    for row in rows:
        assert row["id"] and row["id"] not in ids, "duplicate story id"
        ids.add(row["id"])
        assert row["type"] in TYPES and row["phase"] in PHASES
        assert row["story"] and row["title"] and row["body"]
        assert len(row["title"]) <= 80 and len(row["body"]) <= 4000
        timestamp = datetime.strptime(row["at"], "%Y-%m-%d %H:%M").replace(tzinfo=ZONE)
        row["at_epoch_ms"] = int(timestamp.timestamp() * 1000)
        fact = (row["at_epoch_ms"], row["type"], row["title"], row["body"])
        assert fact not in facts, "duplicate story fact"
        facts.add(fact)
        assert row["story"] == "noise" if row["phase"] == "background" else row["story"] != "noise"
        prose = PROSE_LINT.search(row["title"] + row["body"])
        assert prose is None, f'{row["id"]}: 情境要像载体自己产生的东西，不能写服务说明或态势分析（命中「{prose.group(0)}」）'
    assert len(rows) >= 40, "台本太短了"
    assert sum(row["story"] == "noise" for row in rows) >= 15
    assert sum(row["story"] == "study_room" for row in rows) >= 3
    ordered = sorted(rows, key=lambda row: (row["at_epoch_ms"], row["id"]))
    for earlier, later in zip(ordered, ordered[1:]):
        gap = (later["at_epoch_ms"] - earlier["at_epoch_ms"]) / 60000
        assert gap >= MIN_GAP_MINUTES, f'{earlier["id"]} → {later["id"]} 只隔 {gap:.0f} 分钟，会挤成一堆'
    per_day = Counter(row["at"][:10] for row in rows)
    for day, count in per_day.items():
        assert MIN_PER_DAY <= count <= MAX_PER_DAY, f"{day} 有 {count} 条，超出每天 {MIN_PER_DAY}–{MAX_PER_DAY} 条"
    return ordered


def distribution(rows):
    """The shape of the day, as text: what a ledger is judged on is when its lines land."""
    per_day = Counter(row["at"][:10] for row in rows)
    ordered = sorted(rows, key=lambda row: row["at_epoch_ms"])
    gaps = [(later["at_epoch_ms"] - earlier["at_epoch_ms"]) / 60000
            for earlier, later in zip(ordered, ordered[1:])]
    days = ", ".join(f"{day}={count}" for day, count in sorted(per_day.items()))
    return (f"每天 {days}；最小间隔 {min(gaps):.0f} 分钟，中位 {sorted(gaps)[len(gaps) // 2]:.0f} 分钟")


def inside_span(rows, start, end):
    """Every line has to land where the record has pictures: a note outside them has no scene."""
    for row in rows:
        assert start <= row["at_epoch_ms"] <= end, f'{row["id"]} 落在 {row["at"]}，不在记录的照片跨度内'


def retractions_against(packs, rows):
    """The lines an earlier pack carried and this ledger no longer does, as withdrawal entries."""
    current = {(row["at_epoch_ms"], row["type"], row["title"], row["body"]) for row in rows}
    withdrawn, seen = [], set()
    for pack in packs:
        for entry in json.loads(Path(pack).read_text(encoding="utf-8")):
            key = (entry["at_epoch_ms"], entry.get("type", "NOTE"), entry.get("title", ""), entry.get("body", ""))
            if key in current or key in seen:
                continue
            seen.add(key)
            withdrawn.append({"retract": True, "type": key[1], "title": key[2], "body": key[3],
                              "at_epoch_ms": key[0], "reason": "台本改写后不再收录这一条"})
    return withdrawn


def build(as_of=None, shift_days=0, after=None, span=None, retract_against=()):
    shift_ms = shift_days * 86_400_000
    rows = [row for row in load_rows()
            if (as_of is None or row["at_epoch_ms"] + shift_ms <= as_of)
            and (after is None or row["at_epoch_ms"] + shift_ms > after)]
    if span is not None:
        inside_span([dict(row, at_epoch_ms=row["at_epoch_ms"] + shift_ms) for row in rows], *span)
    added = [dict(type=row["type"], title=row["title"], body=row["body"], at_epoch_ms=row["at_epoch_ms"] + shift_ms)
             for row in rows]
    # Withdrawals belong to a whole ledger: a prefix package is a slice of time, and its own later lines
    # are not superseded just because this build stops before them.
    if retract_against and as_of is None and after is None:
        return retractions_against(retract_against, load_rows()) + added
    return added


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--as-of", help="inclusive Asia/Shanghai cutoff, e.g. 2026-09-19T17:49")
    parser.add_argument("--after", help="exclusive Asia/Shanghai lower bound for a delta import")
    parser.add_argument("--shift-to-date", help="rebase all synthetic events so the C-311 story occurs on this test date")
    parser.add_argument("--record-span", nargs=2, metavar=("START", "END"),
                        help="the record's own photo span, e.g. 2026-09-16T16:21 2026-09-19T19:06")
    parser.add_argument("--retract-against", action="append", default=[], metavar="PACK",
                        help="an imported pack whose dropped lines this build should withdraw (full builds only)")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    at = lambda value: int(datetime.strptime(value, "%Y-%m-%dT%H:%M").replace(tzinfo=ZONE).timestamp() * 1000)
    shift_days = ((date.fromisoformat(args.shift_to_date) - date(2026, 9, 19)).days
                  if args.shift_to_date else 0)
    output = build(at(args.as_of) if args.as_of else None, shift_days,
                   at(args.after) if args.after else None,
                   [at(value) + shift_days * 86_400_000 for value in args.record_span] if args.record_span else None,
                   args.retract_against)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    withdrawn = sum(1 for entry in output if entry.get("retract"))
    print(f"{args.output}: {len(output) - withdrawn} 条"
          + (f"（含撤回 {withdrawn} 条）" if withdrawn else "")
          + "；" + distribution(load_rows()))


if __name__ == "__main__":
    main()
