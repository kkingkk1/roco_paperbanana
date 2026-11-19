"""对照 Java 21 案例输出，验证已迁移的 B 初始化策略。"""

import importlib.util
import json
import unittest
from pathlib import Path

from satellite_scheduler import AttitudeRecord, ProblemAdapter, ProblemInstance, Satellite, Task, TimeWindow
from satellite_scheduler.cases import read_case
from satellite_scheduler.runtime.access import ProblemAccess
from satellite_scheduler.runtime.contracts import (
    BrokenEdge, Budget, Feedback, InitialTrial, LocalContext, LocalPlan, LocalResult, LocalTrial, Phase, SatelliteView,
)


ROOT = Path(__file__).resolve().parents[3]


class Evaluator:
    def __init__(self):
        self.calls = 0

    def evaluate(self, order):
        self.calls += 1
        score = sum(value * (len(order) - index) for index, value in enumerate(order))
        return InitialTrial(tuple(order), (), score)


class FixedRandom:
    """与 Java 对照程序提供相同的整数、均匀数和高斯数序列。"""

    def __init__(self):
        self.index = 0

    def randrange(self, bound):
        self.index += 1
        return self.index % bound

    def random(self):
        self.index += 1
        return ((self.index * 37) % 97) / 97.0

    def gauss(self, mean, sigma):
        self.index += 1
        return mean + sigma * ((self.index * 17) % 13 - 6) / 4.0


class LocalEvaluator:
    def __init__(self, view, scenario_number):
        self.view = view
        self.scenario_number = scenario_number
        self.calls = 0

    def evaluate(self, plan):
        self.calls += 1
        score = sum(value * (len(plan.reconsider) - i) for i, value in enumerate(plan.reconsider))
        score += sum(task * 7 for task in plan.release)
        unavailable = (score + self.scenario_number + self.calls) % 7 == 0
        result = LocalResult.unchanged() if unavailable else LocalResult.applied(())
        return LocalTrial(plan, result, None if unavailable else score)

    def rebuild(self, priorities):
        released = frozenset(item.task.id for item in self.view.observations)
        return self.evaluate(LocalPlan(self.view.hash, released, tuple(priorities)))


def scenario(number: int):
    tasks = []
    windows = []
    for task_id in range(5):
        reward = (task_id * 7 + number * 3) % 13 + 1
        duration = task_id % 3 + 1
        tasks.append(Task(task_id, 0, 0, reward, duration))
        if (task_id + number) % 7 == 0:
            continue
        start = 3 + (task_id * 19 + number * 11) % 40
        end = start + 4 + (task_id * 3 + number) % 7
        records = tuple(AttitudeRecord(time, 0, 0) for time in range(start, end + 1))
        windows.append(TimeWindow(0, task_id, start, end, records))
    problem = ProblemInstance(tuple(tasks), (Satellite(0, tuple(windows)),))
    budget = Budget(lambda: 0, 0, 2**63 - 1, 2**63 - 1)
    view = SatelliteView(0, frozenset(range(5)), (), "view")
    return problem, ProblemAccess(problem, budget), view, budget


