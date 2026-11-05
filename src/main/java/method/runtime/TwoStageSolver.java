package method.runtime;

import method.runtime.SolverContracts.A;
import method.runtime.SolverContracts.Assignment;
import method.runtime.SolverContracts.B;
import method.runtime.SolverContracts.BrokenEdge;
import method.runtime.SolverContracts.C;
import method.runtime.SolverContracts.Feedback;
import method.runtime.SolverContracts.InitialEvaluator;
import method.runtime.SolverContracts.InitialTrial;
import method.runtime.SolverContracts.LocalContext;
import method.runtime.SolverContracts.LocalEvaluator;
import method.runtime.SolverContracts.LocalPlan;
import method.runtime.SolverContracts.LocalResult;
import method.runtime.SolverContracts.LocalStatus;
import method.runtime.SolverContracts.LocalTrial;
import method.runtime.SolverContracts.Move;
import method.runtime.SolverContracts.MoveEvaluator;
import method.runtime.SolverContracts.MoveTrial;
import method.runtime.SolverContracts.Phase;
import method.runtime.SolverContracts.SatelliteView;
import method.runtime.SolverContracts.State;
import model.ProblemInstance;
import model.ScheduledTask;
import model.SchedulingPlan;
import model.Task;
import model.TimeWindow;
import problem.ScheduleValidator;
import utils.TimeUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/** 固定两阶段运行时；只在完整检查通过且收益严格提高时提交移动。 */
public final class TwoStageSolver {
  private static final Comparator<ScheduledTask> ORDER = Comparator.comparingDouble(ScheduledTask::getStartTime)
      .thenComparingDouble(ScheduledTask::getEndTime).thenComparingInt(ScheduledTask::getTaskId);

  public record Limits(long searchNanos, long hardNanos, long initialLocalNanos,
                       long perSatelliteNanos) {
    public Limits {
      if (searchNanos <= 0 || hardNanos < searchNanos || initialLocalNanos < 0
          || initialLocalNanos > searchNanos || perSatelliteNanos <= 0) {
        throw new IllegalArgumentException("invalid solver limits");
      }
    }
    public static Limits defaults() {
      return new Limits(4_500_000_000L, 5_000_000_000L, 1_800_000_000L, 20_000_000L);
    }
  }

  public enum Status { SUCCESS, HARD_TIMEOUT, FAILURE }

  public record Statistics(int initialAssignments, int initialPriorities, int initialBuilds,
                           int initialLocalCalls, int reassignMoves, int reassignLocalCalls,
                           int acceptedMoves, int rejectedMoves, int noFeasibleChanges) { }

  public record Result(Status status, SchedulingPlan plan, Long totalProfit,
                       Map<Integer, Integer> owners, Statistics statistics, String detail,
                       long elapsedNanos) {
    public Result {
      owners = Collections.unmodifiableMap(new LinkedHashMap<>(owners));
    }
  }

  private static final class Counters {
    int initialAssignments, initialPriorities, initialBuilds, initialLocalCalls;
    int reassignMoves, reassignLocalCalls, acceptedMoves, rejectedMoves, noFeasibleChanges;
    Statistics snapshot() {
      return new Statistics(initialAssignments, initialPriorities, initialBuilds, initialLocalCalls,
          reassignMoves, reassignLocalCalls, acceptedMoves, rejectedMoves, noFeasibleChanges);
    }
  }

  private static final class InvalidOutput extends RuntimeException {
    InvalidOutput(String message) { super(message); }
  }

  private final Limits limits;
  private final LongSupplier clock;

  public TwoStageSolver() {
    this(Limits.defaults(), System::nanoTime);
  }

  /** 可注入单调时钟，用于验证截止边界；默认配置用于实际实例。 */
  public TwoStageSolver(Limits limits, LongSupplier clock) {
    this.limits = Objects.requireNonNull(limits);
    this.clock = Objects.requireNonNull(clock);
  }

