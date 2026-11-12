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

/** RKHMA来源初始单星混合种群搜索。 */
public final class B000040 implements B {
  private final int fixedPopulation;
  private final int fixedEvaluations;
  private ProblemInstance problem;
  private ProblemBridge bridge;
  private TaskAllocationCoordinator coordinator;
  private TaskAllocationCoordinator.HybCecConfig config;
  private Map<Integer, Integer> acceptedOwners;
  private RkhmaAllocation unifiedAllocation;

  void bindUnified(RkhmaAllocation allocation) { unifiedAllocation = Objects.requireNonNull(allocation); }

  public B000040() { this(0, 0); }

  public B000040(int population, int evaluations) {
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
