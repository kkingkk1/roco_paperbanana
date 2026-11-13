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
public final class C000062 implements C {
  @Override public List<ScheduledTask> build(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      List<Integer> priorities, Budget budget, RandomGenerator rng) {
    return decode(problem, api, view.satelliteId(), new ArrayList<>(), priorities, budget, rng);
  }

  @Override public LocalResult applyLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      LocalContext context, LocalPlan plan, Budget budget, RandomGenerator rng) {
    List<ScheduledTask> base = new ArrayList<>(view.observations());
    base.removeIf(o -> plan.release().contains(o.getTaskId()));
    List<ScheduledTask> result = decode(problem, api, view.satelliteId(), base, plan.reconsider(), budget, rng);
    if (same(view.observations(), result)) return LocalResult.unchanged();
    budget.check();
    if (!ScheduleValidator.evaluate(problem, new SchedulingPlan(result)).feasible()) return LocalResult.unchanged();
    budget.check();
    return LocalResult.applied(result);
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
    long bestReward = Long.MIN_VALUE;
    for (ScheduledTask candidate : api.candidates(task, satellite, budget)) {
      budget.check();
      int at = position(sorted, candidate);
      ScheduledTask before = at == 0 ? null : sorted.get(at - 1);
      ScheduledTask after = at == sorted.size() ? null : sorted.get(at);
      if (!fits(before, candidate, after)) continue;
      long totalReward = 0;
      for (int sample = 0; sample < 4; sample++) {
        budget.check();
        List<ScheduledTask> simulation = new ArrayList<>(sorted);
        simulation.add(at, candidate);
        Set<Integer> seen = new HashSet<>();
        for (int later : future) {
          budget.check();
          if (!seen.add(later) || contains(simulation, later)) continue;
          ScheduledTask selected = null;
          long count = 0;
          for (ScheduledTask option : api.candidates(later, satellite, budget)) {
            budget.check();
            int pos = position(simulation, option);
            ScheduledTask prior = pos == 0 ? null : simulation.get(pos - 1);
            ScheduledTask next = pos == simulation.size() ? null : simulation.get(pos);
            if (fits(prior, option, next) && rng.nextLong(++count) == 0) selected = option;
          }
          if (selected != null) {
            simulation.add(position(simulation, selected), selected);
            totalReward += problem.task(later).getPriority();
          }
        }
      }
      if (best == null || totalReward > bestReward || (totalReward == bestReward
          && (candidate.getEndTime() < best.getEndTime() || (candidate.getEndTime() == best.getEndTime()
              && candidate.getStartTime() < best.getStartTime())))) {
        best = candidate;
        bestReward = totalReward;
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