  public Result solve(ProblemInstance problem, A a, B b, C c, long seed) {
    Objects.requireNonNull(problem);
    Objects.requireNonNull(a);
    Objects.requireNonNull(b);
    Objects.requireNonNull(c);
    long started = clock.getAsLong();
    Budget search = new Budget(clock, started, limits.hardNanos(), limits.searchNanos());
    Counters counters = new Counters();
    Map<Integer, Integer> emptyOwners = new LinkedHashMap<>();
    problem.tasks().stream().map(Task::getId).sorted().forEach(id -> emptyOwners.put(id, null));
    Map<Integer, List<ScheduledTask>> emptyTimelines = new LinkedHashMap<>();
    problem.satellites().stream().map(s -> s.getId()).sorted().forEach(id -> emptyTimelines.put(id, List.of()));
    State accepted = state(new Assignment(emptyOwners), emptyTimelines, null);
    State[] checkpoint = {null};
    ProblemAccess api = null;
    try {
      long streamSeed = seed ^ instanceKey(problem, search);
      api = new ProblemAccess(problem, search);
      search.check();
      counters.initialAssignments++;
      Assignment initial = a.initialAssign(problem, api, search, random(streamSeed, Phase.INIT, 1, 0, -1, 0));
      search.check();
      validateAssignment(problem, api, initial, search);
      accepted = state(initial, emptyTimelines, api);
      a.prepare(problem, api, initial, search, seed);
      if (a instanceof RkhmaAllocation allocation && b instanceof RkhmaHybridSearch hybrid) {
        hybrid.bindUnified(allocation);
      }
      b.prepare(problem, api, initial, search, seed);
      search.check();

      for (int sat : api.satelliteIds()) {
        search.check();
        SatelliteView view = view(accepted, sat, api);
        counters.initialPriorities++;
        InitialTrial selected = searchInitial(problem, api, accepted.assignment(), view, b, c,
            search, streamSeed, counters);
        accepted = replaceTimeline(accepted, sat, selected.observations(), api);
      }

      int satelliteCount = api.satelliteIds().size();
      long perInitial = satelliteCount == 0 ? 0 : limits.initialLocalNanos() / satelliteCount;
      for (int sat : api.satelliteIds()) {
        if (perInitial == 0) break;
        search.check();
        Budget local = search.within(perInitial);
        int attempt = 0;
        String previous = "START";
        try {
          while (true) {
            local.check();
            SatelliteView view = view(accepted, sat, api);
            LocalContext context = new LocalContext(Phase.INIT, Set.of(), Set.of(), List.of());
            counters.initialLocalCalls++;
            LocalTrial selected = evaluateLocalSession(problem, api, accepted.assignment(), view, context,
                new Feedback(attempt, previous), b, c, local, streamSeed, 0, attempt + 1, false);
            if (selected == null) break;
            LocalResult result = selected.result();
            if (result.status() == LocalStatus.NO_FEASIBLE_CHANGE) {
              counters.noFeasibleChanges++;
              previous = "NO_FEASIBLE_CHANGE";
            } else if (profit(result.observations()) > profit(view.observations())) {
              accepted = replaceTimeline(accepted, sat, result.observations(), api);
              previous = "ACCEPTED";
            } else {
              previous = "NO_IMPROVEMENT";
            }
            attempt++;
          }
        } catch (Budget.SearchExpired expired) {
          search.check();
        }
      }

      int attempt = 0;
      String previous = "START";
      while (true) {
        search.check();
        checkpoint[0] = accepted;
        MoveTrial selected = searchReassignment(problem, api, accepted, new Feedback(attempt, previous),
            a, b, c, search, streamSeed, counters, checkpoint);
        search.check();
        if (selected == null) break;
        State candidate = selected.state();
        if (candidate != null) {
          if (selected.totalProfit() > profit(accepted) || a.acceptsNonImprovingResult()) {
            accepted = candidate;
            b.onAccepted(problem, api, accepted, search);
            search.check();
            counters.acceptedMoves++;
            previous = "ACCEPTED";
            attempt = 0;
          } else {
            counters.rejectedMoves++;
            previous = "NO_IMPROVEMENT";
            attempt++;
          }
        } else {
          counters.rejectedMoves++;
          previous = "NO_FEASIBLE_CHANGE";
          attempt++;
        }
      }
      return finish(problem, accepted, counters, search, "COMPLETED");
    } catch (Budget.SearchExpired expired) {
      try {
        return finish(problem, checkpoint[0] == null ? accepted : checkpoint[0], counters, search, "SEARCH_EXPIRED");
      } catch (Budget.HardTimeout hard) {
        return failed(Status.HARD_TIMEOUT, accepted, counters, search, "hard deadline exceeded");
      }
    } catch (Budget.HardTimeout hard) {
      return failed(Status.HARD_TIMEOUT, accepted, counters, search, "hard deadline exceeded");
    } catch (RuntimeException failure) {
      try {
        search.checkHard();
      } catch (Budget.HardTimeout hard) {
        return failed(Status.HARD_TIMEOUT, accepted, counters, search, "hard deadline exceeded");
      }
      return failed(Status.FAILURE, accepted, counters, search,
          failure.getClass().getSimpleName() + ": " + failure.getMessage());
    }
  }

