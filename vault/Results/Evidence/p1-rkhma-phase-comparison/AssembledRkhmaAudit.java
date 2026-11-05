import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import method.runtime.RkhmaRuntime;
import model.ProblemInstance;
import problem.ScheduleValidator;

/** 通过公开组合入口运行A3B3C3，并只读捕获来源协调器的阶段历史。 */
public final class AssembledRkhmaAudit {
  public static void main(String[] args) throws Exception {
    if (args.length != 5) throw new IllegalArgumentException("scene task-count seed reassign-iterations fixed|auto");
    var fixture = ComparisonFixture.load(args[0], Integer.parseInt(args[1]));
    if (!fixture.sourceCompatible()) throw new IllegalStateException("comparison requires one-second internal records");
    long seed = Long.parseLong(args[2]);
    int iterations = Integer.parseInt(args[3]);
    boolean automatic = switch (args[4]) {
      case "auto" -> true;
      case "fixed" -> false;
      default -> throw new IllegalArgumentException("unknown population budget");
    };
    System.setProperty("autohyb.quiet", "true");
    var problem = new ProblemInstance(fixture.tasks(), fixture.satellites());
    var original = RkhmaRuntime.combination("A3B3C3");
    AtomicReference<Map<Integer, Integer>> initial = new AtomicReference<>();
    AtomicReference<RkhmaRuntime.Context> capturedContext = new AtomicReference<>();
    AtomicReference<method.rkhma.legacy.main.TaskAllocationCoordinator> coordinator = new AtomicReference<>();
    RkhmaRuntime.Allocation observed = new RkhmaRuntime.Allocation() {
      @Override public Map<Integer, Integer> initialOwners(RkhmaRuntime.Context context) {
        var result = original.allocation().initialOwners(context);
        initial.set(new java.util.LinkedHashMap<>(result));
        capturedContext.set(context);
        return result;
      }
      @Override public void configure(method.rkhma.legacy.main.TaskAllocationCoordinator value,
                                      RkhmaRuntime.Context context) {
        coordinator.set(value);
        original.allocation().configure(value, context);
      }
    };
    var parts = new RkhmaRuntime.Components(observed, original.search(), original.decoder());
    var config = new RkhmaRuntime.Config(seed, automatic ? 0 : 12, automatic ? 0 : 120, iterations, 1, .02,
        4_500_000_000L, 5_000_000_000L);
    System.err.println("P1_COMPARISON_READY");
    System.err.flush();
    if (System.in.read() != 1) throw new IllegalStateException("missing start signal");
    var result = RkhmaRuntime.solve(problem, parts, config);
    if (result.status() != method.runtime.TwoStageSolver.Status.SUCCESS) {
      throw new IllegalStateException(result.status() + ": " + result.detail());
    }
    var check = ScheduleValidator.evaluate(problem, result.plan());
    if (!check.feasible() || check.totalProfit() != result.profit()) {
      throw new IllegalStateException("independent validation failed: " + check.violations());
    }
    var source = coordinator.get();
    if (source == null || initial.get() == null) throw new IllegalStateException("expired before initial assignment");
    System.out.println("input_sha256=" + fixture.fingerprint());
    System.out.println("dimensions=" + fixture.tasks().size() + ":" + fixture.satellites().size()
        + ":" + fixture.windows() + ":" + fixture.records());
    System.out.println("source_compatible=" + fixture.sourceCompatible());
    int maxAssigned = initial.get().values().stream().collect(java.util.stream.Collectors.groupingBy(
        value -> value, java.util.stream.Collectors.counting())).values().stream().mapToInt(Long::intValue).max().orElse(0);
    int population = automatic ? Math.max(6, (int) Math.round(2.5 * Math.max(1, maxAssigned))) : 12;
    int evaluations = automatic ? Math.max(population, (int) Math.round(2.5 * Math.max(1, maxAssigned) * 100)) : 120;
    System.out.println("population=" + population + ":" + evaluations);
    System.out.println("initial=" + new TreeMap<>(initial.get()));
    if (iterations == 0) {
      var bridge = capturedContext.get().bridge();
      var baseline = new java.util.ArrayList<model.ScheduledTask>();
      for (int satellite = 0; satellite < bridge.satellites().size(); satellite++) {
        final int sat = satellite;
        var sequence = initial.get().entrySet().stream().filter(entry -> entry.getValue() == sat)
            .map(entry -> bridge.tasks().get(entry.getKey())).toList();
        var plan = new method.rkhma.legacy.model.SchedulingPlan(sequence);
        method.rkhma.legacy.utils.PlanUtils.generateSchedule(plan, bridge.satellites().get(satellite),
            method.rkhma.legacy.operator.insertion.InsertionMode.GREEDY).stream()
            .map(bridge::fromLegacy).forEach(baseline::add);
      }
      var baselineCheck = ScheduleValidator.evaluate(problem, new model.SchedulingPlan(baseline));
      if (!baselineCheck.feasible()) throw new IllegalStateException("invalid baseline: " + baselineCheck.violations());
      System.out.println("baseline_profit=" + baselineCheck.totalProfit());
      System.out.println("baseline_observations=" + baseline.stream()
          .map(o -> o.getTaskId() + ":" + o.getSatelliteId() + ":" + o.getStartTime() + ":" + o.getEndTime())
          .sorted().toList());
    }
    System.out.println("initial_profit=" + first(source.getTotalProfitHistorySnapshot()));
    System.out.println("profit_history=" + source.getTotalProfitHistorySnapshot());
    System.out.println("strategy_history=" + result.history());
    System.out.println("owners=" + new TreeMap<>(source.getBestTaskAssignmentSnapshot()));
    System.out.println("profit=" + result.profit());
    System.out.println("observations=" + result.plan().observations().stream()
        .map(o -> o.getTaskId() + ":" + o.getSatelliteId() + ":" + o.getStartTime() + ":" + o.getEndTime())
        .sorted().toList());
    for (var trace : source.getConvergenceTracesSnapshot()) {
      System.out.println("trace=" + trace.satelliteId + ":" + trace.ensembleIndex + ":" + trace.maxFes + ":"
          + trace.genUsed + ":" + trace.bestFitnessByGeneration + ":" + trace.evalsByGeneration);
    }
    System.out.println("status=" + result.detail());
  }

  private static String first(List<Double> values) {
    return values.isEmpty() ? "unavailable" : values.getFirst().toString();
  }
}
