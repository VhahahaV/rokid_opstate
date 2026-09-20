"""The story ledger's own rules, as checks rather than as sentences in a document.

What this file is really testing is that a ledger cannot quietly become the thing the last one was:
notes that arrive in a burst, notes that explain what a service ought to do, or notes that land where
the record has no pictures. `load_rows` asserts those; here they are exercised on the file that is in
the repository, and on the import packages built from it.
"""

import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import build_story_history as ledger  # noqa: E402


class StoryHistoryLedgerTest(unittest.TestCase):
    def setUp(self):
        self.rows = ledger.load_rows()

    def test_the_ledger_in_the_repository_keeps_the_spacing_and_the_day_size(self):
        ordered = sorted(self.rows, key=lambda row: row["at_epoch_ms"])
        gaps = [(later["at_epoch_ms"] - earlier["at_epoch_ms"]) / 60000
                for earlier, later in zip(ordered, ordered[1:])]
        self.assertGreaterEqual(min(gaps), ledger.MIN_GAP_MINUTES)
        per_day = {}
        for row in self.rows:
            per_day[row["at"][:10]] = per_day.get(row["at"][:10], 0) + 1
        for day, count in per_day.items():
            self.assertLessEqual(count, ledger.MAX_PER_DAY, day)
            self.assertGreaterEqual(count, ledger.MIN_PER_DAY, day)

    def test_no_line_is_written_as_a_service_specification(self):
        for row in self.rows:
            self.assertIsNone(ledger.PROSE_LINT.search(row["title"] + row["body"]), row["id"])

    def test_every_line_is_an_artifact_of_its_carrier(self):
        for row in self.rows:
            if row["type"] == "MESSAGE":
                self.assertTrue(row["title"], row["id"])          # a sender
            if row["type"] == "EMAIL":
                self.assertIn("·", row["title"], row["id"])       # sender · subject
            if row["type"] == "CALENDAR":
                self.assertRegex(row["body"], r"\d", row["id"])   # a time, a place
            if row["type"] == "NOTE":
                self.assertLessEqual(len(row["body"]), 60, row["id"])

    def test_a_build_can_be_held_to_the_records_own_photo_span(self):
        span = (ledger.datetime(2026, 9, 16, 16, 21, tzinfo=ledger.ZONE),
                ledger.datetime(2026, 9, 19, 19, 6, tzinfo=ledger.ZONE))
        packed = ledger.build(span=[int(each.timestamp() * 1000) for each in span])
        self.assertTrue(packed)
        # A span that stops before the first line is refused rather than silently truncated.
        with self.assertRaises(AssertionError):
            ledger.build(span=[int(span[1].timestamp() * 1000), int(span[1].timestamp() * 1000)])

    def test_a_rewrite_withdraws_the_lines_the_imported_pack_carried(self):
        # The packs the phone actually holds: the 66-line prefix and the 4 lines that followed it.
        packs = [ROOT / "configs/synthetic_history/import_asof_2026-09-19_1749.json",
                 ROOT / "configs/synthetic_history/import_after_2026-09-19_1749.json"]
        for pack in packs:
            self.assertTrue(pack.is_file(), pack)
        packed = ledger.build(retract_against=[str(pack) for pack in packs])
        withdrawn = [entry for entry in packed if entry.get("retract")]
        self.assertTrue(withdrawn)
        for entry in withdrawn:
            self.assertTrue(entry["type"] and entry["title"] and entry["body"] and entry["at_epoch_ms"])
            self.assertEqual("台本改写后不再收录这一条", entry["reason"])
        # Nothing the current ledger still carries is withdrawn.
        current = {(row["at_epoch_ms"], row["title"], row["body"]) for row in self.rows}
        for entry in withdrawn:
            self.assertNotIn((entry["at_epoch_ms"], entry["title"], entry["body"]), current)
        # And a prefix package must not withdraw anything: it is a slice of time, not the whole ledger.
        cutoff = int(ledger.datetime(2026, 9, 19, 17, 49, tzinfo=ledger.ZONE).timestamp() * 1000)
        prefix = ledger.build(as_of=cutoff, retract_against=[str(pack) for pack in packs])
        self.assertTrue(prefix)
        self.assertFalse([entry for entry in prefix if entry.get("retract")])


if __name__ == "__main__":
    unittest.main()
