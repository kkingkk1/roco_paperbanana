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

public final class A000002 {
  private A000002() { }
  public static A create() { return new ScarcityAllocation(); }

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
}
