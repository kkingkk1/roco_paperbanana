package method.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import method.runtime.SolverContracts.Assignment;
import method.runtime.SolverContracts.Feedback;
import method.runtime.SolverContracts.LocalContext;
import method.runtime.SolverContracts.Phase;
import method.runtime.SolverContracts.SatelliteView;
import method.runtime.SolverContracts.State;
import model.AttitudeRecord;
import model.ProblemInstance;
import model.Satellite;
import model.ScheduledTask;
import model.Task;
import model.TimeWindow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IndependentPoliciesTest {
  private final Budget budget = new Budget(() -> 0L, 0, Long.MAX_VALUE, Long.MAX_VALUE);

  private TimeWindow window(int satellite, int task, int start, int end) {
    var window = new TimeWindow(satellite, task, 2026, 1, 1, 0, 0, start,
        2026, 1, 1, 0, 0, end);
    for (int time = start; time <= end; time++) {
      window.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, time, 0, 0));
    }
    return window;
  }

  @Test void allocationPoliciesUseDifferentInitialAndReassignmentDecisions() {
    var tasks = List.of(new Task(0, 0, 0, 9, 2), new Task(1, 0, 0, 7, 2));
    var s0 = new Satellite(0);
    var s1 = new Satellite(1);
    var s2 = new Satellite(2);
    var onlyForOne = window(1, 1, 30, 34);
    s0.setTimeWindows(List.of(window(0, 0, 10, 15)));
    s1.setTimeWindows(List.of(window(1, 0, 10, 16), onlyForOne));
    s2.setTimeWindows(List.of(window(2, 0, 10, 15)));
    var problem = new ProblemInstance(tasks, List.of(s0, s1, s2));
    var api = new ProblemAccess(problem, budget);
    var a1 = ManualStrategies.allocationByCandidates();
    var a2 = ManualStrategies.allocationByScarcityAndLoad();

    var first = a1.initialAssign(problem, api, budget, new SplittableRandom(1));
    var second = a2.initialAssign(problem, api, budget, new SplittableRandom(1));
    assertEquals(1, first.owners().get(0));
    assertEquals(0, second.owners().get(0));

    var scheduled = new ScheduledTask(tasks.get(1), onlyForOne,
        onlyForOne.getAttitudeRecord(1), onlyForOne.getAttitudeRecord(3));
    var state = new State(new Assignment(Map.of(0, 0, 1, 1)),
        Map.of(0, List.of(), 1, List.of(scheduled), 2, List.of()), "before-move");
    var move1 = a1.proposeMove(problem, api, state, new Feedback(0, "START"), budget, new SplittableRandom(1));
    var move2 = a2.proposeMove(problem, api, state, new Feedback(0, "START"), budget, new SplittableRandom(1));
    assertEquals(Map.of(0, 1), move1.edits());
    assertEquals(Map.of(0, 2), move2.edits());
  }

  @Test void searchPoliciesChooseDifferentCandidatesInBothPhases() {
    var tasks = List.of(new Task(0, 0, 0, 9, 2), new Task(1, 0, 0, 7, 2));
    var satellite = new Satellite(0);
    satellite.setTimeWindows(List.of(window(0, 0, 10, 18), window(0, 1, 30, 34)));
    var problem = new ProblemInstance(tasks, List.of(satellite));
    var api = new ProblemAccess(problem, budget);
    var view = new SatelliteView(0, Set.of(0, 1), List.of(), "empty");
    var b1 = ManualStrategies.priorityByDensity();
    var b2 = ManualStrategies.priorityByScarcity();
    var initial = new LocalContext(Phase.INIT, Set.of(), Set.of(), List.of());
    var reassignment = new LocalContext(Phase.REASSIGN, Set.of(0, 1), Set.of(), List.of());
    var feedback = new Feedback(0, "START");

    assertEquals(List.of(0, 1), b1.initialPriority(problem, api, view, budget, new SplittableRandom(1)));
    assertEquals(List.of(1, 0), b2.initialPriority(problem, api, view, budget, new SplittableRandom(1)));
    var init1 = b1.proposeInitialLocal(problem, api, view, initial, feedback, budget, new SplittableRandom(1));
    var init2 = b2.proposeInitialLocal(problem, api, view, initial, feedback, budget, new SplittableRandom(1));
    assertEquals(List.of(0), init1.reconsider());
    assertEquals(List.of(1), init2.reconsider());
    var local1 = b1.proposeReassignmentLocal(problem, api, view, reassignment, feedback, budget, new SplittableRandom(1));
    var local2 = b2.proposeReassignmentLocal(problem, api, view, reassignment, feedback, budget, new SplittableRandom(1));
    assertEquals(List.of(0), local1.reconsider());
    assertEquals(List.of(1), local2.reconsider());
    var task1Incoming = new LocalContext(Phase.REASSIGN, Set.of(1), Set.of(), List.of());
    var task0Incoming = new LocalContext(Phase.REASSIGN, Set.of(0), Set.of(), List.of());
    assertEquals(List.of(1), b1.proposeReassignmentLocal(problem, api, view, task1Incoming, feedback,
        budget, new SplittableRandom(1)).reconsider());
    assertEquals(List.of(0), b2.proposeReassignmentLocal(problem, api, view, task0Incoming, feedback,
        budget, new SplittableRandom(1)).reconsider());
  }
}
