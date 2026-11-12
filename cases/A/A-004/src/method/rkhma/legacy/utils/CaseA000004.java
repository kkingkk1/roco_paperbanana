package method.rkhma.legacy.utils;

import java.util.*;
import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.model.TimeWindow;
import method.rkhma.legacy.operator.assignment.AssignmentStrategy;
import method.rkhma.legacy.operator.assignment.MinTaskCountStrategy;

/** 基于来源初始分配循环的独立提取，固定使用本案例策略。 */
public final class CaseA000004 {
  public static List<List<Integer>> initial(List<Task> tasks, List<Satellite> satellites) {
    List<Integer> sequence=tasks.stream().sorted(Comparator.comparingInt(Task::getPriority).reversed())
        .map(Task::getId).toList();
    AssignmentStrategy strategy=new MinTaskCountStrategy();
    Map<Integer,Integer> satelliteIndex=new HashMap<>();
    Map<Integer,Integer> initialCounts=new HashMap<>();
    for (int i=0;i<satellites.size();i++) {
      Satellite satellite=satellites.get(i);
      satelliteIndex.put(satellite.getId(),i);
      initialCounts.put(satellite.getId(),satellite.getTaskCount());
    }
    List<List<Integer>> pairs=new ArrayList<>();
    for (int taskId:sequence) {
      if (taskId<0 || taskId>=tasks.size()) continue;
      Task task=tasks.get(taskId);
      List<Satellite> available=new ArrayList<>();
      for (int j=0;j<satellites.size();j++) {
        for (TimeWindow window:task.getTimeWindowsForSatellite(j)) {
          if (window.canAccommodateTask(task.getImagingDuration())) {
            available.add(satellites.get(j));
            break;
          }
        }
      }
      if (available.isEmpty()) continue;
      Satellite selected=strategy.selectSatellite(task,available,satellites,satelliteIndex);
      if (selected!=null) {
        pairs.add(Arrays.asList(selected.getId(),taskId));
        selected.addTask(task);
      }
    }
    for (Satellite satellite:satellites) {
      int old=initialCounts.getOrDefault(satellite.getId(),0);
      List<Task> assigned=satellite.getAssignedTasks();
      while (assigned.size()>old) assigned.remove(assigned.size()-1);
    }
    return pairs;
  }
}
