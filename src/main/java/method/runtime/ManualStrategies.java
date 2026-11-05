package method.runtime;

import method.runtime.SolverContracts.A;
import method.runtime.SolverContracts.Assignment;
import method.runtime.SolverContracts.B;
import method.runtime.SolverContracts.BrokenEdge;
import method.runtime.SolverContracts.C;
import method.runtime.SolverContracts.Feedback;
import method.runtime.SolverContracts.LocalContext;
import method.runtime.SolverContracts.LocalPlan;
import method.runtime.SolverContracts.LocalResult;
import method.runtime.SolverContracts.Move;
import method.runtime.SolverContracts.SatelliteView;
import method.runtime.SolverContracts.State;
import model.ProblemInstance;
import model.ScheduledTask;
import model.SchedulingPlan;
import model.Task;
import problem.ScheduleValidator;
import utils.TimeUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

/** 与两阶段接口匹配的六个手工种子。 */
public final class ManualStrategies {
  private ManualStrategies() { }

  public record Combination(String name, A a, B b, C c) { }

  public static List<Combination> combinations() {
    List<Combination> result = new ArrayList<>();
    for (int a = 1; a <= 2; a++) {
      for (int b = 1; b <= 2; b++) {
        for (int c = 1; c <= 2; c++) {
          result.add(new Combination("A" + a + "B" + b + "C" + c,
              a == 1 ? allocationByCandidates() : allocationByScarcityAndLoad(),
              b == 1 ? priorityByDensity() : priorityByScarcity(), new Decoder(c == 2)));
        }
      }
    }
    return List.copyOf(result);
  }

  public static A allocationByCandidates() { return new CandidateAllocation(); }
  public static A allocationByScarcityAndLoad() { return new ScarcityAllocation(); }
  public static B priorityByDensity() { return new DensitySearch(); }
  public static B priorityByScarcity() { return new ScarcitySearch(); }
  public static C earliestFinish() { return new Decoder(false); }
  public static C minimumAddedTransition() { return new Decoder(true); }

  private static final class CandidateAllocation implements A {
    private String cursorHash;
    private List<Integer> pending;
    private List<Integer> tasks;
    private List<Integer> satellites;
    private int pendingIndex, satelliteIndex, left, right;

    @Override public Assignment initialAssign(ProblemInstance problem, ProblemAccess api, Budget budget,
                                               RandomGenerator rng) {
      cursorHash = null;
      Map<Integer, Integer> owners = new LinkedHashMap<>();
      List<Integer> ids = problem.tasks().stream().map(Task::getId).sorted().toList();
      Map<Integer, Double> load = new HashMap<>();
      for (int task : ids) {
        budget.check();
        Integer best = null;
        long bestCount = -1;
        double bestLoad = Double.POSITIVE_INFINITY;
        for (int sat : api.satelliteIds()) {
          budget.check();
          if (!api.hasCandidate(task, sat, budget)) continue;
          long count = api.candidateCount(task, sat, budget);
          double satelliteLoad = load.getOrDefault(sat, 0.0);
          if (best == null || count > bestCount || (count == bestCount && satelliteLoad < bestLoad)
              || (count == bestCount && satelliteLoad == bestLoad && sat < best)) {
            best = sat;
            bestCount = count;
            bestLoad = satelliteLoad;
          }
        }
        owners.put(task, best);
        if (best != null) load.merge(best, (double) problem.task(task).getImagingDuration(), Double::sum);
      }
      return new Assignment(owners);
    }

