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

/** Greedy insertion with authorized cascading re-timing. */
public final class C000057 implements C {
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
    List<ScheduledTask> fixed = new ArrayList<>(schedule);
    Map<Integer, ScheduledTask> originals = new HashMap<>();
    for (ScheduledTask observation : schedule) {
      budget.check();
      if (prior.contains(observation.getTaskId())) originals.put(observation.getTaskId(), observation);
    }
    fixed.removeIf(observation -> originals.containsKey(observation.getTaskId()));
    List<Integer> sequence = new ArrayList<>();
    sequence.add(task);
    for (int id : prior) if (originals.containsKey(id)) sequence.add(id);
    Search search = new Search(problem, api, satellite, sequence, originals, budget);
    search.visit(0, fixed, 0);
    return search.best;
  }

  private static final class Search {
    final ProblemInstance problem;
    final ProblemAccess api;
    final int satellite;
    final List<Integer> order;
    final Map<Integer, ScheduledTask> originals;
    final Budget budget;
    List<ScheduledTask> best;
    double bestMovement = Double.POSITIVE_INFINITY;
    double bestSlew = Double.POSITIVE_INFINITY;

    Search(ProblemInstance problem, ProblemAccess api, int satellite, List<Integer> order,
        Map<Integer, ScheduledTask> originals, Budget budget) {
      this.problem = problem;
      this.api = api;
      this.satellite = satellite;
      this.order = order;
      this.originals = originals;
      this.budget = budget;
    }

    void visit(int index, List<ScheduledTask> schedule, double movement) {
      budget.check();
      if (best != null) return;
      if (index == order.size()) {
        if (!ScheduleValidator.evaluate(problem, new SchedulingPlan(schedule)).feasible()) return;
        budget.check();
        best = List.copyOf(schedule);
        return;
      }
      int task = order.get(index);
      for (ScheduledTask candidate : api.candidates(task, satellite, budget)) {
        budget.check();
        int position = position(schedule, candidate);
        if (!fits(schedule, position, candidate)) continue;
        schedule.add(position, candidate);
        ScheduledTask original = originals.get(task);
        double difference = original == null ? 0 : Math.abs(original.getStartTime() - candidate.getStartTime());
        visit(index + 1, schedule, movement + difference);
        schedule.remove(position);
        if (best != null) return;
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
