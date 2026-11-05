package method.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;
import method.runtime.SolverContracts.A;
import method.runtime.SolverContracts.Assignment;
import method.runtime.SolverContracts.B;
import method.runtime.SolverContracts.C;
import method.runtime.SolverContracts.Feedback;
import method.runtime.SolverContracts.InitialEvaluator;
import method.runtime.SolverContracts.InitialTrial;
import method.runtime.SolverContracts.LocalContext;
import method.runtime.SolverContracts.LocalEvaluator;
import method.runtime.SolverContracts.LocalPlan;
import method.runtime.SolverContracts.LocalResult;
import method.runtime.SolverContracts.LocalTrial;
import method.runtime.SolverContracts.Move;
import method.runtime.SolverContracts.MoveEvaluator;
import method.runtime.SolverContracts.MoveTrial;
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
import problem.ScheduleValidator;

import static org.junit.jupiter.api.Assertions.*;

class CandidateTrialTest {
  private static final TwoStageSolver.Limits LIMITS = new TwoStageSolver.Limits(1_000, 2_000, 400, 200);

  private abstract static class TestB implements B {
    @Override public LocalPlan proposeInitialLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
        LocalContext context, Feedback feedback, Budget budget, RandomGenerator rng) { return null; }
    @Override public LocalPlan proposeReassignmentLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
        LocalContext context, Feedback feedback, Budget budget, RandomGenerator rng) { return null; }
  }

  private ProblemInstance problem(int satelliteCount) {
    List<Task> tasks = List.of(new Task(0, 0, 0, 9, 5), new Task(1, 0, 0, 5, 5));
    List<Satellite> satellites = new ArrayList<>();
    for (int sat = 0; sat < satelliteCount; sat++) {
      var satellite = new Satellite(sat);
      List<TimeWindow> windows = new ArrayList<>();
      for (Task task : tasks) {
        var window = new TimeWindow(sat, task.getId(), 2026, 1, 1, 0, 0, 9,
            2026, 1, 1, 0, 0, 16);
        for (int time : new int[]{9, 10, 15, 16}) {
          window.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, time, 0, 0));
        }
        windows.add(window);
      }
      satellite.setTimeWindows(windows);
      satellites.add(satellite);
    }
    return new ProblemInstance(tasks, satellites);
  }

  private A allocation(boolean move) {
    return new A() {
      boolean proposed;
      @Override public Assignment initialAssign(ProblemInstance p, ProblemAccess api, Budget budget, RandomGenerator rng) {
        return new Assignment(Map.of(0, 0, 1, 0));
      }
      @Override public Move proposeMove(ProblemInstance p, ProblemAccess api, State state, Feedback feedback,
                                        Budget budget, RandomGenerator rng) {
        if (!move || proposed) return null;
        proposed = true;
        return new Move(state.hash(), Map.of(1, 1));
      }
    };
  }

  private static final class CountingDecoder implements C {
    final C delegate = ManualStrategies.earliestFinish();
    final AtomicInteger builds = new AtomicInteger();
    final AtomicInteger edits = new AtomicInteger();
    final AtomicInteger rebuilds = new AtomicInteger();
    @Override public List<ScheduledTask> build(ProblemInstance p, ProblemAccess api, SatelliteView view,
                                                List<Integer> order, Budget budget, RandomGenerator rng) {
      builds.incrementAndGet();
      return delegate.build(p, api, view, order, budget, rng);
    }
    @Override public LocalResult applyLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
                                             LocalContext context, LocalPlan plan, Budget budget, RandomGenerator rng) {
      edits.incrementAndGet();
      return delegate.applyLocal(p, api, view, context, plan, budget, rng);
    }
    @Override public List<ScheduledTask> rebuild(ProblemInstance p, ProblemAccess api, SatelliteView view,
        LocalContext context, List<Integer> order, Budget budget, RandomGenerator rng) {
      rebuilds.incrementAndGet();
      assertEquals(Phase.REASSIGN, context.phase());
      assertTrue(view.observations().isEmpty());
      assertTrue(context.incoming().isEmpty() && context.outgoing().isEmpty());
      return delegate.rebuild(p, api, view, context, order, budget, rng);
    }
  }

  @Test void initializationSelectsOneOfSeveralValidatedTrialsWithoutDecodingItAgain() {
    var problem = problem(1);
    var decoder = new CountingDecoder();
    var saved = new AtomicReference<InitialEvaluator>();
    B search = new TestB() {
      @Override public InitialTrial searchInitial(ProblemInstance p, ProblemAccess api, SatelliteView view,
          InitialEvaluator evaluator, Budget budget, RandomGenerator rng) {
        saved.set(evaluator);
        var low = evaluator.evaluate(List.of(1, 0));
        var high = evaluator.evaluate(List.of(0, 1));
        assertEquals(5, low.totalProfit());
        assertEquals(9, high.totalProfit());
        return high;
      }
      @Override public LocalTrial searchInitialLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
          LocalContext context, Feedback feedback, LocalEvaluator evaluator, Budget budget, RandomGenerator rng) {
        return null;
      }
    };
    var result = new TwoStageSolver(LIMITS, () -> 0).solve(problem, allocation(false), search, decoder, 7);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    assertEquals(9L, result.totalProfit());
    assertEquals(2, decoder.builds.get());
    assertEquals(2, result.statistics().initialBuilds());
    assertTrue(ScheduleValidator.evaluate(problem, result.plan()).feasible());
    assertThrows(IllegalStateException.class, () -> saved.get().evaluate(List.of(0)));
  }

  @Test void localSearchCanCompareEditsAndCommitOnlyTheSelectedTrial() {
    var problem = problem(1);
    var decoder = new CountingDecoder();
    B search = new TestB() {
      @Override public List<Integer> initialPriority(ProblemInstance p, ProblemAccess api, SatelliteView view,
          Budget budget, RandomGenerator rng) { return List.of(1); }
      @Override public LocalTrial searchInitialLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
          LocalContext context, Feedback feedback, LocalEvaluator evaluator, Budget budget, RandomGenerator rng) {
        if (feedback.attempt() > 0) return null;
        var improved = evaluator.evaluate(new LocalPlan(view.hash(), Set.of(1), List.of(0)));
        var unchanged = evaluator.evaluate(new LocalPlan(view.hash(), Set.of(), List.of(0)));
        assertEquals(9L, improved.totalProfit());
        assertEquals(SolverContracts.LocalStatus.NO_FEASIBLE_CHANGE, unchanged.result().status());
        assertNull(unchanged.totalProfit());
        return improved;
      }
    };
    var result = new TwoStageSolver(LIMITS, () -> 0).solve(problem, allocation(false), search, decoder, 7);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    assertEquals(9L, result.totalProfit());
    assertEquals(2, decoder.edits.get());
    assertTrue(ScheduleValidator.evaluate(problem, result.plan()).feasible());
  }

  @Test void reassignmentTrialsUseTheRebuildInterfaceOnAnEmptySatelliteView() {
    var problem = problem(2);
    var decoder = new CountingDecoder();
    B search = new TestB() {
      @Override public List<Integer> initialPriority(ProblemInstance p, ProblemAccess api, SatelliteView view,
          Budget budget, RandomGenerator rng) {
        return view.satelliteId() == 0 ? List.of(0, 1) : List.of();
      }
      @Override public LocalTrial searchReassignment(ProblemInstance p, ProblemAccess api, SatelliteView view,
          LocalContext context, Feedback feedback, LocalEvaluator evaluator, Budget budget, RandomGenerator rng) {
        if (context.phase() != Phase.REASSIGN || view.satelliteId() != 1 || feedback.attempt() > 0) return null;
        var edit = evaluator.evaluate(new LocalPlan(view.hash(), Set.of(), List.of(1)));
        var rebuilt = evaluator.rebuild(List.of(1));
        assertEquals(5L, edit.totalProfit());
        assertEquals(5L, rebuilt.totalProfit());
        return rebuilt;
      }
    };
    var result = new TwoStageSolver(LIMITS, () -> 0).solve(problem, allocation(true), search, decoder, 7);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    assertEquals(14L, result.totalProfit());
    assertEquals(1, result.statistics().acceptedMoves());
    assertEquals(1, decoder.edits.get());
    assertEquals(1, decoder.rebuilds.get());
    assertTrue(ScheduleValidator.evaluate(problem, result.plan()).feasible());
  }

  @Test void fabricatedTrialCannotBeSelected() {
    B search = new TestB() {
      @Override public InitialTrial searchInitial(ProblemInstance p, ProblemAccess api, SatelliteView view,
          InitialEvaluator evaluator, Budget budget, RandomGenerator rng) {
        var actual = evaluator.evaluate(List.of(0));
        return new InitialTrial(actual.priorities(), actual.observations(), actual.totalProfit());
      }
    };
    var result = new TwoStageSolver(LIMITS, () -> 0).solve(problem(1), allocation(false), search,
        new CountingDecoder(), 7);
    assertEquals(TwoStageSolver.Status.FAILURE, result.status());
    assertTrue(result.detail().contains("not issued"));
    assertNull(result.plan());
  }

  @Test void invalidDecoderOutputCannotBeHiddenByTheSearchModule() {
    var calls = new AtomicInteger();
    C decoder = new C() {
      final C delegate = ManualStrategies.earliestFinish();
      @Override public List<ScheduledTask> build(ProblemInstance p, ProblemAccess api, SatelliteView view,
          List<Integer> order, Budget budget, RandomGenerator rng) {
        if (calls.incrementAndGet() == 1) return delegate.build(p, api, view, order, budget, rng);
        var window = p.satellite(view.satelliteId()).getTimeWindows().stream()
            .filter(w -> w.getTaskId() == 1).findFirst().orElseThrow();
        return List.of(new ScheduledTask(p.task(1), window, window.getAttitudeRecord(1), window.getAttitudeRecord(2)));
      }
      @Override public LocalResult applyLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
          LocalContext context, LocalPlan plan, Budget budget, RandomGenerator rng) {
        return LocalResult.unchanged();
      }
    };
    B search = new TestB() {
      @Override public InitialTrial searchInitial(ProblemInstance p, ProblemAccess api, SatelliteView view,
          InitialEvaluator evaluator, Budget budget, RandomGenerator rng) {
        var valid = evaluator.evaluate(List.of(0));
        try { evaluator.evaluate(List.of(0)); } catch (RuntimeException ignored) { }
        return valid;
      }
    };
    var result = new TwoStageSolver(LIMITS, () -> 0).solve(problem(1), allocation(false), search, decoder, 7);
    assertEquals(TwoStageSolver.Status.FAILURE, result.status());
    assertTrue(result.detail().contains("outside priorities"), result.detail());
  }

  @Test void trialEvaluationCannotExtendTheSearchOrHardDeadline() {
    var clock = new AtomicLong();
    B delayed = new TestB() {
      @Override public InitialTrial searchInitial(ProblemInstance p, ProblemAccess api, SatelliteView view,
          InitialEvaluator evaluator, Budget budget, RandomGenerator rng) {
        var valid = evaluator.evaluate(List.of(0));
        clock.set(1_000);
        try { evaluator.evaluate(List.of(1)); } catch (RuntimeException ignored) { }
        return valid;
      }
    };
    var result = new TwoStageSolver(LIMITS, clock::get).solve(problem(1), allocation(false), delayed,
        new CountingDecoder(), 7);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status());
    assertEquals("SEARCH_EXPIRED", result.detail());
    assertEquals(0L, result.totalProfit());
    clock.set(0);
    B hard = new TestB() {
      @Override public InitialTrial searchInitial(ProblemInstance p, ProblemAccess api, SatelliteView view,
          InitialEvaluator evaluator, Budget budget, RandomGenerator rng) {
        var valid = evaluator.evaluate(List.of(0));
        clock.set(2_000);
        try { evaluator.evaluate(List.of(1)); } catch (RuntimeException ignored) { }
        return valid;
      }
    };
    var timeout = new TwoStageSolver(LIMITS, clock::get).solve(problem(1), allocation(false), hard,
        new CountingDecoder(), 7);
    assertEquals(TwoStageSolver.Status.HARD_TIMEOUT, timeout.status());
    assertNull(timeout.plan());
  }

  @Test void sequentialTrialsUseImmutableInputsAndReproducibleRandomStreams() {
    assertEquals(randomTrialSamples(), randomTrialSamples());
    var samples = randomTrialSamples();
    assertEquals(2, samples.size());
    assertNotEquals(samples.get(0), samples.get(1));
  }

  private List<Long> randomTrialSamples() {
    List<Long> samples = new ArrayList<>();
    C decoder = new C() {
      @Override public List<ScheduledTask> build(ProblemInstance p, ProblemAccess api, SatelliteView view,
          List<Integer> order, Budget budget, RandomGenerator rng) {
        assertThrows(UnsupportedOperationException.class, () -> order.add(99));
        samples.add(rng.nextLong());
        return List.of();
      }
      @Override public LocalResult applyLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
          LocalContext context, LocalPlan plan, Budget budget, RandomGenerator rng) {
        return LocalResult.unchanged();
      }
    };
    B search = new TestB() {
      @Override public InitialTrial searchInitial(ProblemInstance p, ProblemAccess api, SatelliteView view,
          InitialEvaluator evaluator, Budget budget, RandomGenerator rng) {
        List<Integer> order = new ArrayList<>(List.of(0, 1));
        var selected = evaluator.evaluate(order);
        order.clear();
        assertEquals(List.of(0, 1), selected.priorities());
        evaluator.evaluate(List.of(1, 0));
        return selected;
      }
      @Override public LocalTrial searchInitialLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
          LocalContext context, Feedback feedback, LocalEvaluator evaluator, Budget budget, RandomGenerator rng) {
        return null;
      }
    };
    var result = new TwoStageSolver(LIMITS, () -> 0).solve(problem(1), allocation(false), search, decoder, 7);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    return List.copyOf(samples);
  }

  @Test void sourceRuntimeAlsoOffersControlledTrialsInBothPhases() throws Exception {
    var problem = problem(2);
    var decoder = new CountingDecoder();
    var initialTrials = new AtomicInteger();
    var localTrials = new AtomicInteger();
    B search = new TestB() {
      @Override public InitialTrial searchInitial(ProblemInstance p, ProblemAccess api, SatelliteView view,
          InitialEvaluator evaluator, Budget budget, RandomGenerator rng) {
        if (view.assignedTaskIds().size() < 2) return evaluator.evaluate(view.assignedTaskIds().stream().sorted().toList());
        var low = evaluator.evaluate(List.of(1, 0));
        var high = evaluator.evaluate(List.of(0, 1));
        initialTrials.addAndGet(2);
        return high.totalProfit() >= low.totalProfit() ? high : low;
      }
      @Override public LocalTrial searchReassignment(ProblemInstance p, ProblemAccess api, SatelliteView view,
          LocalContext context, Feedback feedback, LocalEvaluator evaluator, Budget budget, RandomGenerator rng) {
        if (context.phase() != Phase.REASSIGN || view.satelliteId() != 1 || feedback.attempt() > 0
            || !view.assignedTaskIds().contains(1)
            || view.observations().stream().anyMatch(o -> o.getTaskId() == 1)) return null;
        var edit = evaluator.evaluate(new LocalPlan(view.hash(), Set.of(), List.of(1)));
        var rebuilt = evaluator.rebuild(List.of(1));
        localTrials.addAndGet(2);
        return rebuilt.totalProfit() != null ? rebuilt : edit;
      }
    };
    var components = new RkhmaRuntime.Components(allocation(true),
        search, decoder);
    var config = new RkhmaRuntime.Config(7, 6, 30, 3, 2, .1, 10_000_000_000L, 12_000_000_000L);
    var result = RkhmaRuntime.solve(problem, components, config);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    assertEquals(14L, result.profit());
    assertTrue(initialTrials.get() >= 2);
    assertTrue(localTrials.get() >= 2);
    assertTrue(decoder.rebuilds.get() > 0);
    assertTrue(ScheduleValidator.evaluate(problem, result.plan()).feasible());
  }

  @Test void concurrentTrialRequestsWorkInBothRuntimePaths() throws Exception {
    var problem = problem(2);
    var manual = new TwoStageSolver(LIMITS, () -> 0).solve(problem, allocation(false),
        parallelSearch(), new CountingDecoder(), 7);
    assertEquals(TwoStageSolver.Status.SUCCESS, manual.status(), manual.detail());
    assertEquals(9L, manual.totalProfit());

    var components = new RkhmaRuntime.Components(allocation(false),
        parallelSearch(), new CountingDecoder());
    var config = new RkhmaRuntime.Config(7, 6, 30, 0, 2, .1, 10_000_000_000L, 12_000_000_000L);
    var source = RkhmaRuntime.solve(problem, components, config);
    assertEquals(TwoStageSolver.Status.SUCCESS, source.status(), source.detail());
    assertEquals(9L, source.profit());
    assertTrue(ScheduleValidator.evaluate(problem, source.plan()).feasible());
  }

  @Test void sourceAllocationAndDecoderCanBeCombinedWithTrialBasedSearch() throws Exception {
    var problem = problem(2);
    var trials = new AtomicInteger();
    B search = new TestB() {
      @Override public InitialTrial searchInitial(ProblemInstance p, ProblemAccess api, SatelliteView view,
          InitialEvaluator evaluator, Budget budget, RandomGenerator rng) {
        var order = view.assignedTaskIds().stream().sorted().toList();
        var first = evaluator.evaluate(order);
        var second = evaluator.evaluate(order.reversed());
        trials.addAndGet(2);
        return second.totalProfit() > first.totalProfit() ? second : first;
      }
      @Override public LocalTrial searchInitialLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
          LocalContext context, Feedback feedback, LocalEvaluator evaluator, Budget budget, RandomGenerator rng) {
        return null;
      }
    };
    var components = new RkhmaRuntime.Components(RkhmaRuntime.rkhmaAllocation(),
        search, RkhmaRuntime.rkhmaDecoder());
    var config = new RkhmaRuntime.Config(7, 6, 30, 3, 2, .1, 10_000_000_000L, 12_000_000_000L);
    var result = RkhmaRuntime.solve(problem, components, config);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    assertEquals(14L, result.profit());
    assertTrue(trials.get() >= 4);
    assertTrue(ScheduleValidator.evaluate(problem, result.plan()).feasible());
  }

  @Test void allocationCanCompareAndChainTrialsInBothRuntimePaths() throws Exception {
    var genericSaved = new AtomicReference<MoveEvaluator>();
    var generic = new TwoStageSolver(LIMITS, () -> 0).solve(problem(3), trialAllocation(genericSaved),
        rebuildingSearch(), new CountingDecoder(), 7);
    assertEquals(TwoStageSolver.Status.SUCCESS, generic.status(), generic.detail());
    assertEquals(14L, generic.totalProfit());
    assertEquals(Map.of(0, 1, 1, 2), generic.owners());
    assertThrows(IllegalStateException.class, () -> genericSaved.get().evaluate(null, null));

    var sourceSaved = new AtomicReference<MoveEvaluator>();
    var components = new RkhmaRuntime.Components(trialAllocation(sourceSaved), rebuildingSearch(), new CountingDecoder());
    var config = new RkhmaRuntime.Config(7, 6, 30, 3, 2, .1, 10_000_000_000L, 12_000_000_000L);
    var source = RkhmaRuntime.solve(problem(3), components, config);
    assertEquals(TwoStageSolver.Status.SUCCESS, source.status(), source.detail());
    assertEquals(14L, source.profit());
    assertEquals(Map.of(0, 1, 1, 2), source.owners());
    assertThrows(IllegalStateException.class, () -> sourceSaved.get().evaluate(null, null));
  }

  @Test void allocationCannotHideAnInvalidTrialInEitherRuntime() throws Exception {
    A invalid = new A() {
      @Override public Assignment initialAssign(ProblemInstance p, ProblemAccess api, Budget b, RandomGenerator r) {
        return new Assignment(Map.of(0, 0, 1, 0));
      }
      @Override public MoveTrial searchReassignment(ProblemInstance p, ProblemAccess api, State state, Feedback feedback,
          MoveEvaluator evaluator, Budget budget, RandomGenerator rng) {
        try { evaluator.evaluate(state, new Move(state.hash(), Map.of(1, 99))); }
        catch (RuntimeException ignored) { }
        return null;
      }
    };
    var generic = new TwoStageSolver(LIMITS, () -> 0).solve(problem(2), invalid,
        rebuildingSearch(), new CountingDecoder(), 7);
    assertEquals(TwoStageSolver.Status.FAILURE, generic.status());
    var config = new RkhmaRuntime.Config(7, 6, 30, 3, 2, .1, 10_000_000_000L, 12_000_000_000L);
    var source = RkhmaRuntime.solve(problem(2),
        new RkhmaRuntime.Components(invalid, rebuildingSearch(), new CountingDecoder()), config);
    assertEquals(TwoStageSolver.Status.FAILURE, source.status(), source.detail());
  }

  @Test void acceptedAllocationCheckpointSurvivesTheSearchDeadline() {
    var clock = new AtomicLong();
    A checkpointing = new A() {
      @Override public Assignment initialAssign(ProblemInstance p, ProblemAccess api, Budget b, RandomGenerator r) {
        return new Assignment(Map.of(0, 0, 1, 0));
      }
      @Override public MoveTrial searchReassignment(ProblemInstance p, ProblemAccess api, State root, Feedback feedback,
          MoveEvaluator evaluator, Budget budget, RandomGenerator rng) {
        var accepted = evaluator.evaluate(root, new Move(root.hash(), Map.of(1, 1)));
        assertEquals(14L, accepted.totalProfit());
        evaluator.checkpoint(accepted);
        clock.set(1_000);
        return null;
      }
    };
    var result = new TwoStageSolver(LIMITS, clock::get).solve(problem(2), checkpointing,
        rebuildingSearch(), new CountingDecoder(), 7);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    assertEquals("SEARCH_EXPIRED", result.detail());
    assertEquals(14L, result.totalProfit());
    assertEquals(1, result.owners().get(1));
  }

  @Test void allocationCannotCheckpointAnUnissuedTrialEvenIfItCatchesTheError() throws Exception {
    A invalid = new A() {
      @Override public Assignment initialAssign(ProblemInstance p, ProblemAccess api, Budget b, RandomGenerator r) {
        return new Assignment(Map.of(0, 0, 1, 0));
      }
      @Override public MoveTrial searchReassignment(ProblemInstance p, ProblemAccess api, State root, Feedback feedback,
          MoveEvaluator evaluator, Budget budget, RandomGenerator rng) {
        var actual = evaluator.evaluate(root, new Move(root.hash(), Map.of(1, 1)));
        var forged = new MoveTrial(actual.move(), actual.state(), actual.totalProfit());
        try { evaluator.checkpoint(forged); } catch (RuntimeException ignored) { }
        return actual;
      }
    };
    var generic = new TwoStageSolver(LIMITS, () -> 0).solve(problem(2), invalid,
        rebuildingSearch(), new CountingDecoder(), 7);
    assertEquals(TwoStageSolver.Status.FAILURE, generic.status());
    var config = new RkhmaRuntime.Config(7, 6, 30, 3, 2, .1, 10_000_000_000L, 12_000_000_000L);
    var source = RkhmaRuntime.solve(problem(2),
        new RkhmaRuntime.Components(invalid, rebuildingSearch(), new CountingDecoder()), config);
    assertEquals(TwoStageSolver.Status.FAILURE, source.status(), source.detail());
  }

  @Test void fullCalibrationUsesTheSeparateReassignmentSearchEntry() {
    var calls = new AtomicInteger();
    var begins = new AtomicInteger();
    B search = new TestB() {
      @Override public List<Integer> initialPriority(ProblemInstance p, ProblemAccess api, SatelliteView view,
          Budget budget, RandomGenerator rng) { return view.assignedTaskIds().stream().sorted().toList(); }
      @Override public void beginCalibration(ProblemInstance p, ProblemAccess api, State state, Budget budget) {
        begins.incrementAndGet();
      }
      @Override public LocalTrial searchCalibration(ProblemInstance p, ProblemAccess api, SatelliteView view,
          LocalContext local, Feedback feedback, LocalEvaluator evaluator, Budget budget, RandomGenerator rng) {
        if (feedback.attempt() > 0) return null;
        calls.incrementAndGet();
        return evaluator.rebuild(view.assignedTaskIds().stream().sorted().toList());
      }
    };
    A calibrating = new A() {
      boolean done;
      @Override public Assignment initialAssign(ProblemInstance p, ProblemAccess api, Budget b, RandomGenerator r) {
        return new Assignment(Map.of(0, 0, 1, 0));
      }
      @Override public MoveTrial searchReassignment(ProblemInstance p, ProblemAccess api, State root, Feedback feedback,
          MoveEvaluator evaluator, Budget budget, RandomGenerator rng) {
        if (done) return null;
        done = true;
        return evaluator.calibrate(root);
      }
    };
    var result = new TwoStageSolver(LIMITS, () -> 0).solve(problem(2), calibrating, search, new CountingDecoder(), 7);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    assertEquals(1, begins.get());
    assertEquals(2, calls.get());
  }

  private A trialAllocation(AtomicReference<MoveEvaluator> saved) {
    return new A() {
      boolean done;
      @Override public Assignment initialAssign(ProblemInstance p, ProblemAccess api, Budget budget, RandomGenerator rng) {
        return new Assignment(Map.of(0, 0, 1, 0));
      }
      @Override public MoveTrial searchReassignment(ProblemInstance p, ProblemAccess api, State root, Feedback feedback,
          MoveEvaluator evaluator, Budget budget, RandomGenerator rng) {
        if (done) return null;
        done = true;
        saved.set(evaluator);
        var first = evaluator.evaluate(root, new Move(root.hash(), Map.of(0, 1)));
        var alternative = evaluator.evaluate(root, new Move(root.hash(), Map.of(1, 1)));
        assertNotNull(alternative.state());
        assertNotNull(first.state());
        return evaluator.evaluate(first.state(), new Move(first.state().hash(), Map.of(1, 2)));
      }
    };
  }

  private B rebuildingSearch() {
    return new TestB() {
      @Override public List<Integer> initialPriority(ProblemInstance p, ProblemAccess api, SatelliteView view,
          Budget budget, RandomGenerator rng) { return view.assignedTaskIds().stream().sorted().toList(); }
      @Override public LocalTrial searchReassignment(ProblemInstance p, ProblemAccess api, SatelliteView view,
          LocalContext context, Feedback feedback, LocalEvaluator evaluator, Budget budget, RandomGenerator rng) {
        return feedback.attempt() > 0 ? null : evaluator.rebuild(view.assignedTaskIds().stream().sorted().toList());
      }
    };
  }

  private B parallelSearch() {
    return new TestB() {
      @Override public InitialTrial searchInitial(ProblemInstance p, ProblemAccess api, SatelliteView view,
          InitialEvaluator evaluator, Budget budget, RandomGenerator rng) {
        if (view.assignedTaskIds().isEmpty()) return evaluator.evaluate(List.of());
        var pool = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        try {
          var low = pool.submit(() -> evaluator.evaluate(List.of(1, 0)));
          var high = pool.submit(() -> evaluator.evaluate(List.of(0, 1)));
          var first = low.get(2, TimeUnit.SECONDS);
          var second = high.get(2, TimeUnit.SECONDS);
          return first.totalProfit() > second.totalProfit() ? first : second;
        } catch (Exception error) {
          throw new IllegalStateException("concurrent candidate evaluation failed", error);
        } finally {
          pool.shutdownNow();
        }
      }
      @Override public LocalTrial searchInitialLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
          LocalContext context, Feedback feedback, LocalEvaluator evaluator, Budget budget, RandomGenerator rng) {
        return null;
      }
    };
  }

}
