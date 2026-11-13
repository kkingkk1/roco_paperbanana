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

/** Greedy insertion with minimum-loss authorized subset replacement. */
public final class C000077 implements C {
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
    List<Integer> previouslyInserted = new ArrayList<>();
    for (int task : order) {
      budget.check();
      if (contains(schedule, task)) continue;
      ScheduledTask direct = earliest(api, satellite, task, schedule, budget);
      if (direct != null) {
        schedule.add(position(schedule, direct), direct);
        previouslyInserted.add(task);
        continue;
      }
      List<ScheduledTask> repaired = repair(problem, api, satellite, task, schedule, previouslyInserted, budget);
      if (repaired != null) {
        schedule = new ArrayList<>(repaired);
        previouslyInserted.add(task);
      }
    }
    return List.copyOf(schedule);
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

  private static List<ScheduledTask> repair(ProblemInstance problem, ProblemAccess api, int satellite,
      int task, List<ScheduledTask> schedule, List<Integer> prior, Budget budget) {
    List<Integer> removable = new ArrayList<>();
    for (int id : prior) if (contains(schedule, id)) removable.add(id);
    Subset search = new Subset(problem, task, removable, budget);
    for (ScheduledTask candidate : api.candidates(task, satellite, budget)) {
      budget.check();
      search.visit(0, new ArrayList<>(schedule), candidate, 0);
    }
    return search.best;
  }

  private static final class Subset {
    final ProblemInstance problem;
    final int task;
    final List<Integer> removable;
    final Budget budget;
    List<ScheduledTask> best;
    long bestLoss = Long.MAX_VALUE;
    double bestSlew = Double.POSITIVE_INFINITY;

    Subset(ProblemInstance problem, int task, List<Integer> removable, Budget budget) {
      this.problem = problem;
      this.task = task;
      this.removable = removable;
      this.budget = budget;
    }

    void visit(int index, List<ScheduledTask> remaining, ScheduledTask candidate, long loss) {
      budget.check();
      if (loss >= problem.task(task).getPriority() || loss > bestLoss) return;
      if (index == removable.size()) {
        List<ScheduledTask> trial = new ArrayList<>(remaining);
        trial.add(position(trial, candidate), candidate);
        if (!ScheduleValidator.evaluate(problem, new SchedulingPlan(trial)).feasible()) return;
        double slew = totalSlew(trial, budget);
        if (best == null || loss < bestLoss || (loss == bestLoss && slew < bestSlew)) {
          best = List.copyOf(trial);
          bestLoss = loss;
          bestSlew = slew;
        }
        return;
      }
      visit(index + 1, remaining, candidate, loss);
      int id = removable.get(index);
      for (int i = 0; i < remaining.size(); i++) {
        if (remaining.get(i).getTaskId() == id) {
          ScheduledTask removed = remaining.remove(i);
          visit(index + 1, remaining, candidate, loss + removed.task().getPriority());
          remaining.add(i, removed);
          break;
        }
      }
    }
  }

  private static double totalSlew(List<ScheduledTask> schedule, Budget budget) {
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
