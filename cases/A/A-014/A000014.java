package method.runtime;
import java.util.*;
import method.runtime.SolverContracts.Assignment;
import model.ProblemInstance;
import model.Task;
/** 项目自主设计：先用增广路径覆盖可用卫星，再按最低负载处理剩余任务。 */
public final class A000014 {
  public static Assignment assign(ProblemInstance problem, ProblemAccess api, Budget budget) {
    Map<Integer,Integer> matched=new HashMap<>();
    List<Task> ordered=problem.tasks().stream()
        .sorted(Comparator.comparingInt(Task::getPriority).reversed().thenComparingInt(Task::getId)).toList();
    for (Task task:ordered) augment(task.getId(),matched,new HashSet<>(),api,budget);
    Map<Integer,Integer> owners=new LinkedHashMap<>();
    Map<Integer,Long> load=new HashMap<>();
    for (Task task:ordered) owners.put(task.getId(),null);
    for (var e:matched.entrySet()) {
      owners.put(e.getValue(),e.getKey());
      load.merge(e.getKey(),(long)problem.task(e.getValue()).getImagingDuration(),Long::sum);
    }
    for (Task task:ordered) {
      budget.check();
      if (owners.get(task.getId())!=null) continue;
      Integer selected=null;
      for (int sat:api.satelliteIds()) {
        budget.check();
        if (!api.hasCandidate(task.getId(),sat,budget)) continue;
        if (selected==null || load.getOrDefault(sat,0L)<load.getOrDefault(selected,0L) ||
            (load.getOrDefault(sat,0L).equals(load.getOrDefault(selected,0L)) && sat<selected)) selected=sat;
      }
      if (selected!=null) {
        owners.put(task.getId(),selected);
        load.merge(selected,(long)task.getImagingDuration(),Long::sum);
      }
    }
    return new Assignment(owners);
  }
  private static boolean augment(int task,Map<Integer,Integer> matched,Set<Integer> seen,
      ProblemAccess api,Budget budget) {
    for (int sat:api.satelliteIds()) {
      budget.check();
      if (seen.contains(sat) || !api.hasCandidate(task,sat,budget)) continue;
      seen.add(sat);
      Integer old=matched.get(sat);
      if (old==null || augment(old,matched,seen,api,budget)) {
        matched.put(sat,task);
        return true;
      }
    }
    return false;
  }
}
