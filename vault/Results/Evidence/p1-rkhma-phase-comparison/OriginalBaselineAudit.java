import common.RandomProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import model.ScheduledTask;
import model.SchedulingPlan;
import operator.insertion.InsertionMode;
import utils.AssignTaskOperator;
import utils.Initialize;
import utils.PlanUtils;

/** 只计算来源初始分配的首个基准个体，不执行单星种群搜索。 */
public final class OriginalBaselineAudit {
  public static void main(String[] args) throws Exception {
    if (args.length != 5) throw new IllegalArgumentException("scene task-count seed reassign-iterations budget");
    var fixture = ComparisonFixture.load(args[0], Integer.parseInt(args[1]));
    if (!fixture.sourceCompatible()) throw new IllegalStateException("comparison requires one-second internal records");
    long seed = Long.parseLong(args[2]);
    System.setProperty("autohyb.quiet", "true");
    System.err.println("P1_COMPARISON_READY");
    System.err.flush();
    if (System.in.read() != 1) throw new IllegalStateException("missing start signal");
    RandomProvider.reset(seed);
    var pairs = Initialize.initializeWithStrategy(fixture.tasks(), fixture.satellites(), 7);
    AssignTaskOperator.assignTaskToSatellites(pairs, fixture.tasks(), fixture.satellites());
    Map<Integer, Integer> initial = new TreeMap<>();
    for (var pair : pairs) initial.put(pair.get(1), pair.get(0));
    List<ScheduledTask> observations = new ArrayList<>();
    for (var satellite : fixture.satellites()) {
      var plan = new SchedulingPlan(new ArrayList<>(satellite.getAssignedTasks()));
      observations.addAll(PlanUtils.generateSchedule(plan, satellite, InsertionMode.GREEDY));
    }
    System.out.println("input_sha256=" + fixture.fingerprint());
    System.out.println("dimensions=" + fixture.tasks().size() + ":" + fixture.satellites().size()
        + ":" + fixture.windows() + ":" + fixture.records());
    System.out.println("source_compatible=" + fixture.sourceCompatible());
    System.out.println("population=not_applicable");
    System.out.println("initial=" + initial);
    System.out.println("initial_profit=unavailable");
    System.out.println("profit=" + observations.stream().mapToInt(o -> o.getTask().getPriority()).sum());
    System.out.println("observations=" + observations.stream()
        .map(o -> o.getTaskId() + ":" + o.getSatelliteId() + ":" + o.getStartTime() + ":" + o.getEndTime())
        .sorted().toList());
    System.out.println("status=COMPLETED");
  }
}
