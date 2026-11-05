package method.rkhma.legacy.main;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.SchedulingPlan;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.operator.insertion.InsertionMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 大实例的完整移动只能来自同一次统一试评。 */
class UnifiedLargeTrialTest {
  @Test void sourceAndTargetAreEvaluatedTogetherForBothLargeMovePaths() throws Exception {
    var task = new Task(0, 0, 0, 1, 1);
    var satellites = List.of(new Satellite(0), new Satellite(1));
    var context = new AssignmentDeltaEvaluator.Context() {
      @Override public void syncSatelliteTaskViews(Map<Integer, Integer> owners) { throw new AssertionError(); }
      @Override public List<Task> getOrderedTasksForSatellite(int sat, List<Task> tasks, Map<Integer, Integer> owners) {
        throw new AssertionError();
      }
      @Override public List<Task> getOrderedTasksForSatellite(int sat, Map<Integer, Integer> owners) {
        throw new AssertionError();
      }
      @Override public SchedulingPlan scheduleSingleSatellite(Satellite sat, List<Task> tasks,
          TaskAllocationCoordinator.HybCecConfig config, boolean commit, boolean refresh,
          Map<Integer, SatelliteState> states) { throw new AssertionError(); }
      @Override public void materializePlanSchedule(SchedulingPlan plan, Satellite sat, InsertionMode mode) {
        throw new AssertionError();
      }
      @Override public int getCurrentIteration() { return 0; }
    };
    var evaluator = new AssignmentDeltaEvaluator(Collections.nCopies(4000, task), satellites, Map.of(), null, context);
    var calls = new AtomicInteger();
    evaluator.setUnifiedCandidateEvaluator((before, after, affected, plans) -> {
      calls.incrementAndGet();
      assertEquals(Set.of(0, 1), affected);
      return Map.of(0, emptyPlan(), 1, emptyPlan());
    });
    var plans = Map.of(0, emptyPlan(), 1, emptyPlan());
    var config = new TaskAllocationCoordinator.HybCecConfig(new int[]{4, 1}, new int[]{0, 0}, 0, 0, 0,
        6, 30, InsertionMode.GREEDY, 42);
    evaluator.evaluateTrialMove(task, 0, 1, Map.of(0, 0), plans, 0, config);
    assertEquals(1, calls.get());
    evaluator.buildDeltaPlans(plans, Map.of(0, 0), Map.of(0, 1), Set.of(0, 1), config);
    assertEquals(2, calls.get());
  }

  private static SchedulingPlan emptyPlan() {
    var plan = new SchedulingPlan(List.of());
    plan.setFitness(0.0);
    return plan;
  }
}
