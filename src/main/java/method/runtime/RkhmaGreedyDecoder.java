package method.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;
import method.rkhma.bridge.GridGreedyInsertion;
import method.rkhma.bridge.ProblemBridge;
import method.rkhma.legacy.operator.insertion.InsertionMode;
import method.rkhma.legacy.operator.insertion.InsertionStrategyFactory;
import method.rkhma.legacy.utils.PlanUtils;
import method.runtime.SolverContracts.C;
import method.runtime.SolverContracts.LocalContext;
import method.runtime.SolverContracts.LocalPlan;
import method.runtime.SolverContracts.LocalResult;
import method.runtime.SolverContracts.SatelliteView;
import model.ProblemInstance;
import model.ScheduledTask;

/** RKHMA的贪心插入策略，使用统一C接口访问只读问题。 */
public final class RkhmaGreedyDecoder implements C {
  private ProblemInstance bound;
  private ProblemBridge bridge;
  private final Map<Integer, Boolean> compatible = new HashMap<>();

  private void prepare(ProblemInstance problem, Budget budget) {
    Objects.requireNonNull(problem);
    if (bound != problem) {
      bridge = new ProblemBridge(problem, budget::check);
      compatible.clear();
      bound = problem;
    }
  }

  private method.rkhma.legacy.model.Satellite satellite(int satelliteId, Budget budget) {
    int index = bridge.satelliteIndex(satelliteId);
    var satellite = bridge.satellites().get(index);
    boolean sourceCompatible = compatible.computeIfAbsent(index, bridge::sourceCompatible);
    satellite.setInsertionStrategy(sourceCompatible
        ? InsertionStrategyFactory.getStrategy(InsertionMode.GREEDY)
        : new GridGreedyInsertion(bridge, budget::check));
    return satellite;
  }

  /** 来源优化器的插入内核；与通用C使用相同的贪心规则。 */
  void configureSource(ProblemBridge source, Runnable check) {
    for (int i = 0; i < source.satellites().size(); i++) {
      var satellite = source.satellites().get(i);
      satellite.setInsertionStrategy(source.sourceCompatible(i)
          ? InsertionStrategyFactory.getStrategy(InsertionMode.GREEDY)
          : new GridGreedyInsertion(source, check));
    }
  }

  @Override public synchronized List<ScheduledTask> build(ProblemInstance problem, ProblemAccess api,
      SatelliteView view, List<Integer> priorities, Budget budget, RandomGenerator rng) {
    prepare(problem, budget);
    var satellite = satellite(view.satelliteId(), budget);
    var tasks = bridge.tasks();
    var sequence = priorities.stream().map(id -> tasks.get(bridge.taskIndex(id))).toList();
    var plan = new method.rkhma.legacy.model.SchedulingPlan(sequence);
    var built = PlanUtils.generateSchedule(plan, satellite, InsertionMode.GREEDY);
    return built.stream().map(bridge::fromLegacy).toList();
  }

  @Override public synchronized List<ScheduledTask> rebuild(ProblemInstance problem, ProblemAccess api,
      SatelliteView view, LocalContext context, List<Integer> priorities, Budget budget, RandomGenerator rng) {
    prepare(problem, budget);
    var satellite = satellite(view.satelliteId(), budget);
    var tasks = bridge.tasks();
    var sequence = priorities.stream().map(id -> tasks.get(bridge.taskIndex(id))).toList();
    Set<Integer> preferred = context.preferredWindowTasks().stream().map(bridge::taskIndex)
        .collect(java.util.stream.Collectors.toSet());
    var plan = new method.rkhma.legacy.model.SchedulingPlan(sequence);
    var built = PlanUtils.generateSchedule(plan, satellite, InsertionMode.GREEDY, !preferred.isEmpty(), preferred);
    return built.stream().map(bridge::fromLegacy).toList();
  }

  @Override public synchronized LocalResult applyLocal(ProblemInstance problem, ProblemAccess api,
      SatelliteView view, LocalContext context, LocalPlan plan, Budget budget, RandomGenerator rng) {
    prepare(problem, budget);
    var satellite = satellite(view.satelliteId(), budget);
    int index = bridge.satelliteIndex(view.satelliteId());
    var insertion = InsertionStrategyFactory.getStrategy(InsertionMode.GREEDY, satellite);
    var tasks = bridge.tasks();
    var current = new ArrayList<method.rkhma.legacy.model.ScheduledTask>();
    for (var observation : view.observations()) {
      budget.check();
      if (!plan.release().contains(observation.getTaskId())) current.add(bridge.toLegacy(observation));
    }
    for (int task : plan.reconsider()) {
      budget.check();
      if (current.stream().anyMatch(o -> bridge.taskId(o.getTaskId()) == task)) continue;
      var result = insertion.findBestPosition(tasks.get(bridge.taskIndex(task)), index, current);
      if (result != null) current.add(result.getInsertIndex(), result.getTask());
    }
    return LocalResult.applied(current.stream().map(bridge::fromLegacy).toList());
  }
}
