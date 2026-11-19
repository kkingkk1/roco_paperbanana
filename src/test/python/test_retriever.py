"""用真实检索回复检查Python候选、解析和阶段配额。"""

import json
import unittest
from dataclasses import replace
from pathlib import Path

from satellite_scheduler import retriever
from satellite_scheduler.cases import discover_cases


ROOT = Path(__file__).resolve().parents[3]
REVIEWED = ('A-001', 'A-002', 'A-010', 'A-017', 'BI-001', 'BI-003', 'BR-001', 'BR-005',
            'C-001', 'C-002', 'C-003', 'C-066')


class RealRetrieverTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        index = {item.case_id: item for item in discover_cases(ROOT / 'cases')}
        cls.cases = [index[case_id] for case_id in REVIEWED]

    def test_request_contains_only_reviewed_descriptions_and_both_stages(self):
        counts = retriever._counts(self.cases)
        self.assertEqual(counts, {'A': 1, 'B': 2, 'C': 1})
        text = retriever._prompt(self.cases, counts)
        payload = json.loads(text.split('候选：\n', 1)[1])
        self.assertEqual(sum(map(len, payload.values())), len(REVIEWED))
        for group in payload.values():
            for entry in group:
                self.assertLessEqual(set(entry), {'case_id', 'category', 'description', 'sub_library'})
        for case in self.cases:
            self.assertNotIn(case.code, text)
        self.assertEqual(len(retriever._load_sources(ROOT / 'cases', self.cases)), len(REVIEWED))

    def test_parses_actual_model_reply_and_freezes_selected_contents(self):
        # 原文来自 outputs/python-retriever-20261117-134111/response.json，不构造模型回复。
        raw = (Path(__file__).parent / 'fixtures' / 'retriever-real-response.json').read_bytes()
        chosen = retriever._select(raw, self.cases, retriever._counts(self.cases))
        self.assertEqual({module: [item['case_id'] for item in items] for module, items in chosen.items()},
                         {'A': ['A-002'], 'B': ['BI-003', 'BR-005'], 'C': ['C-002']})
        self.assertEqual({item['sub_library'] for item in chosen['B']}, {'initial', 'post_reassignment'})
        index = {case.case_id: case for case in self.cases}
        for entries in chosen.values():
            for item in entries:
                source = index[item['case_id']]
                self.assertEqual(item['case_sha256'], source.case_sha256)
                self.assertEqual(item['code'], source.code)
                self.assertTrue(item['adaptation'].strip())
                self.assertNotIn('source', item)

    def test_semantic_review_is_required_for_each_current_version(self):
        retriever._validated_review(ROOT / 'cases', self.cases)
        index = {item.case_id: item for item in discover_cases(ROOT / 'cases')}
        retriever._validated_review(ROOT / 'cases',
                                    [index[case_id] for case_id in
                                     ('C-008', 'C-010', 'C-020', 'C-022', 'C-023', 'C-027',
                                      'C-060', 'C-065', 'C-071', 'C-075', 'C-077', 'C-079', 'C-080')])
        record = json.loads((ROOT / 'cases' / 'backend' / 'semantic-review.json').read_text(encoding='utf-8'))
        retriever._validated_review(ROOT / 'cases', [index[item['case_id']] for item in record['reviewed']])
        with self.assertRaisesRegex(ValueError, 'has not passed semantic review'):
            retriever._validated_review(ROOT / 'cases', [replace(index['C-004'], case_id='C-999')])
        with self.assertRaisesRegex(ValueError, 'different case version'):
            retriever._validated_review(ROOT / 'cases', [replace(self.cases[0], case_sha256='0' * 64)])


if __name__ == '__main__':
    unittest.main()
