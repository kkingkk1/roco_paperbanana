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

/** Greedy insertion followed by tabu-guided authorized observation re-timing. */
public final class C000072 implements C {
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
    List<ScheduledTask> current = List.copyOf(initial), best = current;
    double bestScore = score(current, budget);
    Map<Integer, model.AttitudeRecord> tabuStart = new HashMap<>();
    Map<Integer, model.TimeWindow> tabuWindow = new HashMap<>();
    Map<Integer, Long> tabuUntil = new HashMap<>();
    long limit = 16L * movable.size();
    for (long step = 0; step < limit; step++) {
      budget.check();
      List<ScheduledTask> selected = null;
      ScheduledTask displaced = null;
      double selectedScore = Double.POSITIVE_INFINITY;
      for (int task : movable) {
        budget.check();
        ScheduledTask old = null;
        List<ScheduledTask> remaining = new ArrayList<>(current);
        for (ScheduledTask o : current) if (o.getTaskId() == task) { old = o; break; }
        if (old == null) continue;
        remaining.remove(old);
        for (ScheduledTask candidate : api.candidates(task, satellite, budget)) {
          budget.check();
          if (candidate.timeWindow() == old.timeWindow() && candidate.startRecord() == old.startRecord()
              && candidate.endRecord() == old.endRecord()) continue;
          int at = position(remaining, candidate);
          if (!fits(remaining, at, candidate)) continue;
          List<ScheduledTask> trial = new ArrayList<>(remaining);
          trial.add(at, candidate);
          if (!ScheduleValidator.evaluate(problem, new SchedulingPlan(trial)).feasible()) continue;
          double value = score(trial, budget);
          boolean tabu = tabuUntil.getOrDefault(task, -1L) > step
              && candidate.timeWindow() == tabuWindow.get(task) && candidate.startRecord() == tabuStart.get(task);
          if (tabu && value >= bestScore) continue;
          if (selected == null || value < selectedScore) {
            selected = List.copyOf(trial);
            selectedScore = value;
            displaced = old;
          }
        }
      }
      if (selected == null) break;
      tabuStart.put(displaced.getTaskId(), displaced.startRecord());
      tabuWindow.put(displaced.getTaskId(), displaced.timeWindow());
      tabuUntil.put(displaced.getTaskId(), step + 4);
      current = selected;
      if (selectedScore < bestScore) {
        best = current;
        bestScore = selectedScore;
      }
    }
    return best;
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
