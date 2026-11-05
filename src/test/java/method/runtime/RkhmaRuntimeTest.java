package method.runtime;

import method.runtime.SolverContracts.A;
import method.runtime.SolverContracts.Assignment;
import method.runtime.SolverContracts.B;
import method.runtime.SolverContracts.C;
import method.runtime.SolverContracts.Feedback;
import method.runtime.SolverContracts.LocalContext;
import method.runtime.SolverContracts.LocalPlan;
import method.runtime.SolverContracts.LocalResult;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

class RkhmaRuntimeTest {
  private static final RkhmaRuntime.Config SMALL =
      new RkhmaRuntime.Config(1234, 6, 30, 3, 2, .1, 10_000_000_000L, 12_000_000_000L);
  private static final Map<Integer, Integer> SOURCE_OWNERS = Map.of(0, 1, 1, 0, 2, 1, 3, 0, 4, 1, 5, 0);
  private static final List<String> SOURCE_HISTORY = List.of("HoldInitialAssignment", "ReassignRejected", "ReassignRejected");
  private static final List<String> SOURCE_OBSERVATIONS = List.of("1:0:10.0:12.0", "2:1:10.0:12.0", "4:1:25.0:27.0", "5:0:25.0:27.0");

  private ProblemInstance fixture(int count) {
    List<Task> tasks = new ArrayList<>();
    List<Satellite> satellites = List.of(new Satellite(0), new Satellite(1));
    List<List<TimeWindow>> windows = List.of(new ArrayList<>(), new ArrayList<>());
    for (int id = 0; id < count; id++) {
      tasks.add(new Task(id, 0, 0, id < 6 ? 5 + id : 1, 2));
      if (id >= 6) continue;
      for (int sat = 0; sat < 2; sat++) {
        int start = 10 + id / 3 * 15;
        var window = new TimeWindow(sat, id, 2026, 1, 1, 0, 0, start - .25,
            2026, 1, 1, 0, 0, start + 2.25);
        window.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, start - .25, 0, 0));
        for (int k = 0; k <= 2; k++) {
          window.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, start + k, 0, 0));
        }
        window.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, start + 2.25, 0, 0));
        windows.get(sat).add(window);
      }
    }
    for (int sat = 0; sat < 2; sat++) satellites.get(sat).setTimeWindows(windows.get(sat));
    return new ProblemInstance(tasks, satellites);
  }

  private List<String> observations(RkhmaRuntime.Result result) {
    return result.plan().observations().stream()
        .map(o -> o.getTaskId() + ":" + o.getSatelliteId() + ":" + o.getStartTime() + ":" + o.getEndTime())
        .sorted().toList();
  }

  private record SourceRun(Map<Integer, Integer> owners, long profit, List<String> observations,
                           List<String> history) { }

  private SourceRun forcedSourceRun(ProblemInstance problem) throws Exception {
    var bridge = new method.rkhma.bridge.ProblemBridge(problem);
    method.rkhma.legacy.common.RandomProvider.reset(1234);
    Map<Integer, Integer> owners = new LinkedHashMap<>();
    for (var task : bridge.tasks()) {
      owners.put(task.getId(), 0);
      bridge.satellites().getFirst().addTask(task);
    }
    var coordinator = new method.rkhma.legacy.main.TaskAllocationCoordinator(bridge.tasks(), bridge.satellites(),
        new method.rkhma.legacy.operator.assignment.MaxWindowCountStrategy(), 3, 2, .1);
    var config = new method.rkhma.legacy.main.TaskAllocationCoordinator.HybCecConfig(
        new int[]{4, 1}, new int[]{0, 0}, 0, 0, 0, 6, 30,
        method.rkhma.legacy.operator.insertion.InsertionMode.GREEDY, 1234);
    var plans = coordinator.scheduleWithReassignment(config, bridge.satellites(), owners,
        method.rkhma.legacy.common.SearchDeadline.unlimited());
    List<ScheduledTask> observations = new ArrayList<>();
    for (var plan : plans.values()) for (var observation : plan.getSchedule()) observations.add(bridge.fromLegacy(observation));
    var checked = ScheduleValidator.evaluate(problem, new model.SchedulingPlan(observations));
    assertTrue(checked.feasible(), checked.violations().toString());
    Map<Integer, Integer> finalOwners = new LinkedHashMap<>();
    coordinator.getBestTaskAssignmentSnapshot().forEach((task, satellite) ->
        finalOwners.put(bridge.taskId(task), bridge.satelliteId(satellite)));
    var formatted = observations.stream()
        .map(o -> o.getTaskId() + ":" + o.getSatelliteId() + ":" + o.getStartTime() + ":" + o.getEndTime())
        .sorted().toList();
    return new SourceRun(finalOwners, checked.totalProfit(), formatted, coordinator.getStrategyHistorySnapshot());
  }

  @Test void unifiedGreedyDecoderMatchesTheSourceDecoderForBuildAndLocalEdit() {
    var problem = fixture(6);
    var budget = new Budget(() -> 0L, 0, Long.MAX_VALUE, Long.MAX_VALUE);
    var api = new ProblemAccess(problem, budget);
    var bridge = new method.rkhma.bridge.ProblemBridge(problem);
    var sourceSatellite = bridge.satellites().getFirst();
    var insertion = method.rkhma.legacy.operator.insertion.InsertionStrategyFactory.getStrategy(
        method.rkhma.legacy.operator.insertion.InsertionMode.GREEDY);
    sourceSatellite.setInsertionStrategy(insertion);
    C unified = new RkhmaGreedyDecoder();
    var view = new SatelliteView(0, Set.of(0, 1, 2), List.of(), "initial");
    var priorities = List.of(0, 1, 2);
    var sourceTasks = bridge.tasks();
    var sourcePlan = new method.rkhma.legacy.model.SchedulingPlan(
        priorities.stream().map(id -> sourceTasks.get(bridge.taskIndex(id))).toList());
    var expected = method.rkhma.legacy.utils.PlanUtils.generateSchedule(sourcePlan, sourceSatellite,
        method.rkhma.legacy.operator.insertion.InsertionMode.GREEDY).stream().map(bridge::fromLegacy).toList();
    var actual = unified.build(problem, api, view, priorities, budget, new java.util.SplittableRandom(1234));
    assertEquals(expected, actual);
    assertFalse(actual.isEmpty());

    var localView = new SatelliteView(0, view.assignedTaskIds(), actual, "local");
    var local = new LocalContext(Phase.REASSIGN, Set.of(), Set.of(), List.of());
    int released = actual.getFirst().getTaskId();
    var edit = new LocalPlan(localView.hash(), Set.of(released), List.of(released));
    var remaining = new ArrayList<method.rkhma.legacy.model.ScheduledTask>();
    for (var observation : expected) if (observation.getTaskId() != released) remaining.add(bridge.toLegacy(observation));
    var inserted = insertion.findBestPosition(sourceTasks.get(bridge.taskIndex(released)), 0, remaining);
    if (inserted != null) remaining.add(inserted.getInsertIndex(), inserted.getTask());
    assertEquals(LocalResult.applied(remaining.stream().map(bridge::fromLegacy).toList()),
        unified.applyLocal(problem, api, localView, local, edit, budget, new java.util.SplittableRandom(1234)));
  }

  @Test void unifiedGreedyRebuildHonorsSourceWindowPreference() {
    var task = new Task(0, 0, 0, 9, 2);
    var satellite = new Satellite(0);
    List<TimeWindow> windows = new ArrayList<>();
    for (int[] interval : List.of(new int[]{5, 9}, new int[]{15, 30})) {
      var window = new TimeWindow(0, 0, 2026, 1, 1, 0, 0, interval[0],
          2026, 1, 1, 0, 0, interval[1]);
      for (int second = interval[0]; second <= interval[1]; second++) {
        window.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, second, 0, 0));
      }
      windows.add(window);
    }
    satellite.setTimeWindows(windows);
    var problem = new ProblemInstance(List.of(task), List.of(satellite));
    var budget = new Budget(() -> 0L, 0, Long.MAX_VALUE, Long.MAX_VALUE);
    var api = new ProblemAccess(problem, budget);
    var bridge = new method.rkhma.bridge.ProblemBridge(problem);
    var sourceSatellite = bridge.satellites().getFirst();
    sourceSatellite.setInsertionStrategy(method.rkhma.legacy.operator.insertion.InsertionStrategyFactory.getStrategy(
        method.rkhma.legacy.operator.insertion.InsertionMode.GREEDY));
    var sourcePlan = new method.rkhma.legacy.model.SchedulingPlan(bridge.tasks());
    var expected = method.rkhma.legacy.utils.PlanUtils.generateSchedule(sourcePlan, sourceSatellite,
        method.rkhma.legacy.operator.insertion.InsertionMode.GREEDY, true, Set.of(0)).stream()
        .map(bridge::fromLegacy).toList();
    var decoder = new RkhmaGreedyDecoder();
    var view = new SatelliteView(0, Set.of(0), List.of(), "empty");
    var plain = decoder.build(problem, api, view, List.of(0), budget, new java.util.SplittableRandom(1));
    var preferred = decoder.rebuild(problem, api, view,
        new LocalContext(Phase.REASSIGN, Set.of(), Set.of(), List.of(), Set.of(0)),
        List.of(0), budget, new java.util.SplittableRandom(1));
    assertEquals(expected, preferred);
    assertSame(windows.get(0), plain.getFirst().timeWindow());
    assertSame(windows.get(1), preferred.getFirst().timeWindow());
  }

  @Test void unifiedHybridSearchMatchesSourceInitialSingleSatelliteSearch() throws Exception {
    var problem = fixture(6);
    var source = RkhmaRuntime.solve(problem, RkhmaRuntime.rkhma(),
        new RkhmaRuntime.Config(1234, 6, 30, 0, 2, .1, 10_000_000_000L, 12_000_000_000L));
    assertEquals(TwoStageSolver.Status.SUCCESS, source.status(), source.detail());
    var bridge = new method.rkhma.bridge.ProblemBridge(problem);
    var pairs = method.rkhma.legacy.utils.Initialize.initializeWithStrategy(bridge.tasks(), bridge.satellites(), 7);
    Map<Integer, Integer> initial = new LinkedHashMap<>();
    for (var pair : pairs) initial.put(bridge.taskId(pair.get(1)), bridge.satelliteId(pair.get(0)));
    problem.tasks().forEach(task -> initial.putIfAbsent(task.getId(), null));
    A fixed = new A() {
      @Override public Assignment initialAssign(ProblemInstance p, ProblemAccess api, Budget budget, RandomGenerator rng) {
        return new Assignment(initial);
      }
      @Override public Move proposeMove(ProblemInstance p, ProblemAccess api, State state, Feedback feedback,
                                        Budget budget, RandomGenerator rng) { return null; }
    };
    var limits = new TwoStageSolver.Limits(10_000_000_000L, 12_000_000_000L, 1_000_000L, 1_000_000L);
    var unified = new TwoStageSolver(limits, () -> 0).solve(problem, fixed,
        new RkhmaHybridSearch(6, 30), new RkhmaGreedyDecoder(), 1234);
    assertEquals(TwoStageSolver.Status.SUCCESS, unified.status(), unified.detail());
    assertEquals(source.profit(), unified.totalProfit());
    assertEquals(observations(source), unified.plan().observations().stream()
        .map(o -> o.getTaskId() + ":" + o.getSatelliteId() + ":" + o.getStartTime() + ":" + o.getEndTime())
        .sorted().toList());
  }

  @Test void unifiedHybridSearchHandlesReassignmentThroughControlledDecoder() {
    var problem = fixture(6);
    A moveOne = new A() {
      boolean offered;
      @Override public Assignment initialAssign(ProblemInstance p, ProblemAccess api, Budget budget, RandomGenerator rng) {
        Map<Integer, Integer> owners = new LinkedHashMap<>();
        for (var task : p.tasks()) owners.put(task.getId(), 0);
        owners.put(3, 1);
        return new Assignment(owners);
      }
      @Override public Move proposeMove(ProblemInstance p, ProblemAccess api, State state, Feedback feedback,
                                        Budget budget, RandomGenerator rng) {
        if (offered) return null;
        offered = true;
        return new Move(state.hash(), Map.of(1, 1));
      }
    };
    var limits = new TwoStageSolver.Limits(10_000_000_000L, 12_000_000_000L, 1_000_000L, 1_000_000L);
    var preferredTrials = new AtomicInteger();
    C decoder = new C() {
      final C delegate = new RkhmaGreedyDecoder();
      @Override public List<ScheduledTask> build(ProblemInstance p, ProblemAccess api, SatelliteView view,
          List<Integer> order, Budget budget, RandomGenerator rng) {
        return delegate.build(p, api, view, order, budget, rng);
      }
      @Override public LocalResult applyLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
          LocalContext local, LocalPlan plan, Budget budget, RandomGenerator rng) {
        return delegate.applyLocal(p, api, view, local, plan, budget, rng);
      }
      @Override public List<ScheduledTask> rebuild(ProblemInstance p, ProblemAccess api, SatelliteView view,
          LocalContext local, List<Integer> order, Budget budget, RandomGenerator rng) {
        if (!local.preferredWindowTasks().isEmpty()) preferredTrials.incrementAndGet();
        return delegate.rebuild(p, api, view, local, order, budget, rng);
      }
    };
    var result = new TwoStageSolver(limits, () -> 0).solve(problem, moveOne,
        new RkhmaHybridSearch(6, 30), decoder, 1234);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    assertEquals(1, result.statistics().acceptedMoves());
    assertEquals(1, result.owners().get(1));
    assertTrue(preferredTrials.get() > 0);
    assertTrue(ScheduleValidator.evaluate(problem, result.plan()).feasible());
  }

  @Test void unifiedRkhmaReassignmentMatchesSourceForForcedInitialOwners() throws Exception {
    var problem = fixture(6);
    var source = forcedSourceRun(problem);
    var allocation = new RkhmaAllocation(3, 2, .1);
    A unifiedForced = new A() {
      @Override public Assignment initialAssign(ProblemInstance p, ProblemAccess api, Budget budget, RandomGenerator rng) {
        allocation.initialAssign(p, api, budget, rng);
        Map<Integer, Integer> owners = new LinkedHashMap<>();
        for (var task : p.tasks()) owners.put(task.getId(), 0);
        return new Assignment(owners);
      }
      @Override public void prepare(ProblemInstance p, ProblemAccess api, Assignment assignment, Budget budget, long seed) {
        allocation.prepare(p, api, assignment, budget, seed);
      }
      @Override public MoveTrial searchReassignment(ProblemInstance p, ProblemAccess api, State state,
          Feedback feedback, MoveEvaluator evaluator, Budget budget, RandomGenerator rng) {
        return allocation.searchReassignment(p, api, state, feedback, evaluator, budget, rng);
      }
    };
    var limits = new TwoStageSolver.Limits(10_000_000_000L, 12_000_000_000L, 1_000_000L, 1_000_000L);
    var unified = new TwoStageSolver(limits, () -> 0).solve(problem, unifiedForced,
        new RkhmaHybridSearch(6, 30), new RkhmaGreedyDecoder(), 1234);
    assertEquals(TwoStageSolver.Status.SUCCESS, unified.status(), unified.detail());
    assertEquals(source.profit(), unified.totalProfit());
    assertEquals(source.owners(), unified.owners());
    assertEquals(source.observations(), unified.plan().observations().stream()
        .map(o -> o.getTaskId() + ":" + o.getSatelliteId() + ":" + o.getStartTime() + ":" + o.getEndTime())
        .sorted().toList());
  }

  @Test void sourceAssignmentSearchReassignmentAndDecodingReassembleAtBothSizes() throws Exception {
    for (int count : List.of(6, 501)) {
      var problem = fixture(count);
      var budget = new Budget(() -> 0L, 0, Long.MAX_VALUE, Long.MAX_VALUE);
      var initial = new RkhmaAllocation().initialAssign(problem, new ProblemAccess(problem, budget),
          budget, new java.util.SplittableRandom(1234)).owners();
      var components = RkhmaRuntime.rkhma();
      var config = count == 6 ? SMALL :
          new RkhmaRuntime.Config(1234, 6, 30, 3, 2, .1, 60_000_000_000L, 65_000_000_000L);
      var result = RkhmaRuntime.solve(problem, components, config);
      assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
      assertEquals("COMPLETED", result.detail());
      SOURCE_OWNERS.forEach((id, owner) -> assertEquals(owner, initial.get(id)));
      SOURCE_OWNERS.forEach((id, owner) -> assertEquals(owner, result.owners().get(id)));
      for (int id = 6; id < count; id++) assertNull(result.owners().get(id));
      assertEquals(32L, result.profit());
      assertEquals(SOURCE_HISTORY, result.history());
      assertEquals(SOURCE_OBSERVATIONS, observations(result));
      assertTrue(ScheduleValidator.evaluate(problem, result.plan()).feasible());
    }
  }

  @Test void allMixedComponentsWorkThroughTheCommonTwoStageInterfaces() {
    var problem = fixture(6);
    var limits = new TwoStageSolver.Limits(10_000_000_000L, 12_000_000_000L, 1_000_000L, 1_000_000L);
    for (int a = 1; a <= 3; a++) for (int b = 1; b <= 3; b++) for (int c = 1; c <= 3; c++) {
      if (a != 3 && b != 3 && c != 3) continue;
      String name = "A" + a + "B" + b + "C" + c;
      var parts = RkhmaRuntime.combination(name);
      A allocation = a == 3 ? new RkhmaAllocation(3, 2, .1) : parts.allocation();
      B search = b == 3 ? new RkhmaHybridSearch(6, 30) : parts.search();
      var result = new TwoStageSolver(limits, () -> 0).solve(problem, allocation, search, parts.decoder(), 1234);
      assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), name + ": " + result.detail());
      var checked = ScheduleValidator.evaluate(problem, result.plan());
      assertTrue(checked.feasible(), name + ": " + checked.violations());
      assertEquals(checked.totalProfit(), result.totalProfit(), name);
    }
  }

  @Test void largeInitialHybridSearchKeepsBothSourceEnsembleRuns() throws Exception {
    var problem = fixture(501);
    var seen = new java.util.concurrent.ConcurrentHashMap<Integer, Set<Integer>>();
    method.rkhma.legacy.main.TaskAllocationCoordinator.setResearchBudgetObserver((sat, ensemble, pop, fes, seed, commit) -> {
      if (commit) seen.computeIfAbsent(sat, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(ensemble);
    });
    try {
      var config = new RkhmaRuntime.Config(1234, 6, 30, 0, 2, .1, 10_000_000_000L, 12_000_000_000L);
      var source = RkhmaRuntime.solve(problem, RkhmaRuntime.rkhma(), config);
      assertEquals(TwoStageSolver.Status.SUCCESS, source.status(), source.detail());
      assertEquals(Set.of(0, 1), seen.get(0));
      assertEquals(Set.of(0, 1), seen.get(1));

      seen.clear();
      var limits = new TwoStageSolver.Limits(10_000_000_000L, 12_000_000_000L, 1_000_000L, 1_000_000L);
      var unified = new TwoStageSolver(limits, () -> 0).solve(problem, new RkhmaAllocation(0, 2, .1),
          new RkhmaHybridSearch(6, 30), new RkhmaGreedyDecoder(), 1234);
      assertEquals(TwoStageSolver.Status.SUCCESS, unified.status(), unified.detail());
      assertEquals(Set.of(0, 1), seen.get(0));
      assertEquals(Set.of(0, 1), seen.get(1));
    } finally {
      method.rkhma.legacy.main.TaskAllocationCoordinator.setResearchBudgetObserver(null);
    }
  }

  @Test void largeReassignmentStillRunsFullCalibrationAfterImprovement() throws Exception {
    var problem = fixture(4000);
    var committedSearchesOnFirstSatellite = new AtomicInteger();
    method.rkhma.legacy.main.TaskAllocationCoordinator.setResearchBudgetObserver((sat, ensemble, pop, fes, seed, commit) -> {
      if (commit && sat == 0 && ensemble == 0) committedSearchesOnFirstSatellite.incrementAndGet();
    });
    try {
      A forced = new A() {
        boolean moved;
        @Override public Assignment initialAssign(ProblemInstance p, ProblemAccess api, Budget budget, RandomGenerator rng) {
          Map<Integer, Integer> owners = new LinkedHashMap<>();
          for (var task : p.tasks()) owners.put(task.getId(), task.getId() < 6 ? 0 : null);
          return new Assignment(owners);
        }
        @Override public Move proposeMove(ProblemInstance p, ProblemAccess api, State state, Feedback feedback,
            Budget budget, RandomGenerator rng) {
          if (moved) return null;
          moved = true;
          return new Move(state.hash(), Map.of(1, 1));
        }
      };
      var config = new RkhmaRuntime.Config(1234, 6, 30, 3, 2, .1, 10_000_000_000L, 12_000_000_000L);
      var result = RkhmaRuntime.solve(problem,
          new RkhmaRuntime.Components(forced, new RkhmaHybridSearch(6, 30), new RkhmaGreedyDecoder()), config);
      assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
      assertEquals(1, result.owners().get(1));
      assertTrue(committedSearchesOnFirstSatellite.get() >= 2, "initial search plus full calibration expected");
    } finally {
      method.rkhma.legacy.main.TaskAllocationCoordinator.setResearchBudgetObserver(null);
    }
  }

  @Test void sourceAndExistingHandModulesCanBeRecombined() throws Exception {
    var problem = fixture(6);
    for (int a = 1; a <= 3; a++) for (int b = 1; b <= 3; b++) for (int c = 1; c <= 3; c++) {
      if (a != 3 && b != 3 && c != 3) continue;
      String name = "A" + a + "B" + b + "C" + c;
      var result = RkhmaRuntime.solve(problem, RkhmaRuntime.combination(name), SMALL);
      assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), name + ": " + result.detail());
      var checked = ScheduleValidator.evaluate(problem, result.plan());
      assertTrue(checked.feasible(), name + ": " + checked.violations());
      assertEquals(checked.totalProfit(), result.profit(), name);
      assertTrue(result.profit() > 0 && result.profit() <= 32, name);
    }
  }

  @Test void sourceDefaultCommitsSatelliteSearchResultsInStableOrder() throws Exception {
    assertProbeOrder(null, "[0, 0, 1, 1]");
    assertProbeOrder("false", "[1, 1, 0, 0]");
  }

  private void assertProbeOrder(String configured, String expected) throws Exception {
    String binary = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
        ? "java.exe" : "java";
    List<String> command = new ArrayList<>(List.of(
        java.nio.file.Path.of(System.getProperty("java.home"), "bin", binary).toString(),
        "-XX:ActiveProcessorCount=1"));
    if (configured != null) command.add("-Dautohyb.deterministic=" + configured);
    command.addAll(List.of("-cp", System.getProperty("java.class.path"), DeterminismProbe.class.getName()));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    if (!process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor();
      fail("determinism probe timed out");
    }
    String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(0, process.exitValue(), output);
    assertTrue(output.contains("trace_satellites=" + expected), output);
  }

  /** 子JVM不继承测试进程的系统属性；强制卫星1先完成，检查最终轨迹提交次序。 */
  public static final class DeterminismProbe {
    public static void main(String[] args) throws Exception {
      System.setProperty("autohyb.quiet", "true");
      var ready = new java.util.concurrent.CountDownLatch(1);
      method.rkhma.legacy.main.TaskAllocationCoordinator.setResearchBudgetObserver((sat, ensemble, pop, fes, seed, commit) -> {
        if (sat == 1 && ensemble == 0) ready.countDown();
        if (sat == 0 && ensemble == 0) {
          try {
            if (!ready.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("second satellite did not start");
            Thread.sleep(200);
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
          }
        }
      });
      try {
        var problem = new RkhmaRuntimeTest().fixture(6);
        var bridge = new method.rkhma.bridge.ProblemBridge(problem);
        method.rkhma.legacy.common.RandomProvider.reset(1234);
        var pairs = method.rkhma.legacy.utils.Initialize.initializeWithStrategy(bridge.tasks(), bridge.satellites(), 7);
        method.rkhma.legacy.utils.AssignTaskOperator.assignTaskToSatellites(pairs, bridge.tasks(), bridge.satellites());
        Map<Integer, Integer> owners = new LinkedHashMap<>();
        for (var pair : pairs) owners.put(pair.get(1), pair.get(0));
        var coordinator = new method.rkhma.legacy.main.TaskAllocationCoordinator(bridge.tasks(), bridge.satellites(),
            new method.rkhma.legacy.operator.assignment.MaxWindowCountStrategy(), 0, 2, .1);
        var config = new method.rkhma.legacy.main.TaskAllocationCoordinator.HybCecConfig(
            new int[]{4, 1}, new int[]{0, 0}, 0, 0, 0, 6, 30,
            method.rkhma.legacy.operator.insertion.InsertionMode.GREEDY, 1234);
        coordinator.scheduleWithReassignment(config, bridge.satellites(), owners,
            new method.rkhma.legacy.common.SearchDeadline(java.time.Duration.ofMillis(4500)));
        System.out.println("trace_satellites=" + coordinator.getConvergenceTracesSnapshot().stream()
            .map(trace -> trace.satelliteId).toList());
      } finally {
        method.rkhma.legacy.main.TaskAllocationCoordinator.setResearchBudgetObserver(null);
      }
    }
  }

  @Test void sourceReassignmentTrajectoryMatchesOriginalFromAForcedInitialAssignment() throws Exception {
    var problem = fixture(6);
    var result = forcedSourceRun(problem);
    assertEquals(Map.of(0, 1, 1, 1, 2, 0, 3, 1, 4, 1, 5, 0), result.owners());
    assertEquals(32L, result.profit());
    assertEquals(List.of("HoldInitialAssignment", "MaxWindowLength", "ReassignRejected"), result.history());
    assertEquals(List.of("1:1:10.0:12.0", "2:0:10.0:12.0", "4:1:25.0:27.0", "5:0:25.0:27.0"), result.observations());
  }

  private static final class TraceB implements B {
    final B base = ManualStrategies.priorityByDensity();
    final AtomicInteger initial = new AtomicInteger(), initLocal = new AtomicInteger(), reassign = new AtomicInteger();
    final AtomicBoolean sawAcceptedTimeline = new AtomicBoolean();
    @Override public List<Integer> initialPriority(ProblemInstance p, ProblemAccess api, SatelliteView view,
                                                   Budget budget, RandomGenerator rng) {
      initial.incrementAndGet();
      return base.initialPriority(p, api, view, budget, rng);
    }
    @Override public LocalPlan proposeInitialLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
                                                    LocalContext context, Feedback feedback, Budget budget, RandomGenerator rng) {
      assertEquals(Phase.INIT, context.phase());
      initLocal.incrementAndGet();
      return base.proposeInitialLocal(p, api, view, context, feedback, budget, rng);
    }
    @Override public LocalPlan proposeReassignmentLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
                                                         LocalContext context, Feedback feedback, Budget budget, RandomGenerator rng) {
      assertEquals(Phase.REASSIGN, context.phase());
      reassign.incrementAndGet();
      if (context.incoming().contains(4) && view.observations().stream().anyMatch(o -> o.getTaskId() == 1)) {
        sawAcceptedTimeline.set(true);
      }
      return base.proposeReassignmentLocal(p, api, view, context, feedback, budget, rng);
    }
  }

  private static final class TraceC implements C {
    final C base = ManualStrategies.earliestFinish();
    final AtomicInteger builds = new AtomicInteger(), rebuilds = new AtomicInteger();
    final AtomicInteger initLocal = new AtomicInteger(), reassign = new AtomicInteger();
    @Override public List<ScheduledTask> build(ProblemInstance p, ProblemAccess api, SatelliteView view,
                                               List<Integer> priorities, Budget budget, RandomGenerator rng) {
      builds.incrementAndGet();
      return base.build(p, api, view, priorities, budget, rng);
    }
    @Override public List<ScheduledTask> rebuild(ProblemInstance p, ProblemAccess api, SatelliteView view,
                                                 LocalContext context, List<Integer> priorities,
                                                 Budget budget, RandomGenerator rng) {
      rebuilds.incrementAndGet();
      return C.super.rebuild(p, api, view, context, priorities, budget, rng);
    }
    @Override public LocalResult applyLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
                                             LocalContext context, LocalPlan plan, Budget budget, RandomGenerator rng) {
      if (context.phase() == Phase.INIT) initLocal.incrementAndGet(); else reassign.incrementAndGet();
      return base.applyLocal(p, api, view, context, plan, budget, rng);
    }
  }

  @Test void handModulesReceiveBothPhasesAndFullRebuildCalls() throws Exception {
    var p = fixture(6);
    var b = new TraceB();
    var c = new TraceC();
    var parts = new RkhmaRuntime.Components(RkhmaRuntime.rkhmaAllocation(), b,
        c);
    var result = RkhmaRuntime.solve(p, parts, SMALL);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    assertTrue(b.initial.get() > 0 && b.initLocal.get() > 0 && b.reassign.get() > 0);
    assertTrue(c.builds.get() > 0 && c.initLocal.get() > 0 && c.reassign.get() > 0);
    var rebuild = new TraceC();
    var sourceWithHandDecoder = new RkhmaRuntime.Components(RkhmaRuntime.rkhmaAllocation(), RkhmaRuntime.rkhmaSearch(),
        rebuild);
    var rebuilt = RkhmaRuntime.solve(p, sourceWithHandDecoder, SMALL);
    assertEquals(TwoStageSolver.Status.SUCCESS, rebuilt.status(), rebuilt.detail());
    assertTrue(rebuild.builds.get() > 0 && rebuild.rebuilds.get() > 0);
  }

  @Test void manualLocalSearchReceivesThePreviouslyAcceptedTimeline() throws Exception {
    var p = fixture(6);
    A fixedMoves = new A() {
      int next;
      @Override public Assignment initialAssign(ProblemInstance problem, ProblemAccess api, Budget budget, RandomGenerator rng) {
        Map<Integer, Integer> owners = new LinkedHashMap<>();
        problem.tasks().forEach(task -> owners.put(task.getId(), 0));
        return new Assignment(owners);
      }
      @Override public Move proposeMove(ProblemInstance problem, ProblemAccess api, State state, Feedback feedback,
                                        Budget budget, RandomGenerator rng) {
        return switch (next++) {
          case 0 -> new Move(state.hash(), Map.of(1, 1));
          case 1 -> new Move(state.hash(), Map.of(4, 1));
          default -> null;
        };
      }
    };
    var b = new TraceB();
    var parts = new RkhmaRuntime.Components(fixedMoves, b,
        RkhmaRuntime.rkhmaDecoder());
    var result = RkhmaRuntime.solve(p, parts, SMALL);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    assertEquals(32L, result.profit());
    assertTrue(b.sawAcceptedTimeline.get(), "second move must see the observation accepted by the first move");
  }

  @Test void nonunitGridPreservesOriginalRecordIdentityAndExactDuration() throws Exception {
    var task = new Task(42, 0, 0, 9, 4);
    var sat = new Satellite(7);
    var window = new TimeWindow(7, 42, 2026, 1, 1, 0, 0, 0, 2026, 1, 1, 0, 0, 30);
    for (double time : new double[]{0, 1, 3, 5, 7, 8}) {
      window.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, time, 0, 0));
    }
    sat.setTimeWindows(List.of(window));
    var problem = new ProblemInstance(List.of(task), List.of(sat));
    var result = RkhmaRuntime.solve(problem, RkhmaRuntime.rkhma(), SMALL);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    assertTrue(result.adaptedGrid());
    assertEquals(9L, result.profit());
    var observation = result.plan().observations().getFirst();
    assertSame(task, observation.task());
    assertSame(window, observation.timeWindow());
    assertSame(window.getAttitudeRecord(1), observation.startRecord());
    assertSame(window.getAttitudeRecord(3), observation.endRecord());
    assertTrue(ScheduleValidator.evaluate(problem, result.plan()).feasible());
  }

  @Test void cutoffKeepsTheSourcePopulationBaselineAsAFeasibleCheckpoint() throws Exception {
    var problem = fixture(6);
    var bridge = new method.rkhma.bridge.ProblemBridge(problem);
    method.rkhma.legacy.common.RandomProvider.reset(1234);
    var pairs = method.rkhma.legacy.utils.Initialize.initializeWithStrategy(bridge.tasks(), bridge.satellites(), 7);
    method.rkhma.legacy.utils.AssignTaskOperator.assignTaskToSatellites(pairs, bridge.tasks(), bridge.satellites());
    Map<Integer, Integer> owners = new LinkedHashMap<>();
    for (var pair : pairs) owners.put(pair.get(1), pair.get(0));
    var config = new method.rkhma.legacy.main.TaskAllocationCoordinator.HybCecConfig(
        new int[]{4, 1}, new int[]{0, 0}, 0, 0, 0, 6, 1_000,
        method.rkhma.legacy.operator.insertion.InsertionMode.GREEDY, 1234);
    var coordinator = new method.rkhma.legacy.main.TaskAllocationCoordinator(bridge.tasks(), bridge.satellites(),
        new method.rkhma.legacy.operator.assignment.MaxWindowCountStrategy(), 3, 2, .1);
    AtomicLong time = new AtomicLong();
    var deadline = new method.rkhma.legacy.common.SearchDeadline(java.time.Duration.ofNanos(50), time::getAndIncrement);
    var plans = coordinator.scheduleWithReassignment(config, bridge.satellites(), owners, deadline);
    List<ScheduledTask> observations = new ArrayList<>();
    for (var plan : plans.values()) for (var observation : plan.getSchedule()) observations.add(bridge.fromLegacy(observation));
    var checked = ScheduleValidator.evaluate(problem, new model.SchedulingPlan(observations));
    assertTrue(time.get() >= 50, "the full population search must be interrupted");
    assertTrue(checked.feasible());
    assertTrue(checked.totalProfit() > 0, "the original first population individual must survive the cutoff");
    assertEquals(owners, coordinator.getBestTaskAssignmentSnapshot());
  }

  @Test void invalidDecoderIsReportedAsFailureAndDeadlineIsNotSuccessWithAnInvalidPlan() throws Exception {
    var p = fixture(6);
    var window = p.satellite(1).getTimeWindows().getFirst();
    var alien = new ScheduledTask(p.task(0), window, window.getAttitudeRecord(1), window.getAttitudeRecord(3));
    C bad = new C() {
      @Override public List<ScheduledTask> build(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                                  List<Integer> priorities, Budget budget, RandomGenerator rng) {
        return List.of(alien);
      }
      @Override public LocalResult applyLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                               LocalContext context, LocalPlan plan, Budget budget, RandomGenerator rng) {
        return LocalResult.unchanged();
      }
    };
    var parts = new RkhmaRuntime.Components(RkhmaRuntime.rkhmaAllocation(), RkhmaRuntime.rkhmaSearch(),
        bad);
    var failed = RkhmaRuntime.solve(p, parts, SMALL);
    assertEquals(TwoStageSolver.Status.FAILURE, failed.status());
    assertNull(failed.plan());
    var expired = RkhmaRuntime.solve(p, RkhmaRuntime.rkhma(),
        new RkhmaRuntime.Config(1, 6, 30, 3, 2, .1, 1, 1_000_000_000L));
    assertEquals(TwoStageSolver.Status.SUCCESS, expired.status());
    assertEquals("SEARCH_EXPIRED", expired.detail());
    assertTrue(ScheduleValidator.evaluate(p, expired.plan()).feasible());
    var hard = RkhmaRuntime.solve(p, RkhmaRuntime.rkhma(), new RkhmaRuntime.Config(1, 6, 30, 3, 2, .1, 1, 1));
    assertEquals(TwoStageSolver.Status.HARD_TIMEOUT, hard.status());
    assertNull(hard.plan());
  }
}
