"""读取 Java 对照结果，验证已迁移的 C 案例。"""

import importlib.util
import json
import unittest
from pathlib import Path

from satellite_scheduler import AttitudeRecord, ProblemAdapter, ProblemInstance, Satellite, Task, TimeWindow
from satellite_scheduler.problem import evaluate
from satellite_scheduler.cases import read_case
from satellite_scheduler.runtime.access import ProblemAccess
from satellite_scheduler.runtime.contracts import Budget, LocalContext, LocalPlan, Phase, SatelliteView


ROOT = Path(__file__).resolve().parents[3]
ORDERS = ((0, 1, 2, 3, 4), (4, 3, 2, 1, 0), (2, 0, 4, 1, 3))
SIMPLE_CASES = (1, 2, 4, 5, 6, 7, 8, 9, 10, 17, 18, 19, 20, 21, 22, 23, 24,
                25, 26, 27, 28, 34, 35, 36, 37, 38, 39, 40)
FUTURE_CASES = tuple(range(11, 17)) + tuple(range(29, 34)) + tuple(range(41, 47))
EXTRA_CASES = (61, 62, 66, 70, 71, 76)
REPAIR_CASES = (51, 52, 53, 54)
DESCENT_CASES = (63, 64, 65, 67, 74, 78, 79)
SEARCH_CASES = (47, 48, 49, 50, 55, 56, 75, 80)
MULTI_REPAIR_CASES = (57, 58, 59, 60, 77)
STOCHASTIC_CASES = (68, 69, 72, 73)


class FixedRandom:
    def __init__(self):
        self.index = 0

    def randrange(self, bound):
        self.index += 1
        return self.index % bound

    def random(self):
        self.index += 1
        return ((self.index * 37) % 97) / 97.0


def scenario(number):
    tasks = []
    windows = []
    for task_id in range(5):
        tasks.append(Task(task_id, 0, 0, (task_id * 7 + number * 3) % 13 + 1, task_id % 3 + 1))
        if (task_id + number) % 7 == 0:
            continue
        for variant in range(2 if task_id % 2 == 0 else 1):
            start = 3 + (task_id * 19 + number * 11) % 70 + variant * 26
            end = start + 4 + (task_id * 3 + number + variant) % 7
            records = tuple(AttitudeRecord(second, (task_id * 7 + number * 3 + second) % 31 - 15,
                                            (task_id * 11 + second * 2 + number) % 21 - 10)
                            for second in range(start, end + 1))
            windows.append(TimeWindow(0, task_id, start, end, records))
    problem = ProblemInstance(tuple(tasks), (Satellite(0, tuple(windows)),))
    budget = Budget(lambda: 0, 0, 2**63 - 1, 2**63 - 1)
    return problem, ProblemAccess(problem, budget), budget


def signature(problem, observations):
    windows = problem.satellite(0).windows
    return "|".join(f"{item.task.id}@{next(i for i, w in enumerate(windows) if w is item.window)}:"
                    f"{item.start:.6f}:{item.end:.6f}" for item in observations)