    @Override public Move proposeMove(ProblemInstance problem, ProblemAccess api, State state, Feedback feedback,
                                      Budget budget, RandomGenerator rng) {
      if (!state.hash().equals(cursorHash)) {
        cursorHash = state.hash();
        Set<Integer> scheduled = new HashSet<>();
        state.timelines().values().forEach(list -> list.forEach(o -> scheduled.add(o.getTaskId())));
        tasks = state.assignment().owners().keySet().stream().sorted().toList();
        pending = tasks.stream().filter(id -> !scheduled.contains(id))
            .sorted(Comparator.comparingInt((Integer id) -> problem.task(id).getPriority()).reversed()
                .thenComparingInt(Integer::intValue)).toList();
        satellites = api.satelliteIds();
        pendingIndex = 0;
        satelliteIndex = 0;
        left = 0;
        right = 1;
      }
      while (pendingIndex < pending.size()) {
        budget.check();
        int task = pending.get(pendingIndex);
        if (satelliteIndex >= satellites.size()) {
          pendingIndex++;
          satelliteIndex = 0;
          continue;
        }
        int target = satellites.get(satelliteIndex++);
        if (!Objects.equals(state.assignment().owners().get(task), target)
            && api.hasCandidate(task, target, budget)) {
          return new Move(state.hash(), Map.of(task, target));
        }
      }
      while (left + 1 < tasks.size()) {
        budget.check();
        if (right >= tasks.size()) {
          left++;
          right = left + 1;
          continue;
        }
        int first = tasks.get(left);
        int second = tasks.get(right++);
        Integer owner1 = state.assignment().owners().get(first);
        Integer owner2 = state.assignment().owners().get(second);
        if (owner1 != null && owner2 != null && !owner1.equals(owner2)
            && api.hasCandidate(first, owner2, budget) && api.hasCandidate(second, owner1, budget)) {
          return new Move(state.hash(), Map.of(first, owner2, second, owner1));
        }
      }
      return null;
    }
  }

  private static final class ScarcityAllocation implements A {
    private String cursorHash;
    private List<Integer> pending, tasks, satellites;
    private int pendingIndex, satelliteIndex, left, right;

    @Override public Assignment initialAssign(ProblemInstance problem, ProblemAccess api, Budget budget,
                                               RandomGenerator rng) {
      cursorHash = null;
      List<Integer> ids = problem.tasks().stream().map(Task::getId).sorted().toList();
      Map<Integer, Integer> owners = new LinkedHashMap<>();
      Map<Integer, List<Integer>> eligible = new HashMap<>();
      for (int task : ids) {
        budget.check();
        owners.put(task, null);
        List<Integer> choices = new ArrayList<>();
        for (int sat : api.satelliteIds()) if (api.hasCandidate(task, sat, budget)) choices.add(sat);
        eligible.put(task, choices);
      }
      List<Integer> order = new ArrayList<>(ids);
      order.sort(Comparator.comparingInt((Integer id) -> eligible.get(id).size())
          .thenComparingInt(Integer::intValue));
      Map<Integer, Double> load = new HashMap<>();
      for (int task : order) {
        budget.check();
        Integer best = null;
        double bestLoad = Double.POSITIVE_INFINITY;
        for (int sat : eligible.get(task)) {
          budget.check();
          double normalized = (load.getOrDefault(sat, 0.0) + problem.task(task).getImagingDuration())
              / Math.max(1e-12, api.capacity(sat, budget));
          if (best == null || normalized < bestLoad || (normalized == bestLoad && sat < best)) {
            best = sat;
            bestLoad = normalized;
          }
        }
        owners.put(task, best);
        if (best != null) load.merge(best, (double) problem.task(task).getImagingDuration(), Double::sum);
      }
      return new Assignment(owners);
    }

