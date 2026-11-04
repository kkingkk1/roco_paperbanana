package main;

import model.ProblemInstance;
import model.SchedulingPlan;
import problem.ScheduleValidator;
import utils.DataLoader;

import java.util.List;

/** 显式读取一个场景并报告输入规模；不执行调度。 */
public final class InspectScene {
  private InspectScene() {
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException("Usage: InspectScene <scene-directory> <task-count> <satellite-count>");
    }
    DataLoader.DataSet data = DataLoader.loadAllData(args[0], Integer.parseInt(args[1]), Integer.parseInt(args[2]));
    ProblemInstance instance = new ProblemInstance(data.getTaskList(), data.getSatelliteList());
    long windows = instance.satellites().stream().mapToLong(s -> s.getTimeWindows().size()).sum();
    long records = instance.satellites().stream().flatMap(s -> s.getTimeWindows().stream())
        .mapToLong(w -> w.getAttitudeRecordCount()).sum();
    System.out.printf("tasks=%d satellites=%d windows=%d attitudeRecords=%d%n",
        instance.tasks().size(), instance.satellites().size(), windows, records);
    ScheduleValidator.Result empty = ScheduleValidator.evaluate(instance, new SchedulingPlan(List.of()));
    System.out.printf("emptyPlan.feasible=%s emptyPlan.totalProfit=%d%n", empty.feasible(), empty.totalProfit());
  }
}
