package method.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import method.runtime.SolverContracts.C;
import method.runtime.SolverContracts.LocalContext;
import method.runtime.SolverContracts.LocalPlan;
import method.runtime.SolverContracts.LocalResult;
import method.runtime.SolverContracts.SatelliteView;
import model.ProblemInstance;
import model.ScheduledTask;
import model.SchedulingPlan;
import problem.ScheduleValidator;
import utils.TimeUtils;

/** Greedy insertion followed by strictly improving authorized observation re-timing. */
public final class C000067 implements C {
  @Override public List<ScheduledTask> build(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      List<Integer> priorities, Budget budget, RandomGenerator rng) {
    return decode(problem, api, view.satelliteId(), List.of(), priorities, budget);
  }

  @Override public LocalResult applyLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      LocalContext context, LocalPlan plan, Budget budget, RandomGenerator rng) {
    List<ScheduledTask> base = new ArrayList<>(view.observations());
    base.removeIf(o -> plan.release().contains(o.getTaskId()));
    List<ScheduledTask> result = decode(problem, api, view.satelliteId(), base, plan.reconsider(), budget);
    if (same(view.observations(), result)) return LocalResult.unchanged();
    budget.check();
    if (!ScheduleValidator.evaluate(problem, new SchedulingPlan(result)).feasible()) return LocalResult.unchanged();
    return LocalResult.applied(result);
  }

  private static List<ScheduledTask> decode(ProblemInstance problem, ProblemAccess api, int satellite,
      List<ScheduledTask> base, List<Integer> order, Budget budget) {
    List<ScheduledTask> schedule = new ArrayList<>(base);
    schedule.sort(Comparator.comparingDouble(ScheduledTask::getStartTime));
    List<Integer> movable = new ArrayList<>();
    for (int task : order) {
      budget.check();
      if (contains(schedule, task)) continue;
      ScheduledTask direct = earliest(api, satellite, task, schedule, budget);
      if (direct != null) {
        schedule.add(position(schedule, direct), direct);
        movable.add(task);
      }
    }
    return improve(problem, api, satellite, schedule, movable, budget);
  }

  private static ScheduledTask earliest(ProblemAccess api, int satellite, int task,
      List<ScheduledTask> schedule, Budget budget) {
    ScheduledTask best = null;
    for (ScheduledTask candidate : api.candidates(task, satellite, budget)) {
      budget.check();
      int at = position(schedule, candidate);
      if (!fits(schedule, at, candidate)) continue;
      if (best == null || candidate.getEndTime() < best.getEndTime()
          || (candidate.getEndTime() == best.getEndTime() && candidate.getStartTime() < best.getStartTime())) {
        best = candidate;
      }
    }
    return best;
  }

  private static List<ScheduledTask> improve(ProblemInstance problem, ProblemAccess api, int satellite,
      List<ScheduledTask> initial, List<Integer> movable, Budget budget) {
    List<ScheduledTask> current = List.copyOf(initial);
    double currentScore = score(current, budget);
    while (true) {
      budget.check();
      List<ScheduledTask> best = null;
      double bestScore = currentScore;
      search:
      for (int task : movable) {
        budget.check();
        List<ScheduledTask> remaining = new ArrayList<>(current);
        remaining.removeIf(o -> o.getTaskId() == task);
        for (ScheduledTask candidate : api.candidates(task, satellite, budget)) {
          budget.check();
          int at = position(remaining, candidate);
          if (!fits(remaining, at, candidate)) continue;
          List<ScheduledTask> trial = new ArrayList<>(remaining);
          trial.add(at, candidate);
          if (!ScheduleValidator.evaluate(problem, new SchedulingPlan(trial)).feasible()) continue;
          double value = score(trial, budget);
          if (Double.compare(value, bestScore) < 0) {
            best = List.copyOf(trial);
            bestScore = value;

          }
        }
      }
      if (best == null) return current;
      current = best;
      currentScore = bestScore;
    }
  }

  private static double score(List<ScheduledTask> schedule, Budget budget) {
    double total = 0;
    for (int i = 1; i < schedule.size(); i++) {
      budget.check();
      total += TimeUtils.calculateTransformationTime(schedule.get(i - 1).endRecord(), schedule.get(i).startRecord());
    }
    return total;
  }

  private static int position(List<ScheduledTask> schedule, ScheduledTask candidate) {
    int index = 0;
    while (index < schedule.size() && schedule.get(index).getStartTime() <= candidate.getStartTime()) index++;
    return index;
  }

  private static boolean fits(List<ScheduledTask> schedule, int index, ScheduledTask candidate) {
    ScheduledTask before = index == 0 ? null : schedule.get(index - 1);
    ScheduledTask after = index == schedule.size() ? null : schedule.get(index);
    return (before == null || (candidate.getStartTime() >= before.getEndTime()
        && TimeUtils.checkTransformationTime(before.endRecord(), candidate.startRecord())))
        && (after == null || (after.getStartTime() >= candidate.getEndTime()
        && TimeUtils.checkTransformationTime(candidate.endRecord(), after.startRecord())));
  }

  private static boolean contains(List<ScheduledTask> schedule, int task) {
    for (ScheduledTask observation : schedule) if (observation.getTaskId() == task) return true;
    return false;
  }

  private static boolean same(List<ScheduledTask> left, List<ScheduledTask> right) {
    if (left.size() != right.size()) return false;
    Map<Integer, ScheduledTask> old = new HashMap<>();
    for (ScheduledTask observation : left) old.put(observation.getTaskId(), observation);
    for (ScheduledTask observation : right) {
      ScheduledTask previous = old.get(observation.getTaskId());
      if (previous == null || previous.task() != observation.task() || previous.timeWindow() != observation.timeWindow()
          || previous.startRecord() != observation.startRecord() || previous.endRecord() != observation.endRecord()) return false;
    }
    return true;
  }
}
