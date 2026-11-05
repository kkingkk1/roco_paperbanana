import java.util.TreeMap;
import method.runtime.RkhmaAllocation;
import method.runtime.RkhmaGreedyDecoder;
import method.runtime.RkhmaHybridSearch;
import method.runtime.RkhmaRuntime;
import method.runtime.TwoStageSolver;
import model.ProblemInstance;
import model.SchedulingPlan;
import problem.ScheduleValidator;

/** 以相同输入分别检查来源协调器组装路径和通用两阶段接口路径。 */
public final class UnifiedRkhmaAudit {
  public static void main(String[] args) throws Exception {
    if (args.length != 5) throw new IllegalArgumentException("scene task-count seed iterations native|generic");
    var fixture = ComparisonFixture.load(args[0], Integer.parseInt(args[1]));
    var problem = new ProblemInstance(fixture.tasks(), fixture.satellites());
    long seed = Long.parseLong(args[2]);
    int iterations = Integer.parseInt(args[3]);
    String mode = args[4];
    if (!mode.equals("native") && !mode.equals("generic")) throw new IllegalArgumentException("unknown mode");
    System.setProperty("autohyb.quiet", "true");

    // 此单独核查发生在就绪信号前；正式运行自身仍包含A的初始分配时间。
    var bridge = new method.rkhma.bridge.ProblemBridge(problem);
    var pairs = method.rkhma.legacy.utils.Initialize.initializeWithStrategy(bridge.tasks(), bridge.satellites(), 7);
    var initial = new TreeMap<Integer, Integer>();
    pairs.forEach(pair -> initial.put(bridge.taskId(pair.get(1)), bridge.satelliteId(pair.get(0))));
    System.err.println("P1_COMPARISON_READY");
    System.err.flush();
    if (System.in.read() != 1) throw new IllegalStateException("missing start signal");

    SchedulingPlan plan;
    java.util.Map<Integer, Integer> owners;
    Long profit;
    String status;
    java.util.List<String> history;
    if (mode.equals("native")) {
      var config = new RkhmaRuntime.Config(seed, 12, 120, iterations, 1, .02, 4_500_000_000L, 5_000_000_000L);
      var result = RkhmaRuntime.solve(problem,
          new RkhmaRuntime.Components(new RkhmaAllocation(), new RkhmaHybridSearch(12, 120), new RkhmaGreedyDecoder()), config);
      if (result.status() != TwoStageSolver.Status.SUCCESS) throw new IllegalStateException(result.detail());
      plan = result.plan(); owners = result.owners(); profit = result.profit();
      status = result.detail(); history = result.history();
    } else {
      var result = new TwoStageSolver().solve(problem, new RkhmaAllocation(iterations, 1, .02),
          new RkhmaHybridSearch(12, 120), new RkhmaGreedyDecoder(), seed);
      if (result.status() != TwoStageSolver.Status.SUCCESS) throw new IllegalStateException(result.detail());
      plan = result.plan(); owners = result.owners(); profit = result.totalProfit();
      status = result.detail(); history = java.util.List.of();
    }
    var validated = ScheduleValidator.evaluate(problem, plan);
    if (!validated.feasible() || validated.totalProfit() != profit) throw new IllegalStateException("invalid result");
    System.out.println("input_sha256=" + fixture.fingerprint());
    System.out.println("dimensions=" + fixture.tasks().size() + ":" + fixture.satellites().size()
        + ":" + fixture.windows() + ":" + fixture.records());
    System.out.println("source_compatible=" + fixture.sourceCompatible());
    System.out.println("population=12:120");
    System.out.println("initial=" + initial);
    System.out.println("strategy_history=" + history);
    var finalOwners = new TreeMap<Integer, Integer>();
    owners.forEach((task, satellite) -> { if (satellite != null) finalOwners.put(task, satellite); });
    System.out.println("owners=" + finalOwners);
    System.out.println("profit=" + profit);
    System.out.println("observations=" + plan.observations().stream()
        .map(o -> o.getTaskId() + ":" + o.getSatelliteId() + ":" + o.getStartTime() + ":" + o.getEndTime())
        .sorted().toList());
    System.out.println("status=" + status);
  }
}