  private MoveTrial searchReassignment(ProblemInstance problem, ProblemAccess api, State root,
      Feedback feedback, A a, B b, C c, Budget budget, long seed, Counters counters, State[] checkpoint) {
    try (var guard = new TrialGuard(budget)) {
      Set<State> issued = Collections.newSetFromMap(new IdentityHashMap<>());
      issued.add(root);
      MoveEvaluator evaluator = new MoveEvaluator() {
        @Override public MoveTrial evaluate(State base, Move move) {
          return guard.evaluate(() -> {
            if (!issued.contains(base)) throw new InvalidOutput("A used a state not issued by this evaluator");
            if (move == null) throw new InvalidOutput("null move");
            int moveNumber = counters.reassignMoves++;
            b.onTrialBase(problem, api, base, budget);
            State candidate = tryMove(problem, api, base, move, b, c, budget, seed, moveNumber, counters);
            if (candidate == null) return new MoveTrial(move, null, null);
            ScheduleValidator.Result checked = validatePlan(problem, candidate);
            budget.check();
            if (!checked.feasible()) return new MoveTrial(move, null, null);
            issued.add(candidate);
            return new MoveTrial(move, candidate, checked.totalProfit());
          });
        }
        @Override public void checkpoint(MoveTrial trial) {
          guard.evaluate(() -> {
            guard.select(trial, true);
            if (trial.state() == null) throw new InvalidOutput("cannot checkpoint an infeasible trial");
            if (trial.totalProfit() > profit(checkpoint[0])) checkpoint[0] = trial.state();
            return trial;
          });
        }
      };
      MoveTrial selected = a.searchReassignment(problem, api, root, feedback, evaluator, budget,
          random(seed, Phase.REASSIGN, 1, counters.reassignMoves, -1, feedback.attempt()));
      return guard.select(selected, false);
    }
  }

  private InitialTrial searchInitial(ProblemInstance problem, ProblemAccess api, Assignment assignment,
                                     SatelliteView view, B b, C c, Budget budget, long seed, Counters counters) {
    try (var guard = new TrialGuard(budget)) {
      int[] calls = {0};
      InitialEvaluator evaluator = priorities -> guard.evaluate(() -> {
        List<Integer> order = priorities == null ? null : List.copyOf(priorities);
        validatePriorities(view, order);
        counters.initialBuilds++;
        List<ScheduledTask> built = c.build(problem, api, view, order, budget,
            trialRandom(seed, Phase.INIT, 0, view.satelliteId(), 0, calls[0]++));
        validateTimeline(problem, assignment, view.satelliteId(), built);
        Set<Integer> allowed = new HashSet<>(order);
        if (built.stream().anyMatch(o -> !allowed.contains(o.getTaskId()))) {
          throw new InvalidOutput("C.build added a task outside priorities");
        }
        return new InitialTrial(order, built, profit(built));
      });
      return guard.select(b.searchInitial(problem, api, view, evaluator, budget,
          random(seed, Phase.INIT, 2, 0, view.satelliteId(), 0)), true);
    }
  }

