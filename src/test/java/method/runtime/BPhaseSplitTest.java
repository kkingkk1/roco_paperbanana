package method.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import javax.tools.ToolProvider;
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
import method.runtime.SolverContracts.Phase;
import method.runtime.SolverContracts.SatelliteView;
import method.runtime.SolverContracts.State;
import model.AttitudeRecord;
import model.ProblemInstance;
import model.Satellite;
import model.ScheduledTask;
import model.Task;
import model.TimeWindow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import problem.ScheduleValidator;

import static org.junit.jupiter.api.Assertions.*;

/** 比较RKHMA分阶段入口与未拆分版本，依赖源码另行逐文件检查。 */
class BPhaseSplitTest {
  private static final Path INITIAL = Path.of("cases", "B", "initial_single_satellite_optimization", "BI-030", "src");
  private static final Path REASSIGN = Path.of("cases", "B", "post_reassignment_adjustment", "BR-010", "src");
  private static Class<?> initialType;
  private static Class<?> reassignmentType;

  @BeforeAll static void compileEntries() throws Exception {
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler);
    Path output = Path.of("target", "test-classes").toAbsolutePath();
    Files.createDirectories(output);
    assertEquals(0, compiler.run(null, null, null, "-encoding", "UTF-8", "--release", "21",
        "-classpath", Path.of("target", "classes").toAbsolutePath().toString(), "-d", output.toString(),
        INITIAL.resolve("method/runtime/B000040.java").toString(),
        REASSIGN.resolve("method/runtime/B000043.java").toString()));
    initialType = Class.forName("method.runtime.B000040");
    reassignmentType = Class.forName("method.runtime.B000043");
  }

  private static B instance(Class<?> type) throws Exception {
    return (B) type.getConstructor(int.class, int.class).newInstance(6, 30);
  }

  private static ProblemInstance fixture() {
    List<Task> tasks = new ArrayList<>();
    List<Satellite> satellites = List.of(new Satellite(0), new Satellite(1));
    List<List<TimeWindow>> windows = List.of(new ArrayList<>(), new ArrayList<>());
    for (int id = 0; id < 4; id++) {
      tasks.add(new Task(id, 0, 0, 5 + id, 2));
      for (int sat = 0; sat < 2; sat++) {
        int start = 8 + id * 8;
        TimeWindow window = new TimeWindow(sat, id, 2026, 1, 1, 0, 0, start - 1,
            2026, 1, 1, 0, 0, start + 3);
        for (int second = start - 1; second <= start + 3; second++) {
          window.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, second, 0, 0));
        }
        windows.get(sat).add(window);
      }
    }
    for (int sat = 0; sat < 2; sat++) satellites.get(sat).setTimeWindows(windows.get(sat));
    return new ProblemInstance(tasks, satellites);
  }

  private static long profit(ProblemInstance problem, List<ScheduledTask> observations) {
    return observations.stream().mapToLong(item -> problem.task(item.getTaskId()).getPriority()).sum();
  }

  private static List<String> observations(List<ScheduledTask> observations) {
    return observations.stream().map(item -> item.getTaskId() + ":" + item.getSatelliteId() + ":"
        + item.getStartTime() + ":" + item.getEndTime()).sorted().toList();
  }

  private static InitialTrial runInitial(B search, ProblemInstance problem) {
    Budget budget = new Budget(() -> 0L, 0, Long.MAX_VALUE, Long.MAX_VALUE);
    ProblemAccess api = new ProblemAccess(problem, budget);
    Assignment assignment = new Assignment(Map.of(0, 0, 1, 0, 2, 0, 3, 1));
    SatelliteView view = new SatelliteView(0, Set.of(0, 1, 2), List.of(), "initial");
    RkhmaGreedyDecoder decoder = new RkhmaGreedyDecoder();
    InitialEvaluator evaluator = order -> {
      var built = decoder.build(problem, api, view, order, budget, new SplittableRandom(5));
      return new InitialTrial(order, built, profit(problem, built));
    };
    search.prepare(problem, api, assignment, budget, 1234);
    return search.searchInitial(problem, api, view, evaluator, budget, new SplittableRandom(5));
  }

  private static LocalTrial runReassignment(B search, ProblemInstance problem) {
    Budget budget = new Budget(() -> 0L, 0, Long.MAX_VALUE, Long.MAX_VALUE);
    ProblemAccess api = new ProblemAccess(problem, budget);
    Assignment assignment = new Assignment(Map.of(0, 0, 1, 0, 2, 1, 3, 1));
    SatelliteView view = new SatelliteView(0, Set.of(0, 1, 2), List.of(), "affected");
    LocalContext context = new LocalContext(Phase.REASSIGN, Set.of(2), Set.of(), List.of());
    LocalEvaluator evaluator = localEvaluator(problem, api, view, context, budget);
    search.prepare(problem, api, assignment, budget, 1234);
    return search.searchReassignment(problem, api, view, context, new Feedback(0, "START"),
        evaluator, budget, new SplittableRandom(5));
  }

  private static LocalEvaluator localEvaluator(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      LocalContext context, Budget budget) {
    RkhmaGreedyDecoder decoder = new RkhmaGreedyDecoder();
    return new LocalEvaluator() {
      @Override public LocalTrial evaluate(LocalPlan plan) { throw new AssertionError("unexpected local edit"); }
      @Override public LocalTrial rebuild(List<Integer> order) { return rebuild(order, Set.of()); }
      @Override public LocalTrial rebuild(List<Integer> order, Set<Integer> preferred) {
        LocalContext requested = new LocalContext(context.phase(), context.incoming(), context.outgoing(),
            context.brokenEdges(), preferred);
        var built = decoder.rebuild(problem, api, view, requested, order, budget, new SplittableRandom(5));
        return new LocalTrial(new LocalPlan(view.hash(), Set.of(), order), LocalResult.applied(built),
            profit(problem, built));
      }
    };
  }

  private record SharedResult(LocalTrial trial, boolean usedFastRefinement) { }

  private static void bind(B search, RkhmaAllocation allocation) throws Exception {
    search.getClass().getDeclaredMethod("bindUnified", RkhmaAllocation.class).invoke(search, allocation);
  }

  private static SharedResult runWithSharedInitialState(boolean split, ProblemInstance problem) throws Exception {
    Budget budget = new Budget(() -> 0L, 0, Long.MAX_VALUE, Long.MAX_VALUE);
    ProblemAccess api = new ProblemAccess(problem, budget);
    Assignment assignment = new Assignment(Map.of(0, 0, 1, 0, 2, 1, 3, 1));
    RkhmaAllocation allocation = new RkhmaAllocation();
    allocation.initialAssign(problem, api, budget, new SplittableRandom(1));
    allocation.prepare(problem, api, assignment, budget, 1234);
    B initial = split ? instance(initialType) : new RkhmaHybridSearch(6, 30);
    bind(initial, allocation);
    initial.prepare(problem, api, assignment, budget, 1234);
    SatelliteView before = new SatelliteView(0, Set.of(0, 1), List.of(), "before");
    RkhmaGreedyDecoder decoder = new RkhmaGreedyDecoder();
    InitialEvaluator initialEvaluator = order -> {
      var built = decoder.build(problem, api, before, order, budget, new SplittableRandom(5));
      return new InitialTrial(order, built, profit(problem, built));
    };
    InitialTrial initialTrial = initial.searchInitial(problem, api, before, initialEvaluator,
        budget, new SplittableRandom(5));
    assertFalse(initialTrial.observations().isEmpty());
    B post = split ? instance(reassignmentType) : initial;
    if (split) {
      bind(post, allocation);
      post.prepare(problem, api, assignment, budget, 1234);
    }
    AtomicBoolean usedFast = new AtomicBoolean();
    allocation.unifiedCoordinator(problem).setLocalRefinement((context, original) -> {
      var result = original.get();
      if (result != null) usedFast.set(true);
      return result;
    });
    SatelliteView after = new SatelliteView(0, Set.of(0, 1, 2), initialTrial.observations(), "after");
    LocalContext context = new LocalContext(Phase.REASSIGN, Set.of(2), Set.of(), List.of());
    LocalTrial trial = post.searchReassignment(problem, api, after, context, new Feedback(0, "START"),
        localEvaluator(problem, api, after, context, budget), budget, new SplittableRandom(5));
    return new SharedResult(trial, usedFast.get());
  }

  @Test void initialAndReassignmentEntriesMatchTheOriginalOnTheSameInputs() throws Exception {
    ProblemInstance problem = fixture();
    InitialTrial originalInitial = runInitial(new RkhmaHybridSearch(6, 30), problem);
    InitialTrial splitInitial = runInitial(instance(initialType), problem);
    assertFalse(originalInitial.observations().isEmpty());
    assertTrue(originalInitial.totalProfit() > 0);
    assertEquals(originalInitial.priorities(), splitInitial.priorities());
    assertEquals(originalInitial.totalProfit(), splitInitial.totalProfit());
    assertEquals(observations(originalInitial.observations()), observations(splitInitial.observations()));

    LocalTrial originalLocal = runReassignment(new RkhmaHybridSearch(6, 30), problem);
    LocalTrial splitLocal = runReassignment(instance(reassignmentType), problem);
    assertNotNull(originalLocal);
    assertNotNull(splitLocal);
    assertFalse(originalLocal.result().observations().isEmpty());
    assertEquals(originalLocal.plan(), splitLocal.plan());
    assertEquals(originalLocal.totalProfit(), splitLocal.totalProfit());
    assertEquals(observations(originalLocal.result().observations()), observations(splitLocal.result().observations()));
  }

  @Test void eachEntryDefinesOnlyItsOwnSearch() throws Exception {
    assertTrue(java.util.Arrays.stream(initialType.getDeclaredMethods()).anyMatch(m -> m.getName().equals("searchInitial")));
    assertFalse(java.util.Arrays.stream(initialType.getDeclaredMethods()).anyMatch(m -> m.getName().equals("searchReassignment")));
    assertTrue(java.util.Arrays.stream(reassignmentType.getDeclaredMethods()).anyMatch(m -> m.getName().equals("searchReassignment")));
    assertFalse(java.util.Arrays.stream(reassignmentType.getDeclaredMethods()).anyMatch(m -> m.getName().equals("searchInitial")));
    assertFalse(java.util.Arrays.stream(reassignmentType.getDeclaredMethods()).anyMatch(m -> m.getName().equals("searchCalibration")));
    assertTrue(java.util.Arrays.stream(reassignmentType.getMethods())
        .anyMatch(m -> m.getName().equals("searchCalibration") && m.getDeclaringClass() == B.class));
  }

  @Test void reassignmentRetainsFastRefinementWithExplicitlySharedState() throws Exception {
    ProblemInstance problem = fixture();
    SharedResult original = runWithSharedInitialState(false, problem);
    SharedResult split = runWithSharedInitialState(true, problem);
    assertTrue(original.usedFastRefinement());
    assertTrue(split.usedFastRefinement());
    assertNotNull(original.trial());
    assertNotNull(split.trial());
    assertEquals(original.trial().plan(), split.trial().plan());
    assertEquals(original.trial().totalProfit(), split.trial().totalProfit());
    assertEquals(observations(original.trial().result().observations()),
        observations(split.trial().result().observations()));
  }

  @Test void reassignmentCaseWorksAfterAnIndependentInitialStrategy() throws Exception {
    List<Task> tasks = List.of(new Task(0, 0, 0, 9, 5), new Task(1, 0, 0, 5, 5));
    List<Satellite> satellites = List.of(new Satellite(0), new Satellite(1));
    for (int sat = 0; sat < satellites.size(); sat++) {
      List<TimeWindow> windows = new ArrayList<>();
      for (Task task : tasks) {
        TimeWindow window = new TimeWindow(sat, task.getId(), 2026, 1, 1, 0, 0, 9,
            2026, 1, 1, 0, 0, 16);
        for (int second : new int[]{9, 10, 15, 16}) {
          window.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, second, 0, 0));
        }
        windows.add(window);
      }
      satellites.get(sat).setTimeWindows(windows);
    }
    ProblemInstance problem = new ProblemInstance(tasks, satellites);
    A allocation = new A() {
      private boolean proposed;
      @Override public Assignment initialAssign(ProblemInstance p, ProblemAccess api, Budget budget,
          RandomGenerator rng) {
        return new Assignment(Map.of(0, 0, 1, 0));
      }
      @Override public Move proposeMove(ProblemInstance p, ProblemAccess api, State state,
          Feedback feedback, Budget budget, RandomGenerator rng) {
        if (proposed) return null;
        proposed = true;
        return new Move(state.hash(), Map.of(1, 1));
      }
    };
    B initial = ManualStrategies.priorityByDensity();
    B post = instance(reassignmentType);
    AtomicBoolean reassignmentStarted = new AtomicBoolean();
    AtomicInteger initialCalls = new AtomicInteger();
    AtomicInteger localCalls = new AtomicInteger();
    B combined = new B() {
      @Override public void prepare(ProblemInstance p, ProblemAccess api, Assignment assignment,
          Budget budget, long seed) {
        post.prepare(p, api, assignment, budget, seed);
      }
      @Override public InitialTrial searchInitial(ProblemInstance p, ProblemAccess api, SatelliteView view,
          InitialEvaluator evaluator, Budget budget, RandomGenerator rng) {
        assertFalse(reassignmentStarted.get());
        initialCalls.incrementAndGet();
        return initial.searchInitial(p, api, view, evaluator, budget, rng);
      }
      @Override public LocalTrial searchReassignment(ProblemInstance p, ProblemAccess api, SatelliteView view,
          LocalContext context, Feedback feedback, LocalEvaluator evaluator, Budget budget, RandomGenerator rng) {
        assertEquals(Phase.REASSIGN, context.phase());
        reassignmentStarted.set(true);
        localCalls.incrementAndGet();
        return post.searchReassignment(p, api, view, context, feedback, evaluator, budget, rng);
      }
      @Override public void onTrialBase(ProblemInstance p, ProblemAccess api, State state, Budget budget) {
        post.onTrialBase(p, api, state, budget);
      }
      @Override public void onAccepted(ProblemInstance p, ProblemAccess api, State state, Budget budget) {
        post.onAccepted(p, api, state, budget);
      }
      @Override public boolean usesInstanceBudgetForReassignment() {
        return post.usesInstanceBudgetForReassignment();
      }
    };
    C decoder = ManualStrategies.earliestFinish();
    AtomicInteger rebuilds = new AtomicInteger();
    C tracked = new C() {
      @Override public List<ScheduledTask> build(ProblemInstance p, ProblemAccess api, SatelliteView view,
          List<Integer> order, Budget budget, RandomGenerator rng) {
        assertFalse(reassignmentStarted.get());
        return decoder.build(p, api, view, order, budget, rng);
      }
      @Override public LocalResult applyLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
          LocalContext context, LocalPlan plan, Budget budget, RandomGenerator rng) {
        return decoder.applyLocal(p, api, view, context, plan, budget, rng);
      }
      @Override public List<ScheduledTask> rebuild(ProblemInstance p, ProblemAccess api, SatelliteView view,
          LocalContext context, List<Integer> order, Budget budget, RandomGenerator rng) {
        assertEquals(Phase.REASSIGN, context.phase());
        rebuilds.incrementAndGet();
        return decoder.rebuild(p, api, view, context, order, budget, rng);
      }
    };
    var result = new TwoStageSolver(new TwoStageSolver.Limits(1_000, 2_000, 0, 200), () -> 0)
        .solve(problem, allocation, combined, tracked, 7);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    assertEquals(2, initialCalls.get());
    assertTrue(localCalls.get() > 0);
    assertTrue(rebuilds.get() > 0);
    assertEquals(14, result.totalProfit());
    assertTrue(ScheduleValidator.evaluate(problem, result.plan()).feasible());
  }

  @Test void sharedDependenciesMatchTheOriginalArchiveAndProjectSources() throws Exception {
    Path reference = Path.of("cases", "backend", "legacy_B_cases", "B-003", "src");
    int checked = 0;
    try (var files = Files.walk(reference)) {
      for (Path source : files.filter(Files::isRegularFile).toList()) {
        Path relative = reference.relativize(source);
        if (relative.toString().replace('\\', '/').equals("method/runtime/RkhmaHybridSearch.java")) continue;
        assertEquals(-1, Files.mismatch(source, INITIAL.resolve(relative)), relative.toString());
        assertEquals(-1, Files.mismatch(source, REASSIGN.resolve(relative)), relative.toString());
        assertEquals(-1, Files.mismatch(source, Path.of("src/main/java").resolve(relative)), relative.toString());
        checked++;
      }
    }
    assertEquals(139, checked);
  }
}
