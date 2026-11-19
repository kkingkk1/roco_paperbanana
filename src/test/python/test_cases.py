"""案例内容完整性及 B 阶段配额。"""

import hashlib
import json
import unittest
import warnings
import zipfile
from pathlib import Path

from satellite_scheduler.cases import (CaseData, _relative_source_path, _validate_discoveries, b_required_count,
                                       discover_cases, read_case, validate_b_selection)


ROOT = Path(__file__).resolve().parents[3]
CASES = ROOT / "cases"


def case(case_id: str, phase: str) -> CaseData:
    return CaseData(case_id, "test", "description", "code", "hash", phase)


def candidates(initial: int, post: int) -> list[CaseData]:
    return ([case(f"BI-{i:03d}", "initial") for i in range(1, initial + 1)]
            + [case(f"BR-{i:03d}", "post_reassignment") for i in range(1, post + 1)])


class CaseReaderTest(unittest.TestCase):
    def test_discovers_current_cases_and_checks_real_directories(self):
        discovered = discover_cases(CASES)
        self.assertEqual(len(discovered), 149)
        self.assertEqual(len({data.case_id for data in discovered}), 149)
        self.assertEqual(sum(data.sub_library == 'initial' for data in discovered), 30)
        self.assertEqual(sum(data.sub_library == 'post_reassignment' for data in discovered), 10)

    def test_discovery_rejects_misplaced_cases_and_conflicting_versions(self):
        root = Path('cases-root')
        initial = case('BI-001', 'initial')
        valid = root / 'B' / 'initial_single_satellite_optimization' / 'BI-001' / 'BI-001.json'
        wrong_stage = root / 'B' / 'post_reassignment_adjustment' / 'BI-001' / 'BI-001.json'
        wrong_module = root / 'A' / 'BI-001' / 'BI-001.json'
        with self.assertRaisesRegex(ValueError, 'wrong module or stage'):
            _validate_discoveries(root, [(wrong_stage, initial)])
        with self.assertRaisesRegex(ValueError, 'wrong module or stage'):
            _validate_discoveries(root, [(wrong_module, initial)])
        changed = CaseData('BI-001', 'test', 'different', 'code', 'other-hash', 'initial')
        with self.assertRaisesRegex(ValueError, 'Conflicting versions of case ID'):
            _validate_discoveries(root, [(valid, initial), (wrong_stage, changed)])
        with self.assertRaisesRegex(ValueError, 'Duplicate case ID'):
            _validate_discoveries(root, [(valid, initial), (wrong_stage, initial)])

    def test_reads_all_current_cases(self):
        roots = (CASES / "A", CASES / "B" / "initial_single_satellite_optimization",
                 CASES / "B" / "post_reassignment_adjustment", CASES / "C")
        counts = []
        ids: set[str] = set()
        for root in roots:
            files = sorted(root.glob("*/*.json"))
            self.assertTrue(files, str(root))
            counts.append(len(files))
            for path in files:
                data = read_case(path)
                self.assertEqual(path.parent.name, data.case_id)
                self.assertEqual(data.case_sha256, hashlib.sha256(path.read_bytes()).hexdigest())
                self.assertTrue(data.description)
                self.assertTrue(data.code)
                self.assertNotIn(data.case_id, ids)
                ids.add(data.case_id)
        self.assertEqual(len(ids), sum(counts))

    def test_historical_metadata_for_renamed_case(self):
        directory = CASES / "B" / "initial_single_satellite_optimization" / "BI-001"
        history = (CASES / "backend" / "versions" / "B-004" /
                   "57a966eacb6b45926d3dc6c370158716c5341db5f51fc80aaf7a03ad55ed7f31.json")
        prior_current = (CASES / "backend" / "versions" / "BI-001" /
                         "c55b61cf044f311f10105928aa3bd621d5f49e278db6fa2b91fb6dc7158bfa2f.json")
        current = read_case(directory / "BI-001.json")
        previous = read_case(history, directory, historical_case_id="B-004")
        self.assertEqual(previous.case_id, "B-004")
        self.assertEqual(previous.sub_library, "initial")
        self.assertEqual(previous.code, read_case(prior_current, directory).code)
        self.assertIn("def search(", current.code)
        with self.assertRaises(ValueError):
            read_case(history, directory)
        with self.assertRaises(ValueError):
            read_case(directory / "BI-001.json", directory, historical_case_id="BI-001")

    def test_historical_java_cases_are_readable_from_verified_archive(self):
        archive_path = CASES / "backend" / "archives" / "c-python-parity-20261117.zip"
        with zipfile.ZipFile(archive_path) as archive:
            entries = json.loads(archive.read("manifest.json"))
        self.assertEqual(len(entries), 17)
        for entry in entries:
            with self.subTest(case=entry["case_id"]):
                case_id = entry["case_id"]
                directory = CASES / "C" / case_id
                snapshot = CASES / "backend" / "versions" / case_id / (entry["snapshot_sha256"] + ".json")
                case_data = read_case(snapshot, directory, source_archive=archive_path)
                self.assertEqual(case_data.case_sha256, entry["snapshot_sha256"])
                self.assertEqual(hashlib.sha256(case_data.code.encode("utf-8")).hexdigest(), entry["source_sha256"])
                live_source = ROOT / entry["source_path"]
                if live_source.is_file():
                    self.assertEqual(case_data.code, live_source.read_bytes().decode("utf-8"))
        with self.assertRaisesRegex(ValueError, "only available for historical metadata"):
            read_case(CASES / "C" / "C-060" / "C-060.json", source_archive=archive_path)

    def test_archive_rejects_altered_and_ambiguous_source(self):
        original = CASES / "backend" / "archives" / "c-python-parity-20261117.zip"
        with zipfile.ZipFile(original) as archive:
            record = next(item for item in json.loads(archive.read("manifest.json")) if item["case_id"] == "C-060")
            correct = archive.read(record["entry"])
        directory = ROOT / "target" / "case-archive-security"
        directory.mkdir(parents=True, exist_ok=True)
        altered = directory / "altered.zip"
        with zipfile.ZipFile(altered, "w") as archive:
            archive.writestr(record["entry"], b"altered source")
        snapshot = CASES / "backend" / "versions" / "C-060" / (record["snapshot_sha256"] + ".json")
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            read_case(snapshot, CASES / "C" / "C-060", source_archive=altered)
        duplicate = directory / "duplicate.zip"
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(duplicate, "w") as archive:
                archive.writestr(record["entry"], correct)
                archive.writestr(record["entry"], correct)
        with self.assertRaisesRegex(ValueError, "ambiguous archived source"):
            read_case(snapshot, CASES / "C" / "C-060", source_archive=duplicate)
        for value in ("../other.java", "/outside.java", "C:outside.java", "dir\\outside.java"):
            with self.subTest(path=value), self.assertRaises(ValueError):
                _relative_source_path(value)

    def test_b_quota_and_selection(self):
        for initial, post, expected in ((1, 1, 2), (19, 1, 2), (20, 1, 2),
                                        (39, 1, 2), (40, 1, 3), (50, 50, 5)):
            self.assertEqual(b_required_count(candidates(initial, post)), expected)
        choices = candidates(40, 1)
        validate_b_selection(choices, ("BI-001", "BI-002", "BR-001"))
        for invalid in (("BI-001", "BI-002", "BI-003"),
                        ("BI-001", "BI-001", "BR-001"),
                        ("BI-001", "BI-002", "BR-999"),
                        ("BI-001", "BR-001")):
            with self.assertRaises(ValueError):
                validate_b_selection(choices, invalid)

    def test_b_rejects_missing_or_duplicate_stages(self):
        for invalid in (candidates(0, 1), candidates(1, 0),
                        [case("BI-001", "initial"), case("BR-001", "post_reassignment"),
                         case("BI-001", "initial")],
                        [case("BI-001", "post_reassignment"), case("BR-001", "post_reassignment")]):
            with self.assertRaises(ValueError):
                b_required_count(invalid)


if __name__ == "__main__":
    unittest.main()