def decoder(number):
    path = ROOT / "cases" / "C" / f"C-{number:03d}" / f"C{number:06d}.py"
    spec = importlib.util.spec_from_file_location(f"semantic_c_{number}", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.create()


def one_candidate_window(task, start, roll=0):
    return TimeWindow(0, task, start - 1, start + 2,
                      tuple(AttitudeRecord(time, roll, 0) for time in range(start - 1, start + 3)))


def compare_local(problem, original_indices, released, reconsider, numbers):
    budget = Budget(lambda: 0, 0, 2**63 - 1, 2**63 - 1)
    api = ProblemAccess(problem, budget)
    adapter = ProblemAdapter(problem)
    original = tuple(next(adapter.candidates(problem.satellite(0).windows[index])) for index in original_indices)
    view = SatelliteView(0, frozenset(task.id for task in problem.tasks), original, "view")
    plan = LocalPlan("view", frozenset(released), tuple(reconsider))
    results = {}
    for number in numbers:
        local = decoder(number).apply_local(problem, api, view, LocalContext(Phase.REASSIGN),
                                            plan, budget, FixedRandom())
        results[number] = (local.status.name, tuple((item.task.id, item.start) for item in local.observations))
        if local.observations:
            assert evaluate(problem, local.observations).feasible
    return results


class MigratedCCaseTest(unittest.TestCase):
    def test_preceding_gap_and_earliest_finish_can_choose_different_insertions(self):
        tasks = tuple(Task(index, 0, 0, 1, 1) for index in range(3))
        windows = tuple(one_candidate_window(task, start)
                        for task, start in ((0, 20), (1, 80), (2, 35), (2, 93)))
        problem = ProblemInstance(tasks, (Satellite(0, windows),))
        self.assertEqual(compare_local(problem, (0, 1), (), (2,), (1, 23)), {
            1: ("APPLIED", ((0, 20), (2, 35), (1, 80))),
            23: ("APPLIED", ((0, 20), (1, 80), (2, 93))),
        })

    def test_added_slew_and_neighbor_slew_can_choose_different_insertions(self):
        tasks = tuple(Task(index, 0, 0, 1, 1) for index in range(3))
        windows = tuple(one_candidate_window(task, start, roll)
                        for task, start, roll in ((0, 20, -30), (1, 100, 30), (2, 60, 0), (2, 120, 30)))
        problem = ProblemInstance(tasks, (Satellite(0, windows),))
        self.assertEqual(compare_local(problem, (0, 1), (), (2,), (2, 22)), {
            2: ("APPLIED", ((0, 20), (2, 60), (1, 100))),
            22: ("APPLIED", ((0, 20), (1, 100), (2, 120))),
        })

    def test_neighbor_gap_and_schedule_span_can_choose_different_insertions(self):
        tasks = tuple(Task(index, 0, 0, 1, 1) for index in range(4))
        windows = tuple(one_candidate_window(task, start)
                        for task, start in ((0, 20), (1, 100), (2, 150), (3, 50), (3, 125)))
        problem = ProblemInstance(tasks, (Satellite(0, windows),))
        self.assertEqual(compare_local(problem, (0, 1, 2), (), (3,), (8, 20)), {
            8: ("APPLIED", ((0, 20), (1, 100), (3, 125), (2, 150))),
            20: ("APPLIED", ((0, 20), (3, 50), (1, 100), (2, 150))),
        })

    def test_absolute_and_normalized_window_center_can_differ(self):
        task = Task(0, 0, 0, 1, 1)
        long_window = TimeWindow(0, 0, 0, 100, tuple(AttitudeRecord(time, 0, 0) for time in (39, 40, 41, 42)))
        short_window = TimeWindow(0, 0, 20, 40, tuple(AttitudeRecord(time, 0, 0) for time in (22, 23, 24, 25)))
        problem = ProblemInstance((task,), (Satellite(0, (long_window, short_window)),))
        budget = Budget(lambda: 0, 0, 2**63 - 1, 2**63 - 1)
        api = ProblemAccess(problem, budget)
        view = SatelliteView(0, frozenset((0,)), (), "view")
        actual = {number: tuple((item.task.id, item.start) for item in
                                decoder(number).build(problem, api, view, (0,), budget, FixedRandom()))
                  for number in (10, 27)}
        self.assertEqual(actual, {10: ((0, 23),), 27: ((0, 40),)})

    def test_preserving_original_window_differs_from_earliest_finish(self):
        task = Task(0, 0, 0, 1, 1)
        windows = (one_candidate_window(0, 10), one_candidate_window(0, 50))
        problem = ProblemInstance((task,), (Satellite(0, windows),))
        self.assertEqual(compare_local(problem, (1,), (0,), (0,), (1, 71)), {
            1: ("APPLIED", ((0, 10),)), 71: ("NO_FEASIBLE_CHANGE", ()),
        })

    def test_first_and_best_span_descent_can_choose_different_local_optima(self):
        tasks = (Task(0, 0, 0, 2, 2), Task(1, 0, 0, 7, 1),
                 Task(2, 0, 0, 6, 3), Task(3, 0, 0, 9, 1))
        specifications = ((0, 70, 75, 20, 10), (1, 56, 62, 0, 10), (2, 102, 108, -20, 10),
                          (2, 35, 41, 20, 10), (3, 83, 88, 0, 10), (3, 102, 108, 40, -10))
        windows = tuple(TimeWindow(0, task, start, end,
                                  tuple(AttitudeRecord(time, roll, pitch) for time in range(start, end + 1)))
                        for task, start, end, roll, pitch in specifications)
        problem = ProblemInstance(tasks, (Satellite(0, windows),))
        budget = Budget(lambda: 0, 0, 2**63 - 1, 2**63 - 1)
        api = ProblemAccess(problem, budget)
        view = SatelliteView(0, frozenset(range(4)), (), "view")
        actual = {number: tuple((item.task.id, item.start) for item in
                                decoder(number).build(problem, api, view, (1, 0, 2, 3), budget, FixedRandom()))
                  for number in (65, 79)}
        self.assertEqual(actual, {65: ((2, 36), (1, 58)), 79: ((2, 37), (1, 59))})

    def test_zero_reward_distinguishes_greedy_and_subset_repair(self):
        tasks = (Task(0, 0, 0, 0, 1), Task(1, 0, 0, 1, 1), Task(2, 0, 0, 10, 1))
        windows = tuple(one_candidate_window(task, start) for task, start in enumerate((10, 40, 40)))
        problem = ProblemInstance(tasks, (Satellite(0, windows),))
        budget = Budget(lambda: 0, 0, 2**63 - 1, 2**63 - 1)
        api = ProblemAccess(problem, budget)
        view = SatelliteView(0, frozenset(range(3)), (), "view")
        # 预期结果来自实际运行的旧Java案例，见 outputs/case-acceptance-20261117/c-edge-java.txt。
        expected = {60: ((0, 10), (2, 40)), 77: ((2, 40),)}
        for number in expected:
            with self.subTest(case=number):
                observations = decoder(number).build(problem, api, view, (0, 1, 2), budget, FixedRandom())
                self.assertEqual(tuple((item.task.id, item.start) for item in observations), expected[number])
                self.assertEqual(evaluate(problem, observations).total_profit, 10)
                self.assertTrue(evaluate(problem, observations).feasible)

    def test_minimum_movement_and_minimum_changes_can_choose_different_schedules(self):
        tasks = tuple(Task(index, 0, 0, 1, 1) for index in range(3))
        specifications = ((0, 10), (0, 50), (0, 80), (1, 40), (1, 30), (2, 10))
        windows = tuple(one_candidate_window(task, start) for task, start in specifications)
        problem = ProblemInstance(tasks, (Satellite(0, windows),))
        budget = Budget(lambda: 0, 0, 2**63 - 1, 2**63 - 1)
        api = ProblemAccess(problem, budget)
        adapter = ProblemAdapter(problem)
        original = tuple(next(adapter.candidates(windows[index])) for index in (0, 3))
        self.assertTrue(evaluate(problem, original).feasible)
        view = SatelliteView(0, frozenset(range(3)), original, "view")
        plan = LocalPlan("view", frozenset((0, 1)), (2, 0, 1))
        context = LocalContext(Phase.REASSIGN)
        expected = {75: ((2, 10), (1, 30), (0, 50)), 80: ((2, 10), (1, 40), (0, 80))}
        for number in expected:
            with self.subTest(case=number):
                result = decoder(number).apply_local(problem, api, view, context, plan, budget, FixedRandom())
                self.assertEqual(result.status.name, "APPLIED")
                self.assertEqual(tuple((item.task.id, item.start) for item in result.observations), expected[number])
                self.assertTrue(evaluate(problem, result.observations).feasible)

    def test_retry_and_single_pass_are_distinct_when_a_later_task_bridges_attitudes(self):
        tasks = tuple(Task(task, 0, 0, 1, 1) for task in range(3))
        specifications = (
            (0, 9, (0, 0, 0, 0)),
            (1, 39, (60, 60, 60, 60)),
            (2, 22, (0, 0, 60, 60)),
        )
        windows = tuple(
            TimeWindow(0, task, first, first + 3,
                       tuple(AttitudeRecord(first + offset, roll, 0)
                             for offset, roll in enumerate(rolls)))
            for task, first, rolls in specifications
        )
        problem = ProblemInstance(tasks, (Satellite(0, windows),))
        budget = Budget(lambda: 0, 0, 2**63 - 1, 2**63 - 1)
        api = ProblemAccess(problem, budget)
        view = SatelliteView(0, frozenset(range(3)), (), "view")
        outcomes = {}
        for number in (1, 66):
            path = ROOT / "cases" / "C" / f"C-{number:03d}" / f"C{number:06d}.py"
            spec = importlib.util.spec_from_file_location(f"distinct_c_{number}", path)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            result = module.create().build(problem, api, view, (0, 1, 2), budget, FixedRandom())
            self.assertTrue(evaluate(problem, result).feasible)
            outcomes[number] = {item.task.id for item in result}
        self.assertEqual(outcomes[1], {0, 2})
        self.assertEqual(outcomes[66], {0, 1, 2})

    def test_simple_decoders_match_java_build_and_local_edits(self):
        self._compare("c-simple-java-baseline.json", SIMPLE_CASES, 5040, True)

    def test_future_decoders_match_java_build_and_local_edits(self):
        self._compare("c-future-java-baseline.json", FUTURE_CASES, 3060, True)

    def test_extra_decoders_match_java_build_and_local_edits(self):
        self._compare("c-extra-java-baseline.json", EXTRA_CASES, 1080, True)

    def test_repair_decoders_match_java_build_and_local_edits(self):
        self._compare("c-repair-java-baseline.json", REPAIR_CASES, 720, True)

    def test_descent_decoders_match_java_build_and_local_edits(self):
        self._compare("c-descent-java-baseline.json", DESCENT_CASES, 1260, True)

    def test_rkhma_decoder_matches_java_build_and_local_edits(self):
        self._compare("c-rkhma-java-baseline.json", (3,), 180, True)

    def test_runtime_rkhma_decoder_matches_original_java(self):
        self._compare("c-rkhma-runtime-java-baseline.json", (3,), 180, True, runtime_rkhma=True)
        self._compare_rkhma_rebuild("c-rkhma-runtime-java-baseline.json", runtime_rkhma=True)

    def test_rkhma_decoder_matches_java_window_preference_on_rebuild(self):
        self._compare_rkhma_rebuild("c-rkhma-java-baseline.json")

    def _compare_rkhma_rebuild(self, fixture, runtime_rkhma=False):
        baseline = Path(__file__).parent / "fixtures" / fixture
        rows = json.loads(baseline.read_text(encoding="utf-8"))["rebuild"]
        self.assertEqual(len(rows), 72)
        modules = {}
        for number in (1, 3):
            path = ROOT / "cases" / "C" / f"C-{number:03d}" / f"C{number:06d}.py"
            spec = importlib.util.spec_from_file_location(f"rebuild_c_{number}", path)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            modules[number] = module
        if runtime_rkhma:
            from satellite_scheduler.runtime import rkhma_decoder
            modules[3] = rkhma_decoder
        for scene, mode, variant, expected in rows:
            with self.subTest(scene=scene, mode=mode, variant=variant):
                problem, api, budget = scenario(scene)
                order = ORDERS[mode]
                empty = SatelliteView(0, frozenset(range(5)), (), "view")
                original = modules[1].create().build(problem, api, empty, order, budget, FixedRandom())
                view = SatelliteView(0, frozenset(range(5)), original, "view")
                preferred = frozenset() if variant == 0 else frozenset((0, 2, 4))
                context = LocalContext(Phase.REASSIGN, preferred_window_tasks=preferred)
                result = modules[3].create().rebuild(problem, api, view, context, order, budget, FixedRandom())
                self.assertEqual(signature(problem, result), expected)

    def test_search_decoders_match_java_build_and_local_edits(self):
        self._compare("c-search-java-baseline.json", SEARCH_CASES, 1440, True)

    def test_multi_repair_decoders_match_java_build_and_local_edits(self):
        self._compare("c-multi-repair-java-baseline.json", MULTI_REPAIR_CASES, 900, True)

    def test_stochastic_decoders_match_java_build_and_local_edits(self):
        self._compare("c-stochastic-java-baseline.json", STOCHASTIC_CASES, 720, True)

    def _compare(self, fixture, numbers, count, registered, runtime_rkhma=False):
        baseline = Path(__file__).parent / "fixtures" / fixture
        rows = json.loads(baseline.read_text(encoding="utf-8"))["rows"]
        self.assertEqual(len(rows), count)
        modules = {}
        for number in dict.fromkeys((1, *numbers)):
            directory = ROOT / "cases" / "C" / f"C-{number:03d}"
            path = directory / f"C{number:06d}.py"
            if registered:
                data = read_case(directory / f"C-{number:03d}.json")
                self.assertEqual(data.code, path.read_text(encoding="utf-8"))
            spec = importlib.util.spec_from_file_location(f"migrated_c_{number}", path)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            modules[number] = module
        if runtime_rkhma:
            from satellite_scheduler.runtime import rkhma_decoder
            modules[3] = rkhma_decoder
        fixtures = {number: scenario(number) for number in range(12)}
        for kind, number, case_number, mode, variant, expected_status, expected in rows:
            with self.subTest(scenario=number, case=case_number, mode=mode, variant=variant):
                problem, api, budget = fixtures[number]
                order = ORDERS[mode]
                empty = SatelliteView(0, frozenset(range(5)), (), "view")
                decoder = modules[case_number].create()
                if kind == "BUILD":
                    result = decoder.build(problem, api, empty, order, budget, FixedRandom())
                    actual = signature(problem, result)
                else:
                    original = modules[1].create().build(problem, api, empty, order, budget, FixedRandom())
                    present = {item.task.id for item in original}
                    released = set()
                    if original:
                        if variant == 1:
                            released.add(original[0].task.id)
                        if variant == 2:
                            released.update(present)
                        if variant == 3:
                            released.add(original[-1].task.id)
                    reconsider = tuple(task for task in order if task not in present)
                    reconsider += tuple(task for task in order if task in released)
                    view = SatelliteView(0, frozenset(range(5)), original, "view")
                    context = LocalContext(Phase.INIT if variant % 2 == 0 else Phase.REASSIGN)
                    plan = LocalPlan("view", frozenset(released), reconsider)
                    result = decoder.apply_local(problem, api, view, context, plan, budget, FixedRandom())
                    self.assertEqual(result.status.name, expected_status)
                    actual = signature(problem, result.observations)
                self.assertEqual(actual, expected)


if __name__ == "__main__":
    unittest.main()
