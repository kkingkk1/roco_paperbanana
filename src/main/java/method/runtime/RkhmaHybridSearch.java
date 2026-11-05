package method.runtime;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;
import method.rkhma.bridge.ProblemBridge;
import method.rkhma.legacy.common.RandomProvider;
import method.rkhma.legacy.common.SearchDeadline;
import method.rkhma.legacy.main.TaskAllocationCoordinator;
import method.rkhma.legacy.operator.assignment.MaxWindowCountStrategy;
import method.rkhma.legacy.operator.insertion.InsertionMode;
import method.runtime.SolverContracts.Assignment;
import method.runtime.SolverContracts.B;
import method.runtime.SolverContracts.Feedback;
import method.runtime.SolverContracts.InitialEvaluator;
import method.runtime.SolverContracts.InitialTrial;
import method.runtime.SolverContracts.LocalContext;
import method.runtime.SolverContracts.LocalEvaluator;
import method.runtime.SolverContracts.LocalTrial;
import method.runtime.SolverContracts.SatelliteView;
import method.runtime.SolverContracts.State;
import model.ProblemInstance;

/** RKHMA来源混合种群搜索与重分配后的单星修补。 */
public final class RkhmaHybridSearch implements B {
  private final int fixedPopulation;
  private final int fixedEvaluations;
  private ProblemInstance problem;
  private ProblemBridge bridge;
  private TaskAllocationCoordinator coordinator;
  private TaskAllocationCoordinator.HybCecConfig config;
  private Map<Integer, Integer> acceptedOwners;
  private RkhmaAllocation unifiedAllocation;

  void bindUnified(RkhmaAllocation allocation) { unifiedAllocation = Objects.requireNonNull(allocation); }

  public RkhmaHybridSearch() { this(0, 0); }

  public RkhmaHybridSearch(int population, int evaluations) {
    boolean automatic = population == 0 && evaluations == 0;
    if (!automatic && (population < 6 || evaluations < population)) {
      throw new IllegalArgumentException("invalid population or evaluation budget");
    }
    fixedPopulation = population;
    fixedEvaluations = evaluations;
  }

  @Override public synchronized void prepare(ProblemInstance problem, ProblemAccess api, Assignment assignment,
                                             Budget budget, long seed) {
    this.problem = Objects.requireNonNull(problem);
    RkhmaAllocation shared = unifiedAllocation;
    unifiedAllocation = null;
    bridge = shared == null ? new ProblemBridge(problem, budget::check) : shared.unifiedBridge(problem);
    acceptedOwners = legacyOwners(assignment.owners());
    int population = fixedPopulation;
    int evaluations = fixedEvaluations;
    if (population == 0) {
      Map<Integer, Integer> counts = new LinkedHashMap<>();
      acceptedOwners.values().forEach(satellite -> counts.merge(satellite, 1, Integer::sum));
      int maximum = Math.max(1, counts.values().stream().mapToInt(Integer::intValue).max().orElse(0));
      long n = Math.max(6, Math.round(2.5 * maximum));
      long f = Math.max(n, Math.round(2.5 * maximum * 100));
      if (n > Integer.MAX_VALUE || f > Integer.MAX_VALUE) throw new IllegalArgumentException("population too large");
      population = (int) n;
      evaluations = (int) f;
    }
    if (shared == null) RandomProvider.reset(seed);
    coordinator = shared == null ? new TaskAllocationCoordinator(bridge.tasks(), bridge.satellites(),
        new MaxWindowCountStrategy(), 25, 1, .02) : shared.unifiedCoordinator(problem);
    config = new TaskAllocationCoordinator.HybCecConfig(new int[]{4, 1}, new int[]{0, 0}, 0, 0, 0,
        population, evaluations, InsertionMode.GREEDY, seed);
    var deadline = SearchDeadline.startingAt(Duration.ofNanos(Long.MAX_VALUE), () -> {
      budget.check();
      return budget.elapsedNanos();
    }, 0);
    if (shared == null) coordinator.prepareUnifiedSingleSearch(config, acceptedOwners, deadline);
  }

