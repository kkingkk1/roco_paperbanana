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

public final class B000041 {
  private B000041() { }
  public static B create() { return new DensitySearch(); }

  private static final class DensitySearch implements B {
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
}
