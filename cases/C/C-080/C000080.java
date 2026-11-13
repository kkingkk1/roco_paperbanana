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

/** Exhaustive authorized insertion minimizing changed observations. */
public final class C000080 implements C {
  @Override public List<ScheduledTask> build(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      List<Integer> priorities, Budget budget, RandomGenerator rng) {
    List<ScheduledTask> result = search(problem, api, view.satelliteId(), List.of(), priorities, budget, Map.of());
    return result == null ? List.of() : result;
  }

  @Override public LocalResult applyLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      LocalContext context, LocalPlan plan, Budget budget, RandomGenerator rng) {
    List<ScheduledTask> base = new ArrayList<>(view.observations());
    Map<Integer, ScheduledTask> originals = new HashMap<>();
    for (ScheduledTask observation : view.observations()) {
      if (plan.release().contains(observation.getTaskId())) originals.put(observation.getTaskId(), observation);
    }
    base.removeIf(o -> plan.release().contains(o.getTaskId()));
    List<ScheduledTask> result = search(problem, api, view.satelliteId(), base, plan.reconsider(), budget, originals);
    if (result == null || same(view.observations(), result)) return LocalResult.unchanged();
    return LocalResult.applied(result);
  }

  private static List<ScheduledTask> search(ProblemInstance problem, ProblemAccess api, int satellite,
      List<ScheduledTask> base, List<Integer> order, Budget budget, Map<Integer, ScheduledTask> originals) {
    List<ScheduledTask> sorted = new ArrayList<>(base);
    sorted.sort(Comparator.comparingDouble(ScheduledTask::getStartTime));
    Search state = new Search(problem, api, satellite, order, budget, originals);
    state.visit(0, sorted);
    return state.best;
  }

  private static final class Search {
    final ProblemInstance problem;
    final ProblemAccess api;
    final int satellite;
    final List<Integer> order;
    final Budget budget;
    final Map<Integer, ScheduledTask> originals;
    List<ScheduledTask> best;
    long bestProfit;

    Search(ProblemInstance problem, ProblemAccess api, int satellite, List<Integer> order, Budget budget,
        Map<Integer, ScheduledTask> originals) {
      this.problem = problem;
      this.api = api;
      this.satellite = satellite;
      this.order = order;
      this.budget = budget;
      this.originals = originals;
    }

    void visit(int index, List<ScheduledTask> schedule) {
      budget.check();
      if (index == order.size()) {
        var evaluation = ScheduleValidator.evaluate(problem, new SchedulingPlan(schedule));
        budget.check();
        if (evaluation.feasible() && better(schedule, evaluation.totalProfit())) {
          best = List.copyOf(schedule);
          bestProfit = evaluation.totalProfit();
        }
        return;
      }
      int task = order.get(index);
      if (!contains(schedule, task)) {
        for (ScheduledTask candidate : api.candidates(task, satellite, budget)) {
          budget.check();
          int position = position(schedule, candidate);
          if (!fits(schedule, position, candidate)) continue;
          schedule.add(position, candidate);
          visit(index + 1, schedule);
          schedule.remove(position);
        }
      }
      visit(index + 1, schedule);
    }

    int changed(List<ScheduledTask> schedule) {
      int total = 0;
      for (ScheduledTask observation : schedule) {
        budget.check();
        ScheduledTask original = originals.get(observation.getTaskId());
        if (original != null && (original.timeWindow() != observation.timeWindow()
            || original.startRecord() != observation.startRecord() || original.endRecord() != observation.endRecord())) total++;
      }
      return total;
    }

    double movement(List<ScheduledTask> schedule) {
      double total = 0;
      for (ScheduledTask observation : schedule) {
        budget.check();
        ScheduledTask original = originals.get(observation.getTaskId());
        if (original != null) total += Math.abs(observation.getStartTime() - original.getStartTime());
      }
      return total;
    }

    boolean better(List<ScheduledTask> schedule, long profit) {
      if (best == null) return true;

      for (int task : order) {
        boolean candidateHas = contains(schedule, task), incumbentHas = contains(best, task);
        if (candidateHas != incumbentHas) return candidateHas;
      }
      int changes = changed(schedule), bestChanges = changed(best);
      if (changes != bestChanges) return changes < bestChanges;
      double movement = movement(schedule), bestMovement = movement(best);
      if (movement != bestMovement) return movement < bestMovement;
      for (int i = 0; i < Math.min(schedule.size(), best.size()); i++) {
        int comparison = Double.compare(schedule.get(i).getStartTime(), best.get(i).getStartTime());
        if (comparison != 0) return comparison < 0;
      }
      return false;
    }
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
      ScheduledTask prior = old.get(observation.getTaskId());
      if (prior == null || prior.task() != observation.task() || prior.timeWindow() != observation.timeWindow()
          || prior.startRecord() != observation.startRecord() || prior.endRecord() != observation.endRecord()) return false;
    }
    return true;
  }
}
