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

/** Dynamic programming over observations in the supplied chronological task order. */
public final class C000055 implements C {
  private record State(List<ScheduledTask> schedule, ScheduledTask last) { }

  @Override public List<ScheduledTask> build(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      List<Integer> priorities, Budget budget, RandomGenerator rng) {
    List<ScheduledTask> result = search(problem, api, view.satelliteId(), List.of(), priorities, budget);
    return result == null ? List.of() : result;
  }

  @Override public LocalResult applyLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      LocalContext context, LocalPlan plan, Budget budget, RandomGenerator rng) {
    List<ScheduledTask> base = new ArrayList<>(view.observations());
    base.removeIf(o -> plan.release().contains(o.getTaskId()));
    List<ScheduledTask> result = search(problem, api, view.satelliteId(), base, plan.reconsider(), budget);
    if (result == null || same(view.observations(), result)) return LocalResult.unchanged();
    return LocalResult.applied(result);
  }

  private static List<ScheduledTask> search(ProblemInstance problem, ProblemAccess api, int satellite,
      List<ScheduledTask> base, List<Integer> order, Budget budget) {
    List<ScheduledTask> initial = new ArrayList<>(base);
    initial.sort(Comparator.comparingDouble(ScheduledTask::getStartTime));
    List<State> states = new ArrayList<>();
    states.add(new State(List.copyOf(initial), null));
    for (int task : order) {
      budget.check();
      if (contains(initial, task)) continue;
      List<State> additions = new ArrayList<>();
      for (ScheduledTask candidate : api.candidates(task, satellite, budget)) {
        budget.check();
        State best = null;
        for (State previous : states) {
          budget.check();
          if (previous.last() != null && candidate.getStartTime() < previous.last().getStartTime()) continue;
          int index = position(previous.schedule(), candidate);
          if (!fits(previous.schedule(), index, candidate)) continue;
          List<ScheduledTask> next = new ArrayList<>(previous.schedule());
          next.add(index, candidate);
          if (!prefixFeasible(next, candidate.getStartTime())) continue;
          if (best == null || better(next, best.schedule(), order)) best = new State(List.copyOf(next), candidate);
        }
        if (best != null) additions.add(best);
      }
      states.addAll(additions);
    }
    List<ScheduledTask> best = null;
    for (State state : states) {
      budget.check();
      if (ScheduleValidator.evaluate(problem, new SchedulingPlan(state.schedule())).feasible()
          && (best == null || better(state.schedule(), best, order))) best = state.schedule();
    }
    return best;
  }

  private static boolean prefixFeasible(List<ScheduledTask> schedule, double until) {
    for (int i = 1; i < schedule.size() && schedule.get(i).getStartTime() <= until; i++) {
      ScheduledTask previous = schedule.get(i - 1), next = schedule.get(i);
      if (next.getStartTime() < previous.getEndTime()
          || !TimeUtils.checkTransformationTime(previous.endRecord(), next.startRecord())) return false;
    }
    return true;
  }

  private static boolean better(List<ScheduledTask> left, List<ScheduledTask> right, List<Integer> order) {
    long l = score(left), r = score(right);
    if (l != r) return l > r;
    for (int task : order) {
      boolean lc = contains(left, task), rc = contains(right, task);
      if (lc != rc) return lc;
    }
    for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
      int comparison = Double.compare(left.get(i).getStartTime(), right.get(i).getStartTime());
      if (comparison != 0) return comparison < 0;
    }
    return false;
  }

  private static long score(List<ScheduledTask> schedule) {
    long value = 0;
    for (ScheduledTask o : schedule) value += o.task().getPriority();
    return value;
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
