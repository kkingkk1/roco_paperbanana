package method.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.random.RandomGenerator;
import method.runtime.SolverContracts.C;
import method.runtime.SolverContracts.LocalContext;
import method.runtime.SolverContracts.LocalPlan;
import method.runtime.SolverContracts.LocalResult;
import method.runtime.SolverContracts.SatelliteView;
import model.ProblemInstance;
import model.ScheduledTask;
import model.SchedulingPlan;
import model.TimeWindow;
import problem.ScheduleValidator;
import utils.TimeUtils;

/** Complete decoder archived as a case; it uses the project's read-only runtime API. */
public final class C000068 implements C {
  @Override public List<ScheduledTask> build(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      List<Integer> priorities, Budget budget, RandomGenerator rng) {
    List<ScheduledTask> best = search(problem, api, view.satelliteId(), List.of(), priorities, budget, rng);
    return best == null ? List.of() : best;
  }

  @Override public LocalResult applyLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      LocalContext context, LocalPlan plan, Budget budget, RandomGenerator rng) {
    List<ScheduledTask> base = new ArrayList<>(view.observations());
    base.removeIf(o -> plan.release().contains(o.getTaskId()));
    List<ScheduledTask> best = search(problem, api, view.satelliteId(), base, plan.reconsider(), budget, rng);
    if (best == null || same(view.observations(), best)) return LocalResult.unchanged();
    return LocalResult.applied(best);
  }

  private List<ScheduledTask> search(ProblemInstance problem, ProblemAccess api, int satellite,
      List<ScheduledTask> base, List<Integer> order, Budget budget, RandomGenerator rng) {
    List<ScheduledTask> best = null;
    long bestProfit = Long.MIN_VALUE;
    for (int restart = 0; restart < 16; restart++) {
      budget.check();
      List<ScheduledTask> trial = decode(problem, api, satellite, new ArrayList<>(base), order, budget, rng);
      var evaluation = ScheduleValidator.evaluate(problem, new SchedulingPlan(trial));
      budget.check();
      if (!evaluation.feasible()) continue;
      if (best == null || evaluation.totalProfit() > bestProfit
          || (evaluation.totalProfit() == bestProfit && prefer(trial, best, order))) {
        best = trial;
        bestProfit = evaluation.totalProfit();
      }
    }
    return best;
  }

  private static boolean prefer(List<ScheduledTask> left, List<ScheduledTask> right, List<Integer> order) {
    for (int id : order) {
      boolean l = contains(left, id), r = contains(right, id);
      if (l != r) return l;
    }
    for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
      int comparison = Double.compare(left.get(i).getStartTime(), right.get(i).getStartTime());
      if (comparison != 0) return comparison < 0;
    }
    return false;
  }

  private List<ScheduledTask> decode(ProblemInstance problem, ProblemAccess api, int satellite,
      List<ScheduledTask> schedule, List<Integer> order, Budget budget, RandomGenerator rng) {
    for (int i = 0; i < order.size(); i++) {
      budget.check();
      int task = order.get(i);
      if (contains(schedule, task)) continue;
      ScheduledTask chosen = choose(problem, api, satellite, task, schedule,
          order.subList(i + 1, order.size()), budget, rng);
      if (chosen != null) schedule.add(chosen);
    }
    schedule.sort(Comparator.comparingDouble(ScheduledTask::getStartTime));
    return List.copyOf(schedule);
  }

  private ScheduledTask choose(ProblemInstance problem, ProblemAccess api, int satellite, int task,
      List<ScheduledTask> schedule, List<Integer> future, Budget budget, RandomGenerator rng) {
    List<ScheduledTask> sorted = new ArrayList<>(schedule);
    sorted.sort(Comparator.comparingDouble(ScheduledTask::getStartTime));
    ScheduledTask best = null;
    double bestScore = 0;

    for (ScheduledTask c : api.candidates(task, satellite, budget)) {
      budget.check();
      int pos = position(sorted, c);
      ScheduledTask before = pos == 0 ? null : sorted.get(pos - 1);
      ScheduledTask after = pos == sorted.size() ? null : sorted.get(pos);
      if (!fits(before, c, after)) continue;


      double score = rng.nextDouble();
      if (best == null || Double.compare(score, bestScore) < 0
          || (Double.compare(score, bestScore) == 0 && c.getEndTime() < best.getEndTime())
          || (Double.compare(score, bestScore) == 0 && c.getEndTime() == best.getEndTime()
              && c.getStartTime() < best.getStartTime())) {
        best = c;
        bestScore = score;
      }
    }
    return best;
  }

  private static int position(List<ScheduledTask> sorted, ScheduledTask c) {
    int i = 0;
    while (i < sorted.size() && sorted.get(i).getStartTime() <= c.getStartTime()) i++;
    return i;
  }

  private static boolean fits(ScheduledTask before, ScheduledTask c, ScheduledTask after) {
    return (before == null || (c.getStartTime() >= before.getEndTime()
        && TimeUtils.checkTransformationTime(before.endRecord(), c.startRecord())))
        && (after == null || (after.getStartTime() >= c.getEndTime()
        && TimeUtils.checkTransformationTime(c.endRecord(), after.startRecord())));
  }

  private static boolean contains(List<ScheduledTask> schedule, int task) {
    for (ScheduledTask observation : schedule) if (observation.getTaskId() == task) return true;
    return false;
  }

  private static boolean same(List<ScheduledTask> left, List<ScheduledTask> right) {
    if (left.size() != right.size()) return false;
    Map<Integer, ScheduledTask> existing = new HashMap<>();
    for (ScheduledTask observation : left) existing.put(observation.getTaskId(), observation);
    for (ScheduledTask observation : right) {
      ScheduledTask original = existing.get(observation.getTaskId());
      if (original == null || original.task() != observation.task()
          || original.timeWindow() != observation.timeWindow()
          || original.startRecord() != observation.startRecord()
          || original.endRecord() != observation.endRecord()) return false;
    }
    return true;
  }

}