class MigratedBCaseTest(unittest.TestCase):
    def test_java_results_for_twelve_scenarios_and_four_budgets(self):
        baseline = Path(__file__).parent / "fixtures" / "b-case-java-baseline.json"
        rows = json.loads(baseline.read_text(encoding="utf-8"))["rows"]
        self.assertEqual(len(rows), 1344)
        modules = {}
        for number in (*range(4, 31), 40):
            case_id = f"BI-{number - 3:03d}" if number != 40 else "BI-030"
            directory = ROOT / "cases" / "B" / "initial_single_satellite_optimization" / case_id
            metadata = directory / f"{case_id}.json"
            data = read_case(metadata)
            path = directory / f"B{number:06d}.py"
            self.assertEqual(data.code, path.read_text(encoding="utf-8"))
            spec = importlib.util.spec_from_file_location(f"migrated_case_{number}", path)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            modules[number] = module
        for scenario_number, number, limit, expected_score, expected_order in rows:
            with self.subTest(scenario=scenario_number, case=number, limit=limit):
                problem, api, view, budget = scenario(scenario_number)
                evaluator = Evaluator()
                result = modules[number].search(problem, api, view, evaluator, budget, FixedRandom(), limit)
                self.assertEqual(result.priorities, tuple(expected_order))
                self.assertEqual(result.total_profit, expected_score)
                self.assertLessEqual(evaluator.calls, limit)
        for module in modules.values():
            problem, api, view, budget = scenario(0)
            with self.assertRaises(ValueError):
                module.search(problem, api, view, Evaluator(), budget, FixedRandom(), 0)

    def test_initial_phase_priority_and_local_plans_match_java(self):
        baseline = Path(__file__).parent / "fixtures" / "b-phase-java-baseline.json"
        rows = json.loads(baseline.read_text(encoding="utf-8"))["rows"]
        self.assertEqual(len(rows), 1512)
        methods = {}
        for number in (38, 39):
            case_id = f"BI-{number - 10:03d}"
            directory = ROOT / "cases" / "B" / "initial_single_satellite_optimization" / case_id
            data = read_case(directory / f"{case_id}.json")
            path = directory / f"B{number:06d}.py"
            self.assertEqual(data.code, path.read_text(encoding="utf-8"))
            spec = importlib.util.spec_from_file_location(f"migrated_case_{number}", path)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            methods[number] = module.create()
        for kind, number, case_number, mode, attempt, expected in rows:
            with self.subTest(scenario=number, case=case_number, mode=mode, attempt=attempt):
                problem, api, _, budget = scenario(number)
                adapter = ProblemAdapter(problem)
                present = []
                for window in problem.satellite(0).windows:
                    if mode == 0 or window.task_id % 2 != mode - 1:
                        continue
                    candidate = next(adapter.candidates(window), None)
                    if candidate is not None:
                        present.append(candidate)
                view = SatelliteView(0, frozenset(range(5)), tuple(present), "view")
                method = methods[case_number]
                if kind == "PRIORITY":
                    actual = list(method.initial_priority(problem, api, view, budget, None))
                else:
                    result = method.propose_initial_local(problem, api, view, LocalContext(Phase.INIT),
                                                          Feedback(attempt, "START"), budget, None)
                    actual = None if result is None else [sorted(result.release), list(result.reconsider)]
                self.assertEqual(actual, expected)

    def test_reassignment_cases_match_java(self):
        baseline = Path(__file__).parent / "fixtures" / "b-reassign-java-baseline.json"
        rows = json.loads(baseline.read_text(encoding="utf-8"))["rows"]
        self.assertEqual(len(rows), 3456)
        modules = {}
        for number in (31, 32, 33, 34, 35, 36, 37, 41, 42, 43):
            case_id = f"BR-{number - 30:03d}" if number <= 37 else f"BR-{number - 33:03d}"
            directory = ROOT / "cases" / "B" / "post_reassignment_adjustment" / case_id
            data = read_case(directory / f"{case_id}.json")
            path = directory / f"B{number:06d}.py"
            self.assertEqual(data.code, path.read_text(encoding="utf-8"))
            spec = importlib.util.spec_from_file_location(f"migrated_case_{number}", path)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            modules[number] = module
        for row in rows:
            kind, number, case_number, mode, parameter = row[:5]
            with self.subTest(scenario=number, case=case_number, mode=mode, parameter=parameter):
                problem, api, _, budget = scenario(number)
                adapter = ProblemAdapter(problem)
                present = []
                for window in problem.satellite(0).windows:
                    if mode == 0 or (mode < 3 and window.task_id % 2 != mode - 1):
                        continue
                    candidate = next(adapter.candidates(window), None)
                    if candidate is not None:
                        present.append(candidate)
                present.sort(key=lambda item: item.start)
                view = SatelliteView(0, frozenset(range(5)), tuple(present), "view")
                broken = tuple(BrokenEdge(a.task.id, b.task.id) for a, b in zip(present, present[1:])) \
                    if number % 2 == 0 else ()
                context = LocalContext(Phase.REASSIGN, frozenset((2, 4)), frozenset(), broken)
                if kind == "PLAN":
                    expected = row[5]
                    result = modules[case_number].create().propose_reassignment_local(
                        problem, api, view, context, Feedback(parameter, "START"), budget, FixedRandom())
                    actual = None if result is None else [sorted(result.release), list(result.reconsider)]
                else:
                    calls, expected = row[5:]
                    evaluator = LocalEvaluator(view, number)
                    result = modules[case_number].search(
                        problem, api, view, context, evaluator, budget, FixedRandom(), parameter)
                    actual = None if result is None else [result.total_profit, sorted(result.plan.release),
                                                          list(result.plan.reconsider)]
                    self.assertEqual(evaluator.calls, calls)
                    self.assertLessEqual(evaluator.calls, parameter)
                self.assertEqual(actual, expected)


if __name__ == "__main__":
    unittest.main()
