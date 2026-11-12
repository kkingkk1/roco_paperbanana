package method.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;
import method.rkhma.bridge.ProblemBridge;
import method.rkhma.legacy.common.RandomProvider;
import method.rkhma.legacy.common.SearchDeadline;
import method.rkhma.legacy.main.TaskAllocationCoordinator;
import method.rkhma.legacy.operator.assignment.MaxWindowCountStrategy;
import method.rkhma.legacy.operator.insertion.InsertionMode;
import method.rkhma.legacy.utils.Initialize;
import method.runtime.SolverContracts.A;
import method.runtime.SolverContracts.Assignment;
import method.runtime.SolverContracts.Feedback;
import method.runtime.SolverContracts.Move;
import method.runtime.SolverContracts.MoveEvaluator;
import method.runtime.SolverContracts.MoveTrial;
import method.runtime.SolverContracts.State;
import model.ProblemInstance;
import model.ScheduledTask;

/** RKHMA来源的复合初始归属和跨星重分配策略。 */
public final class RkhmaAllocation implements A {
  private final int maxIterations;
  private final int perturbThreshold;
  private final double perturbStrength;
  private ProblemInstance problem;
  private ProblemBridge bridge;
  private TaskAllocationCoordinator coordinator;
  private TaskAllocationCoordinator.HybCecConfig config;
  private boolean finished;
  private boolean sourceBound;

  public RkhmaAllocation() { this(25, 1, .02); }
  public RkhmaAllocation(int maxIterations, int perturbThreshold, double perturbStrength) {
    if (maxIterations < 0 || perturbThreshold <= 0 || !Double.isFinite(perturbStrength) || perturbStrength < 0) {
      throw new IllegalArgumentException("invalid reassignment settings");
    }
    this.maxIterations = maxIterations;
    this.perturbThreshold = perturbThreshold;
    this.perturbStrength = perturbStrength;
  }

  @Override public synchronized Assignment initialAssign(ProblemInstance problem, ProblemAccess api,
      Budget budget, RandomGenerator rng) {
    Objects.requireNonNull(problem);
    if (!sourceBound || this.problem != problem) bridge = new ProblemBridge(problem, budget::check);
    this.problem = problem;
    sourceBound = false;
    var pairs = Initialize.initializeWithStrategy(bridge.tasks(), bridge.satellites(), 7);
    Map<Integer, Integer> owners = new LinkedHashMap<>();
    for (var pair : pairs) owners.put(bridge.taskId(pair.get(1)), bridge.satelliteId(pair.get(0)));
    problem.tasks().forEach(task -> owners.putIfAbsent(task.getId(), null));
    return new Assignment(owners);
  }

  void bindSource(ProblemBridge source) {
    bridge = Objects.requireNonNull(source);
    problem = source.problem();
    sourceBound = true;
  }

  @Override public synchronized void prepare(ProblemInstance problem, ProblemAccess api, Assignment assignment,
                                              Budget budget, long seed) {
    requirePrepared(problem);
    RandomProvider.reset(seed);
    coordinator = new TaskAllocationCoordinator(bridge.tasks(), bridge.satellites(),
        new MaxWindowCountStrategy(), maxIterations, perturbThreshold, perturbStrength);
    config = new TaskAllocationCoordinator.HybCecConfig(new int[]{4, 1}, new int[]{0, 0}, 0, 0, 0,
        6, 30, InsertionMode.GREEDY, seed);
    var deadline = SearchDeadline.startingAt(Duration.ofNanos(Long.MAX_VALUE), () -> {
      budget.check();
      return budget.elapsedNanos();
    }, 0);
    coordinator.prepareUnifiedSingleSearch(config, legacyOwners(assignment.owners()), deadline);
    finished = false;
  }

  ProblemBridge unifiedBridge(ProblemInstance problem) {
    requirePrepared(problem);
    return bridge;
  }

  TaskAllocationCoordinator unifiedCoordinator(ProblemInstance problem) {
    requirePrepared(problem);
    if (coordinator == null) throw new IllegalStateException("RKHMA allocation not prepared");
    return coordinator;
  }

