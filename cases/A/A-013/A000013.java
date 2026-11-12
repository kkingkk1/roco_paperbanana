package method.runtime;
import java.util.*;
import method.runtime.SolverContracts.Assignment;
import model.ProblemInstance;
import model.Task;
/** 项目自主设计：动态比较最优与次优卫星得分，优先处理后悔值最大的任务。 */
public final class A000013 {
  private record Choice(Task task,int satellite,double regret) { }
  public static Assignment assign(ProblemInstance problem, ProblemAccess api, Budget budget) {
    Map<Integer,Integer> owners=new LinkedHashMap<>();
    Map<Integer,Long> load=new HashMap<>();
    List<Task> remaining=new ArrayList<>(problem.tasks());
    remaining.sort(Comparator.comparingInt(Task::getId));
    while (!remaining.isEmpty()) {
      budget.check();
      Choice chosen=null;
      for (Task task:remaining) {
        double first=Double.NEGATIVE_INFINITY,second=Double.NEGATIVE_INFINITY;
        int bestSat=-1,number=0;
        for (int sat:api.satelliteIds()) {
          budget.check();
          long count=api.candidateCount(task.getId(),sat,budget);
          if (count==0) continue;
          number++;
          double score=count/(1.0+load.getOrDefault(sat,0L));
          if (bestSat<0 || score>first || (score==first && sat<bestSat)) {
            second=first; first=score; bestSat=sat;
          } else if (score>second) second=score;
        }
        if (number==0) continue;
        double regret=number==1 ? Double.POSITIVE_INFINITY : first-second;
        Choice proposal=new Choice(task,bestSat,regret);
        if (chosen==null || regret>chosen.regret() ||
            (regret==chosen.regret() && task.getPriority()>chosen.task().getPriority()) ||
            (regret==chosen.regret() && task.getPriority()==chosen.task().getPriority()
              && task.getId()<chosen.task().getId())) chosen=proposal;
      }
      if (chosen==null) {
        for (Task task:remaining) owners.put(task.getId(),null);
        break;
      }
      owners.put(chosen.task().getId(),chosen.satellite());
      load.merge(chosen.satellite(),(long)chosen.task().getImagingDuration(),Long::sum);
      remaining.remove(chosen.task());
    }
    return new Assignment(owners);
  }
}
