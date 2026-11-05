package method.runtime;

import model.ProblemInstance;
import model.ScheduledTask;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

/** A/B/C 的两阶段接口与只读运行时消息。 */
public final class SolverContracts {
  private SolverContracts() { }

  private static Map<Integer, Integer> nullableMap(Map<Integer, Integer> source) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(source)));
  }

  public record Assignment(Map<Integer, Integer> owners) {
    public Assignment { owners = nullableMap(owners); }
  }

  public record Move(String baseStateHash, Map<Integer, Integer> edits, Set<Integer> repairSatellites,
                     boolean fullRecalibration) {
    public Move(String baseStateHash, Map<Integer, Integer> edits) {
      this(baseStateHash, edits, Set.of(), false);
    }
    public Move(String baseStateHash, Map<Integer, Integer> edits, Set<Integer> repairSatellites) {
      this(baseStateHash, edits, repairSatellites, false);
    }
    public Move {
      Objects.requireNonNull(baseStateHash);
      edits = nullableMap(edits);
      repairSatellites = Set.copyOf(repairSatellites);
    }
  }

  public record State(Assignment assignment, Map<Integer, List<ScheduledTask>> timelines, String hash) {
    public State {
      Objects.requireNonNull(assignment);
      Map<Integer, List<ScheduledTask>> copy = new LinkedHashMap<>();
      timelines.forEach((key, value) -> copy.put(key, List.copyOf(value)));
      timelines = Collections.unmodifiableMap(copy);
      Objects.requireNonNull(hash);
    }
  }

  public record SatelliteView(int satelliteId, Set<Integer> assignedTaskIds,
                              List<ScheduledTask> observations, String hash) {
    public SatelliteView {
      assignedTaskIds = Set.copyOf(assignedTaskIds);
      observations = List.copyOf(observations);
      Objects.requireNonNull(hash);
    }
  }

  public enum Phase { INIT, REASSIGN }
  public enum LocalStatus { APPLIED, NO_FEASIBLE_CHANGE }
  public record BrokenEdge(int previousTaskId, int nextTaskId) { }

  public record LocalContext(Phase phase, Set<Integer> incoming, Set<Integer> outgoing,
                             List<BrokenEdge> brokenEdges, Set<Integer> preferredWindowTasks) {
    public LocalContext(Phase phase, Set<Integer> incoming, Set<Integer> outgoing, List<BrokenEdge> brokenEdges) {
      this(phase, incoming, outgoing, brokenEdges, Set.of());
    }
    public LocalContext {
      Objects.requireNonNull(phase);
      incoming = Set.copyOf(incoming);
      outgoing = Set.copyOf(outgoing);
      brokenEdges = List.copyOf(brokenEdges);
      preferredWindowTasks = Set.copyOf(preferredWindowTasks);
    }
  }

  public record Feedback(int attempt, String previousOutcome) {
    public Feedback {
      if (attempt < 0) throw new IllegalArgumentException("negative attempt");
      Objects.requireNonNull(previousOutcome);
    }
  }

  public record LocalPlan(String baseViewHash, Set<Integer> release, List<Integer> reconsider) {
    public LocalPlan {
      Objects.requireNonNull(baseViewHash);
      release = Set.copyOf(release);
      reconsider = List.copyOf(reconsider);
    }
  }

  public record LocalResult(LocalStatus status, List<ScheduledTask> observations) {
    public LocalResult {
      Objects.requireNonNull(status);
      observations = List.copyOf(observations);
    }
    public static LocalResult unchanged() {
      return new LocalResult(LocalStatus.NO_FEASIBLE_CHANGE, List.of());
    }
    public static LocalResult applied(List<ScheduledTask> observations) {
      return new LocalResult(LocalStatus.APPLIED, observations);
    }
  }

  /** 运行时签发的初始化试评；候选顺序与时间轴均为只读副本。 */
  public record InitialTrial(List<Integer> priorities, List<ScheduledTask> observations, long totalProfit) {
    public InitialTrial {
      priorities = List.copyOf(priorities);
      observations = List.copyOf(observations);
    }
  }

  /** 无可行编辑时收益为空；成功时收益为该星候选时间轴的原始收益。 */
  public record LocalTrial(LocalPlan plan, LocalResult result, Long totalProfit) {
    public LocalTrial {
      Objects.requireNonNull(plan);
      Objects.requireNonNull(result);
    }
  }

  @FunctionalInterface public interface InitialEvaluator {
    InitialTrial evaluate(List<Integer> priorities);
  }

  public interface LocalEvaluator {
    LocalTrial evaluate(LocalPlan plan);
    LocalTrial rebuild(List<Integer> priorities);
    /** 窗口偏好只影响本次受控重建，由C决定其具体解码方式。 */
    default LocalTrial rebuild(List<Integer> priorities, Set<Integer> preferredWindowTasks) {
      if (!preferredWindowTasks.isEmpty()) throw new UnsupportedOperationException("window preferences not supported");
      return rebuild(priorities);
    }
  }

  /** A的完整候选；不可行时state和totalProfit为空。 */
  public record MoveTrial(Move move, State state, Long totalProfit) {
    public MoveTrial {
      Objects.requireNonNull(move);
      if ((state == null) != (totalProfit == null)) throw new IllegalArgumentException("inconsistent move trial");
    }
  }

  @FunctionalInterface public interface MoveEvaluator {
    MoveTrial evaluate(State base, Move move);
    /** 保存本次调用中已接受的试评，供搜索截止时返回。 */
    default void checkpoint(MoveTrial trial) { }
    /** 受控重算所有卫星，结果仍须由调用方选择。 */
    default MoveTrial calibrate(State base) {
      return evaluate(base, new Move(base.hash(), Map.of(), base.timelines().keySet(), true));
    }
  }

  public interface A {
    Assignment initialAssign(ProblemInstance problem, ProblemAccess api, Budget budget, RandomGenerator rng);
    default void prepare(ProblemInstance problem, ProblemAccess api, Assignment assignment, Budget budget, long seed) { }
    /** 来源算法可在内部完成多轮搜索后提交收益未严格增长的最终方案。 */
    default boolean acceptsNonImprovingResult() { return false; }
    default Move proposeMove(ProblemInstance problem, ProblemAccess api, State state, Feedback feedback,
                             Budget budget, RandomGenerator rng) {
      throw new UnsupportedOperationException("reassignment is not implemented");
    }
    /** A可受控比较多个完整候选，或基于本次试评出的状态继续构造候选。 */
    default MoveTrial searchReassignment(ProblemInstance problem, ProblemAccess api, State state,
        Feedback feedback, MoveEvaluator evaluator, Budget budget, RandomGenerator rng) {
      Move move = proposeMove(problem, api, state, feedback, budget, rng);
      return move == null ? null : evaluator.evaluate(state, move);
    }
  }

  public interface B {
    /** 读取初始归属，为本次实例准备单星搜索状态。 */
    default void prepare(ProblemInstance problem, ProblemAccess api, Assignment assignment,
                         Budget budget, long seed) { }

    /** 实例级预算仍由运行时强制；需要时允许单星搜索使用剩余实例预算。 */
    default boolean usesInstanceBudgetForReassignment() { return false; }

    /** 试评可从本次A调用已产生的候选状态继续；此通知不表示移动被接受。 */
    default void onTrialBase(ProblemInstance problem, ProblemAccess api, State state, Budget budget) { }

    /** 整体移动接受后更新本模块状态；拒绝的试评不会触发。 */
    default void onAccepted(ProblemInstance problem, ProblemAccess api, State state, Budget budget) { }

    /** 一次完整重算开始前的通知；普通局部策略无需维护额外状态。 */
    default void beginCalibration(ProblemInstance problem, ProblemAccess api, State state, Budget budget) { }

    default List<Integer> initialPriority(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                          Budget budget, RandomGenerator rng) {
      throw new UnsupportedOperationException("initial search is not implemented");
    }
    default LocalPlan proposeInitialLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                          LocalContext context, Feedback feedback, Budget budget, RandomGenerator rng) {
      throw new UnsupportedOperationException("initial local search is not implemented");
    }
    default LocalPlan proposeReassignmentLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                               LocalContext context, Feedback feedback, Budget budget, RandomGenerator rng) {
      throw new UnsupportedOperationException("reassignment local search is not implemented");
    }

    /** B可反复试评；只能返回本次调用中由运行时签发的候选。 */
    default InitialTrial searchInitial(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                       InitialEvaluator evaluator, Budget budget, RandomGenerator rng) {
      return evaluator.evaluate(initialPriority(problem, api, view, budget, rng));
    }

    /** 初始化阶段的局部改进；返回null表示结束本星搜索。 */
    default LocalTrial searchInitialLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                          LocalContext context, Feedback feedback, LocalEvaluator evaluator,
                                          Budget budget, RandomGenerator rng) {
      LocalPlan plan = proposeInitialLocal(problem, api, view, context, feedback, budget, rng);
      return plan == null ? null : evaluator.evaluate(plan);
    }

    /** 重分配阶段的局部改进；试评不直接提交时间轴。 */
    default LocalTrial searchReassignment(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                          LocalContext context, Feedback feedback, LocalEvaluator evaluator,
                                          Budget budget, RandomGenerator rng) {
      LocalPlan plan = proposeReassignmentLocal(problem, api, view, context, feedback, budget, rng);
      return plan == null ? null : evaluator.evaluate(plan);
    }

    /** 重分配接受后的全星重算；由B选择本星的处理方式。 */
    default LocalTrial searchCalibration(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                        LocalContext context, Feedback feedback, LocalEvaluator evaluator,
                                        Budget budget, RandomGenerator rng) {
      return searchReassignment(problem, api, view, context, feedback, evaluator, budget, rng);
    }
  }

  public interface C {
    List<ScheduledTask> build(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                              List<Integer> priorities, Budget budget, RandomGenerator rng);
    LocalResult applyLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                           LocalContext context, LocalPlan plan, Budget budget, RandomGenerator rng);

    /** B授权整星重建时，重分配仍通过重分配阶段的编辑接口处理。 */
    default List<ScheduledTask> rebuild(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                        LocalContext context, List<Integer> priorities,
                                        Budget budget, RandomGenerator rng) {
      Set<Integer> release = new java.util.HashSet<>();
      view.observations().forEach(o -> release.add(o.getTaskId()));
      LocalResult result = applyLocal(problem, api, view, context,
          new LocalPlan(view.hash(), release, priorities), budget, rng);
      if (result == null) throw new IllegalArgumentException("null rebuild result");
      if (result.status() == LocalStatus.NO_FEASIBLE_CHANGE && !result.observations().isEmpty()) {
        throw new IllegalArgumentException("no-change rebuild contains observations");
      }
      return result.status() == LocalStatus.NO_FEASIBLE_CHANGE ? view.observations() : result.observations();
    }
  }
}
