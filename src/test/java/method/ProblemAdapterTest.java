package method;

import model.AttitudeRecord;
import model.ProblemInstance;
import model.Satellite;
import model.ScheduledTask;
import model.SchedulingPlan;
import model.Task;
import model.TimeWindow;
import org.junit.jupiter.api.Test;
import problem.ScheduleValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

class ProblemAdapterTest {
  private record Fixture(ProblemInstance instance, Task task, TimeWindow window) { }

  private AttitudeRecord record(double time, double roll) {
    return new AttitudeRecord(2013, 4, 20, 0, 0, time, roll, 0);
  }

  private TimeWindow window(int taskId, double start, double end, double... times) {
    TimeWindow window = new TimeWindow(0, taskId, 2013, 4, 20, 0, 0, start,
        2013, 4, 20, 0, 0, end);
    for (double time : times) {
      window.addAttitudeRecord(record(time, 0));
    }
    return window;
  }

  private Fixture fixture(int duration, double start, double end, double... times) {
    Task task = new Task(0, 0, 0, 7, duration);
    TimeWindow window = window(0, start, end, times);
    Satellite satellite = new Satellite(0);
    satellite.setTimeWindows(List.of(window));
    return new Fixture(new ProblemInstance(List.of(task), List.of(satellite)), task, window);
  }

  private List<ScheduledTask> candidates(ProblemAdapter adapter, TimeWindow window) {
    return StreamSupport.stream(adapter.candidates(window).spliterator(), false).toList();
  }

  @Test void keepsOriginalInternalObjectsInAReadOnlyList() {
    Fixture f = fixture(2, 0, 9, 0, 0.5, 2.5, 4.5, 6.5, 9);
    List<AttitudeRecord> original = List.copyOf(f.window().getAttitudeRecords());
    ProblemAdapter adapter = new ProblemAdapter(f.instance());
    var grid = adapter.grid(f.window());
    assertEquals(4, grid.size());
    assertEquals(2, grid.stepSeconds().orElseThrow());
    for (int i = 0; i < grid.size(); i++) {
      assertSame(original.get(i + 1), grid.records().get(i));
    }
    assertThrows(UnsupportedOperationException.class, () -> grid.records().clear());
    assertEquals(original, f.window().getAttitudeRecords());
    TimeWindow equalButForeign = window(0, 0, 9, 0, 0.5, 2.5, 4.5, 6.5, 9);
    assertEquals(f.window(), equalButForeign);
    assertThrows(IllegalArgumentException.class, () -> adapter.grid(equalButForeign));
  }

  @Test void tooFewInternalRecordsProduceNoCandidates() {
    for (double[] times : List.of(new double[] {}, new double[] {1}, new double[] {1, 2},
        new double[] {1, 2, 3})) {
      Fixture f = fixture(5, 0, 20, times);
      var adapter = new ProblemAdapter(f.instance());
      assertEquals(Math.max(0, times.length - 2), adapter.grid(f.window()).size());
      assertTrue(adapter.grid(f.window()).stepSeconds().isEmpty());
      var iterator = adapter.candidates(f.window()).iterator();
      assertFalse(iterator.hasNext());
      assertThrows(NoSuchElementException.class, iterator::next);
    }
    Fixture f = fixture(5, 0, 20, 0, 1, 6, 7);
    assertEquals(1, candidates(new ProblemAdapter(f.instance()), f.window()).size());
  }

