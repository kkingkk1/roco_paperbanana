"""比较 A 案例与 Java 的归属及完整候选移动序列。"""

import importlib.util
import json
import math
import random
import struct
import unittest
from pathlib import Path

from satellite_scheduler import AttitudeRecord, ProblemInstance, Satellite, Task, TimeWindow, evaluate
from satellite_scheduler.cases import read_case
from satellite_scheduler.runtime.access import ProblemAccess
from satellite_scheduler.runtime.contracts import Assignment, Budget, Feedback, MoveTrial, SatelliteView, State
from satellite_scheduler.runtime.manual import combination
from satellite_scheduler.runtime.solver import TwoStageSolver


ROOT = Path(__file__).resolve().parents[3]


def load(relative):
    path = ROOT / relative
    spec = importlib.util.spec_from_file_location(path.stem, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def scenario(number):
    tasks = tuple(Task(task, 0, 0, (task * 7 + number * 3) % 13 + 1, task % 3 + 1)
                  for task in range(7))
    satellites = []
    for satellite in range(3):
        windows = []
        for task in range(7):
            if (task * 5 + satellite * 3 + number) % 5 == 0:
                continue
            variants = 2 if (task + satellite + number) % 3 == 0 else 1
            for variant in range(variants):
                start = 3 + (task * 19 + satellite * 7 + number * 11) % 80 + variant * 25
                end = start + 4 + (task * 3 + satellite + number + variant) % 7
                records = tuple(AttitudeRecord(
                    second, (task * 7 + number * 3 + second) % 31 - 15,
                    (task * 11 + second * 2 + number) % 21 - 10)
                    for second in range(start, end + 1))
                windows.append(TimeWindow(satellite, task, start, end, records))
        satellites.append(Satellite(satellite, tuple(windows)))
    return ProblemInstance(tasks, tuple(satellites))


def entries(mapping):
    return ",".join(f"{key}:{'-' if value is None else value}"
                    for key, value in sorted(mapping.items()))


def matching_matrix(number):
    if number == 0:
        return []
    if number == 1:
        return [[], [], []]
    rows, columns = 1 + (number * 3) % 6, 1 + (number * 7) % 5
    matrix = []
    for row in range(rows):
        values = []
        for column in range(columns):
            marker = (row * 7 + column * 11 + number) % 23
            special = {0: float("inf"), 1: float("-inf"), 2: float("nan"), 3: -0.0, 4: 0.0}
            values.append(special.get(marker, ((row * 17 + column * 11 + number * 5) % 29 - 14) / 3.0))
        matrix.append(values)
    return matrix


def gap_data(number):
    resources, tasks = 2 + number % 3, 2 + (number * 3) % 5
    profit = [[1 + (resource * 13 + task * 7 + number * 3) % 23
               for task in range(tasks)] for resource in range(resources)]
    weight = [[1 + (resource * 5 + task * 3 + number) % 6
               for task in range(tasks)] for resource in range(resources)]
    capacity = [min(weight[resource]) + (number + resource) % 8 for resource in range(resources)]
    return profit, weight, capacity


class MoveCollector:
    def evaluate(self, state, move):
        return move


class JavaRandom:
    """仅用于比较来源案例使用相同随机数输入的测试。"""

    def __init__(self, seed):
        self.state = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)

    def _next(self, bits):
        self.state = (self.state * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
        return self.state >> (48 - bits)

    def randrange(self, bound):
        if bound & (bound - 1) == 0:
            return (bound * self._next(31)) >> 31
        while True:
            bits = self._next(31)
            result = bits % bound
            if bits - result + bound - 1 < 2**31:
                return result


class TrialEvaluator:
    def __init__(self, problem, api, budget, scenario_number):
        self.problem, self.api, self.budget = problem, api, budget
        self.scenario_number = scenario_number
        self.calls, self.checkpoints = [], []

    def evaluate(self, base, move):
        self.calls.append(entries(move.edits))
        mix = self.scenario_number + sum(task * 3 + satellite * 7
                                         for task, satellite in move.edits.items())
        if mix % 5 == 0:
            return MoveTrial(move, None, None)
        owners = dict(base.assignment.owners)
        timelines = {satellite: list(timeline) for satellite, timeline in base.timelines.items()}
        for task, satellite in move.edits.items():
            owners[task] = satellite
            for other in timelines:
                timelines[other] = [item for item in timelines[other] if item.task.id != task]
            if (task + satellite + self.scenario_number) % 3 != 0:
                timelines[satellite].append(next(self.api.candidates(task, satellite, self.budget)))
        profit = sum(self.problem.task(item.task.id).priority
                     for timeline in timelines.values() for item in timeline)
        return MoveTrial(move, State(Assignment(owners), timelines, "trial"), profit)

    def checkpoint(self, trial):
        self.checkpoints.append(entries(trial.move.edits))


class MigratedACaseTest(unittest.TestCase):
    def test_rkhma_adaptation_initial_assignment_matches_java_samples(self):
        reference = json.loads((Path(__file__).parent / 'fixtures' /
                                'a-rkhma-initial-java-baseline.json').read_text(encoding='utf-8'))['rows']
        strategy = load('cases/A/A-010/A000010.py').create()
        budget = Budget(lambda: 0, 0, 2**63 - 1, 2**63 - 1)
        actual = []
        for number in range(12):
            problem = scenario(number)
            assignment = strategy.initial_assign(problem, ProblemAccess(problem, budget), budget, random.Random(42))
            actual.append(f'RKHMA_INITIAL={number};{entries(assignment.owners)}')
        self.assertEqual(actual, reference)

    def test_rkhma_adaptation_tries_each_local_move_kind(self):
        tasks = tuple(Task(task, 0, 0, task + 1, 2) for task in range(4))
        satellites = tuple(Satellite(satellite, tuple(
            TimeWindow(satellite, task, 10, 16,
                       tuple(AttitudeRecord(second, 0, 0) for second in range(10, 17)))
            for task in range(4))) for satellite in range(2))
        problem = ProblemInstance(tasks, satellites)
        budget = Budget(lambda: 0, 0, 2**63 - 1, 2**63 - 1)
        api = ProblemAccess(problem, budget)
        assignment = Assignment({0: 0, 1: 1, 2: 1, 3: 1})
        observation = next(api.candidates(0, 0, budget))
        state = State(assignment, {0: (observation,), 1: ()}, 'base')

        class Evaluator:
            def __init__(self, kind):
                self.kind, self.checkpoints = kind, []

            def evaluate(self, base, move):
                edits = move.edits
                if len(edits) == 1:
                    kind = 'migration'
                elif any(value is None for value in edits.values()):
                    kind = 'replacement'
                elif 0 in edits:
                    kind = 'exchange'
                else:
                    kind = 'bundle'
                return MoveTrial(move, base, 6) if kind == self.kind else MoveTrial(move, None, None)

            def checkpoint(self, trial):
                self.checkpoints.append(trial)

        for kind in ('migration', 'replacement', 'exchange', 'bundle'):
            with self.subTest(kind=kind):
                strategy = load('cases/A/A-010/A000010.py').create()
                strategy.prepare(problem, api, assignment, budget, 42)
                evaluator = Evaluator(kind)
                selected = strategy.search_reassignment(problem, api, state, Feedback(0, ''),
                                                        evaluator, budget, random.Random(42))
                self.assertIsNotNone(selected)
                self.assertEqual(selected.total_profit, 6)
                self.assertIn(selected, evaluator.checkpoints)

    def test_rkhma_adaptation_runs_through_real_trial_guard(self):
        from test_runtime import fixtures
        for name, problem in fixtures().items():
            with self.subTest(fixture=name):
                manual = combination('A1B1C1')
                strategy = load('cases/A/A-010/A000010.py').create()
                result = TwoStageSolver(clock=lambda: 0).solve(problem, strategy, manual.b, manual.c, 42)
                self.assertEqual(result.status, 'SUCCESS', result.detail)
                self.assertTrue(evaluate(problem, result.observations).feasible)

    def test_matching_allocators_match_java_on_rectangular_and_nonfinite_inputs(self):
        reference = json.loads((Path(__file__).parent / "fixtures" /
                                "a-matching-java-baseline.json").read_text(encoding="utf-8"))["rows"]
        self.assertEqual(len(reference), 240)
        modules = {}
        for case in (20, 25):
            source = f"cases/A/A-{case:03d}/A{case:06d}.py"
            metadata = ROOT / "cases" / "A" / f"A-{case:03d}" / f"A-{case:03d}.json"
            self.assertEqual(read_case(metadata).code, (ROOT / source).read_text(encoding="utf-8"))
            modules[case] = load(source)
        actual = []
        for number in range(120):
            matrix = matching_matrix(number)
            for case, module in modules.items():
                result = module.solve(matrix)
                bits = struct.pack(">d", result.total_cost).hex()
                row_assignments = ",".join(map(str, result.row_assignment))
                column_assignments = ",".join(map(str, result.column_assignment))
                actual.append(f"MATCH={number};{case};{bits};{row_assignments};{column_assignments}")
        self.assertEqual(actual, reference)

    def test_hungarian_allocator_matches_java(self):
        reference = json.loads((Path(__file__).parent / "fixtures" /
                                "a-hungarian-java-baseline.json").read_text(encoding="utf-8"))["rows"]
        self.assertEqual(len(reference), 120)
        source = "cases/A/A-021/A000021.py"
        metadata = ROOT / "cases/A/A-021/A-021.json"
        self.assertEqual(read_case(metadata).code, (ROOT / source).read_text(encoding="utf-8"))
        module = load(source)
        actual = []
        for number in range(120):
            matrix = matching_matrix(number)
            for row, values in enumerate(matrix):
                for column, value in enumerate(values):
                    if not math.isfinite(value):
                        values[column] = row * 5.0 - column * 3.0
            try:
                result = module.solve(matrix)
            except ValueError as error:
                actual.append(f"HUNGARIAN_ERROR={number};{error}")
            else:
                bits = struct.pack(">d", result.total_cost).hex()
                rows = ",".join(map(str, result.row_assignment))
                columns = ",".join(map(str, result.column_assignment))
                actual.append(f"HUNGARIAN={number};{bits};{rows};{columns}")
        self.assertEqual(actual, reference)

    def test_auction_and_scaling_allocators_match_java(self):
        reference = json.loads((Path(__file__).parent / "fixtures" /
                                "a-scaling-java-baseline.json").read_text(encoding="utf-8"))["rows"]
        self.assertEqual(len(reference), 240)
        modules = []
        for name, case in (("AUCTION", 22), ("SCALING", 27)):
            source = f"cases/A/A-{case:03d}/A{case:06d}.py"
            metadata = ROOT / "cases" / "A" / f"A-{case:03d}" / f"A-{case:03d}.json"
            self.assertEqual(read_case(metadata).code, (ROOT / source).read_text(encoding="utf-8"))
            modules.append((name, load(source)))
        actual = []
        for number in range(120):
            matrix = matching_matrix(number)
            for row, values in enumerate(matrix):
                for column, value in enumerate(values):
                    if not math.isfinite(value):
                        values[column] = row * 5.0 - column * 3.0
            for name, module in modules:
                try:
                    result = module.solve(matrix)
                except ValueError as error:
                    actual.append(f"{name}_ERROR={number};{error}")
                else:
                    bits = struct.pack(">d", result.total_cost).hex()
                    rows = ",".join(map(str, result.row_assignment))
                    columns = ",".join(map(str, result.column_assignment))
                    actual.append(f"{name}={number};{bits};{rows};{columns}")
        self.assertEqual(actual, reference)

    def test_murty_allocator_matches_java_solution_order(self):
        reference = json.loads((Path(__file__).parent / "fixtures" /
                                "a-murty-java-baseline.json").read_text(encoding="utf-8"))["rows"]
        self.assertEqual(len(reference), 480)
        source = "cases/A/A-026/A000026.py"
        metadata = ROOT / "cases/A/A-026/A-026.json"
        self.assertEqual(read_case(metadata).code, (ROOT / source).read_text(encoding="utf-8"))
        module = load(source)
        actual = []
        for number in range(120):
            matrix = matching_matrix(number)
            for row, values in enumerate(matrix):
                for column, value in enumerate(values):
                    if not math.isfinite(value):
                        values[column] = row * 5.0 - column * 3.0
            for count in (0, 1, 3, 8):
                try:
                    results = module.solve_k_best(matrix, count)
                except ValueError as error:
                    actual.append(f"MURTY_ERROR={number};{count};{error}")
                else:
                    solutions = []
                    for result in results:
                        bits = struct.pack(">d", result.total_cost).hex()
                        rows = ",".join(map(str, result.row_assignment))
                        columns = ",".join(map(str, result.column_assignment))
                        solutions.append(f"{bits}@{rows}@{columns}")
                    actual.append(f"MURTY={number};{count};{'|'.join(solutions)}")
        self.assertEqual(actual, reference)

    def test_simplex_allocator_and_duals_match_java(self):
        reference = json.loads((Path(__file__).parent / "fixtures" /
                                "a-simplex-java-baseline.json").read_text(encoding="utf-8"))["rows"]
        self.assertEqual(len(reference), 240)
        source = "cases/A/A-024/A000024.py"
        metadata = ROOT / "cases/A/A-024/A-024.json"
        self.assertEqual(read_case(metadata).code, (ROOT / source).read_text(encoding="utf-8"))
        module = load(source)
        actual = []
        for number in range(120):
            matrix = matching_matrix(number)
            for row, values in enumerate(matrix):
                for column, value in enumerate(values):
                    if not math.isfinite(value):
                        values[column] = row * 5.0 - column * 3.0
            for name, function in (("SIMPLEX", module.solve), ("DUALS", module.solve_duals)):
                try:
                    result = function(matrix)
                except ValueError as error:
                    actual.append(f"{name}_ERROR={number};{error}")
                else:
                    assignment = result.assignment if name == "DUALS" else result
                    bits = struct.pack(">d", assignment.total_cost).hex()
                    rows = ",".join(map(str, assignment.row_assignment))
                    columns = ",".join(map(str, assignment.column_assignment))
                    line = f"{name}={number};{bits};{rows};{columns}"
                    if name == "DUALS":
                        row_dual = ",".join(struct.pack(">d", value).hex() for value in result.row_dual)
                        column_dual = ",".join(struct.pack(">d", value).hex() for value in result.column_dual)
                        line += f";{row_dual};{column_dual}"
                    actual.append(line)
        self.assertEqual(actual, reference)

    def test_quota_allocator_matches_java_without_wall_time_cutoff(self):
        reference = json.loads((Path(__file__).parent / "fixtures" /
                                "a-quota-java-baseline.json").read_text(encoding="utf-8"))["rows"]
        self.assertEqual(len(reference), 720)
        source = "cases/A/A-028/A000028.py"
        metadata = ROOT / "cases/A/A-028/A-028.json"
        self.assertEqual(read_case(metadata).code, (ROOT / source).read_text(encoding="utf-8"))
        module = load(source)
        actual = []
        for number in range(120):
            columns, rows = 2 + number % 3, 2 + number % 3 + number % 5
            quotas = [1] * columns
            for row in range(columns, rows):
                quotas[(row + number) % columns] += 1
            scores = [[((row * 17 + column * 11 + number * 7) % 31 - 10) / 3.0
                       for column in range(columns)] for row in range(rows)]
            absolute = 1.0 if number % 7 == 0 else 0.0
            percent = 2.0 if number % 11 == 0 else 0.0
            for skip in (False, True):
                for limit in (0, 1, 5):
                    result = module.solve(scores, quotas, 60.0, absolute, percent, limit, skip)
                    bits = struct.pack(">f", result.total_score).hex()
                    groups = "|".join(",".join(map(str, group)) for group in result.rows_by_resource)
                    actual.append(f"QUOTA={number};{str(skip).lower()};{limit};{bits};"
                                  f"{result.stop_reason};{result.iterations};{groups}")
        self.assertEqual(actual, reference)

    def test_flow_allocator_matches_java(self):
        reference = json.loads((Path(__file__).parent / "fixtures" /
                                "a-flow-java-baseline.json").read_text(encoding="utf-8"))["rows"]
        self.assertEqual(len(reference), 120)
        source = "cases/A/A-023/A000023.py"
        metadata = ROOT / "cases/A/A-023/A-023.json"
        self.assertEqual(read_case(metadata).code, (ROOT / source).read_text(encoding="utf-8"))
        module = load(source)
        actual = []
        for number in range(120):
            result = module.solve(matching_matrix(number))
            bits = struct.pack(">d", result.total_cost).hex()
            rows = ",".join(map(str, result.row_assignment))
            columns = ",".join(map(str, result.column_assignment))
            actual.append(f"FLOW={number};{bits};{rows};{columns}")
        self.assertEqual(actual, reference)

    def test_asymmetric_allocator_matches_java_with_fixed_row_order(self):
        reference = json.loads((Path(__file__).parent / "fixtures" /
                                "a-asymmetric-java-baseline.json").read_text(encoding="utf-8"))["rows"]
        self.assertEqual(len(reference), 120)
        source = "cases/A/A-029/A000029.py"
        metadata = ROOT / "cases/A/A-029/A-029.json"
        self.assertEqual(read_case(metadata).code, (ROOT / source).read_text(encoding="utf-8"))
        module = load(source)
        actual = []
        for number in range(120):
            rows = 1 + number % 5
            columns = rows + (number * 3) % 4
            matrix = [[((row * 17 + column * 11 + number * 7) % 19) - 9
                       for column in range(columns)] for row in range(rows)]
            order = list(range(rows)) if number % 2 == 0 else list(reversed(range(rows)))
            result = module.optimize(matrix, number % 3 == 0, order, 15, 3, 0.001, 1 + number % 3)
            actual.append(f"ASYMMETRIC={number};{','.join(map(str, result.rows))};"
                          f"{','.join(map(str, result.columns))}")
        self.assertEqual(actual, reference)

    def test_generalized_assignment_bounds_match_java(self):
        reference = json.loads((Path(__file__).parent / "fixtures" /
                                "a-gap-java-baseline.json").read_text(encoding="utf-8"))["rows"]
        self.assertEqual(len(reference), 800)
        source = "cases/A/A-019/A000019.py"
        metadata = ROOT / "cases/A/A-019/A-019.json"
        self.assertEqual(read_case(metadata).code, (ROOT / source).read_text(encoding="utf-8"))
        module = load(source)
        actual = []
        for number in range(80):
            profit, weight, capacity = gap_data(number)
            for minimize in (False, True):
                for limit in (-1, 0, 1, 5, 30):
                    result = module.solve(profit, weight, capacity, minimize, limit)
                    actual.append(f"GAP={number};{str(minimize).lower()};{limit};{result.value};"
                                  f"{result.bound};{str(result.optimal).lower()};{result.explored_nodes};"
                                  f"{','.join(map(str, result.resource_by_task))}")
        self.assertEqual(actual, reference)

    def test_generalized_assignment_heuristic_matches_java(self):
        reference = json.loads((Path(__file__).parent / "fixtures" /
                                "a-mthg-java-baseline.json").read_text(encoding="utf-8"))["rows"]
        self.assertEqual(len(reference), 160)
        source = "cases/A/A-018/A000018.py"
        metadata = ROOT / "cases/A/A-018/A-018.json"
        self.assertEqual(read_case(metadata).code, (ROOT / source).read_text(encoding="utf-8"))
        module = load(source)
        actual = []
        for number in range(80):
            profit, weight, capacity = gap_data(number)
            for minimize in (False, True):
                result = module.solve(profit, weight, capacity, minimize)
                actual.append(f"MTHG={number};{str(minimize).lower()};{result.value};"
                              f"{','.join(map(str, result.resource_by_task))}")
            self.assertEqual((profit, weight, capacity), gap_data(number))
        self.assertEqual(actual, reference)

    def test_source_allocators_match_java_and_preserve_prior_assignments(self):
        reference = json.loads((Path(__file__).parent / "fixtures" /
                                "a-origin-java-baseline.json").read_text(encoding="utf-8"))["rows"]
        self.assertEqual(len(reference), 168)
        modules = {}
        for case in range(3, 10):
            source = f"cases/A/A-{case:03d}/A{case:06d}.py"
            metadata = ROOT / "cases" / "A" / f"A-{case:03d}" / f"A-{case:03d}.json"
            self.assertEqual(read_case(metadata).code, (ROOT / source).read_text(encoding="utf-8"))
            modules[case] = load(source)
        actual = []
        for number in range(12):
            for variant in range(2):
                problem = scenario(number)
                prior = {
                    satellite.id: ([Task(100 + satellite.id, 0, 0,
                                          (satellite.id * 3 + number) % 9 + 1, 2)]
                                   if (number + satellite.id) % 2 == 0 else [])
                    for satellite in problem.satellites
                }
                expected_prior = {satellite: tuple(tasks) for satellite, tasks in prior.items()}
                validated = {}
                if variant == 1:
                    for task in problem.tasks:
                        for satellite in problem.satellites:
                            for index, window in enumerate(problem.windows_for(task.id, satellite.id)):
                                if (task.id + satellite.id + number + index) % 4 == 0:
                                    validated[window, task.duration] = (task.id + satellite.id + number) % 3 != 0
                for case, module in modules.items():
                    result = module.initial(problem, prior, JavaRandom(42), validated)
                    actual.append(f"ORIGIN={number};{variant};{case};"
                                  + "|".join(f"{satellite}:{task}" for satellite, task in result))
                    self.assertEqual({satellite: tuple(tasks) for satellite, tasks in prior.items()},
                                     expected_prior)
        self.assertEqual(actual, reference)

    def test_feedback_allocators_match_java_trial_sequences(self):
        reference = json.loads((Path(__file__).parent / "fixtures" /
                                "a-feedback-java-baseline.json").read_text(encoding="utf-8"))["rows"]
        self.assertEqual(len(reference), 240)
        decoder = load("cases/C/C-001/C000001.py").create()
        actual = []
        for number in range(12):
            problem = scenario(number)
            budget = Budget(lambda: 0, 0, 2**63 - 1, 2**63 - 1)
            api = ProblemAccess(problem, budget)
            for case in (11, 17):
                source = f"cases/A/A-{case:03d}/A{case:06d}.py"
                metadata = ROOT / "cases" / "A" / f"A-{case:03d}" / f"A-{case:03d}.json"
                self.assertEqual(read_case(metadata).code, (ROOT / source).read_text(encoding="utf-8"))
                strategy = load(source).create()
                assignment = strategy.initial_assign(problem, api, budget, random.Random(42))
                actual.append(f"FEEDBACK_INITIAL={number};{case};{entries(assignment.owners)}")
                built = {}
                for satellite in range(3):
                    order = tuple(task for task in range(7) if assignment.owners[task] == satellite)
                    view = SatelliteView(satellite, frozenset(order), (), "view")
                    built[satellite] = decoder.build(problem, api, view, order, budget, random.Random(42))
                for variant in range(3):
                    timelines = {
                        satellite: tuple(item for item in built[satellite]
                                         if variant == 1 or
                                         (variant == 2 and (item.task.id + number) % 2 == 0))
                        for satellite in range(3)
                    }
                    state = State(assignment, timelines, f"f{number}v{variant}")
                    for round_number in range(3):
                        evaluator = TrialEvaluator(problem, api, budget, number)
                        best = strategy.search_reassignment(
                            problem, api, state, Feedback(round_number, ""), evaluator,
                            budget, random.Random(42))
                        selected = "-" if best is None else entries(best.move.edits)
                        profit = "-" if best is None else best.total_profit
                        actual.append(f"FEEDBACK={number};{case};{variant};{round_number};"
                                      f"{selected};{profit};{'|'.join(evaluator.calls)};"
                                      f"{'|'.join(evaluator.checkpoints)}")
        self.assertEqual(actual, reference)

    def test_initial_only_allocators_match_java(self):
        reference = json.loads((Path(__file__).parent / "fixtures" /
                                "a-initial-java-baseline.json").read_text(encoding="utf-8"))["rows"]
        self.assertEqual(len(reference), 60)
        actual = []
        for number in range(12):
            problem = scenario(number)
            budget = Budget(lambda: 0, 0, 2**63 - 1, 2**63 - 1)
            api = ProblemAccess(problem, budget)
            for case in range(12, 17):
                source = f"cases/A/A-{case:03d}/A{case:06d}.py"
                metadata = ROOT / "cases" / "A" / f"A-{case:03d}" / f"A-{case:03d}.json"
                self.assertEqual(read_case(metadata).code, (ROOT / source).read_text(encoding="utf-8"))
                assignment = load(source).assign(problem, api, budget)
                actual.append(f"INITIAL={number};{case};{entries(assignment.owners)}")
        self.assertEqual(actual, reference)

    def test_manual_allocators_match_java(self):
        reference = json.loads((Path(__file__).parent / "fixtures" /
                                "a-manual-java-baseline.json").read_text(encoding="utf-8"))["rows"]
        self.assertEqual(len(reference), 96)
        decoder = load("cases/C/C-001/C000001.py").create()
        actual = []
        for number in range(12):
            problem = scenario(number)
            budget = Budget(lambda: 0, 0, 2**63 - 1, 2**63 - 1)
            api = ProblemAccess(problem, budget)
            for case in (1, 2):
                source = f"cases/A/A-{case:03d}/A{case:06d}.py"
                metadata = ROOT / "cases" / "A" / f"A-{case:03d}" / f"A-{case:03d}.json"
                self.assertEqual(read_case(metadata).code, (ROOT / source).read_text(encoding="utf-8"))
                strategy = load(source).create()
                assignment = strategy.initial_assign(problem, api, budget, random.Random(42))
                actual.append(f"ASSIGN={number};{case};{entries(assignment.owners)}")
                built = {}
                for satellite in range(3):
                    order = tuple(task for task in range(7)
                                  if assignment.owners[task] == satellite)
                    view = SatelliteView(satellite, frozenset(order), (), "view")
                    built[satellite] = decoder.build(problem, api, view, order, budget, random.Random(42))
                for variant in range(3):
                    timelines = {
                        satellite: tuple(item for item in built[satellite]
                                         if variant == 1 or
                                         (variant == 2 and (item.task.id + number) % 2 == 0))
                        for satellite in range(3)
                    }
                    state = State(assignment, timelines, f"s{number}v{variant}")
                    moves = []
                    for index in range(1000):
                        move = strategy.search_reassignment(
                            problem, api, state, Feedback(index, ""), MoveCollector(), budget, random.Random(42))
                        if move is None:
                            break
                        moves.append(entries(move.edits))
                    else:
                        self.fail("move enumeration did not finish")
                    actual.append(f"MOVES={number};{case};{variant};{'|'.join(moves)}")
        self.assertEqual(actual, reference)


if __name__ == "__main__":
    unittest.main()