  @Override public boolean usesInstanceBudgetForReassignment() { return true; }

  /** 来源协调器使用本模块的原生HybCec与快速修补内核。 */
  void configureSource(TaskAllocationCoordinator source) {
    source.useRkhmaPopulationSearch();
    source.useRkhmaLocalRefinement();
  }

  @Override public synchronized InitialTrial searchInitial(ProblemInstance problem, ProblemAccess api,
      SatelliteView view, InitialEvaluator evaluator, Budget budget, RandomGenerator rng) {
    requirePrepared(problem);
    int satelliteId = bridge.satelliteIndex(view.satelliteId());
    var satellite = bridge.satellites().get(satelliteId);
    Map<String, InitialTrial> issued = new ConcurrentHashMap<>();
    satellite.setScheduleBuilder((ignored, sequence) -> {
      var order = sequence.stream().map(task -> bridge.taskId(task.getId())).toList();
      var trial = evaluator.evaluate(order);
      issued.put(trialKey(order, trial.observations(), api), trial);
      return trial.observations().stream().map(bridge::toLegacy).toList();
    });
    try {
      var plan = coordinator.searchUnifiedSingleSatellite(satelliteId, config);
      var order = plan.getTaskSequence().stream().map(task -> bridge.taskId(task.getId())).toList();
      var observations = plan.getSchedule().stream().map(bridge::fromLegacy).toList();
      var selected = issued.get(trialKey(order, observations, api));
      return selected != null ? selected : evaluator.evaluate(order);
    } catch (Exception error) {
      throw propagate(error);
    } finally {
      satellite.setScheduleBuilder(null);
    }
  }