    @Override public Move proposeMove(ProblemInstance problem, ProblemAccess api, State state, Feedback feedback,
                                      Budget budget, RandomGenerator rng) {
      if (!state.hash().equals(cursorHash)) {
        cursorHash = state.hash();
        Set<Integer> scheduled = new HashSet<>();
        state.timelines().values().forEach(list -> list.forEach(o -> scheduled.add(o.getTaskId())));
        tasks = state.assignment().owners().keySet().stream()
            .sorted(Comparator.comparingInt((Integer id) -> problem.task(id).getPriority()).reversed()
                .thenComparingInt(Integer::intValue)).toList();
        pending = new ArrayList<>(tasks.stream().filter(id -> !scheduled.contains(id)).toList());
        List<Integer> ids = api.satelliteIds();
        Map<Integer, Integer> alternatives = new HashMap<>();
        for (int task : pending) {
          int count = 0;
          for (int sat : ids) {
            budget.check();
            if (!Objects.equals(state.assignment().owners().get(task), sat)
                && api.hasCandidate(task, sat, budget)) count++;
          }
          alternatives.put(task, count);
        }
        pending.sort(Comparator.comparingInt((Integer id) -> alternatives.get(id))
            .thenComparing(Comparator.comparingInt((Integer id) -> problem.task(id).getPriority()).reversed())
            .thenComparingInt(Integer::intValue));
        Map<Integer, Double> normalized = new HashMap<>();
        for (int sat : ids) {
          budget.check();
          double occupied = state.timelines().getOrDefault(sat, List.of()).stream()
              .mapToDouble(o -> o.getEndTime() - o.getStartTime()).sum();
          normalized.put(sat, occupied / Math.max(1e-12, api.capacity(sat, budget)));
        }
        satellites = ids.stream().sorted(Comparator.comparingDouble((Integer sat) -> normalized.get(sat))
            .thenComparingInt(Integer::intValue)).toList();
        pendingIndex = 0;
        satelliteIndex = 0;
        left = 0;
        right = 1;
      }
      while (pendingIndex < pending.size()) {
        budget.check();
        if (satelliteIndex >= satellites.size()) {
          pendingIndex++;
          satelliteIndex = 0;
          continue;
        }
        int task = pending.get(pendingIndex);
        int target = satellites.get(satelliteIndex++);
        if (!Objects.equals(state.assignment().owners().get(task), target)
            && api.hasCandidate(task, target, budget)) return new Move(state.hash(), Map.of(task, target));
      }
      while (left + 1 < tasks.size()) {
        budget.check();
        if (right >= tasks.size()) {
          left++;
          right = left + 1;
          continue;
        }
        int first = tasks.get(left), second = tasks.get(right++);
        Integer owner1 = state.assignment().owners().get(first), owner2 = state.assignment().owners().get(second);
        if (owner1 != null && owner2 != null && !owner1.equals(owner2)
            && api.hasCandidate(first, owner2, budget) && api.hasCandidate(second, owner1, budget)) {
          return new Move(state.hash(), Map.of(first, owner2, second, owner1));
        }
      }
      return null;
    }
  }

  private static final class DensitySearch implements B {
    @Override public List<Integer> initialPriority(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                                   Budget budget, RandomGenerator rng) {
      Map<Integer, Double> density = new HashMap<>();
      for (int id : view.assignedTaskIds()) {
        budget.check();
        Task task = problem.task(id);
        density.put(id, (double) task.getPriority() / task.getImagingDuration());
      }
      return view.assignedTaskIds().stream()
          .sorted(Comparator.comparingDouble((Integer id) -> density.get(id)).reversed()
              .thenComparingInt(Integer::intValue)).toList();
    }

    @Override public LocalPlan proposeInitialLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                                    LocalContext context, Feedback feedback, Budget budget,
                                                    RandomGenerator rng) {
      Set<Integer> present = new HashSet<>();
      view.observations().forEach(o -> present.add(o.getTaskId()));
      List<Integer> missing = new ArrayList<>(view.assignedTaskIds());
      missing.removeAll(present);
      missing.sort(Comparator.comparingDouble((Integer id) ->
          (double) problem.task(id).getPriority() / problem.task(id).getImagingDuration()).reversed()
          .thenComparingInt(Integer::intValue));
      List<Integer> existing = present.stream().sorted(Comparator.comparingInt((Integer id) -> problem.task(id).getPriority())
          .thenComparingInt(Integer::intValue)).toList();
      int ordinal = 0;
      for (int task : missing) {
        budget.check();
        if (ordinal++ == feedback.attempt()) return new LocalPlan(view.hash(), Set.of(), List.of(task));
        for (int removed : existing) {
          budget.check();
          if (problem.task(removed).getPriority() >= problem.task(task).getPriority()) continue;
          if (ordinal++ == feedback.attempt()) return new LocalPlan(view.hash(), Set.of(removed), List.of(task, removed));
        }
      }
      for (int task : existing) {
        budget.check();
        if (ordinal++ == feedback.attempt()) return new LocalPlan(view.hash(), Set.of(task), List.of(task));
      }
      for (int i = 0; i < existing.size(); i++) for (int j = i + 1; j < existing.size(); j++) {
        budget.check();
        int a = existing.get(i), b = existing.get(j);
        if (ordinal++ == feedback.attempt()) return new LocalPlan(view.hash(), Set.of(a, b), List.of(b, a));
      }
      return null;
    }