  @Override public boolean acceptsNonImprovingResult() { return true; }

  /** 供来源协调器使用同一重分配策略内核。 */
  void configureSource(TaskAllocationCoordinator source) { source.useRkhmaReassignment(); }

  @Override public synchronized MoveTrial searchReassignment(ProblemInstance problem, ProblemAccess api,
      State root, Feedback feedback, MoveEvaluator evaluator, Budget budget, RandomGenerator rng) {
    requirePrepared(problem);
    if (coordinator == null) throw new IllegalStateException("RKHMA allocation not prepared");
    if (finished || problem.satellites().size() <= 1) return null;
    finished = true;
    Map<String, State> states = new LinkedHashMap<>();
    Map<String, MoveTrial> trials = new HashMap<>();
    states.put(root.hash(), root);
    Map<Integer, Integer> owners = legacyOwners(root.assignment().owners());
    Map<Integer, method.rkhma.legacy.model.SchedulingPlan> plans = legacyPlans(root);
    try {
      var result = coordinator.runUnifiedReassignment(owners, plans, config,
            (before, after, affected, basePlans) -> {
              State reconstructed = fromLegacy(before, basePlans, api);
              State base = states.get(reconstructed.hash());
              if (base == null) throw new IllegalStateException("source candidate base was not issued");
              Map<Integer, Integer> edits = new LinkedHashMap<>();
              for (var task : problem.tasks()) {
                int index = bridge.taskIndex(task.getId());
                Integer oldOwner = before.get(index), newOwner = after.get(index);
                if (!Objects.equals(oldOwner, newOwner)) {
                  Integer target = newOwner == null ? null : bridge.satelliteId(newOwner);
                  // 来源有些窗口没有问题层允许的起点；跳过该内部候选，不能作为非法A输出提交。
                  if (target != null && !api.hasCandidate(task.getId(), target, budget)) {
                    return emptyAffectedPlans(after, affected);
                  }
                  edits.put(task.getId(), target);
                }
              }
              Set<Integer> repair = affected.stream().map(bridge::satelliteId).collect(java.util.stream.Collectors.toSet());
              MoveTrial trial = evaluator.evaluate(base, new Move(base.hash(), edits, repair));
              Map<Integer, method.rkhma.legacy.model.SchedulingPlan> converted;
              if (trial.state() == null) {
                converted = emptyAffectedPlans(after, affected);
              } else {
                states.put(trial.state().hash(), trial.state());
                trials.put(trial.state().hash(), trial);
                converted = legacyPlans(trial.state());
              }
              Map<Integer, method.rkhma.legacy.model.SchedulingPlan> selected = new HashMap<>();
              for (int satellite : affected) selected.put(satellite, converted.get(satellite));
              return selected;
            },
            (assignment, currentPlans) -> {
              State reconstructed = fromLegacy(assignment, currentPlans, api);
              State base = states.get(reconstructed.hash());
              if (base == null) {
                Map<Integer, Integer> expected = legacyOwners(reconstructed.assignment().owners());
                for (State candidate : states.values()) {
                  if (legacyOwners(candidate.assignment().owners()).equals(expected)) base = candidate;
                }
              }
              if (base == null) throw new IllegalStateException("source calibration base was not issued");
              MoveTrial trial = evaluator.calibrate(base);
              if (trial.state() == null) throw new IllegalStateException("source calibration was infeasible");
              states.put(trial.state().hash(), trial.state());
              trials.put(trial.state().hash(), trial);
              evaluator.checkpoint(trial);
              return legacyPlans(trial.state());
            },
            (assignment, acceptedPlans) -> {
              State accepted = fromLegacy(assignment, acceptedPlans, api);
              if (!accepted.hash().equals(root.hash())) {
                MoveTrial trial = trials.get(accepted.hash());
                if (trial == null) throw new IllegalStateException("source checkpoint was not issued by runtime");
                evaluator.checkpoint(trial);
              }
            });
      State selected = fromLegacy(result.owners(), result.plans(), api);
      if (selected.hash().equals(root.hash())) return null;
      MoveTrial trial = trials.get(selected.hash());
      if (trial == null) throw new IllegalStateException("source selected a candidate not issued by runtime");
      return trial;
    } catch (Exception error) {
      throw propagate(error);
    }
  }