  @Override public LocalTrial searchInitialLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      LocalContext context, Feedback feedback, LocalEvaluator evaluator, Budget budget, RandomGenerator rng) {
    return null;
  }

  @Override public synchronized LocalTrial searchReassignment(ProblemInstance problem, ProblemAccess api,
      SatelliteView view, LocalContext context, Feedback feedback, LocalEvaluator evaluator,
      Budget budget, RandomGenerator rng) {
    requirePrepared(problem);
    if (feedback.attempt() > 0) return null;
    int satelliteId = bridge.satelliteIndex(view.satelliteId());
    var satellite = bridge.satellites().get(satelliteId);
    Map<Integer, Integer> proposed = new LinkedHashMap<>(acceptedOwners);
    for (int task : context.outgoing()) proposed.remove(bridge.taskIndex(task));
    for (int task : context.incoming()) proposed.put(bridge.taskIndex(task), satelliteId);
    Map<String, LocalTrial> issued = new ConcurrentHashMap<>();
    satellite.setScheduleBuilder(new method.rkhma.legacy.model.Satellite.ScheduleBuilder() {
      @Override public List<method.rkhma.legacy.model.ScheduledTask> build(
          method.rkhma.legacy.model.Satellite ignored, List<method.rkhma.legacy.model.Task> sequence) {
        return buildWithPreferences(ignored, sequence, java.util.Set.of());
      }
      @Override public List<method.rkhma.legacy.model.ScheduledTask> buildWithPreferences(
          method.rkhma.legacy.model.Satellite ignored, List<method.rkhma.legacy.model.Task> sequence,
          java.util.Set<Integer> preferences) {
        var order = sequence.stream().map(task -> bridge.taskId(task.getId())).toList();
        var preferred = preferences.stream().map(bridge::taskId).collect(java.util.stream.Collectors.toSet());
        var trial = evaluator.rebuild(order, preferred);
        issued.put(trialKey(order, trial.result().observations(), api), trial);
        return trial.result().observations().stream().map(bridge::toLegacy).toList();
      }
    });
    try {
      var plan = coordinator.refineUnifiedSingleSatellite(satelliteId, acceptedOwners, proposed, config, 0);
      var order = plan.getTaskSequence().stream().map(task -> bridge.taskId(task.getId())).toList();
      var observations = plan.getSchedule().stream().map(bridge::fromLegacy).toList();
      var selected = issued.get(trialKey(order, observations, api));
      return selected != null ? selected : evaluator.rebuild(order);
    } catch (Exception error) {
      throw propagate(error);
    } finally {
      satellite.setScheduleBuilder(null);
    }
  }

  @Override public synchronized void onAccepted(ProblemInstance problem, ProblemAccess api, State state, Budget budget) {
    requirePrepared(problem);
    acceptedOwners = legacyOwners(state.assignment().owners());
    coordinator.acceptUnifiedAssignment(acceptedOwners);
  }

  @Override public synchronized void onTrialBase(ProblemInstance problem, ProblemAccess api, State state, Budget budget) {
    requirePrepared(problem);
    acceptedOwners = legacyOwners(state.assignment().owners());
    coordinator.acceptUnifiedAssignment(acceptedOwners);
  }

  @Override public synchronized void beginCalibration(ProblemInstance problem, ProblemAccess api, State state, Budget budget) {
    requirePrepared(problem);
    coordinator.beginUnifiedCalibration();
  }

  @Override public synchronized LocalTrial searchCalibration(ProblemInstance problem, ProblemAccess api,
      SatelliteView view, LocalContext context, Feedback feedback, LocalEvaluator evaluator,
      Budget budget, RandomGenerator rng) {
    requirePrepared(problem);
    if (feedback.attempt() > 0) return null;
    int satelliteId = bridge.satelliteIndex(view.satelliteId());
    var satellite = bridge.satellites().get(satelliteId);
    Map<String, LocalTrial> issued = new ConcurrentHashMap<>();
    satellite.setScheduleBuilder((ignored, sequence) -> {
      var order = sequence.stream().map(task -> bridge.taskId(task.getId())).toList();
      var trial = evaluator.rebuild(order);
      issued.put(trialKey(order, trial.result().observations(), api), trial);
      return trial.result().observations().stream().map(bridge::toLegacy).toList();
    });
    try {
      var plan = coordinator.searchUnifiedCalibrationSatellite(satelliteId, config);
      var order = plan.getTaskSequence().stream().map(task -> bridge.taskId(task.getId())).toList();
      var observations = plan.getSchedule().stream().map(bridge::fromLegacy).toList();
      var selected = issued.get(trialKey(order, observations, api));
      return selected != null ? selected : evaluator.rebuild(order);
    } catch (Exception error) {
      throw propagate(error);
    } finally {
      satellite.setScheduleBuilder(null);
    }
  }

  private Map<Integer, Integer> legacyOwners(Map<Integer, Integer> owners) {
    Map<Integer, Integer> converted = new LinkedHashMap<>();
    owners.forEach((task, satellite) -> {
      if (satellite != null) converted.put(bridge.taskIndex(task), bridge.satelliteIndex(satellite));
    });
    return converted;
  }

  private void requirePrepared(ProblemInstance problem) {
    if (this.problem != problem || coordinator == null) throw new IllegalStateException("RKHMA search not prepared");
  }

  private static String trialKey(List<Integer> order, List<model.ScheduledTask> observations, ProblemAccess api) {
    StringBuilder key = new StringBuilder(order.toString());
    observations.stream().sorted(java.util.Comparator.comparingInt(model.ScheduledTask::getTaskId)).forEach(o ->
        key.append('|').append(o.getTaskId()).append(':').append(api.windowIndex(o.timeWindow()))
            .append(':').append(Double.doubleToLongBits(o.getStartTime()))
            .append(':').append(Double.doubleToLongBits(o.getEndTime())));
    return key.toString();
  }

  private RuntimeException propagate(Exception error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof Budget.SearchExpired expired) return expired;
      if (cause instanceof Budget.HardTimeout timeout) return timeout;
    }
    return error instanceof RuntimeException runtime ? runtime : new IllegalStateException("RKHMA search failed", error);
  }
}