  private LocalTrial evaluateLocalSession(ProblemInstance problem, ProblemAccess api, Assignment assignment,
                                 SatelliteView view, LocalContext context, Feedback feedback, B b, C c,
                                 Budget budget, long seed, int move, int call, boolean calibration) {
    try (var guard = new TrialGuard(budget)) {
      int[] calls = {0};
      LocalEvaluator evaluator = new LocalEvaluator() {
        @Override public LocalTrial evaluate(LocalPlan plan) {
          return guard.evaluate(() -> {
            validateLocalPlan(view, plan);
            LocalResult result = c.applyLocal(problem, api, view, context, plan, budget,
                trialRandom(seed, context.phase(), move, view.satelliteId(), call, calls[0]++));
            inspectLocalResult(problem, assignment, view, plan, result);
            return new LocalTrial(plan, result,
                result.status() == LocalStatus.APPLIED ? profit(result.observations()) : null);
          });
        }

        @Override public LocalTrial rebuild(List<Integer> priorities) {
          return rebuild(priorities, Set.of());
        }

        @Override public LocalTrial rebuild(List<Integer> priorities, Set<Integer> preferredWindowTasks) {
          return guard.evaluate(() -> {
            List<Integer> order = priorities == null ? null : List.copyOf(priorities);
            validatePriorities(view, order);
            Set<Integer> preferred = Set.copyOf(preferredWindowTasks);
            if (!view.assignedTaskIds().containsAll(preferred)) throw new InvalidOutput("invalid window preference tasks");
            Set<Integer> release = new HashSet<>();
            view.observations().forEach(o -> release.add(o.getTaskId()));
            LocalPlan plan = new LocalPlan(view.hash(), release, order);
            validateLocalPlan(view, plan);
            SatelliteView empty = emptyView(view);
            LocalContext rebuild = new LocalContext(context.phase(), Set.of(), Set.of(), List.of(), preferred);
            RandomGenerator rng = trialRandom(seed, context.phase(), move, view.satelliteId(), call, calls[0]++);
            List<ScheduledTask> built = context.phase() == Phase.INIT
                ? c.build(problem, api, empty, order, budget, rng)
                : c.rebuild(problem, api, empty, rebuild, order, budget, rng);
            validateTimeline(problem, assignment, view.satelliteId(), built);
            Set<Integer> allowed = new HashSet<>(order);
            if (built.stream().anyMatch(o -> !allowed.contains(o.getTaskId()))) {
              throw new InvalidOutput("C rebuild added a task outside priorities");
            }
            LocalResult result = LocalResult.applied(built);
            inspectLocalResult(problem, assignment, view, plan, result);
            return new LocalTrial(plan, result, profit(built));
          });
        }
      };
      RandomGenerator rng = random(seed, context.phase(), 2, move, view.satelliteId(), call);
      LocalTrial selected = context.phase() == Phase.INIT
          ? b.searchInitialLocal(problem, api, view, context, feedback, evaluator, budget, rng)
          : calibration ? b.searchCalibration(problem, api, view, context, feedback, evaluator, budget, rng)
          : b.searchReassignment(problem, api, view, context, feedback, evaluator, budget, rng);
      return guard.select(selected, false);
    }
  }

  private static SatelliteView emptyView(SatelliteView view) {
    StringBuilder value = new StringBuilder().append('S').append(view.satelliteId()).append(':');
    view.assignedTaskIds().stream().sorted().forEach(id -> value.append('T').append(id).append(';'));
    return new SatelliteView(view.satelliteId(), view.assignedTaskIds(), List.of(), digest(value));
  }

  private static RandomGenerator trialRandom(long seed, Phase phase, int move, int satellite, int call, int trial) {
    return random(seed ^ (0xd1342543de82ef95L * trial), phase, 3, move, satellite, call);
  }

