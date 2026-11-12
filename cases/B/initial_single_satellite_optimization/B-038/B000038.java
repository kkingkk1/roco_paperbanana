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

public final class B000038 {
  private B000038() { }
  public static B create() { return new DensitySearch(); }

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


  }
}
