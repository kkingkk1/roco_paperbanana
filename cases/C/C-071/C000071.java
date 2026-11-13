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
public final class C000071 implements C {
  @Override public List<ScheduledTask> build(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      List<Integer> priorities, Budget budget, RandomGenerator rng) {
    return decode(problem, api, view.satelliteId(), new ArrayList<>(), priorities, budget, rng);
  }

  @Override public LocalResult applyLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      LocalContext context, LocalPlan plan, Budget budget, RandomGenerator rng) {
    List<ScheduledTask> schedule = new ArrayList<>(view.observations());
    Map<Integer, ScheduledTask> original = new HashMap<>();
    for (ScheduledTask observation : view.observations()) original.put(observation.getTaskId(), observation);
    schedule.removeIf(o -> plan.release().contains(o.getTaskId()));
    for (int task : plan.reconsider()) {
      budget.check();
      if (contains(schedule, task)) continue;
      ScheduledTask chosen = stableChoice(api, view.satelliteId(), task, schedule, original.get(task), budget);
      if (chosen != null) schedule.add(chosen);
    }
    schedule.sort(Comparator.comparingDouble(ScheduledTask::getStartTime));
    if (same(view.observations(), schedule)) return LocalResult.unchanged();
    budget.check();
    if (!ScheduleValidator.evaluate(problem, new SchedulingPlan(schedule)).feasible()) return LocalResult.unchanged();
    budget.check();
    return LocalResult.applied(schedule);
  }

  private static ScheduledTask stableChoice(ProblemAccess api, int satellite, int task,
      List<ScheduledTask> schedule, ScheduledTask previous, Budget budget) {
    List<ScheduledTask> sorted = new ArrayList<>(schedule);
    sorted.sort(Comparator.comparingDouble(ScheduledTask::getStartTime));
    ScheduledTask best = null;
    double bestScore = 0;
    for (ScheduledTask candidate : api.candidates(task, satellite, budget)) {
      budget.check();
      int at = position(sorted, candidate);
      ScheduledTask before = at == 0 ? null : sorted.get(at - 1);
      ScheduledTask after = at == sorted.size() ? null : sorted.get(at);
      if (!fits(before, candidate, after)) continue;
      double value = previous != null && candidate.timeWindow() == previous.timeWindow() ? 0 : 1;
      boolean sameWindow = previous != null && candidate.timeWindow() == previous.timeWindow();
      boolean bestSameWindow = previous != null && best != null && best.timeWindow() == previous.timeWindow();
      if (best == null || value < bestScore || (value == bestScore && sameWindow && !bestSameWindow)
          || (value == bestScore && sameWindow == bestSameWindow && candidate.getEndTime() < best.getEndTime())
          || (value == bestScore && sameWindow == bestSameWindow && candidate.getEndTime() == best.getEndTime()
              && candidate.getStartTime() < best.getStartTime())) {
        best = candidate;
        bestScore = value;
      }
    }
    return best;
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

      double score = c.getEndTime();
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
