"""手工组合与既有 Java 穷举验收的同输入对照。"""

import csv
import json
import unittest
from pathlib import Path

from satellite_scheduler import AttitudeRecord, ProblemInstance, Satellite, Task, TimeWindow, evaluate
from satellite_scheduler.runtime.contracts import Assignment, InitialTrial, Limits, LocalPlan, LocalResult
from satellite_scheduler.runtime.manual import combination
from satellite_scheduler.runtime.solver import InvalidOutput, TwoStageSolver
from satellite_scheduler.manual_supervisor import validate_output


ROOT = Path(__file__).resolve().parents[3]
JAVA_EVIDENCE = ROOT / "vault" / "Results" / "Evidence" / "p1-small-enumeration" / "data.csv"


def window(satellite: int, task: int, first: float, step: float, count: int, roll: float) -> TimeWindow:
    start = first - .25
    end = first + step * (count - 1) + .25
    times = [start, *(first + step * index for index in range(count)), end]
    return TimeWindow(satellite, task, start, end, tuple(AttitudeRecord(time, roll, 0) for time in times))


def fixtures() -> dict[str, ProblemInstance]:
    contest_tasks = tuple(Task(i, 0, 0, profit, 5) for i, profit in enumerate((9, 5, 3)))
    contest_sats = tuple(Satellite(sat, tuple(window(sat, task, 10, 5, 2, 0) for task in range(3)))
                         for sat in range(2))
    grid_tasks = (Task(0, 0, 0, 10, 2), Task(1, 0, 0, 6, 2),
                  Task(2, 0, 0, 8, 2), Task(3, 0, 0, 7, 3))
    grid_sats = (Satellite(0, (window(0, 0, 10, .5, 7, 0), window(0, 1, 24, .5, 7, 0),
                               window(0, 2, 10, .5, 5, 30))),
                 Satellite(1, (window(1, 2, 40, .5, 5, 0), window(1, 0, 40, .5, 5, 0),
                               window(1, 3, 50, .5, 3, 0))))
    slew_tasks = tuple(Task(i, 0, 0, profit, 2) for i, profit in enumerate((5, 9, 7, 3)))
    slew_sats = (Satellite(0, (window(0, 0, 10, 2, 2, 0), window(0, 1, 36, 2, 2, 30),
                               window(0, 2, 37, 2, 2, 30), window(0, 3, 64, 2, 2, 0))),)
    return {"contest": ProblemInstance(contest_tasks, contest_sats),
            "half_second": ProblemInstance(grid_tasks, grid_sats),
            "slew_boundary": ProblemInstance(slew_tasks, slew_sats)}


