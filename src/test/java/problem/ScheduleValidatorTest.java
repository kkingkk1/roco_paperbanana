package problem;

import model.AttitudeRecord;
import model.ProblemInstance;
import model.Satellite;
import model.ScheduledTask;
import model.SchedulingPlan;
import model.Task;
import model.TimeWindow;
import org.junit.jupiter.api.Test;
import utils.TimeUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleValidatorTest {
  private record Fixture(ProblemInstance instance, Task first, Task second,
                         TimeWindow firstWindow, TimeWindow secondWindow, TimeWindow otherSatellite) {
  }

  private Fixture fixture() {
    Task first = new Task(0, 10, 20, 7, 5);
    Task second = new Task(1, 10, 20, 3, 5);
    TimeWindow firstWindow = window(0, 0);
    TimeWindow secondWindow = window(0, 1);
    TimeWindow otherSatellite = window(1, 0);
    Satellite satellite0 = new Satellite(0);
    satellite0.setTimeWindows(List.of(firstWindow, secondWindow));
    Satellite satellite1 = new Satellite(1);
    satellite1.setTimeWindows(List.of(otherSatellite));
    return new Fixture(new ProblemInstance(List.of(first, second), List.of(satellite0, satellite1)),
        first, second, firstWindow, secondWindow, otherSatellite);
  }

  private TimeWindow window(int satelliteId, int taskId) {
    TimeWindow window = new TimeWindow(satelliteId, taskId, 2013, 4, 20, 0, 0, 0,
        2013, 4, 20, 0, 3, 20);
    for (int i = 0; i <= 200; i++) {
      window.addAttitudeRecord(record(i, 0));
    }
    return window;
  }

  private AttitudeRecord record(double seconds, double roll) {
    return new AttitudeRecord(2013, 4, 20, 0, 0, seconds, roll, 0);
  }

  private ScheduledTask observation(Task task, TimeWindow window, int start) {
    return new ScheduledTask(task, window, window.getAttitudeRecord(start),
        window.getAttitudeRecord(start + task.getImagingDuration()));
  }

  @Test void emptyPlanIsFeasible() {
    var result = ScheduleValidator.evaluate(fixture().instance(), new SchedulingPlan(List.of()));
    assertTrue(result.feasible());
    assertEquals(0, result.totalProfit());
  }

  @Test void validatesUnsortedObservationsWithoutChangingInput() {
    Fixture f = fixture();
    ScheduledTask first = observation(f.first(), f.firstWindow(), 1);
    ScheduledTask second = observation(f.second(), f.secondWindow(), 30);
    SchedulingPlan plan = new SchedulingPlan(List.of(second, first));
    var result = ScheduleValidator.evaluate(f.instance(), plan);
    assertTrue(result.feasible(), result.violations().toString());
    assertEquals(10, result.totalProfit());
    assertEquals(List.of(second, first), plan.observations());
  }

  @Test void profitDoesNotUseTransitionSlack() {
    Fixture f = fixture();
    var near = ScheduleValidator.evaluate(f.instance(), new SchedulingPlan(List.of(
        observation(f.first(), f.firstWindow(), 1), observation(f.second(), f.secondWindow(), 18))));
    var far = ScheduleValidator.evaluate(f.instance(), new SchedulingPlan(List.of(
        observation(f.first(), f.firstWindow(), 1), observation(f.second(), f.secondWindow(), 90))));
    assertTrue(near.feasible());
    assertTrue(far.feasible());
    assertEquals(near.totalProfit(), far.totalProfit());
    assertEquals(10, near.totalProfit());
  }

  @Test void rejectsDuplicateTaskAcrossSatellites() {
    Fixture f = fixture();
    var result = ScheduleValidator.evaluate(f.instance(), new SchedulingPlan(List.of(
        observation(f.first(), f.firstWindow(), 1), observation(f.first(), f.otherSatellite(), 1))));
    assertFalse(result.feasible());
    assertEquals(0, result.totalProfit());
    assertTrue(result.violations().stream().anyMatch(v -> v.contains("DUPLICATE_TASK")));
  }

  @Test void differentSatellitesCanObserveAtSameTime() {
    Fixture f = fixture();
    var result = ScheduleValidator.evaluate(f.instance(), new SchedulingPlan(List.of(
        observation(f.first(), f.otherSatellite(), 1), observation(f.second(), f.secondWindow(), 1))));
    assertTrue(result.feasible());
    assertEquals(10, result.totalProfit());
  }

  @Test void rejectsOverlap() {
    Fixture f = fixture();
    var result = ScheduleValidator.evaluate(f.instance(), new SchedulingPlan(List.of(
        observation(f.first(), f.firstWindow(), 1), observation(f.second(), f.secondWindow(), 3))));
    assertTrue(result.violations().stream().anyMatch(v -> v.contains("OVERLAP")));
  }

  @Test void rejectsInsufficientTransitionTime() {
    Fixture f = fixture();
    var result = ScheduleValidator.evaluate(f.instance(), new SchedulingPlan(List.of(
        observation(f.first(), f.firstWindow(), 1), observation(f.second(), f.secondWindow(), 16))));
    assertFalse(result.feasible());
    assertTrue(result.violations().stream().anyMatch(v -> v.contains("ATTITUDE_TRANSITION")));
  }

  @Test void rejectsWrongDurationAndOutsideWindow() {
    Fixture f = fixture();
    ScheduledTask shortObservation = new ScheduledTask(f.first(), f.firstWindow(),
        f.firstWindow().getAttitudeRecord(1), f.firstWindow().getAttitudeRecord(5));
    assertFalse(ScheduleValidator.evaluate(f.instance(), new SchedulingPlan(List.of(shortObservation))).feasible());
    f.firstWindow().setStartTime(2);
    var outside = ScheduleValidator.evaluate(f.instance(), new SchedulingPlan(List.of(
        observation(f.first(), f.firstWindow(), 1))));
    assertTrue(outside.violations().stream().anyMatch(v -> v.contains("OUTSIDE_WINDOW")));
  }

  @Test void rejectsForgedTaskWindowAndAttitude() {
    Fixture f = fixture();
    Task forgedTask = new Task(0, 10, 20, 999, 5);
    ScheduledTask forged = observation(forgedTask, f.firstWindow(), 1);
    assertFalse(ScheduleValidator.evaluate(f.instance(), new SchedulingPlan(List.of(forged))).feasible());
    ScheduledTask fakeWindow = observation(f.first(), window(0, 0), 1);
    assertFalse(ScheduleValidator.evaluate(f.instance(), new SchedulingPlan(List.of(fakeWindow))).feasible());
    ScheduledTask fakeRecord = new ScheduledTask(f.first(), f.firstWindow(), record(1, 0), record(6, 0));
    assertFalse(ScheduleValidator.evaluate(f.instance(), new SchedulingPlan(List.of(fakeRecord))).feasible());
  }

  @Test void preservesPiecewisePhysicalTransitionModel() {
    double[] angles = {0, 10, 10.01, 30, 30.01, 60, 60.01, 90, 90.01};
    double[] expected = {11.66, 11.66, 10.01 / 1.5 + 5, 25, 30.01 / 2 + 10,
        40, 60.01 / 2.5 + 16, 52, 90.01 / 3 + 22};
    for (int i = 0; i < angles.length; i++) {
      assertEquals(expected[i], TimeUtils.calculateTransformationTime(record(0, 0), record(100, angles[i])), 1e-12);
    }
    assertTrue(TimeUtils.checkTransformationTime(record(0, 0), record(11.66, 0)));
    assertFalse(TimeUtils.checkTransformationTime(record(0, 0), record(11.659, 0)));
  }

  @Test void rejectsInvalidInstanceNumbersAndIds() {
    assertThrows(IllegalArgumentException.class, () -> new ProblemInstance(
        List.of(new Task(0, Double.NaN, 0, 1, 5)), List.of(new Satellite(0))));
    Task task = new Task(0, 0, 0, 1, 5);
    assertThrows(IllegalArgumentException.class, () -> new ProblemInstance(List.of(task, task), List.of()));
    TimeWindow window = window(0, 0);
    window.addAttitudeRecord(record(Double.NaN, 0));
    Satellite satellite = new Satellite(0);
    satellite.setTimeWindows(List.of(window));
    assertThrows(IllegalArgumentException.class, () -> new ProblemInstance(List.of(task), List.of(satellite)));
  }

  @Test void retainsUnusableRawWindowButRejectsItsObservation() {
    Task task = new Task(0, 0, 0, 1, 5);
    TimeWindow reversed = new TimeWindow(0, 0, 2013, 4, 20, 0, 0, 10,
        2013, 4, 20, 0, 0, 9);
    AttitudeRecord start = record(10, 0);
    AttitudeRecord end = record(15, 0);
    reversed.setAttitudeRecords(List.of(record(9, 0), start, end, record(16, 0)));
    Satellite satellite = new Satellite(0);
    satellite.setTimeWindows(List.of(reversed));
    ProblemInstance instance = new ProblemInstance(List.of(task), List.of(satellite));
    var result = ScheduleValidator.evaluate(instance, new SchedulingPlan(List.of(
        new ScheduledTask(task, reversed, start, end))));
    assertEquals(1, instance.satellite(0).getTimeWindows().size());
    assertFalse(result.feasible());
    assertTrue(result.violations().stream().anyMatch(v -> v.contains("OUTSIDE_WINDOW")));
  }
}