    @Override public LocalPlan proposeReassignmentLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                                         LocalContext context, Feedback feedback, Budget budget,
                                                         RandomGenerator rng) {
      Set<Integer> present = new HashSet<>();
      view.observations().forEach(o -> present.add(o.getTaskId()));
      List<Integer> pending = new ArrayList<>(view.assignedTaskIds());
      pending.removeAll(present);
      pending.sort(Comparator.comparing((Integer id) -> !context.incoming().contains(id))
          .thenComparing(Comparator.comparingInt((Integer id) -> problem.task(id).getPriority()).reversed())
          .thenComparingInt(Integer::intValue));
      List<Integer> existing = present.stream().sorted(Comparator.comparingInt((Integer id) -> problem.task(id).getPriority())
          .thenComparingInt(Integer::intValue)).toList();
      int ordinal = 0;
      if (!context.brokenEdges().isEmpty()) {
        Set<Integer> release = new LinkedHashSet<>();
        for (BrokenEdge edge : context.brokenEdges()) {
          int a = edge.previousTaskId(), b = edge.nextTaskId();
          release.add(problem.task(a).getPriority() <= problem.task(b).getPriority() ? a : b);
        }
        List<Integer> reconsider = new ArrayList<>();
        for (int id : pending) if (context.incoming().contains(id)) reconsider.add(id);
        reconsider.addAll(release);
        if (ordinal++ == feedback.attempt()) return new LocalPlan(view.hash(), release, reconsider);
      }
      for (int task : pending) {
        budget.check();
        if (ordinal++ == feedback.attempt()) return new LocalPlan(view.hash(), Set.of(), List.of(task));
        for (int removed : existing) {
          budget.check();
          if (problem.task(removed).getPriority() >= problem.task(task).getPriority()) continue;
          if (ordinal++ == feedback.attempt()) return new LocalPlan(view.hash(), Set.of(removed), List.of(task, removed));
        }
      }
      for (int task : existing) {
        budget.check();
        if (ordinal++ == feedback.attempt()) return new LocalPlan(view.hash(), Set.of(task), List.of(task));
      }
      for (int i = 0; i < existing.size(); i++) for (int j = i + 1; j < existing.size(); j++) {
        budget.check();
        int a = existing.get(i), b = existing.get(j);
        if (ordinal++ == feedback.attempt()) return new LocalPlan(view.hash(), Set.of(a, b), List.of(b, a));
      }
      return null;
    }
  }

  private static final class ScarcitySearch implements B {
    @Override public List<Integer> initialPriority(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                                   Budget budget, RandomGenerator rng) {
      Map<Integer, Double> score = new HashMap<>();
      for (int id : view.assignedTaskIds()) {
        budget.check();
        Task task = problem.task(id);
        score.put(id, ((double) task.getPriority() / task.getImagingDuration())
            / (1 + Math.log1p(api.candidateCount(id, view.satelliteId(), budget))));
      }
      return view.assignedTaskIds().stream()
          .sorted(Comparator.comparingDouble((Integer id) -> score.get(id)).reversed()
              .thenComparingInt(Integer::intValue)).toList();
    }

    @Override public LocalPlan proposeInitialLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                                    LocalContext context, Feedback feedback, Budget budget,
                                                    RandomGenerator rng) {
      Set<Integer> present = new HashSet<>();
      view.observations().forEach(o -> present.add(o.getTaskId()));
      List<Integer> missing = new ArrayList<>(view.assignedTaskIds());
      missing.removeAll(present);
      Map<Integer, Long> choices = new HashMap<>();
      for (int id : missing) choices.put(id, api.candidateCount(id, view.satelliteId(), budget));
      missing.sort(Comparator.comparingLong((Integer id) -> choices.get(id))
          .thenComparing(Comparator.comparingInt((Integer id) -> problem.task(id).getPriority()).reversed())
          .thenComparingInt(Integer::intValue));
      List<Integer> existing = present.stream().sorted(Comparator.comparingInt((Integer id) -> problem.task(id).getPriority())
          .thenComparingInt(Integer::intValue)).toList();
      int ordinal = 0;
      for (int task : missing) {
        budget.check();
        if (ordinal++ == feedback.attempt()) return new LocalPlan(view.hash(), Set.of(), List.of(task));
        for (int removed : existing) {
          budget.check();
          if (problem.task(removed).getPriority() >= problem.task(task).getPriority()) continue;
          if (ordinal++ == feedback.attempt()) return new LocalPlan(view.hash(), Set.of(removed), List.of(task, removed));
        }
      }
      for (int i = 0; i < existing.size(); i++) for (int j = i + 1; j < existing.size(); j++) {
        budget.check();
        int a = existing.get(i), b = existing.get(j);
        if (ordinal++ == feedback.attempt()) return new LocalPlan(view.hash(), Set.of(a, b), List.of(b, a));
      }
      for (int task : existing) {
        budget.check();
        if (ordinal++ == feedback.attempt()) return new LocalPlan(view.hash(), Set.of(task), List.of(task));
      }
      return null;
    }

    @Override public LocalPlan proposeReassignmentLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                                         LocalContext context, Feedback feedback, Budget budget,
                                                         RandomGenerator rng) {
      Set<Integer> present = new HashSet<>();
      view.observations().forEach(o -> present.add(o.getTaskId()));
      List<Integer> pending = new ArrayList<>(view.assignedTaskIds());
      pending.removeAll(present);
      Map<Integer, Long> choices = new HashMap<>();
      for (int id : view.assignedTaskIds()) choices.put(id, api.candidateCount(id, view.satelliteId(), budget));
      pending.sort(Comparator.comparing((Integer id) -> !context.incoming().contains(id))
          .thenComparingLong(id -> choices.get(id))
          .thenComparing(Comparator.comparingInt((Integer id) -> problem.task(id).getPriority()).reversed())
          .thenComparingInt(Integer::intValue));
      List<Integer> existing = present.stream()
          .sorted(Comparator.comparingLong((Integer id) -> choices.get(id)).reversed()
              .thenComparingInt(id -> problem.task(id).getPriority()).thenComparingInt(Integer::intValue)).toList();
      int ordinal = 0;
      if (!context.brokenEdges().isEmpty()) {
        Set<Integer> release = new LinkedHashSet<>();
        for (BrokenEdge edge : context.brokenEdges()) {
          int a = edge.previousTaskId(), b = edge.nextTaskId();
          long first = choices.get(a), second = choices.get(b);
          release.add(first == second
              ? (problem.task(a).getPriority() <= problem.task(b).getPriority() ? a : b)
              : (first > second ? a : b));
        }
        List<Integer> reconsider = new ArrayList<>();
        for (int id : pending) if (context.incoming().contains(id)) reconsider.add(id);
        reconsider.addAll(release);
        if (ordinal++ == feedback.attempt()) return new LocalPlan(view.hash(), release, reconsider);
      }
      for (int task : pending) {
        budget.check();
        if (ordinal++ == feedback.attempt()) return new LocalPlan(view.hash(), Set.of(), List.of(task));
        for (int removed : existing) {
          budget.check();
          if (problem.task(removed).getPriority() >= problem.task(task).getPriority()) continue;
          if (ordinal++ == feedback.attempt()) return new LocalPlan(view.hash(), Set.of(removed), List.of(task, removed));
        }
      }
      for (int i = 0; i < existing.size(); i++) for (int j = i + 1; j < existing.size(); j++) {
        budget.check();
        int a = existing.get(i), b = existing.get(j);
        if (ordinal++ == feedback.attempt()) return new LocalPlan(view.hash(), Set.of(a, b), List.of(b, a));
      }
      for (int task : existing) {
        budget.check();
        if (ordinal++ == feedback.attempt()) return new LocalPlan(view.hash(), Set.of(task), List.of(task));
      }
      return null;
    }
  }

  private static final class Decoder implements C {
    private final boolean minimizeTransition;
    private Decoder(boolean minimizeTransition) { this.minimizeTransition = minimizeTransition; }

    @Override public List<ScheduledTask> build(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                               List<Integer> priorities, Budget budget, RandomGenerator rng) {
      List<ScheduledTask> schedule = new ArrayList<>();
      for (int task : priorities) {
        budget.check();
        ScheduledTask choice = choose(api, task, view.satelliteId(), schedule, budget);
        if (choice != null) schedule.add(choice);
      }
      schedule.sort(Comparator.comparingDouble(ScheduledTask::getStartTime));
      return List.copyOf(schedule);
    }

    @Override public LocalResult applyLocal(ProblemInstance problem, ProblemAccess api, SatelliteView view,
                                            LocalContext context, LocalPlan plan, Budget budget,
                                            RandomGenerator rng) {
      List<ScheduledTask> schedule = new ArrayList<>(view.observations());
      schedule.removeIf(o -> plan.release().contains(o.getTaskId()));
      for (int task : plan.reconsider()) {
        budget.check();
        if (schedule.stream().anyMatch(o -> o.getTaskId() == task)) continue;
        ScheduledTask choice = choose(api, task, view.satelliteId(), schedule, budget);
        if (choice != null) schedule.add(choice);
      }
      schedule.sort(Comparator.comparingDouble(ScheduledTask::getStartTime));
      if (same(view.observations(), schedule)) return LocalResult.unchanged();
      budget.check();
      if (!ScheduleValidator.evaluate(problem, new SchedulingPlan(schedule)).feasible()) return LocalResult.unchanged();
      budget.check();
      return LocalResult.applied(schedule);
    }

    private ScheduledTask choose(ProblemAccess api, int task, int sat, List<ScheduledTask> schedule, Budget budget) {
      List<ScheduledTask> sorted = schedule.stream().sorted(Comparator.comparingDouble(ScheduledTask::getStartTime)).toList();
      ScheduledTask best = null;
      double bestCost = Double.POSITIVE_INFINITY;
      for (ScheduledTask candidate : api.candidates(task, sat, budget)) {
        budget.check();
        int position = 0;
        while (position < sorted.size() && sorted.get(position).getStartTime() <= candidate.getStartTime()) position++;
        ScheduledTask before = position == 0 ? null : sorted.get(position - 1);
        ScheduledTask after = position == sorted.size() ? null : sorted.get(position);
        if (before != null && (candidate.getStartTime() < before.getEndTime()
            || !TimeUtils.checkTransformationTime(before.endRecord(), candidate.startRecord()))) continue;
        if (after != null && (after.getStartTime() < candidate.getEndTime()
            || !TimeUtils.checkTransformationTime(candidate.endRecord(), after.startRecord()))) continue;
        double cost = minimizeTransition ? addedTransition(before, candidate, after) : candidate.getEndTime();
        if (best == null || cost < bestCost || (cost == bestCost && candidate.getEndTime() < best.getEndTime())
            || (cost == bestCost && candidate.getEndTime() == best.getEndTime()
                && candidate.getStartTime() < best.getStartTime())) {
          best = candidate;
          bestCost = cost;
        }
      }
      return best;
    }

    private double addedTransition(ScheduledTask before, ScheduledTask candidate, ScheduledTask after) {
      double added = 0;
      if (before != null) added += TimeUtils.calculateTransformationTime(before.endRecord(), candidate.startRecord());
      if (after != null) added += TimeUtils.calculateTransformationTime(candidate.endRecord(), after.startRecord());
      if (before != null && after != null) added -= TimeUtils.calculateTransformationTime(before.endRecord(), after.startRecord());
      return added;
    }

    private boolean same(List<ScheduledTask> left, List<ScheduledTask> right) {
      if (left.size() != right.size()) return false;
      Map<Integer, ScheduledTask> existing = new HashMap<>();
      left.forEach(o -> existing.put(o.getTaskId(), o));
      for (ScheduledTask observation : right) {
        ScheduledTask original = existing.get(observation.getTaskId());
        if (original == null || original.task() != observation.task() || original.timeWindow() != observation.timeWindow()
            || original.startRecord() != observation.startRecord() || original.endRecord() != observation.endRecord()) return false;
      }
      return true;
    }
  }
}
