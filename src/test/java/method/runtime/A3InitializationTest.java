package method.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import method.rkhma.bridge.ProblemBridge;
import method.rkhma.legacy.utils.Initialize;
import model.AttitudeRecord;
import model.ProblemInstance;
import model.Satellite;
import model.Task;
import model.TimeWindow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A3InitializationTest {
  @Test void largeAssignmentUsesTheSameCompositeRuleAsSmallInstances() {
    var problem = differentInitialRulesProblem();
    var sevenBridge = new ProblemBridge(problem);
    var eightBridge = new ProblemBridge(problem);
    Map<Integer, Integer> expected = owners(Initialize.initializeWithStrategy(
        sevenBridge.tasks(), sevenBridge.satellites(), 7));
    Map<Integer, Integer> previous = owners(Initialize.initializeWithStrategy(
        eightBridge.tasks(), eightBridge.satellites(), 8));
    assertNotEquals(expected, previous, "fixture must distinguish the two initialization rules");

    var bridge = new ProblemBridge(problem);
    var budget = new Budget(() -> 0L, 0, Long.MAX_VALUE, Long.MAX_VALUE);
    var allocation = new RkhmaAllocation();
    allocation.bindSource(bridge);
    problem.tasks().forEach(task -> expected.putIfAbsent(task.getId(), null));
    assertEquals(expected, allocation.initialAssign(problem, new ProblemAccess(problem, budget),
        budget, new SplittableRandom(42)).owners());
  }

  private Map<Integer, Integer> owners(List<List<Integer>> pairs) {
    Map<Integer, Integer> result = new LinkedHashMap<>();
    for (var pair : pairs) result.put(pair.get(1), pair.get(0));
    return result;
  }

  private ProblemInstance differentInitialRulesProblem() {
    List<Task> tasks = new ArrayList<>();
    List<Satellite> satellites = List.of(new Satellite(0), new Satellite(1));
    List<List<TimeWindow>> windows = List.of(new ArrayList<>(), new ArrayList<>());
    for (int id = 0; id < 501; id++) {
      tasks.add(new Task(id, 0, 0, id < 3 ? 10 - id : 1, 2));
      if (id >= 3) continue;
      for (int sat = 0; sat < 2; sat++) {
        if (id == 1 && sat == 1) continue;
        var window = new TimeWindow(sat, id, 2026, 1, 1, 0, 0, 9,
            2026, 1, 1, 0, 0, 13);
        for (int time = 9; time <= 13; time++) {
          window.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, time, 0, 0));
        }
        windows.get(sat).add(window);
      }
    }
    for (int sat = 0; sat < satellites.size(); sat++) satellites.get(sat).setTimeWindows(windows.get(sat));
    return new ProblemInstance(tasks, satellites);
  }

  @Test void largeConflictingAssignmentDoesNotDecodeBeforeReturningOwners() {
    var problem = conflictingProblem();
    var bridge = new ProblemBridge(problem);
    var decoderCalls = new AtomicInteger();
    for (var satellite : bridge.satellites()) {
      satellite.setScheduleBuilder((ignored, sequence) -> {
        decoderCalls.incrementAndGet();
        return List.of();
      });
    }
    var budget = new Budget(() -> 0L, 0, Long.MAX_VALUE, Long.MAX_VALUE);
    var allocation = new RkhmaAllocation();
    allocation.bindSource(bridge);
    var owners = allocation.initialAssign(problem, new ProblemAccess(problem, budget),
        budget, new SplittableRandom(42)).owners();

    assertEquals(501, owners.size());
    assertEquals(0, decoderCalls.get(), "initial allocation must not call C");
  }

  @Test void initializationSkipsSourceWindowsWithoutLegalInternalRecords() {
    var task = new Task(0, 0, 0, 9, 2);
    var first = new Satellite(0);
    var second = new Satellite(1);
    List<TimeWindow> invalid = new ArrayList<>();
    for (int n = 0; n < 2; n++) {
      var window = new TimeWindow(0, 0, 2026, 1, 1, 0, 0, 9, 2026, 1, 1, 0, 0, 16);
      for (int secondValue : new int[]{9, 12, 16}) {
        window.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, secondValue, 0, 0));
      }
      invalid.add(window);
    }
    var valid = new TimeWindow(1, 0, 2026, 1, 1, 0, 0, 9, 2026, 1, 1, 0, 0, 16);
    for (int secondValue = 9; secondValue <= 16; secondValue++) {
      valid.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, secondValue, 0, 0));
    }
    first.setTimeWindows(invalid);
    second.setTimeWindows(List.of(valid));
    var problem = new ProblemInstance(List.of(task), List.of(first, second));
    var budget = new Budget(() -> 0L, 0, Long.MAX_VALUE, Long.MAX_VALUE);
    var api = new ProblemAccess(problem, budget);
    var bridge = new ProblemBridge(problem);
    assertEquals(1, owners(Initialize.initializeWithStrategy(bridge.tasks(), bridge.satellites(), 7)).get(0));
    assertFalse(api.hasCandidate(0, 0, budget));
    assertTrue(api.hasCandidate(0, 1, budget));
    assertEquals(1, new RkhmaAllocation().initialAssign(problem, api, budget, new SplittableRandom(42)).owners().get(0));
  }

  private ProblemInstance conflictingProblem() {
    List<Task> tasks = new ArrayList<>();
    List<Satellite> satellites = List.of(new Satellite(0), new Satellite(1));
    List<List<TimeWindow>> windows = List.of(new ArrayList<>(), new ArrayList<>());
    for (int id = 0; id < 501; id++) {
      tasks.add(new Task(id, 0, 0, id % 10 + 1, 2));
      for (int sat = 0; sat < satellites.size(); sat++) {
        var window = new TimeWindow(sat, id, 2026, 1, 1, 0, 0, 9,
            2026, 1, 1, 0, 0, 13);
        for (int time = 9; time <= 13; time++) {
          window.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, time, 0, 0));
        }
        windows.get(sat).add(window);
      }
    }
    for (int sat = 0; sat < satellites.size(); sat++) satellites.get(sat).setTimeWindows(windows.get(sat));
    return new ProblemInstance(tasks, satellites);
  }
}