  private State tryMove(ProblemInstance problem, ProblemAccess api, State base, Move move, B b, C c,
                        Budget search, long seed, int moveNumber, Counters counters) {
    if (!base.hash().equals(move.baseStateHash())) throw new InvalidOutput("stale move");
    if (move.edits().isEmpty() && move.repairSatellites().isEmpty()) throw new InvalidOutput("empty move");
    if (move.fullRecalibration()
        && (!move.edits().isEmpty() || !move.repairSatellites().equals(base.timelines().keySet()))) {
      throw new InvalidOutput("invalid full recalibration");
    }
    if (move.fullRecalibration()) b.beginCalibration(problem, api, base, search);
    Map<Integer, Integer> owners = new LinkedHashMap<>(base.assignment().owners());
    Set<Integer> affected = new LinkedHashSet<>();
    Map<Integer, Set<Integer>> incoming = new HashMap<>();
    Map<Integer, Set<Integer>> outgoing = new HashMap<>();
    for (int satellite : move.repairSatellites()) {
      search.check();
      if (problem.satellite(satellite) == null) throw new InvalidOutput("unknown repair satellite");
      affected.add(satellite);
    }
    for (var edit : move.edits().entrySet()) {
      search.check();
      int task = edit.getKey();
      if (!owners.containsKey(task)) throw new InvalidOutput("move has unknown task");
      Integer from = owners.get(task);
      Integer to = edit.getValue();
      if (Objects.equals(from, to)) throw new InvalidOutput("move has unchanged owner");
      if (to != null && (problem.satellite(to) == null || !api.hasCandidate(task, to, search))) {
        throw new InvalidOutput("move owner has no legal candidate");
      }
      if (from != null) {
        affected.add(from);
        outgoing.computeIfAbsent(from, ignored -> new HashSet<>()).add(task);
      }
      if (to != null) {
        affected.add(to);
        incoming.computeIfAbsent(to, ignored -> new HashSet<>()).add(task);
      }
      owners.put(task, to);
    }
    Assignment assignment = new Assignment(owners);
    Map<Integer, List<ScheduledTask>> timelines = new LinkedHashMap<>(base.timelines());
    for (int sat : affected) {
      Set<Integer> leaving = outgoing.getOrDefault(sat, Set.of());
      timelines.put(sat, timelines.get(sat).stream().filter(o -> !leaving.contains(o.getTaskId())).toList());
    }
    State trial = state(assignment, timelines, api);
    List<Integer> satellites = affected.stream().sorted().toList();
    for (int sat : satellites) {
      search.check();
      Budget local = b.usesInstanceBudgetForReassignment()
          ? search : search.within(limits.perSatelliteNanos());
      int attempt = 0;
      String previous = "START";
      try {
        while (true) {
          local.check();
          SatelliteView view = view(trial, sat, api);
          LocalContext context = new LocalContext(Phase.REASSIGN, incoming.getOrDefault(sat, Set.of()),
              outgoing.getOrDefault(sat, Set.of()), brokenEdges(view.observations()));
          counters.reassignLocalCalls++;
          LocalTrial selected = evaluateLocalSession(problem, api, assignment, view, context,
              new Feedback(attempt, previous), b, c, local, seed, moveNumber, attempt, move.fullRecalibration());
          if (selected == null) break;
          LocalResult result = selected.result();
          if (result.status() == LocalStatus.NO_FEASIBLE_CHANGE) {
            counters.noFeasibleChanges++;
            previous = "NO_FEASIBLE_CHANGE";
          } else {
            previous = "APPLIED";
            trial = replaceTimeline(trial, sat, result.observations(), api);
          }
          attempt++;
        }
      } catch (Budget.SearchExpired expired) {
        search.check();
        return null;
      }
    }
    return trial;
  }

  private void validateAssignment(ProblemInstance problem, ProblemAccess api, Assignment assignment, Budget budget) {
    if (assignment == null) throw new InvalidOutput("null assignment");
    Set<Integer> expected = new HashSet<>();
    for (Task task : problem.tasks()) expected.add(task.getId());
    if (!assignment.owners().keySet().equals(expected)) throw new InvalidOutput("assignment task set differs");
    for (var entry : assignment.owners().entrySet()) {
      budget.check();
      Integer sat = entry.getValue();
      if (sat != null && (problem.satellite(sat) == null || !api.hasCandidate(entry.getKey(), sat, budget))) {
        throw new InvalidOutput("assigned satellite has no candidate");
      }
    }
  }

  private static void validatePriorities(SatelliteView view, List<Integer> priorities) {
    if (priorities == null || priorities.stream().anyMatch(Objects::isNull)
        || new HashSet<>(priorities).size() != priorities.size()
        || !view.assignedTaskIds().containsAll(priorities)) {
      throw new InvalidOutput("invalid initial priorities");
    }
  }

  private static void validateLocalPlan(SatelliteView view, LocalPlan plan) {
    if (plan == null || !view.hash().equals(plan.baseViewHash())) throw new InvalidOutput("stale local plan");
    Set<Integer> existing = new HashSet<>();
    for (ScheduledTask observation : view.observations()) existing.add(observation.getTaskId());
    if (!existing.containsAll(plan.release()) || !view.assignedTaskIds().containsAll(plan.reconsider())
        || new HashSet<>(plan.reconsider()).size() != plan.reconsider().size()) {
      throw new InvalidOutput("invalid release or reconsider set");
    }
    for (int task : plan.reconsider()) {
      if (existing.contains(task) && !plan.release().contains(task)) {
        throw new InvalidOutput("scheduled task reconsidered without release");
      }
    }
  }