  @Test void acceptsUnevenBoundaryGapsButRejectsUnequalInternalSpacing() {
    assertDoesNotThrow(() -> fixture(1, 0, 20, 0, 1, 2, 3, 8));
    assertDoesNotThrow(() -> fixture(1, 0, 20, 0, 0.1, 0.2, 0.3, 0.4));
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> fixture(1, 0, 20, 0, 1, 2, 3.0000002, 8));
    assertTrue(error.getMessage().contains("raw index 3"));
  }

  @Test void enumeratesOnlyExactDurationPairsInsideWindowWithoutRounding() {
    Fixture f = fixture(1, 1, 3, 0, 0.5, 1.5, 2.5, 3.5, 4);
    var found = candidates(new ProblemAdapter(f.instance()), f.window());
    assertEquals(1, found.size());
    assertSame(f.window().getAttitudeRecord(2), found.getFirst().startRecord());
    assertSame(f.window().getAttitudeRecord(3), found.getFirst().endRecord());
    Fixture noMultiple = fixture(3, 0, 20, 0, 1, 3, 5, 7, 8);
    assertTrue(candidates(new ProblemAdapter(noMultiple.instance()), noMultiple.window()).isEmpty());
    Fixture within = fixture(5, 0, 20, 0, 1, 6.0000005, 7);
    Fixture outside = fixture(5, 0, 20, 0, 1, 6.0000011, 7);
    assertEquals(1, candidates(new ProblemAdapter(within.instance()), within.window()).size());
    assertTrue(candidates(new ProblemAdapter(outside.instance()), outside.window()).isEmpty());
    Fixture reversed = fixture(1, 10, 9, 0, 1, 2, 3);
    assertTrue(candidates(new ProblemAdapter(reversed.instance()), reversed.window()).isEmpty());
  }

  @Test void candidatesMatchIndependentExhaustivePairsAndPassValidator() {
    List<Fixture> fixtures = List.of(
        fixture(2, 0, 10, 0, 1, 2, 3, 4, 5, 9),
        fixture(3, 2, 8, 0, 1, 2.5, 4, 5.5, 7, 8.5, 10),
        fixture(2, 0, 10, 0, 1, 3, 5, 7, 9, 10),
        fixture(5, 0, 10, 0, 1, 2, 3, 4, 5, 9));
    for (Fixture f : fixtures) {
      List<ScheduledTask> expected = new ArrayList<>();
      var raw = f.window().getAttitudeRecords();
      for (int i = 1; i < raw.size() - 1; i++) {
        for (int j = i + 1; j < raw.size() - 1; j++) {
          double start = raw.get(i).getTotalSeconds();
          double end = raw.get(j).getTotalSeconds();
          if (start >= f.window().getStartTime() && end <= f.window().getEndTime()
              && Math.abs(end - start - f.task().getImagingDuration()) <= 1e-6) {
            expected.add(new ScheduledTask(f.task(), f.window(), raw.get(i), raw.get(j)));
          }
        }
      }
      var actual = candidates(new ProblemAdapter(f.instance()), f.window());
      assertEquals(expected, actual);
      for (ScheduledTask candidate : actual) {
        var result = ScheduleValidator.evaluate(f.instance(), new SchedulingPlan(List.of(candidate)));
        assertTrue(result.feasible(), result.violations().toString());
        assertEquals(f.task().getPriority(), result.totalProfit());
      }
    }
  }

  @Test void removingMiddleObservationRequiresCheckingNewTransition() {
    Task a = new Task(0, 0, 0, 1, 5);
    Task b = new Task(1, 0, 0, 1, 5);
    Task c = new Task(2, 0, 0, 1, 5);
    TimeWindow wa = window(0, 0, 50, 0, 1, 6, 7);
    TimeWindow wb = window(1, 0, 50, 17, 18, 23, 24);
    TimeWindow wc = window(2, 0, 50, 34, 35, 40, 41);
    wb.setAttitudeRecords(List.of(record(17, 0), record(18, 0), record(23, 100), record(24, 100)));
    wc.setAttitudeRecords(List.of(record(34, 100), record(35, 100), record(40, 100), record(41, 100)));
    Satellite satellite = new Satellite(0);
    satellite.setTimeWindows(List.of(wa, wb, wc));
    ProblemInstance instance = new ProblemInstance(List.of(a, b, c), List.of(satellite));
    ScheduledTask first = new ScheduledTask(a, wa, wa.getAttitudeRecord(1), wa.getAttitudeRecord(2));
    ScheduledTask middle = new ScheduledTask(b, wb, wb.getAttitudeRecord(1), wb.getAttitudeRecord(2));
    ScheduledTask last = new ScheduledTask(c, wc, wc.getAttitudeRecord(1), wc.getAttitudeRecord(2));
    assertTrue(ScheduleValidator.evaluate(instance, new SchedulingPlan(List.of(first, middle, last))).feasible());
    var afterRemoval = ScheduleValidator.evaluate(instance, new SchedulingPlan(List.of(first, last)));
    assertFalse(afterRemoval.feasible());
    assertTrue(afterRemoval.violations().stream().anyMatch(v -> v.contains("ATTITUDE_TRANSITION")));
  }
}
