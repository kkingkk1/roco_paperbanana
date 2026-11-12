package method.runtime;
import java.util.*;
import method.runtime.SolverContracts.Assignment;
import model.ProblemInstance;
import model.Task;
import model.TimeWindow;
/** 项目自主设计：按当前星的窗口冲突任务数和负载分配任务。 */
public final class A000016 {
  public static Assignment assign(ProblemInstance problem, ProblemAccess api, Budget budget) {
    Map<Integer,Integer> owners=new LinkedHashMap<>();
    Map<Integer,List<Integer>> assigned=new HashMap<>();
    Map<Integer,Long> load=new HashMap<>();
    List<Task> ordered=problem.tasks().stream()
        .sorted(Comparator.comparingInt(Task::getPriority).reversed().thenComparingInt(Task::getId)).toList();
    for (Task task:ordered) {
      budget.check();
      Integer best=null;
      int bestConflicts=Integer.MAX_VALUE;
      long bestLoad=Long.MAX_VALUE;
      for (int sat:api.satelliteIds()) {
        budget.check();
        if (!api.hasCandidate(task.getId(),sat,budget)) continue;
        int conflicts=0;
        for (int other:assigned.getOrDefault(sat,List.of())) {
          budget.check();
          if (overlap(api.windows(task.getId(),sat),api.windows(other,sat),budget)) conflicts++;
        }
        long current=load.getOrDefault(sat,0L);
        if (best==null || conflicts<bestConflicts ||
            (conflicts==bestConflicts && current<bestLoad) ||
            (conflicts==bestConflicts && current==bestLoad && sat<best)) {
          best=sat; bestConflicts=conflicts; bestLoad=current;
        }
      }
      owners.put(task.getId(),best);
      if (best!=null) {
        assigned.computeIfAbsent(best,key->new ArrayList<>()).add(task.getId());
        load.merge(best,(long)task.getImagingDuration(),Long::sum);
      }
    }
    return new Assignment(owners);
  }
  private static boolean overlap(List<TimeWindow> left,List<TimeWindow> right,Budget budget) {
    for (TimeWindow a:left) for (TimeWindow b:right) {
      budget.check();
      if (a.getStartTime()<b.getEndTime() && b.getStartTime()<a.getEndTime()) return true;
    }
    return false;
  }
}