  private static String inspectLocalResult(ProblemInstance problem, Assignment assignment, SatelliteView view,
                                           LocalPlan plan, LocalResult result) {
    if (result == null) throw new InvalidOutput("null local result");
    if (result.status() == LocalStatus.NO_FEASIBLE_CHANGE) {
      if (!result.observations().isEmpty()) throw new InvalidOutput("no-change result contains observations");
      return "NO_FEASIBLE_CHANGE";
    }
    validateTimeline(problem, assignment, view.satelliteId(), result.observations());
    Map<Integer, ScheduledTask> before = byTask(view.observations());
    Map<Integer, ScheduledTask> after = byTask(result.observations());
    for (var entry : before.entrySet()) {
      ScheduledTask next = after.get(entry.getKey());
      if (next == null && !plan.release().contains(entry.getKey())) {
        throw new InvalidOutput("C removed an unauthorized observation");
      }
      if (next != null && !sameObservation(entry.getValue(), next)
          && (!plan.release().contains(entry.getKey()) || !plan.reconsider().contains(entry.getKey()))) {
        throw new InvalidOutput("C changed an unauthorized observation");
      }
    }
    for (int task : after.keySet()) {
      if (!before.containsKey(task) && !plan.reconsider().contains(task)) {
        throw new InvalidOutput("C added an unauthorized observation");
      }
    }
    return "APPLIED";
  }

  private static void validateTimeline(ProblemInstance problem, Assignment assignment, int sat,
                                       List<ScheduledTask> observations) {
    if (observations == null || observations.stream().anyMatch(Objects::isNull)) {
      throw new InvalidOutput("null timeline");
    }
    for (ScheduledTask observation : observations) {
      if (observation.getSatelliteId() != sat
          || !Objects.equals(assignment.owners().get(observation.getTaskId()), sat)) {
        throw new InvalidOutput("timeline violates owner or satellite");
      }
    }
    ScheduleValidator.Result result = ScheduleValidator.evaluate(problem, new SchedulingPlan(observations));
    if (!result.feasible()) throw new InvalidOutput("infeasible timeline: " + result.violations());
  }

  private static List<BrokenEdge> brokenEdges(List<ScheduledTask> observations) {
    List<ScheduledTask> sorted = observations.stream().sorted(ORDER).toList();
    List<BrokenEdge> result = new ArrayList<>();
    for (int i = 1; i < sorted.size(); i++) {
      ScheduledTask before = sorted.get(i - 1);
      ScheduledTask after = sorted.get(i);
      if (after.getStartTime() < before.getEndTime()
          || !TimeUtils.checkTransformationTime(before.endRecord(), after.startRecord())) {
        result.add(new BrokenEdge(before.getTaskId(), after.getTaskId()));
      }
    }
    return result;
  }

  private static boolean sameObservation(ScheduledTask a, ScheduledTask b) {
    return a.task() == b.task() && a.timeWindow() == b.timeWindow()
        && a.startRecord() == b.startRecord() && a.endRecord() == b.endRecord();
  }

  private static Map<Integer, ScheduledTask> byTask(List<ScheduledTask> observations) {
    Map<Integer, ScheduledTask> map = new HashMap<>();
    for (ScheduledTask observation : observations) map.put(observation.getTaskId(), observation);
    return map;
  }

  private static State replaceTimeline(State state, int sat, List<ScheduledTask> observations, ProblemAccess api) {
    Map<Integer, List<ScheduledTask>> copy = new LinkedHashMap<>(state.timelines());
    copy.put(sat, observations.stream().sorted(ORDER).toList());
    return state(state.assignment(), copy, api);
  }

  static State state(Assignment assignment, Map<Integer, List<ScheduledTask>> timelines, ProblemAccess api) {
    StringBuilder value = new StringBuilder();
    assignment.owners().keySet().stream().sorted().forEach(id ->
        value.append('T').append(id).append(':').append(assignment.owners().get(id)).append(';'));
    timelines.keySet().stream().sorted().forEach(sat -> {
      value.append('S').append(sat).append(':');
      for (ScheduledTask observation : timelines.get(sat).stream().sorted(ORDER).toList()) {
        appendObservation(value, observation, api);
      }
    });
    return new State(assignment, timelines, digest(value));
  }

