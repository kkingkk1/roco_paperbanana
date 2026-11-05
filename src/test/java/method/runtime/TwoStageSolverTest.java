package method.runtime;

import method.ProblemAdapter;
import method.runtime.SolverContracts.A;
import method.runtime.SolverContracts.Assignment;
import method.runtime.SolverContracts.B;
import method.runtime.SolverContracts.C;
import method.runtime.SolverContracts.Feedback;
import method.runtime.SolverContracts.LocalContext;
import method.runtime.SolverContracts.LocalPlan;
import method.runtime.SolverContracts.LocalResult;
import method.runtime.SolverContracts.Move;
import method.runtime.SolverContracts.Phase;
import method.runtime.SolverContracts.SatelliteView;
import method.runtime.SolverContracts.State;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

class TwoStageSolverTest {
  private static final TwoStageSolver.Limits LIMITS = new TwoStageSolver.Limits(1000, 2000, 400, 200);

  private abstract static class TestB implements B {
    @Override public LocalPlan proposeInitialLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
        LocalContext context, Feedback feedback, Budget budget, RandomGenerator rng) { return null; }
    @Override public LocalPlan proposeReassignmentLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
        LocalContext context, Feedback feedback, Budget budget, RandomGenerator rng) { return null; }
  }

  private AttitudeRecord record(double time, double roll) {
    return new AttitudeRecord(2013, 4, 20, 0, 0, time, roll, 0);
  }

  private TimeWindow window(int sat, int task, double start, double end, double rollStart, double rollEnd) {
    TimeWindow w = new TimeWindow(sat, task, 2013, 4, 20, 0, 0, 0,
        2013, 4, 20, 0, 0, 200);
    w.setAttitudeRecords(List.of(record(start - 1, rollStart), record(start, rollStart),
        record(end, rollEnd), record(end + 1, rollEnd)));
    return w;
  }

  private ProblemInstance instance(List<Task> tasks, List<List<TimeWindow>> windows) {
    List<Satellite> satellites = new ArrayList<>();
    for (int i = 0; i < windows.size(); i++) {
      Satellite sat = new Satellite(i);
      sat.setTimeWindows(windows.get(i));
      satellites.add(sat);
    }
    return new ProblemInstance(tasks, satellites);
  }

  private ProblemInstance contested() {
    List<Task> tasks = List.of(new Task(0, 0, 0, 9, 5), new Task(1, 0, 0, 5, 5),
        new Task(2, 0, 0, 3, 5));
    List<List<TimeWindow>> windows = new ArrayList<>();
    for (int sat = 0; sat < 2; sat++) {
      List<TimeWindow> row = new ArrayList<>();
      for (Task task : tasks) row.add(window(sat, task.getId(), 10, 15, 0, 0));
      windows.add(row);
    }
    return instance(tasks, windows);
  }

  private ScheduledTask observation(ProblemInstance p, int sat, int task) {
    TimeWindow window = p.satellite(sat).getTimeWindows().stream()
        .filter(w -> w.getTaskId() == task).findFirst().orElseThrow();
    return new ScheduledTask(p.task(task), window, window.getAttitudeRecord(1), window.getAttitudeRecord(2));
  }

  private long enumerate(ProblemInstance problem, int taskIndex, List<ScheduledTask> partial) {
    if (taskIndex == problem.tasks().size()) {
      var result = ScheduleValidator.evaluate(problem, new SchedulingPlan(partial));
      return result.feasible() ? result.totalProfit() : -1;
    }
    long best = enumerate(problem, taskIndex + 1, partial);
    Task task = problem.tasks().get(taskIndex);
    ProblemAdapter adapter = new ProblemAdapter(problem);
    for (var sat : problem.satellites()) {
      for (TimeWindow w : sat.getTimeWindows()) {
        if (w.getTaskId() != task.getId()) continue;
        for (ScheduledTask candidate : adapter.candidates(w)) {
          partial.add(candidate);
          best = Math.max(best, enumerate(problem, taskIndex + 1, partial));
          partial.removeLast();
        }
      }
    }
    return best;
  }

  private static final class TraceB implements B {
    final B delegate;
    int initialPriority, initLocal, reassignLocal;
    TraceB(B delegate) { this.delegate = delegate; }
    @Override public List<Integer> initialPriority(ProblemInstance p, ProblemAccess api, SatelliteView view,
                                                   Budget budget, RandomGenerator rng) {
      initialPriority++;
      return delegate.initialPriority(p, api, view, budget, rng);
    }
    @Override public LocalPlan proposeInitialLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
                                                    LocalContext ctx, Feedback feedback, Budget budget, RandomGenerator rng) {
      assertEquals(Phase.INIT, ctx.phase());
      initLocal++;
      return delegate.proposeInitialLocal(p, api, view, ctx, feedback, budget, rng);
    }
    @Override public LocalPlan proposeReassignmentLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
                                                         LocalContext ctx, Feedback feedback, Budget budget, RandomGenerator rng) {
      assertEquals(Phase.REASSIGN, ctx.phase());
      reassignLocal++;
      return delegate.proposeReassignmentLocal(p, api, view, ctx, feedback, budget, rng);
    }
  }

  private static final class TraceC implements C {
    final C delegate;
    int builds, initLocal, reassignLocal;
    TraceC(C delegate) { this.delegate = delegate; }
    @Override public List<ScheduledTask> build(ProblemInstance p, ProblemAccess api, SatelliteView view,
                                                List<Integer> priorities, Budget budget, RandomGenerator rng) {
      builds++;
      return delegate.build(p, api, view, priorities, budget, rng);
    }
    @Override public LocalResult applyLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
                                             LocalContext ctx, LocalPlan plan, Budget budget, RandomGenerator rng) {
      if (ctx.phase() == Phase.INIT) initLocal++; else reassignLocal++;
      return delegate.applyLocal(p, api, view, ctx, plan, budget, rng);
    }
  }

  @Test void allEightCombinationsCallBothPhasesAndNeverExceedEnumeratedOptimum() {
    ProblemInstance p = contested();
    long optimum = enumerate(p, 0, new ArrayList<>());
    assertEquals(14, optimum);
    assertEquals(8, ManualStrategies.combinations().size());
    for (var combo : ManualStrategies.combinations()) {
      TraceB b = new TraceB(combo.b());
      TraceC c = new TraceC(combo.c());
      var result = new TwoStageSolver(LIMITS, () -> 0).solve(p, combo.a(), b, c, 42);
      assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), combo.name() + ": " + result.detail());
      var checked = ScheduleValidator.evaluate(p, result.plan());
      assertTrue(checked.feasible(), combo.name() + ": " + checked.violations());
      assertEquals(checked.totalProfit(), result.totalProfit());
      assertTrue(result.totalProfit() <= optimum);
      assertEquals(2, b.initialPriority, combo.name());
      assertEquals(2, c.builds, combo.name());
      assertTrue(b.initLocal > 0 && c.initLocal > 0, combo.name());
      assertTrue(b.reassignLocal > 0 && c.reassignLocal > 0, combo.name());
      assertTrue(result.statistics().reassignMoves() > 0, combo.name());
    }
  }

  @Test void normalExpiryReturnsLastFeasiblePlanButHardExpiryFails() {
    ProblemInstance p = contested();
    AtomicLong time = new AtomicLong();
    A delayed = new A() {
      @Override public Assignment initialAssign(ProblemInstance problem, ProblemAccess api, Budget budget,
                                                 RandomGenerator rng) {
        Map<Integer, Integer> owners = new LinkedHashMap<>();
        problem.tasks().forEach(task -> owners.put(task.getId(), null));
        time.set(1000);
        return new Assignment(owners);
      }
      @Override public Move proposeMove(ProblemInstance problem, ProblemAccess api, State state, Feedback feedback,
                                        Budget budget, RandomGenerator rng) { fail("unexpected move"); return null; }
    };
    var soft = new TwoStageSolver(LIMITS, time::get).solve(p, delayed,
        ManualStrategies.priorityByDensity(), ManualStrategies.earliestFinish(), 1);
    assertEquals(TwoStageSolver.Status.SUCCESS, soft.status());
    assertEquals("SEARCH_EXPIRED", soft.detail());
    assertTrue(soft.plan().observations().isEmpty());
    assertEquals(0L, soft.totalProfit());

    time.set(0);
    A hardDelay = new A() {
      @Override public Assignment initialAssign(ProblemInstance problem, ProblemAccess api, Budget budget,
                                                 RandomGenerator rng) {
        Map<Integer, Integer> owners = new LinkedHashMap<>();
        problem.tasks().forEach(task -> owners.put(task.getId(), null));
        time.set(2000);
        return new Assignment(owners);
      }
      @Override public Move proposeMove(ProblemInstance problem, ProblemAccess api, State state, Feedback feedback,
                                        Budget budget, RandomGenerator rng) { fail("unexpected move"); return null; }
    };
    var hard = new TwoStageSolver(LIMITS, time::get).solve(p, hardDelay,
        ManualStrategies.priorityByDensity(), ManualStrategies.earliestFinish(), 1);
    assertEquals(TwoStageSolver.Status.HARD_TIMEOUT, hard.status());
    assertNull(hard.plan());
    assertNull(hard.totalProfit());
  }

  @Test void reusingTheSameStrategyDoesNotRetainThePreviousMoveCursor() {
    ProblemInstance p = contested();
    var combo = ManualStrategies.combinations().getFirst();
    var solver = new TwoStageSolver(LIMITS, () -> 0);
    var first = solver.solve(p, combo.a(), combo.b(), combo.c(), 42);
    var second = solver.solve(p, combo.a(), combo.b(), combo.c(), 42);
    assertEquals(TwoStageSolver.Status.SUCCESS, first.status(), first.detail());
    assertEquals(TwoStageSolver.Status.SUCCESS, second.status(), second.detail());
    assertTrue(first.statistics().reassignMoves() > 0);
    assertEquals(first.statistics().reassignMoves(), second.statistics().reassignMoves());
    assertEquals(first.plan().observations(), second.plan().observations());
  }

  @Test void randomStreamsSeparateInstanceContentWithoutDependingOnStrategyIdentity() {
    ProblemInstance first = instance(List.of(new Task(0, 0, 0, 1, 5)), List.of(List.of()));
    ProblemInstance second = instance(List.of(new Task(0, 0, 0, 2, 5)), List.of(List.of()));
    long firstSample = randomSample(first);
    assertEquals(firstSample, randomSample(first));
    assertNotEquals(firstSample, randomSample(second));
  }

  private long randomSample(ProblemInstance p) {
    AtomicLong sample = new AtomicLong();
    A a = new A() {
      @Override public Assignment initialAssign(ProblemInstance problem, ProblemAccess api, Budget budget,
                                                 RandomGenerator rng) {
        sample.set(rng.nextLong());
        Map<Integer, Integer> owners = new LinkedHashMap<>();
        problem.tasks().forEach(task -> owners.put(task.getId(), null));
        return new Assignment(owners);
      }
      @Override public Move proposeMove(ProblemInstance problem, ProblemAccess api, State state, Feedback feedback,
                                        Budget budget, RandomGenerator rng) { return null; }
    };
    var result = new TwoStageSolver(LIMITS, () -> 0).solve(p, a,
        ManualStrategies.priorityByDensity(), ManualStrategies.earliestFinish(), 42);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    return sample.get();
  }

  @Test void cCannotRemoveAnObservationOutsideItsReleaseSet() {
    List<Task> tasks = List.of(new Task(0, 0, 0, 7, 5), new Task(1, 0, 0, 5, 5));
    ProblemInstance p = instance(tasks, List.of(List.of(window(0, 0, 1, 6, 0, 0),
        window(0, 1, 30, 35, 0, 0))));
    A a = fixedOwners(Map.of(0, 0, 1, 0));
    B b = new TestB() {
      @Override public List<Integer> initialPriority(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                                     Budget budget, RandomGenerator rng) { return List.of(0, 1); }
      @Override public LocalPlan proposeInitialLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                               LocalContext ctx, Feedback fb, Budget budget, RandomGenerator rng) {
        return new LocalPlan(view.hash(), Set.of(0), List.of(0));
      }
    };
    C malicious = new C() {
      @Override public List<ScheduledTask> build(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                                  List<Integer> priorities, Budget budget, RandomGenerator rng) {
        return List.of(observation(p, 0, 0), observation(p, 0, 1));
      }
      @Override public LocalResult applyLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                               LocalContext ctx, LocalPlan plan, Budget budget, RandomGenerator rng) {
        return LocalResult.applied(List.of(observation(p, 0, 0)));
      }
    };
    var result = new TwoStageSolver(LIMITS, () -> 0).solve(p, a, b, malicious, 1);
    assertEquals(TwoStageSolver.Status.FAILURE, result.status());
    assertTrue(result.detail().contains("unauthorized"), result.detail());
    assertNull(result.plan());
  }

  @Test void removingTransferredObservationRejectsNewInfeasibleAdjacentEdge() {
    List<Task> tasks = List.of(new Task(0, 0, 0, 7, 5), new Task(1, 0, 0, 5, 5),
        new Task(2, 0, 0, 3, 5));
    ProblemInstance p = instance(tasks, List.of(
        List.of(window(0, 0, 1, 6, 0, 0), window(0, 1, 18, 23, 0, 100),
            window(0, 2, 35, 40, 100, 100)),
        List.of(window(1, 1, 70, 75, 0, 0))));
    A a = new A() {
      boolean sent;
      @Override public Assignment initialAssign(ProblemInstance problem, ProblemAccess api, Budget budget,
                                                 RandomGenerator rng) { return new Assignment(Map.of(0, 0, 1, 0, 2, 0)); }
      @Override public Move proposeMove(ProblemInstance problem, ProblemAccess api, State state, Feedback feedback,
                                        Budget budget, RandomGenerator rng) {
        if (sent) return null;
        sent = true;
        return new Move(state.hash(), Map.of(1, 1));
      }
    };
    boolean[] sawBrokenEdge = {false};
    B b = new TestB() {
      @Override public List<Integer> initialPriority(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                                     Budget budget, RandomGenerator rng) {
        return view.assignedTaskIds().stream().sorted().toList();
      }
      @Override public LocalPlan proposeReassignmentLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                               LocalContext ctx, Feedback fb, Budget budget, RandomGenerator rng) {
        if (ctx.phase() == Phase.INIT) return null;
        if (view.satelliteId() == 0) {
          sawBrokenEdge[0] = !ctx.brokenEdges().isEmpty();
          return null;
        }
        if (fb.attempt() == 0) return new LocalPlan(view.hash(), Set.of(), List.of(1));
        return null;
      }
    };
    C decoder = ManualStrategies.earliestFinish();
    var result = new TwoStageSolver(LIMITS, () -> 0).solve(p, a, b, decoder, 1);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    assertTrue(sawBrokenEdge[0]);
    assertEquals(15, result.totalProfit());
    assertEquals(0, result.owners().get(1));
    assertEquals(1, result.statistics().rejectedMoves());
    assertEquals(3, result.plan().observations().size());
    assertTrue(ScheduleValidator.evaluate(p, result.plan()).feasible());
  }

  @Test void rejectedMoveDoesNotPreventLaterImprovingMove() {
    List<Task> tasks = List.of(new Task(0, 0, 0, 4, 5), new Task(1, 0, 0, 9, 5),
        new Task(2, 0, 0, 10, 5));
    ProblemInstance p = instance(tasks, List.of(
        List.of(window(0, 0, 10, 15, 0, 0), window(0, 1, 10, 15, 0, 0)),
        List.of(window(1, 0, 30, 35, 0, 0), window(1, 2, 30, 35, 0, 0)),
        List.of(window(2, 0, 60, 65, 0, 0))));
    A a = new A() {
      int step;
      @Override public Assignment initialAssign(ProblemInstance problem, ProblemAccess api, Budget budget,
                                                 RandomGenerator rng) { return new Assignment(Map.of(0, 0, 1, 0, 2, 1)); }
      @Override public Move proposeMove(ProblemInstance problem, ProblemAccess api, State state, Feedback feedback,
                                        Budget budget, RandomGenerator rng) {
        return switch (step++) {
          case 0 -> new Move(state.hash(), Map.of(0, 1));
          case 1 -> new Move(state.hash(), Map.of(0, 2));
          default -> null;
        };
      }
    };
    var result = new TwoStageSolver(LIMITS, () -> 0).solve(p, a,
        ManualStrategies.priorityByDensity(), ManualStrategies.earliestFinish(), 1);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    assertEquals(23L, result.totalProfit());
    assertEquals(2, result.owners().get(0));
    assertEquals(1, result.statistics().acceptedMoves());
    assertEquals(1, result.statistics().rejectedMoves());
    assertTrue(result.statistics().noFeasibleChanges() > 0);
    ScheduledTask untouched = result.plan().observations().stream()
        .filter(o -> o.getTaskId() == 2).findFirst().orElseThrow();
    assertSame(p.satellite(1).getTimeWindows().get(1).getAttitudeRecord(1), untouched.startRecord());
    assertTrue(ScheduleValidator.evaluate(p, result.plan()).feasible());
  }

  @Test void localTimeoutRollsBackTheWholeUnfinishedMove() {
    List<Task> tasks = List.of(new Task(0, 0, 0, 4, 5), new Task(1, 0, 0, 9, 5));
    ProblemInstance p = instance(tasks, List.of(
        List.of(window(0, 0, 10, 15, 0, 0), window(0, 1, 10, 15, 0, 0)),
        List.of(window(1, 0, 30, 35, 0, 0))));
    AtomicLong time = new AtomicLong();
    A a = new A() {
      boolean sent;
      @Override public Assignment initialAssign(ProblemInstance problem, ProblemAccess api, Budget budget,
                                                 RandomGenerator rng) { return new Assignment(Map.of(0, 0, 1, 0)); }
      @Override public Move proposeMove(ProblemInstance problem, ProblemAccess api, State state, Feedback feedback,
                                        Budget budget, RandomGenerator rng) {
        if (sent) return null;
        sent = true;
        return new Move(state.hash(), Map.of(0, 1));
      }
    };
    C actual = ManualStrategies.earliestFinish();
    C delayed = new C() {
      @Override public List<ScheduledTask> build(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                                  List<Integer> priorities, Budget budget, RandomGenerator rng) {
        return actual.build(problem, api, view, priorities, budget, rng);
      }
      @Override public LocalResult applyLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                               LocalContext ctx, LocalPlan plan, Budget budget, RandomGenerator rng) {
        LocalResult result = actual.applyLocal(problem, api, view, ctx, plan, budget, rng);
        if (ctx.phase() == Phase.REASSIGN && view.satelliteId() == 1) time.set(200);
        return result;
      }
    };
    var result = new TwoStageSolver(LIMITS, time::get).solve(p, a, ManualStrategies.priorityByDensity(), delayed, 1);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    assertEquals(9L, result.totalProfit());
    assertEquals(0, result.owners().get(0));
    assertEquals(1, result.statistics().rejectedMoves());
    assertTrue(ScheduleValidator.evaluate(p, result.plan()).feasible());
  }

  @Test void allocationCanProposeTwoTaskOwnershipSwap() {
    List<Task> tasks = List.of(new Task(0, 0, 0, 4, 5), new Task(1, 0, 0, 9, 5));
    ProblemInstance p = instance(tasks, List.of(
        List.of(window(0, 0, 10, 15, 0, 0), window(0, 1, 40, 45, 0, 0)),
        List.of(window(1, 0, 10, 15, 0, 0), window(1, 1, 40, 45, 0, 0))));
    Budget budget = new Budget(() -> 0, 0, 2000, 1000);
    ProblemAccess api = new ProblemAccess(p, budget);
    State state = new State(new Assignment(Map.of(0, 0, 1, 1)),
        Map.of(0, List.of(observation(p, 0, 0)), 1, List.of(observation(p, 1, 1))), "state");
    Move move = ManualStrategies.allocationByCandidates().proposeMove(p, api, state,
        new Feedback(0, "START"), budget, new java.util.SplittableRandom(1));
    assertNotNull(move);
    assertEquals(Map.of(0, 1, 1, 0), move.edits());
  }

  @Test void ownershipSwapIsCommittedOnlyAfterBothSatellitesAreFeasibleAndProfitIncreases() {
    List<Task> tasks = List.of(new Task(0, 0, 0, 7, 5), new Task(1, 0, 0, 9, 5),
        new Task(2, 0, 0, 3, 5));
    ProblemInstance p = instance(tasks, List.of(
        List.of(window(0, 0, 10, 15, 0, 0), window(0, 1, 10, 15, 0, 0),
            window(0, 2, 50, 55, 0, 0)),
        List.of(window(1, 0, 30, 35, 0, 0), window(1, 2, 30, 35, 0, 0))));
    A a = new A() {
      boolean sent;
      @Override public Assignment initialAssign(ProblemInstance problem, ProblemAccess api, Budget budget,
                                                 RandomGenerator rng) { return new Assignment(Map.of(0, 0, 1, 0, 2, 1)); }
      @Override public Move proposeMove(ProblemInstance problem, ProblemAccess api, State state, Feedback feedback,
                                        Budget budget, RandomGenerator rng) {
        if (sent) return null;
        sent = true;
        return new Move(state.hash(), Map.of(0, 1, 2, 0));
      }
    };
    var result = new TwoStageSolver(LIMITS, () -> 0).solve(p, a,
        ManualStrategies.priorityByDensity(), ManualStrategies.earliestFinish(), 1);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    assertEquals(19L, result.totalProfit());
    assertEquals(Map.of(0, 1, 1, 0, 2, 0), result.owners());
    assertEquals(1, result.statistics().acceptedMoves());
    assertTrue(ScheduleValidator.evaluate(p, result.plan()).feasible());
  }

  @Test void staleMoveIsAnInterfaceFailure() {
    ProblemInstance p = contested();
    A a = new A() {
      @Override public Assignment initialAssign(ProblemInstance problem, ProblemAccess api, Budget budget,
                                                 RandomGenerator rng) { return new Assignment(Map.of(0, 0, 1, 0, 2, 1)); }
      @Override public Move proposeMove(ProblemInstance problem, ProblemAccess api, State state, Feedback feedback,
                                        Budget budget, RandomGenerator rng) { return new Move("stale", Map.of(0, 1)); }
    };
    var result = new TwoStageSolver(LIMITS, () -> 0).solve(p, a,
        ManualStrategies.priorityByDensity(), ManualStrategies.earliestFinish(), 1);
    assertEquals(TwoStageSolver.Status.FAILURE, result.status());
    assertTrue(result.detail().contains("stale move"), result.detail());
    assertNull(result.plan());
  }

  @Test void decodersChooseEarliestFinishOrMinimumAddedTransition() {
    List<Task> tasks = List.of(new Task(0, 0, 0, 7, 5), new Task(1, 0, 0, 5, 5),
        new Task(2, 0, 0, 8, 5));
    ProblemInstance p = instance(tasks, List.of(List.of(window(0, 0, 1, 6, 0, 0),
        window(0, 1, 80, 85, 0, 0), window(0, 2, 35, 40, 20, 20),
        window(0, 2, 50, 55, 0, 0))));
    Budget budget = new Budget(() -> 0, 0, 2000, 1000);
    ProblemAccess api = new ProblemAccess(p, budget);
    SatelliteView view = new SatelliteView(0, Set.of(0, 1, 2),
        List.of(observation(p, 0, 0), observation(p, 0, 1)), "view");
    LocalContext context = new LocalContext(Phase.INIT, Set.of(), Set.of(), List.of());
    LocalPlan plan = new LocalPlan("view", Set.of(), List.of(2));
    var earliest = ManualStrategies.earliestFinish().applyLocal(p, api, view, context, plan,
        budget, new java.util.SplittableRandom(1));
    var transition = ManualStrategies.minimumAddedTransition().applyLocal(p, api, view, context, plan,
        budget, new java.util.SplittableRandom(1));
    assertEquals(35, earliest.observations().stream().filter(o -> o.getTaskId() == 2).findFirst().orElseThrow().getStartTime());
    assertEquals(50, transition.observations().stream().filter(o -> o.getTaskId() == 2).findFirst().orElseThrow().getStartTime());
    assertTrue(ScheduleValidator.evaluate(p, new SchedulingPlan(earliest.observations())).feasible());
    assertTrue(ScheduleValidator.evaluate(p, new SchedulingPlan(transition.observations())).feasible());
  }

  @Test void densityAndScarcityPrioritiesUseActualCandidateCounts() {
    List<Task> tasks = List.of(new Task(0, 0, 0, 10, 5), new Task(1, 0, 0, 8, 5));
    TimeWindow many = new TimeWindow(0, 0, 2013, 4, 20, 0, 0, 0,
        2013, 4, 20, 0, 0, 200);
    for (double time : new double[] {0, 1, 6, 11, 16, 21, 26, 27}) many.addAttitudeRecord(record(time, 0));
    ProblemInstance p = instance(tasks, List.of(List.of(many, window(0, 1, 1, 6, 0, 0))));
    Budget budget = new Budget(() -> 0, 0, 2000, 1000);
    ProblemAccess api = new ProblemAccess(p, budget);
    SatelliteView view = new SatelliteView(0, Set.of(0, 1), List.of(), "view");
    assertEquals(5, api.candidateCount(0, 0, budget));
    assertEquals(1, api.candidateCount(1, 0, budget));
    assertEquals(List.of(0, 1), ManualStrategies.priorityByDensity().initialPriority(p, api, view,
        budget, new java.util.SplittableRandom(1)));
    assertEquals(List.of(1, 0), ManualStrategies.priorityByScarcity().initialPriority(p, api, view,
        budget, new java.util.SplittableRandom(1)));
  }

  @Test void oneMoveCanAdjustMoreThanFourOtherTasks() {
    List<Task> tasks = new ArrayList<>();
    List<TimeWindow> destination = new ArrayList<>();
    for (int id = 0; id < 6; id++) {
      tasks.add(new Task(id, 0, 0, 7, 5));
      int start = 1 + 30 * id;
      destination.add(window(1, id, start, start + 5, 0, 0));
      destination.add(window(1, id, start + 2, start + 7, 0, 0));
    }
    tasks.add(new Task(6, 0, 0, 9, 5));
    destination.add(window(1, 6, 185, 190, 0, 0));
    ProblemInstance p = instance(tasks, List.of(List.of(window(0, 6, 185, 190, 0, 0)), destination));
    A a = new A() {
      boolean sent;
      @Override public Assignment initialAssign(ProblemInstance problem, ProblemAccess api, Budget budget,
                                                 RandomGenerator rng) {
        Map<Integer, Integer> owners = new HashMap<>();
        for (int id = 0; id < 6; id++) owners.put(id, 1);
        owners.put(6, 0);
        return new Assignment(owners);
      }
      @Override public Move proposeMove(ProblemInstance problem, ProblemAccess api, State state, Feedback feedback,
                                        Budget budget, RandomGenerator rng) {
        if (sent) return null;
        sent = true;
        return new Move(state.hash(), Map.of(6, 1));
      }
    };
    B b = new TestB() {
      @Override public List<Integer> initialPriority(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                                     Budget budget, RandomGenerator rng) {
        return view.satelliteId() == 0 ? List.of() : view.assignedTaskIds().stream().sorted().toList();
      }
      @Override public LocalPlan proposeReassignmentLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                               LocalContext ctx, Feedback fb, Budget budget, RandomGenerator rng) {
        if (ctx.phase() == Phase.INIT || view.satelliteId() == 0 || fb.attempt() > 5) return null;
        if (fb.attempt() == 5) return new LocalPlan(view.hash(), Set.of(), List.of(6));
        return new LocalPlan(view.hash(), Set.of(fb.attempt()), List.of(fb.attempt()));
      }
    };
    C base = ManualStrategies.earliestFinish();
    C editing = new C() {
      @Override public List<ScheduledTask> build(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                                  List<Integer> priorities, Budget budget, RandomGenerator rng) {
        return base.build(problem, api, view, priorities, budget, rng);
      }
      @Override public LocalResult applyLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                               LocalContext ctx, LocalPlan plan, Budget budget, RandomGenerator rng) {
        if (plan.release().isEmpty()) return base.applyLocal(problem, api, view, ctx, plan, budget, rng);
        int id = plan.release().iterator().next();
        TimeWindow alternate = p.satellite(1).getTimeWindows().stream().filter(w -> w.getTaskId() == id).skip(1)
            .findFirst().orElseThrow();
        var replacement = new ScheduledTask(p.task(id), alternate, alternate.getAttitudeRecord(1),
            alternate.getAttitudeRecord(2));
        List<ScheduledTask> changed = new ArrayList<>(view.observations());
        changed.removeIf(o -> o.getTaskId() == id);
        changed.add(replacement);
        return LocalResult.applied(changed);
      }
    };
    var result = new TwoStageSolver(LIMITS, () -> 0).solve(p, a, b, editing, 1);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    assertEquals(1, result.statistics().acceptedMoves());
    assertEquals(51, result.totalProfit());
    for (int id = 0; id < 5; id++) {
      int taskId = id;
      assertEquals(3 + 30 * id, result.plan().observations().stream()
          .filter(o -> o.getTaskId() == taskId).findFirst().orElseThrow().getStartTime());
    }
    assertTrue(ScheduleValidator.evaluate(p, result.plan()).feasible());
  }

  private A fixedOwners(Map<Integer, Integer> owners) {
    return new A() {
      @Override public Assignment initialAssign(ProblemInstance p, ProblemAccess api, Budget budget,
                                                 RandomGenerator rng) { return new Assignment(owners); }
      @Override public Move proposeMove(ProblemInstance p, ProblemAccess api, State state, Feedback feedback,
                                        Budget budget, RandomGenerator rng) { return null; }
    };
  }
}
