package method.runtime;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import javax.tools.ToolProvider;
import method.runtime.SolverContracts.B;
import method.runtime.SolverContracts.BrokenEdge;
import method.runtime.SolverContracts.Feedback;
import method.runtime.SolverContracts.InitialEvaluator;
import method.runtime.SolverContracts.InitialTrial;
import method.runtime.SolverContracts.LocalContext;
import method.runtime.SolverContracts.LocalEvaluator;
import method.runtime.SolverContracts.LocalPlan;
import method.runtime.SolverContracts.LocalResult;
import method.runtime.SolverContracts.LocalTrial;
import method.runtime.SolverContracts.Phase;
import method.runtime.SolverContracts.SatelliteView;
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

class BCaseArchiveTest {
  private static ClassLoader archive;
  private final Budget budget = new Budget(() -> 0L, 0, Long.MAX_VALUE, Long.MAX_VALUE);

  @BeforeAll static void compileArchivedCases() throws Exception {
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "a Java 21 JDK is required");
    Path destination = Path.of("target", "b-case-archive-test").toAbsolutePath();
    Files.createDirectories(destination);
    List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "--release", "21",
        "-classpath", Path.of("target", "classes").toAbsolutePath().toString(), "-d", destination.toString()));
    for (int id = 1; id <= 37; id++) {
      if (id == 3) continue;
      String caseId = String.format("B-%03d", id);
      Path root = id <= 2 ? Path.of("cases", "backend", "legacy_B_cases", caseId)
          : Path.of("cases", "B", id <= 30 ? "initial_single_satellite_optimization"
              : "post_reassignment_adjustment", caseId);
      args.add(root.resolve(String.format("B%06d.java", id)).toString());
    }
    for (int id : List.of(38, 39, 41, 42)) {
      String group = id <= 39 ? "initial_single_satellite_optimization" : "post_reassignment_adjustment";
      args.add(Path.of("cases", "B", group, String.format("B-%03d", id),
          String.format("B%06d.java", id)).toString());
    }
    assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)));
    archive = new URLClassLoader(new URL[]{destination.toUri().toURL()}, BCaseArchiveTest.class.getClassLoader());
  }

  private static ProblemInstance fixture() {
    List<Task> tasks = List.of(new Task(0, 0, 0, 9, 2), new Task(1, 0, 0, 7, 2),
        new Task(2, 0, 0, 4, 2), new Task(3, 0, 0, 3, 2), new Task(4, 0, 0, 1, 2));
    Satellite satellite = new Satellite(0);
    List<TimeWindow> windows = new ArrayList<>();
    for (int id = 0; id < tasks.size(); id++) {
      int start = 5 + id * 8;
      TimeWindow window = new TimeWindow(0, id, 2026, 1, 1, 0, 0, start,
          2026, 1, 1, 0, 0, start + 5 + id);
      for (int second = start; second <= start + 5 + id; second++) {
        window.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, second, 0, 0));
      }
      windows.add(window);
    }
    satellite.setTimeWindows(windows);
    return new ProblemInstance(tasks, List.of(satellite));
  }

  private static Method initializer(int id) throws Exception {
    Class<?> type = archive.loadClass("method.cases.examples.B" + String.format("%06d", id));
    return type.getMethod("search", ProblemInstance.class, ProblemAccess.class, SatelliteView.class,
        InitialEvaluator.class, Budget.class, RandomGenerator.class, int.class);
  }

  private static Method localSearch(int id) throws Exception {
    Class<?> type = archive.loadClass("method.cases.examples.B" + String.format("%06d", id));
    return type.getMethod("search", ProblemInstance.class, ProblemAccess.class, SatelliteView.class,
        LocalContext.class, LocalEvaluator.class, Budget.class, RandomGenerator.class, int.class);
  }

  @Test void archivedManualCasesMatchOriginalStrategiesAcrossBothPhases() throws Exception {
    ProblemInstance problem = fixture();
    ProblemAccess api = new ProblemAccess(problem, budget);
    List<TimeWindow> windows = problem.satellite(0).getTimeWindows();
    List<ScheduledTask> observations = List.of(
        new ScheduledTask(problem.task(0), windows.get(0), windows.get(0).getAttitudeRecord(1),
            windows.get(0).getAttitudeRecord(3)),
        new ScheduledTask(problem.task(1), windows.get(1), windows.get(1).getAttitudeRecord(1),
            windows.get(1).getAttitudeRecord(3)));
    for (int id = 1; id <= 2; id++) {
      B original = id == 1 ? ManualStrategies.priorityByDensity() : ManualStrategies.priorityByScarcity();
      Class<?> type = archive.loadClass("method.runtime.B" + String.format("%06d", id));
      B saved = (B) type.getMethod("create").invoke(null);
      B initialPart = (B) archive.loadClass("method.runtime.B" + String.format("%06d", id + 37))
          .getMethod("create").invoke(null);
      B reassignmentPart = (B) archive.loadClass("method.runtime.B" + String.format("%06d", id + 40))
          .getMethod("create").invoke(null);
      for (List<ScheduledTask> timeline : List.of(List.<ScheduledTask>of(), observations)) {
        SatelliteView view = new SatelliteView(0, Set.of(0, 1, 2, 3, 4), timeline, "view");
        var priority = original.initialPriority(problem, api, view, budget, new SplittableRandom(1));
        assertEquals(priority, saved.initialPriority(problem, api, view, budget, new SplittableRandom(1)));
        assertEquals(priority, initialPart.initialPriority(problem, api, view, budget, new SplittableRandom(1)));
        LocalContext init = new LocalContext(Phase.INIT, Set.of(), Set.of(), List.of());
        LocalContext reassign = new LocalContext(Phase.REASSIGN, Set.of(2), Set.of(),
            timeline.isEmpty() ? List.of() : List.of(new BrokenEdge(0, 1)));
        for (int attempt = 0; attempt < 30; attempt++) {
          Feedback feedback = new Feedback(attempt, "START");
          assertEquals(original.proposeInitialLocal(problem, api, view, init, feedback, budget, new SplittableRandom(1)),
              saved.proposeInitialLocal(problem, api, view, init, feedback, budget, new SplittableRandom(1)));
          assertEquals(original.proposeInitialLocal(problem, api, view, init, feedback, budget, new SplittableRandom(1)),
              initialPart.proposeInitialLocal(problem, api, view, init, feedback, budget, new SplittableRandom(1)));
          assertEquals(original.proposeReassignmentLocal(problem, api, view, reassign, feedback, budget, new SplittableRandom(1)),
              saved.proposeReassignmentLocal(problem, api, view, reassign, feedback, budget, new SplittableRandom(1)));
          assertEquals(original.proposeReassignmentLocal(problem, api, view, reassign, feedback, budget, new SplittableRandom(1)),
              reassignmentPart.proposeReassignmentLocal(problem, api, view, reassign, feedback, budget, new SplittableRandom(1)));
        }
        assertThrows(UnsupportedOperationException.class, () -> initialPart.proposeReassignmentLocal(
            problem, api, view, reassign, new Feedback(0, "START"), budget, new SplittableRandom(1)));
        assertThrows(UnsupportedOperationException.class, () -> reassignmentPart.initialPriority(
            problem, api, view, budget, new SplittableRandom(1)));
      }
    }
  }

  @Test void initializersReturnIssuedPermutationsWithinTheirEvaluationLimits() throws Exception {
    ProblemInstance problem = fixture();
    ProblemAccess api = new ProblemAccess(problem, budget);
    for (Set<Integer> ids : List.of(Set.<Integer>of(), Set.of(2), Set.of(0, 1, 2, 3, 4))) {
      SatelliteView view = new SatelliteView(0, ids, List.of(), "view");
      for (int id = 4; id <= 30; id++) {
        for (int limit : List.of(1, 2, 12, 45)) {
          Set<InitialTrial> issued = Collections.newSetFromMap(new IdentityHashMap<>());
          InitialEvaluator evaluator = order -> {
            assertEquals(ids.size(), order.size());
            assertEquals(ids, Set.copyOf(order));
            long score = 0;
            for (int i = 0; i < order.size(); i++) score += (long) order.get(i) * (order.size() - i);
            InitialTrial trial = new InitialTrial(order, List.of(), score);
            issued.add(trial);
            return trial;
          };
          InitialTrial result = (InitialTrial) initializer(id).invoke(null, problem, api, view,
              evaluator, budget, new SplittableRandom(4), limit);
          assertTrue(issued.contains(result), "case " + id + " must return an issued trial");
          assertTrue(issued.size() <= limit, "case " + id + " exceeded " + limit);
        }
      }
    }
  }

  @Test void enumerationFindsBestOrderAfterAllPermutations() throws Exception {
    ProblemInstance problem = fixture();
    ProblemAccess api = new ProblemAccess(problem, budget);
    SatelliteView view = new SatelliteView(0, Set.of(0, 1, 2, 3, 4), List.of(), "view");
    Set<List<Integer>> seen = new java.util.HashSet<>();
    InitialEvaluator evaluator = order -> {
      assertTrue(seen.add(List.copyOf(order)), "duplicate permutation");
      long score = 0;
      for (int i = 0; i < order.size(); i++) score += (long) order.get(i) * (order.size() - i);
      return new InitialTrial(order, List.of(), score);
    };
    InitialTrial best = (InitialTrial) initializer(16).invoke(null, problem, api, view,
        evaluator, budget, new SplittableRandom(4), 120);
    assertEquals(120, seen.size());
    assertEquals(List.of(4, 3, 2, 1, 0), best.priorities());
    assertEquals(40, best.totalProfit());
  }

  @Test void firstAndBestImprovementSwapsMakeDifferentDecisions() throws Exception {
    ProblemInstance problem = fixture();
    ProblemAccess api = new ProblemAccess(problem, budget);
    SatelliteView view = new SatelliteView(0, Set.of(0, 1, 2), List.of(), "view");
    InitialEvaluator evaluator = order -> {
      long score = order.equals(List.of(1, 0, 2)) ? 1
          : order.equals(List.of(2, 1, 0)) ? 9 : order.equals(List.of(0, 2, 1)) ? 2 : 0;
      return new InitialTrial(order, List.of(), score);
    };
    InitialTrial first = (InitialTrial) initializer(6).invoke(null, problem, api, view,
        evaluator, budget, new SplittableRandom(1), 4);
    InitialTrial best = (InitialTrial) initializer(7).invoke(null, problem, api, view,
        evaluator, budget, new SplittableRandom(1), 4);
    assertEquals(List.of(1, 0, 2), first.priorities());
    assertEquals(List.of(2, 1, 0), best.priorities());
  }

  @Test void localCasesRespectEditPermissionsAndReturnIssuedTrials() throws Exception {
    ProblemInstance problem = fixture();
    ProblemAccess api = new ProblemAccess(problem, budget);
    List<TimeWindow> windows = problem.satellite(0).getTimeWindows();
    List<ScheduledTask> observations = List.of(
        new ScheduledTask(problem.task(0), windows.get(0), windows.get(0).getAttitudeRecord(1),
            windows.get(0).getAttitudeRecord(3)),
        new ScheduledTask(problem.task(1), windows.get(1), windows.get(1).getAttitudeRecord(1),
            windows.get(1).getAttitudeRecord(3)));
    SatelliteView view = new SatelliteView(0, Set.of(0, 1, 2, 3, 4), observations, "local-view");
    LocalContext context = new LocalContext(Phase.REASSIGN, Set.of(2), Set.of(), List.of(new BrokenEdge(0, 1)));
    for (int id = 31; id <= 37; id++) {
      for (int limit : List.of(1, 2, 20)) {
        Set<LocalTrial> issued = Collections.newSetFromMap(new IdentityHashMap<>());
        LocalEvaluator evaluator = new LocalEvaluator() {
          @Override public LocalTrial evaluate(LocalPlan plan) {
            assertEquals(view.hash(), plan.baseViewHash());
            assertTrue(Set.of(0, 1).containsAll(plan.release()));
            assertTrue(view.assignedTaskIds().containsAll(plan.reconsider()));
            assertEquals(plan.reconsider().size(), Set.copyOf(plan.reconsider()).size());
            for (int task : plan.reconsider()) {
              assertTrue(!Set.of(0, 1).contains(task) || plan.release().contains(task));
            }
            LocalTrial trial = new LocalTrial(plan, LocalResult.applied(observations),
                (long) plan.reconsider().stream().mapToInt(problemId -> problem.task(problemId).getPriority()).sum());
            issued.add(trial);
            return trial;
          }
          @Override public LocalTrial rebuild(List<Integer> order) {
            assertEquals(view.assignedTaskIds(), Set.copyOf(order));
            assertEquals(order.size(), Set.copyOf(order).size());
            LocalTrial trial = new LocalTrial(new LocalPlan(view.hash(), Set.of(0, 1), order),
                LocalResult.applied(observations), (long) order.getFirst());
            issued.add(trial);
            return trial;
          }
        };
        LocalTrial result = (LocalTrial) localSearch(id).invoke(null, problem, api, view, context,
            evaluator, budget, new SplittableRandom(4), limit);
        assertTrue(result == null || issued.contains(result), "case " + id + " must return an issued trial");
        assertTrue(issued.size() <= limit, "case " + id + " exceeded " + limit);
      }
    }
  }

  @Test void adaptedCycleRepairUsesReassignmentChanges() throws Exception {
    ProblemInstance problem = fixture();
    ProblemAccess api = new ProblemAccess(problem, budget);
    List<TimeWindow> windows = problem.satellite(0).getTimeWindows();
    List<ScheduledTask> observations = new ArrayList<>();
    for (int id = 0; id < 4; id++) {
      TimeWindow window = windows.get(id);
      observations.add(new ScheduledTask(problem.task(id), window, window.getAttitudeRecord(1),
          window.getAttitudeRecord(3)));
    }
    SatelliteView view = new SatelliteView(0, Set.of(0, 1, 2, 3, 4), observations, "reassigned");
    LocalContext changed = new LocalContext(Phase.REASSIGN, Set.of(4), Set.of(), List.of(new BrokenEdge(0, 1)));
    List<LocalTrial> issued = new ArrayList<>();
    LocalEvaluator evaluator = new LocalEvaluator() {
      @Override public LocalTrial evaluate(LocalPlan plan) {
        assertTrue(plan.release().contains(0) || plan.release().contains(1));
        assertEquals(4, plan.reconsider().getFirst());
        LocalTrial trial = new LocalTrial(plan, LocalResult.applied(observations), (long) issued.size());
        issued.add(trial);
        return trial;
      }
      @Override public LocalTrial rebuild(List<Integer> order) { throw new AssertionError("unexpected rebuild"); }
    };
    LocalTrial result = (LocalTrial) localSearch(33).invoke(null, problem, api, view, changed,
        evaluator, budget, new SplittableRandom(1), 4);
    assertEquals(4, issued.size());
    assertSame(issued.getLast(), result);

    LocalContext unchanged = new LocalContext(Phase.REASSIGN, Set.of(), Set.of(), List.of());
    assertNull(localSearch(33).invoke(null, problem, api, view, unchanged,
        evaluator, budget, new SplittableRandom(1), 4));
    var error = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> localSearch(33)
        .invoke(null, problem, api, view, new LocalContext(Phase.INIT, Set.of(), Set.of(), List.of()),
            evaluator, budget, new SplittableRandom(1), 4));
    assertInstanceOf(IllegalArgumentException.class, error.getCause());
  }

  @Test void archivedSearchesWorkWithTheProjectDecoderAndRuntime() throws Exception {
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
    for (int id = 4; id <= 37; id++) {
      final int caseId = id;
      final Method method = id <= 30 ? initializer(id) : localSearch(id);
      SolverContracts.A allocation = new SolverContracts.A() {
        private boolean proposed;
        @Override public SolverContracts.Assignment initialAssign(ProblemInstance p, ProblemAccess api,
            Budget budget, RandomGenerator rng) {
          return new SolverContracts.Assignment(java.util.Map.of(0, 0, 1, 0));
        }
        @Override public SolverContracts.Move proposeMove(ProblemInstance p, ProblemAccess api,
            SolverContracts.State state, Feedback feedback, Budget budget, RandomGenerator rng) {
          if (proposed) return null;
          proposed = true;
          return new SolverContracts.Move(state.hash(), java.util.Map.of(1, 1));
        }
      };
      B search = new B() {
        @Override public InitialTrial searchInitial(ProblemInstance p, ProblemAccess api, SatelliteView view,
            InitialEvaluator evaluator, Budget budget, RandomGenerator rng) {
          if (caseId >= 31) return evaluator.evaluate(view.assignedTaskIds().stream().sorted().toList());
          try { return (InitialTrial) method.invoke(null, p, api, view, evaluator, budget, rng, 32); }
          catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        @Override public LocalTrial searchInitialLocal(ProblemInstance p, ProblemAccess api, SatelliteView view,
            LocalContext context, Feedback feedback, LocalEvaluator evaluator, Budget budget, RandomGenerator rng) {
          return null;
        }
        @Override public LocalTrial searchReassignment(ProblemInstance p, ProblemAccess api, SatelliteView view,
            LocalContext context, Feedback feedback, LocalEvaluator evaluator, Budget budget, RandomGenerator rng) {
          if (caseId <= 30 || feedback.attempt() > 0) return null;
          try { return (LocalTrial) method.invoke(null, p, api, view, context, evaluator, budget, rng, 20); }
          catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
      };
      var result = new TwoStageSolver(new TwoStageSolver.Limits(1_000, 2_000, 400, 200), () -> 0)
          .solve(problem, allocation, search, ManualStrategies.earliestFinish(), 7);
      assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), "case " + id + ": " + result.detail());
      assertTrue(ScheduleValidator.evaluate(problem, result.plan()).feasible(), "case " + id);
      if (List.of(31, 34, 36, 37).contains(id)) assertEquals(14, result.totalProfit(), "case " + id);
    }
  }
}