class RuntimeParityTest(unittest.TestCase):
    def test_process_output_is_rebuilt_and_independently_validated(self):
        instance = fixtures()["contest"]
        data = {"status": "SUCCESS", "profit": 9, "elapsed_ns": 1, "detail": "COMPLETED",
                "observations": [{"task": 0, "satellite": 0, "window": 0, "start": 1, "end": 2}]}
        _, observations = validate_output(instance, json.dumps(data).encode())
        self.assertIs(observations[0].task, instance.task(0))
        for changes in (
            {"profit": 10},
            {"observations": [data["observations"][0], data["observations"][0]]},
            {"observations": [{**data["observations"][0], "start": 0}]},
            {"observations": [{**data["observations"][0], "task": 1}]},
            {"observations": [{**data["observations"][0], "window": -1}]},
            {"observations": [{**data["observations"][0], "start": True}]},
        ):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_output(instance, json.dumps({**data, **changes}).encode())
        with self.assertRaises(ValueError):
            validate_output(instance, b'{"status":"SUCCESS","status":"FAILURE"}')
        with self.assertRaises(ValueError):
            validate_output(instance, b'{"status":"SUCCESS","profit":NaN}')
        failure = {**data, "status": "FAILURE", "profit": None, "observations": []}
        self.assertEqual(validate_output(instance, json.dumps(failure).encode())[1], ())
        with self.assertRaises(ValueError):
            validate_output(instance, json.dumps({**failure, "profit": 9}).encode())

    def test_four_c3_combinations_against_java(self):
        path = Path(__file__).parent / 'fixtures' / 'c-rkhma-combinations-java-baseline.json'
        rows = json.loads(path.read_text(encoding='utf-8'))['rows']
        self.assertEqual(len(rows), 12)
        instances = fixtures()
        for fixture, name, profit, decisions, moves, accepted in rows:
            with self.subTest(fixture=fixture, combination=name):
                instance = instances[fixture]
                strategies = combination(name)
                result = TwoStageSolver(clock=lambda: 0).solve(
                    instance, strategies.a, strategies.b, strategies.c, 42)
                self.assertEqual(result.status, 'SUCCESS', result.detail)
                self.assertTrue(evaluate(instance, result.observations).feasible)
                self.assertEqual(result.total_profit, int(profit))
                actual = '|'.join(f'{o.task.id}@{o.window.satellite_id}:{o.start:.2f}:{o.end:.2f}'
                                  for o in sorted(result.observations, key=lambda item: item.task.id))
                self.assertEqual(actual, decisions)
                self.assertEqual(result.statistics.reassign_moves, int(moves))
                self.assertEqual(result.statistics.accepted_moves, int(accepted))

    def test_eight_combinations_against_java_enumeration_evidence(self):
        instances = fixtures()
        with JAVA_EVIDENCE.open(encoding="utf-8", newline="") as file:
            rows = list(csv.DictReader(file))
        self.assertEqual(len(rows), 24)
        for row in rows:
            with self.subTest(fixture=row["fixture"], combination=row["combination"]):
                instance = instances[row["fixture"]]
                strategies = combination(row["combination"])
                result = TwoStageSolver(clock=lambda: 0).solve(
                    instance, strategies.a, strategies.b, strategies.c, 42)
                self.assertEqual(result.status, row["status"], result.detail)
                self.assertEqual(result.total_profit, int(row["profit"]))
                self.assertEqual(len(result.observations), int(row["observations"]))
                self.assertTrue(evaluate(instance, result.observations).feasible)
                self.assertLessEqual(result.total_profit, int(row["optimum"]))
                decisions = "|".join(f"{o.task.id}@{o.window.satellite_id}:{o.start:.2f}:{o.end:.2f}"
                                     for o in sorted(result.observations, key=lambda item: item.task.id))
                self.assertEqual(decisions, row["decisions"])
                self.assertEqual(result.statistics.reassign_moves, int(row["reassign_moves"]))
                self.assertEqual(result.statistics.accepted_moves, int(row["accepted_moves"]))

    def test_rejects_unissued_initial_trial(self):
        strategies = combination("A1B1C1")

        class BadB:
            def search_initial(self, problem, api, view, evaluator, budget, rng):
                return InitialTrial((), (), 0)

        result = TwoStageSolver(clock=lambda: 0).solve(fixtures()["contest"],
                                                         strategies.a, BadB(), strategies.c, 42)
        self.assertEqual(result.status, "FAILURE")
        self.assertIn("not issued", result.detail)

    def test_rejects_owner_without_candidates(self):
        strategies = combination("A1B1C1")

        class BadA:
            def initial_assign(self, problem, api, budget, rng):
                return Assignment({task.id: 999 for task in problem.tasks})

        result = TwoStageSolver(clock=lambda: 0).solve(fixtures()["contest"],
                                                         BadA(), strategies.b, strategies.c, 42)
        self.assertEqual(result.status, "FAILURE")
        self.assertIn("no candidate", result.detail)

    def test_rejects_unauthorized_edit_even_if_strategy_catches_error(self):
        strategies = combination("A1B1C1")

        class CatchingB:
            def __getattr__(self, name):
                return getattr(strategies.b, name)

            def search_initial_local(self, problem, api, view, context, feedback, evaluator, budget, rng):
                if not view.observations:
                    return None
                try:
                    evaluator.evaluate(LocalPlan(view.hash, frozenset(), ()))
                except InvalidOutput:
                    return None

        class RemovingC:
            def __getattr__(self, name):
                return getattr(strategies.c, name)

            def apply_local(self, problem, api, view, context, plan, budget, rng):
                return LocalResult.applied(view.observations[1:])

        result = TwoStageSolver(clock=lambda: 0).solve(fixtures()["contest"],
                                                       strategies.a, CatchingB(), RemovingC(), 42)
        self.assertEqual(result.status, "FAILURE")
        self.assertIn("removed an unauthorized observation", result.detail)
        self.assertIsNone(result.total_profit)

    def test_trial_evaluator_closes_after_strategy_returns(self):
        strategies = combination("A1B1C1")
        captured = []

        class CapturingB:
            def __getattr__(self, name):
                return getattr(strategies.b, name)

            def search_initial(self, problem, api, view, evaluator, budget, rng):
                captured.append(evaluator)
                return strategies.b.search_initial(problem, api, view, evaluator, budget, rng)

        result = TwoStageSolver(clock=lambda: 0).solve(fixtures()["contest"],
                                                         strategies.a, CapturingB(), strategies.c, 42)
        self.assertEqual(result.status, "SUCCESS", result.detail)
        with self.assertRaisesRegex(InvalidOutput, "closed"):
            captured[0].evaluate(())

    def test_search_and_hard_deadlines_are_distinct(self):
        instance = fixtures()["contest"]
        strategies = combination("A1B1C1")
        ticks = iter((0, 1, 11, 11, 11, 11, 11))
        limits = Limits(search_ns=10, hard_ns=100, initial_local_ns=0, per_satellite_ns=1)
        result = TwoStageSolver(limits, clock=lambda: next(ticks, 11)).solve(
            instance, strategies.a, strategies.b, strategies.c, 42)
        self.assertEqual(result.status, "SUCCESS")
        self.assertEqual(result.detail, "SEARCH_EXPIRED")
        self.assertEqual(result.total_profit, 0)
        ticks = iter((0, 100, 100, 100))
        result = TwoStageSolver(limits, clock=lambda: next(ticks, 100)).solve(
            instance, strategies.a, strategies.b, strategies.c, 42)
        self.assertEqual(result.status, "HARD_TIMEOUT")
        self.assertIsNone(result.total_profit)


if __name__ == "__main__":
    unittest.main()