  private Map<Integer, Integer> legacyOwners(Map<Integer, Integer> owners) {
    Map<Integer, Integer> result = new LinkedHashMap<>();
    owners.forEach((task, satellite) -> {
      if (satellite != null) result.put(bridge.taskIndex(task), bridge.satelliteIndex(satellite));
    });
    return result;
  }

  private Map<Integer, method.rkhma.legacy.model.SchedulingPlan> legacyPlans(State state) {
    Map<Integer, method.rkhma.legacy.model.SchedulingPlan> result = new LinkedHashMap<>();
    var tasks = bridge.tasks();
    for (var satellite : problem.satellites()) {
      int sat = bridge.satelliteIndex(satellite.getId());
      List<method.rkhma.legacy.model.Task> assigned = new ArrayList<>();
      state.assignment().owners().forEach((task, owner) -> {
        if (Objects.equals(owner, satellite.getId())) assigned.add(tasks.get(bridge.taskIndex(task)));
      });
      var plan = new method.rkhma.legacy.model.SchedulingPlan(assigned);
      plan.setSchedule(state.timelines().getOrDefault(satellite.getId(), List.of()).stream()
          .map(bridge::toLegacy).toList());
      plan.setFitness((double) plan.getTotalProfit());
      result.put(sat, plan);
    }
    return result;
  }

  private Map<Integer, method.rkhma.legacy.model.SchedulingPlan> emptyAffectedPlans(
      Map<Integer, Integer> owners, Set<Integer> affected) {
    Map<Integer, method.rkhma.legacy.model.SchedulingPlan> plans = new HashMap<>();
    var tasks = bridge.tasks();
    for (int satellite : affected) {
      var assigned = owners.entrySet().stream().filter(entry -> Objects.equals(entry.getValue(), satellite))
          .map(entry -> tasks.get(entry.getKey())).toList();
      var plan = new method.rkhma.legacy.model.SchedulingPlan(assigned);
      plan.setFitness(0.0);
      plans.put(satellite, plan);
    }
    return plans;
  }

  private State fromLegacy(Map<Integer, Integer> owners,
      Map<Integer, method.rkhma.legacy.model.SchedulingPlan> plans, ProblemAccess api) {
    Map<Integer, Integer> converted = new LinkedHashMap<>();
    problem.tasks().forEach(task -> {
      Integer satellite = owners.get(bridge.taskIndex(task.getId()));
      converted.put(task.getId(), satellite == null ? null : bridge.satelliteId(satellite));
    });
    Map<Integer, List<ScheduledTask>> timelines = new LinkedHashMap<>();
    for (var satellite : problem.satellites()) {
      var plan = plans.get(bridge.satelliteIndex(satellite.getId()));
      List<ScheduledTask> observations = plan == null ? List.of()
          : plan.getSchedule().stream().map(bridge::fromLegacy)
              .sorted(Comparator.comparingDouble(ScheduledTask::getStartTime)
                  .thenComparingDouble(ScheduledTask::getEndTime).thenComparingInt(ScheduledTask::getTaskId)).toList();
      timelines.put(satellite.getId(), observations);
    }
    return TwoStageSolver.state(new Assignment(converted), timelines, api);
  }

  private void requirePrepared(ProblemInstance problem) {
    if (this.problem != problem || bridge == null) throw new IllegalStateException("RKHMA allocation not initialized");
  }

  private RuntimeException propagate(Exception error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof Budget.SearchExpired expired) return expired;
      if (cause instanceof Budget.HardTimeout timeout) return timeout;
    }
    return error instanceof RuntimeException runtime ? runtime : new IllegalStateException("RKHMA allocation failed", error);
  }
}
