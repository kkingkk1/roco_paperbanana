"""Python A3/B3 的 Java 对照及限时退出验收。"""

import csv
import json
import random
import unittest
from pathlib import Path

from satellite_scheduler import ProblemInstance, Satellite, Task, evaluate
from satellite_scheduler.runtime.access import ProblemAccess
from satellite_scheduler.runtime.contracts import Assignment, Budget, Limits
from satellite_scheduler.runtime.manual import combination
from satellite_scheduler.runtime.rkhma_allocation import RkhmaAllocation
from satellite_scheduler.runtime.rkhma_decoder import RkhmaGreedyDecoder
from satellite_scheduler.runtime.rkhma_search import RkhmaHybridSearch
from satellite_scheduler.runtime.solver import TwoStageSolver

from test_migrated_a_cases import entries, scenario
from test_runtime import fixtures, window


FIXTURES = Path(__file__).parent / 'fixtures'
LIMITS = Limits(search_ns=100, hard_ns=200, initial_local_ns=0, per_satellite_ns=100)


def _contested_problem(extra_star=False):
    tasks = tuple(Task(t, 0, 0, 5 + t, 2) for t in range(6 + extra_star))
    satellites = tuple(Satellite(sat, tuple(window(sat, task, 10 + task // 3 * 15, 1, 3, 0)
                                            for task in range(6))) for sat in range(2))
    if extra_star:
        satellites += (Satellite(2, (window(2, 6, 50, 1, 3, 0),)),)
    return ProblemInstance(tasks, satellites)


class _ForcedAllocation(RkhmaAllocation):
    def initial_assign(self, problem, api, budget, rng):
        return Assignment({task.id: 2 if task.id == 6 else 0 for task in problem.tasks})


def _allocation(kind=RkhmaAllocation):
    return kind(max_rounds=3, perturb_threshold=2, perturb_strength=.1)


def _solve(problem, allocation, search_type=RkhmaHybridSearch, decoder=None, clock=lambda: 0):
    search = search_type(allocation.keys, population=6, evaluations=30)
    return TwoStageSolver(LIMITS, clock=clock).solve(
        problem, allocation, search, decoder or RkhmaGreedyDecoder(), 1234)


def _observations(result):
    return sorted((item.task.id, item.window.satellite_id, item.start, item.end)
                  for item in result.observations)


class RkhmaRuntimeTest(unittest.TestCase):
    def assert_feasible_result(self, problem, result, detail='COMPLETED'):
        self.assertEqual(result.status, 'SUCCESS', result.detail)
        self.assertEqual(result.detail, detail)
        validation = evaluate(problem, result.observations)
        self.assertTrue(validation.feasible, validation.violations)
        self.assertEqual(validation.total_profit, result.total_profit)

    def test_initial_strategy_7_against_java(self):
        reference = json.loads((FIXTURES / 'a-rkhma-initial-java-baseline.json').read_text(encoding='utf-8'))
        budget = Budget(lambda: 0, 0, 200, 100)
        actual = []
        for number in range(12):
            problem = scenario(number)
            assignment = RkhmaAllocation().initial_assign(problem, ProblemAccess(problem, budget),
                                                          budget, random.Random(42))
            actual.append(f'RKHMA_INITIAL={number};{entries(assignment.owners)}')
        self.assertEqual(actual, reference['rows'])

    def test_new_combinations_against_java(self):
        with (FIXTURES / 'rkhma-combinations-java-baseline.csv').open(encoding='utf-8', newline='') as file:
            rows = {(row['fixture'], row['combination']): row for row in csv.DictReader(file)}
        cases = fixtures()
        expected = {(fixture, f'A{a}B{b}C{c}') for fixture in cases
                    for a in (1, 2, 3) for b in (1, 2, 3) for c in (1, 2, 3) if a == 3 or b == 3}
        self.assertEqual(rows.keys(), expected)
        for (fixture, name), row in rows.items():
            with self.subTest(fixture=fixture, combination=name):
                problem = cases[fixture]
                strategies = combination(name)
                result = TwoStageSolver(clock=lambda: 0).solve(problem, strategies.a, strategies.b, strategies.c, 42)
                self.assert_feasible_result(problem, result)
                self.assertEqual(result.total_profit, int(row['profit']))
                self.assertEqual(entries(result.owners).replace(',', '|'), row['owners'])
                self.assertEqual('|'.join(f'{t}@{s}:{start:.2f}:{end:.2f}'
                                          for t, s, start, end in _observations(result)), row['observations'])
                self.assertEqual(result.statistics.reassign_moves, int(row['reassign_moves']))
                self.assertEqual(result.statistics.accepted_moves, int(row['accepted_moves']))

    def test_forced_reassignment_matches_source(self):
        # 对照 RkhmaRuntimeTest.sourceReassignmentTrajectoryMatchesOriginalFromAForcedInitialAssignment。
        problem = _contested_problem()
        result = _solve(problem, _allocation(_ForcedAllocation))
        self.assert_feasible_result(problem, result)
        self.assertEqual(result.total_profit, 32)
        self.assertEqual(result.owners, {0: 1, 1: 1, 2: 0, 3: 1, 4: 1, 5: 0})
        self.assertEqual(_observations(result), [(1, 1, 10, 12), (2, 0, 10, 12), (4, 1, 25, 27), (5, 0, 25, 27)])
        self.assertGreater(result.statistics.accepted_moves, 0)

    def test_full_calibration_searches_the_unaffected_satellite(self):
        problem = _contested_problem(extra_star=True)
        calibrated = []

        class TracedSearch(RkhmaHybridSearch):
            def search_calibration(self, problem, api, view, context, feedback, evaluator, budget, rng):
                if not feedback.attempt:
                    calibrated.append(view.satellite_id)
                return super().search_calibration(problem, api, view, context, feedback, evaluator, budget, rng)

        result = _solve(problem, _allocation(_ForcedAllocation), TracedSearch)
        self.assert_feasible_result(problem, result)
        self.assertEqual(set(calibrated), {0, 1, 2})
        self.assertEqual(result.total_profit, 43)
        self.assertIn((6, 2, 50, 52), _observations(result))

    def test_deadline_preserves_baselines_and_accepted_migration(self):
        problem = _contested_problem(extra_star=True)
        now = 0

        class InterruptPopulation(RkhmaGreedyDecoder):
            def __init__(self):
                self.builds = 0

            def build(self, problem, api, view, priorities, budget, rng):
                nonlocal now
                self.builds += 1
                if self.builds == 3:  # 两颗有初始任务的卫星都已提交基线。
                    now = LIMITS.search_ns
                return super().build(problem, api, view, priorities, budget, rng)

        initial = _solve(problem, _allocation(_ForcedAllocation), decoder=InterruptPopulation(), clock=lambda: now)
        self.assert_feasible_result(problem, initial, 'SEARCH_EXPIRED')
        self.assertEqual({o.window.satellite_id for o in initial.observations}, {0, 2})

        class InterruptCalibration(RkhmaHybridSearch):
            def begin_calibration(self, problem, api, base, budget):
                nonlocal now
                now = LIMITS.search_ns
                super().begin_calibration(problem, api, base, budget)

        now = 0
        reassigned = _solve(problem, _allocation(_ForcedAllocation), InterruptCalibration, clock=lambda: now)
        self.assert_feasible_result(problem, reassigned, 'SEARCH_EXPIRED')
        self.assertGreater(reassigned.total_profit, initial.total_profit)
        self.assertEqual({o.window.satellite_id for o in reassigned.observations}, {0, 1, 2})


if __name__ == '__main__':
    unittest.main()