  private static SatelliteView view(State state, int sat, ProblemAccess api) {
    Set<Integer> assigned = new HashSet<>();
    state.assignment().owners().forEach((task, owner) -> { if (Objects.equals(owner, sat)) assigned.add(task); });
    List<ScheduledTask> observations = state.timelines().get(sat);
    StringBuilder value = new StringBuilder().append('S').append(sat).append(':');
    assigned.stream().sorted().forEach(id -> value.append('T').append(id).append(';'));
    observations.stream().sorted(ORDER).forEach(observation -> appendObservation(value, observation, api));
    return new SatelliteView(sat, assigned, observations, digest(value));
  }

  private static void appendObservation(StringBuilder value, ScheduledTask observation, ProblemAccess api) {
    value.append('O').append(observation.getTaskId()).append(':').append(api.windowIndex(observation.timeWindow()))
        .append(':').append(Double.doubleToLongBits(observation.getStartTime()))
        .append(':').append(Double.doubleToLongBits(observation.getEndTime())).append(';');
  }

  private static String digest(CharSequence value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static long profit(List<ScheduledTask> observations) {
    return observations.stream().mapToLong(o -> o.task().getPriority()).sum();
  }

  private static long profit(State state) {
    return state.timelines().values().stream().mapToLong(TwoStageSolver::profit).sum();
  }

  private static ScheduleValidator.Result validatePlan(ProblemInstance problem, State state) {
    List<ScheduledTask> observations = state.timelines().values().stream().flatMap(List::stream).toList();
    return ScheduleValidator.evaluate(problem, new SchedulingPlan(observations));
  }

  private Result finish(ProblemInstance problem, State state, Counters counters, Budget budget, String detail) {
    budget.checkHard();
    ScheduleValidator.Result result = validatePlan(problem, state);
    budget.checkHard();
    if (!result.feasible()) return failed(Status.FAILURE, state, counters, budget, "final validation failed");
    List<ScheduledTask> observations = state.timelines().values().stream().flatMap(List::stream).toList();
    budget.checkHard();
    return new Result(Status.SUCCESS, new SchedulingPlan(observations), result.totalProfit(),
        state.assignment().owners(), counters.snapshot(), detail, budget.elapsedNanos());
  }

  private static Result failed(Status status, State state, Counters counters, Budget budget, String detail) {
    return new Result(status, null, null, state.assignment().owners(), counters.snapshot(), detail, budget.elapsedNanos());
  }

  private static RandomGenerator random(long seed, Phase phase, int module, int move, int satellite, int call) {
    long mixed = seed ^ ((long) phase.ordinal() << 61) ^ ((long) module << 56);
    mixed ^= 0x9e3779b97f4a7c15L * (move + 1L);
    mixed ^= 0xbf58476d1ce4e5b9L * (satellite + 2L);
    mixed ^= 0x94d049bb133111ebL * (call + 1L);
    return new SplittableRandom(mixed);
  }

  /** 随机流由完整输入内容区分实例，不包含策略代码哈希。 */
  private static long instanceKey(ProblemInstance problem, Budget budget) {
    long hash = 0xcbf29ce484222325L;
    for (Task task : problem.tasks().stream().sorted(Comparator.comparingInt(Task::getId)).toList()) {
      budget.check();
      hash = mix(hash, task.getId());
      hash = mix(hash, Double.doubleToLongBits(task.getLongitude()));
      hash = mix(hash, Double.doubleToLongBits(task.getLatitude()));
      hash = mix(hash, task.getPriority());
      hash = mix(hash, task.getImagingDuration());
    }
    for (var satellite : problem.satellites().stream().sorted(Comparator.comparingInt(s -> s.getId())).toList()) {
      budget.check();
      hash = mix(hash, satellite.getId());
      hash = mix(hash, satellite.getTimeWindows().size());
      for (TimeWindow window : satellite.getTimeWindows()) {
        budget.check();
        hash = mix(hash, window.getTaskId());
        hash = mix(hash, Double.doubleToLongBits(window.getStartTime()));
        hash = mix(hash, Double.doubleToLongBits(window.getEndTime()));
        hash = mix(hash, window.getAttitudeRecordCount());
        for (var record : window.getAttitudeRecords()) {
          budget.check();
          hash = mix(hash, Double.doubleToLongBits(record.getTotalSeconds()));
          hash = mix(hash, Double.doubleToLongBits(record.getRollAngle()));
          hash = mix(hash, Double.doubleToLongBits(record.getPitchAngle()));
        }
      }
    }
    return hash;
  }

  private static long mix(long hash, long value) {
    return (hash ^ value) * 0x100000001b3L;
  }
}
