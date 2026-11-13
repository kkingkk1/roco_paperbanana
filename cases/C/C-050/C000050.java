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

/** Beam search for observation choices; the beam is limited to four timelines. */
public final class C000050 implements C {
  private static final int BEAM_WIDTH = 4;

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
    List<List<ScheduledTask>> beam = new ArrayList<>();
    beam.add(initial);
    for (int task : order) {
      budget.check();
      List<List<ScheduledTask>> next = new ArrayList<>();
      for (List<ScheduledTask> timeline : beam) {
        offer(problem, order, next, timeline, budget);
        if (contains(timeline, task)) continue;
        for (ScheduledTask candidate : api.candidates(task, satellite, budget)) {
          budget.check();
          int index = position(timeline, candidate);
          if (!fits(timeline, index, candidate)) continue;
          List<ScheduledTask> changed = new ArrayList<>(timeline);
          changed.add(index, candidate);
          offer(problem, order, next, changed, budget);
        }
      }
      beam = next;
    }
    for (List<ScheduledTask> timeline : beam) {
      budget.check();
      if (ScheduleValidator.evaluate(problem, new SchedulingPlan(timeline)).feasible()) return List.copyOf(timeline);
    }
    return null;
  }

  private static int compare(ProblemInstance problem, List<Integer> order,
      List<ScheduledTask> left, List<ScheduledTask> right) {

    for (int task : order) {
      boolean l = contains(left, task), r = contains(right, task);
      if (l != r) return l ? -1 : 1;
    }
    for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
      int result = Double.compare(left.get(i).getStartTime(), right.get(i).getStartTime());
      if (result != 0) return result;
    }
    return Integer.compare(left.size(), right.size());
  }

  private static long reward(List<ScheduledTask> timeline) {
    long result = 0;
    for (ScheduledTask observation : timeline) result += observation.task().getPriority();
    return result;
  }

  private static void offer(ProblemInstance problem, List<Integer> order,
      List<List<ScheduledTask>> values, List<ScheduledTask> value, Budget budget) {
    for (List<ScheduledTask> existing : values) {
      budget.check();
      if (same(existing, value)) return;
    }
    values.add(List.copyOf(value));
    values.sort((left, right) -> { budget.check(); return compare(problem, order, left, right); });
    if (values.size() > BEAM_WIDTH) values.removeLast();
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
