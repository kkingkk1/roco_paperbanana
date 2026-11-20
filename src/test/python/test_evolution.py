"""用已验收的 P1 模块检查父版、组合身份与静态失败范围。"""

import unittest
from dataclasses import FrozenInstanceError, replace

from satellite_scheduler import evolution, generation
from satellite_scheduler.model_call import json_bytes


class EvolutionAssemblyTest(unittest.TestCase):
    def setUp(self):
        self.parent = evolution.preset_version('A1B1C1')
        replacements = evolution.preset_version('A2B2C2')
        self.candidates = {
            module.role: evolution.ModuleCandidate(self.parent.algorithm_hash, module)
            for module in replacements.modules
        }

    def test_preset_substitutions_preserve_all_eight_versions_and_the_parent(self):
        expected_presets = {
            '000': 'A1B1C1', '100': 'A2B1C1', '010': 'A1B2C1', '001': 'A1B1C2',
            '110': 'A2B2C1', '101': 'A2B1C2', '011': 'A1B2C2', '111': 'A2B2C2',
        }
        original_modules = self.parent.modules
        original_hash = self.parent.algorithm_hash
        slots = evolution.assemble_combinations(self.parent, self.candidates)
        self.assertEqual([slot.record()['slot'] for slot in slots], list(expected_presets))
        for slot, expected in zip(slots, expected_presets.values(), strict=True):
            self.assertEqual(''.join(module.preset for module in slot.algorithm.modules), expected)
            self.assertEqual(slot.algorithm.algorithm_hash, evolution.preset_version(expected).algorithm_hash)
            for old_module, selected in zip(original_modules, slot.algorithm.modules, strict=True):
                if old_module.role not in slot.changed_modules:
                    self.assertIs(selected, old_module)
        self.assertEqual(len({slot.algorithm.algorithm_hash for slot in slots}), 8)
        self.assertEqual(self.parent.algorithm_hash, original_hash)
        self.assertIs(self.parent.modules, original_modules)
        with self.assertRaises(FrozenInstanceError):
            self.parent.modules[0].code = 'changed'

    def test_module_hash_depends_on_preset_parameters_but_ignores_description(self):
        for old in self.parent.modules:
            new = self.candidates[old.role].version
            self.assertEqual(old.code, new.code)
            self.assertNotEqual(old.version_hash, new.version_hash)
            self.assertEqual(new.version_hash, replace(new, description='different summary').version_hash)

    def test_static_failure_impacts_only_the_four_slots_that_use_that_module(self):
        for role in self.candidates:
            with self.subTest(role=role):
                candidates = {**self.candidates, role: 'invalid strategy'}
                slots = evolution.assemble_combinations(self.parent, candidates)
                failed = [slot for slot in slots if slot.algorithm is None]
                self.assertEqual(len(failed), 4)
                for slot in slots:
                    record = slot.record()
                    if role in slot.changed_modules:
                        self.assertEqual(record['status'], 'static_failure')
                        self.assertEqual(record['failures'], {role: 'invalid strategy'})
                        self.assertIsNone(record['module_versions'][role])
                    else:
                        self.assertEqual(record['status'], 'assembled')
                        old = next(module for module in self.parent.modules if module.role == role)
                        self.assertEqual(record['module_versions'][role], old.version_hash)

    def test_checks_role_and_common_parent_binding(self):
        candidates = {**self.candidates, 'A': self.candidates['B'],
                      'B': replace(self.candidates['B'], base_algorithm_hash='wrong parent')}
        slots = {slot.record()['slot']: slot.record()
                 for slot in evolution.assemble_combinations(self.parent, candidates)}
        self.assertIn('role', slots['100']['failures']['A'])
        self.assertIn('base_algorithm_hash', slots['010']['failures']['B'])
        self.assertEqual(set(slots['110']['failures']), {'A', 'B'})
        self.assertEqual({slot for slot, row in slots.items() if row['status'] == 'assembled'}, {'000', '001'})


class StaticPermissionsTest(unittest.TestCase):
    _SOURCE = (
        'from satellite_scheduler.runtime.contracts import Assignment\n'
        'class Strategy:\n'
        '    def initial_assign(self, problem, api, budget, rng):\n'
        '        owners = {}\n'
        '        return Assignment(owners)\n'
        '    def search_reassignment(self, problem, api, state, feedback, evaluator, budget, rng):\n'
        '        return None\n'
    )

    def validate(self, source):
        return generation.validate_response('A', json_bytes({
            'description': 'Initialization: assign owners.\n\nReassignment:\n- Stop.',
            'code': source,
        }), check_permissions=True)

    def test_accepts_public_interfaces_and_local_state_as_source(self):
        source = self._SOURCE.replace('owners = {}', 'self.owners = {}\n        owners = self.owners')
        self.assertEqual(self.validate(source)['code'], source)

    def test_rejects_direct_permission_and_binding_violations(self):
        examples = {
            'private project': 'from satellite_scheduler.runtime.contracts import _private\n' + self._SOURCE,
            'outside strategy': 'from satellite_scheduler.runtime.manual import combination\n' + self._SOURCE,
            'forbidden name': self._SOURCE.replace('owners = {}', 'owners = open("private.txt")'),
            'read-only input': self._SOURCE.replace('return None', 'state.assignment.owners[1] = None'),
            'another role': self._SOURCE + '    def build(self):\n        return ()\n',
            'rebind a strategy': self._SOURCE + '    initial_assign = None\n',
        }
        for reason, source in examples.items():
            with self.subTest(reason=reason), self.assertRaisesRegex(ValueError, reason):
                self.validate(source)


if __name__ == '__main__':
    unittest.main()
