"""问题合同边界；问题层与方法策略分开。"""

import math
import unittest
from dataclasses import FrozenInstanceError
from pathlib import Path

from satellite_scheduler import (
    AttitudeRecord, ProblemAdapter, ProblemInstance, Satellite, ScheduledTask,
    Task, TimeWindow, evaluate, load_scene, transformation_time,
)
from satellite_scheduler.problem import transition_feasible


def record(time: float, roll: float = 0, pitch: float = 0) -> AttitudeRecord:
    return AttitudeRecord(time, roll, pitch)


def window(satellite_id: int, task_id: int, start: float = 0, end: float = 200) -> TimeWindow:
    return TimeWindow(satellite_id, task_id, start, end, tuple(record(i) for i in range(201)))


def fixture():
    tasks = (Task(0, 10, 20, 7, 5), Task(1, 10, 20, 3, 5))
    first, second, other = window(0, 0), window(0, 1), window(1, 0)
    instance = ProblemInstance(tasks, (Satellite(0, (first, second)), Satellite(1, (other,))))
    return instance, tasks, first, second, other


def observation(task: Task, w: TimeWindow, start_index: int) -> ScheduledTask:
    return ScheduledTask(task, w, w.attitudes[start_index], w.attitudes[start_index + task.duration])


class ProblemContractTest(unittest.TestCase):
    def test_valid_unsorted_and_empty_plans(self):
        instance, tasks, first, second, _ = fixture()
        plan = (observation(tasks[1], second, 30), observation(tasks[0], first, 1))
        result = evaluate(instance, plan)
        self.assertTrue(result.feasible, result.violations)
        self.assertEqual(result.total_profit, 10)
        self.assertEqual(plan[0].task.id, 1)
        self.assertEqual(evaluate(instance, ()).total_profit, 0)
        self.assertTrue(evaluate(instance, ()).feasible)

    def test_duplicate_overlap_and_transition_are_rejected(self):
        instance, tasks, first, second, other = fixture()
        duplicate = evaluate(instance, (observation(tasks[0], first, 1),
                                        observation(tasks[0], other, 1)))
        self.assertFalse(duplicate.feasible)
        self.assertEqual(duplicate.total_profit, 0)
        self.assertTrue(any("DUPLICATE_TASK" in item for item in duplicate.violations))
        overlap = evaluate(instance, (observation(tasks[0], first, 1),
                                      observation(tasks[1], second, 3)))
        self.assertTrue(any("OVERLAP" in item for item in overlap.violations))
        short = evaluate(instance, (observation(tasks[0], first, 1),
                                    observation(tasks[1], second, 16)))
        self.assertTrue(any("ATTITUDE_TRANSITION" in item for item in short.violations))
        valid = evaluate(instance, (observation(tasks[0], first, 1),
                                    observation(tasks[1], second, 18)))
        self.assertTrue(valid.feasible)

    def test_foreign_objects_and_boundary_records_are_rejected(self):
        instance, tasks, first, _, _ = fixture()
        foreign_task = Task(0, 10, 20, 7, 5)
        foreign = evaluate(instance, (observation(foreign_task, first, 1),))
        self.assertIn("TASK_NOT_IN_INSTANCE", foreign.violations[0])
        foreign_window = window(0, 0)
        foreign = evaluate(instance, (observation(tasks[0], foreign_window, 1),))
        self.assertIn("WINDOW_NOT_IN_INSTANCE", foreign.violations[0])
        foreign_record = ScheduledTask(tasks[0], first, record(1), first.attitudes[6])
        self.assertIn("ATTITUDE_NOT_IN_WINDOW", evaluate(instance, (foreign_record,)).violations[0])
        raw_start = observation(tasks[0], first, 0)
        self.assertIn("ATTITUDE_OUTSIDE_INTERNAL_GRID", evaluate(instance, (raw_start,)).violations[0])

    def test_duration_and_window_bounds_are_enforced(self):
        instance, tasks, first, _, _ = fixture()
        short = ScheduledTask(tasks[0], first, first.attitudes[1], first.attitudes[5])
        self.assertIn("IMAGING_DURATION", evaluate(instance, (short,)).violations[0])
        bounded = TimeWindow(0, 0, 2, 50, first.attitudes)
        own = ProblemInstance((tasks[0],), (Satellite(0, (bounded,)),))
        result = evaluate(own, (observation(tasks[0], bounded, 1),))
        self.assertTrue(any("OUTSIDE_WINDOW" in item for item in result.violations))

    def test_piecewise_transformation_boundaries(self):
        angles = (0, 10, 10.01, 30, 30.01, 60, 60.01, 90, 90.01)
        expected = (11.66, 11.66, 10.01 / 1.5 + 5, 25, 30.01 / 2 + 10,
                    40, 60.01 / 2.5 + 16, 52, 90.01 / 3 + 22)
        for angle, value in zip(angles, expected, strict=True):
            self.assertAlmostEqual(transformation_time(record(0), record(100, angle)), value)
        self.assertTrue(transition_feasible(record(0), record(11.66)))
        self.assertFalse(transition_feasible(record(0), record(11.659)))

    def test_candidate_enumeration_matches_exhaustive_pairs(self):
        task = Task(0, 0, 0, 7, 2)
        attitudes = tuple(record(time) for time in (0, 1, 2, 3, 4, 5, 9))
        w = TimeWindow(0, 0, 0, 10, attitudes)
        instance = ProblemInstance((task,), (Satellite(0, (w,)),))
        adapter = ProblemAdapter(instance)
        expected = [(a.time, b.time) for a in attitudes[1:-1] for b in attitudes[1:-1]
                    if b.time > a.time and abs(b.time - a.time - 2) <= 1e-6]
        actual = [(item.start, item.end) for item in adapter.candidates(w)]
        self.assertEqual(actual, expected)
        self.assertIs(adapter.grid(w)[0], attitudes[1])
        with self.assertRaises(ValueError):
            adapter.grid(TimeWindow(0, 0, 0, 10, attitudes))

    def test_uneven_boundary_is_allowed_but_internal_step_is_checked(self):
        task = Task(0, 0, 0, 1, 1)
        good = TimeWindow(0, 0, 0, 20, tuple(record(t) for t in (0, 1, 2, 3, 8)))
        ProblemInstance((task,), (Satellite(0, (good,)),))
        bad = TimeWindow(0, 0, 0, 20, tuple(record(t) for t in (0, 1, 2, 3.0000002, 8)))
        with self.assertRaisesRegex(ValueError, "raw index 3"):
            ProblemInstance((task,), (Satellite(0, (bad,)),))

    def test_invalid_inputs_and_immutable_data(self):
        task = Task(0, 0, 0, 1, 5)
        with self.assertRaises(ValueError):
            ProblemInstance((task, task), ())
        with self.assertRaises(ValueError):
            ProblemInstance((Task(0, math.nan, 0, 1, 5),), ())
        bad = TimeWindow(0, 0, 0, 5, (record(math.nan),))
        with self.assertRaises(ValueError):
            ProblemInstance((task,), (Satellite(0, (bad,)),))
        with self.assertRaises(FrozenInstanceError):
            task.priority = 4

    def test_loads_original_odd_line_format_and_validates_count(self):
        scene = Path(__file__).parent / "fixtures" / "basic"
        instance = load_scene(scene, 1, 1)
        self.assertEqual(instance.task(0).priority, 7)
        self.assertEqual(len(instance.windows_for(0, 0)), 1)
        self.assertEqual(len(instance.satellite(0).windows[0].attitudes), 4)
        self.assertEqual(len(list(ProblemAdapter(instance).candidates(instance.satellite(0).windows[0]))), 1)
        with self.assertRaisesRegex(ValueError, "Expected 2 tasks"):
            load_scene(scene, 2, 1)


if __name__ == "__main__":
    unittest.main()
