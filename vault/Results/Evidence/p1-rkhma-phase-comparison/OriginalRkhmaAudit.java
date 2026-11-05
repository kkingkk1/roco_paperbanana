import common.RandomProvider;
import common.SearchDeadline;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import main.TaskAllocationCoordinator;
import model.ScheduledTask;
import model.SchedulingPlan;
import operator.assignment.MaxWindowCountStrategy;
import operator.insertion.InsertionMode;
import utils.AssignTaskOperator;
import utils.Initialize;
import utils.PlanUtils;

/** 在来源项目已编译类上运行来源RKHMA，不写来源项目。 */
public final class OriginalRkhmaAudit {
  public static void main(String[] args) throws Exception {
    if (args.length != 6) throw new IllegalArgumentException("scene task-count seed reassign-iterations timed|untimed fixed|auto");
    var fixture = ComparisonFixture.load(args[0], Integer.parseInt(args[1]));
    if (!fixture.sourceCompatible()) throw new IllegalStateException("comparison requires one-second internal records");
    long seed = Long.parseLong(args[2]);
    int iterations = Integer.parseInt(args[3]);
    boolean timed = switch (args[4]) {
      case "timed" -> true;
      case "untimed" -> false;
      default -> throw new IllegalArgumentException("unknown source execution mode");
    };
    boolean automatic = switch (args[5]) {
      case "auto" -> true;
      case "fixed" -> false;
      default -> throw new IllegalArgumentException("unknown population budget");
    };
    System.setProperty("autohyb.quiet", "true");
    System.err.println("P1_COMPARISON_READY");
    System.err.flush();
    if (System.in.read() != 1) throw new IllegalStateException("missing start signal");
    long start = System.nanoTime();
    SearchDeadline deadline = SearchDeadline.startingAt(Duration.ofMillis(4500), System::nanoTime, start);
    RandomProvider.reset(seed);
    var pairs = Initialize.initializeWithStrategy(fixture.tasks(), fixture.satellites(), 7);
    AssignTaskOperator.assignTaskToSatellites(pairs, fixture.tasks(), fixture.satellites());
    Map<Integer, Integer> initial = new TreeMap<>();
    for (var pair : pairs) initial.put(pair.get(1), pair.get(0));
    var coordinator = new TaskAllocationCoordinator(fixture.tasks(), fixture.satellites(),
        new MaxWindowCountStrategy(), iterations, 1, .02);
    int maxAssigned = fixture.satellites().stream().mapToInt(s -> s.getAssignedTasks().size()).max().orElse(0);
    int population = automatic ? Math.max(1, (int) Math.round(2.5 * Math.max(1, maxAssigned))) : 12;
    int evaluations = automatic ? (int) Math.round(2.5 * Math.max(1, maxAssigned) * 100) : 120;
    var config = new TaskAllocationCoordinator.HybCecConfig(new int[]{4, 1}, new int[]{0, 0},
        0, 0, 0, population, evaluations, InsertionMode.GREEDY, seed);
    var plans = timed ? coordinator.scheduleWithReassignment(config, fixture.satellites(), initial, deadline)
        : coordinator.scheduleWithReassignment(config, fixture.satellites(), initial);
    List<ScheduledTask> observations = new ArrayList<>();
    plans.values().forEach(plan -> observations.addAll(plan.getSchedule()));
    System.out.println("input_sha256=" + fixture.fingerprint());
    System.out.println("dimensions=" + fixture.tasks().size() + ":" + fixture.satellites().size()
        + ":" + fixture.windows() + ":" + fixture.records());
    System.out.println("source_compatible=" + fixture.sourceCompatible());
    System.out.println("population=" + population + ":" + evaluations);
    System.out.println("initial=" + initial);
    if (iterations == 0) {
      List<ScheduledTask> baseline = new ArrayList<>();
      for (var satellite : fixture.satellites()) {
        var sequence = pairs.stream().filter(pair -> pair.get(0) == satellite.getId())
            .map(pair -> fixture.tasks().get(pair.get(1))).toList();
        baseline.addAll(PlanUtils.generateSchedule(new SchedulingPlan(sequence), satellite, InsertionMode.GREEDY));
      }
      System.out.println("baseline_profit=" + baseline.stream().mapToInt(o -> o.getTask().getPriority()).sum());
      System.out.println("baseline_observations=" + baseline.stream()
          .map(o -> o.getTaskId() + ":" + o.getSatelliteId() + ":" + o.getStartTime() + ":" + o.getEndTime())
          .sorted().toList());
    }
    System.out.println("initial_profit=" + first(coordinator.getTotalProfitHistorySnapshot()));
    System.out.println("profit_history=" + coordinator.getTotalProfitHistorySnapshot());
    System.out.println("strategy_history=" + coordinator.getStrategyHistorySnapshot());
    System.out.println("owners=" + new TreeMap<>(coordinator.getBestTaskAssignmentSnapshot()));
    System.out.println("profit=" + plans.values().stream().mapToInt(SchedulingPlan::getTotalProfit).sum());
    System.out.println("observations=" + observations.stream()
        .map(o -> o.getTaskId() + ":" + o.getSatelliteId() + ":" + o.getStartTime() + ":" + o.getEndTime())
        .sorted().toList());
    for (var trace : coordinator.getConvergenceTracesSnapshot()) {
      System.out.println("trace=" + trace.satelliteId + ":" + trace.ensembleIndex + ":" + trace.maxFes + ":"
          + trace.genUsed + ":" + trace.bestFitnessByGeneration + ":" + trace.evalsByGeneration);
    }
    System.out.println("status=" + (timed && deadline.expired() ? "SEARCH_EXPIRED" : "COMPLETED"));
  }

  private static String first(List<Double> values) {
    return values.isEmpty() ? "unavailable" : values.getFirst().toString();
  }
}
