import common.RandomProvider;
import main.TaskAllocationCoordinator;
import model.AttitudeRecord;
import model.Satellite;
import model.Task;
import model.TimeWindow;
import operator.assignment.MaxWindowCountStrategy;
import operator.insertion.InsertionMode;
import utils.Initialize;
import utils.AssignTaskOperator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 在来源项目的已编译类上重放两个固定夹具。 */
public final class SourceComparator {
  public static void main(String[] args) throws Exception {
    if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("task count and optional force required");
    int taskCount = Integer.parseInt(args[0]);
    if (taskCount != 6 && taskCount != 501) throw new IllegalArgumentException("expected 6 or 501 tasks");
    System.setProperty("autohyb.quiet", "true");
    RandomProvider.reset(1234L);
    List<Satellite> satellites = List.of(new Satellite(0), new Satellite(1));
    List<List<TimeWindow>> satelliteWindows = List.of(new ArrayList<>(), new ArrayList<>());
    List<Task> tasks = new ArrayList<>();
    for (int id = 0; id < taskCount; id++) {
      Task task = new Task(id, 0, 0, id < 6 ? 5 + id : 1, 2);
      if (id < 6) for (int sat = 0; sat < 2; sat++) {
        int start = 10 + id / 3 * 15;
        TimeWindow window = new TimeWindow(sat, id, 2026, 1, 1, 0, 0, start - .25,
            2026, 1, 1, 0, 0, start + 2.25);
        window.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, start - .25, 0, 0));
        for (int k = 0; k <= 2; k++) {
          window.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, start + k, 0, 0));
        }
        window.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, start + 2.25, 0, 0));
        task.addTimeWindowsForSatellite(sat, List.of(window));
        satelliteWindows.get(sat).add(window);
      }
      tasks.add(task);
    }
    for (int sat = 0; sat < 2; sat++) satellites.get(sat).setTimeWindows(satelliteWindows.get(sat));
    int assignmentStrategy = taskCount <= 500 ? 7 : 8;
    var pairs = Initialize.initializeWithStrategy(tasks, satellites, assignmentStrategy);
    if (args.length == 2) {
      if (!args[1].equals("force")) throw new IllegalArgumentException("unknown mode");
      pairs = new ArrayList<>();
      for (int id = 0; id < 6; id++) pairs.add(List.of(0, id));
    }
    AssignTaskOperator.assignTaskToSatellites(pairs, tasks, satellites);
    Map<Integer, Integer> initial = new LinkedHashMap<>();
    for (var pair : pairs) initial.put(pair.get(1), pair.get(0));
    var config = new TaskAllocationCoordinator.HybCecConfig(new int[]{4, 1}, new int[]{0, 0},
        0, 0, 0, 6, 30, InsertionMode.GREEDY, 1234L);
    var coordinator = new TaskAllocationCoordinator(tasks, satellites, new MaxWindowCountStrategy(), 3, 2, .1);
    var plans = coordinator.scheduleWithReassignment(config, satellites, initial);
    var observations = plans.values().stream().flatMap(plan -> plan.getSchedule().stream())
        .map(o -> o.getTaskId() + ":" + o.getSatelliteId() + ":" + o.getStartTime() + ":" + o.getEndTime())
        .sorted().toList();
    System.out.println("tasks=" + taskCount);
    System.out.println("mode=" + (args.length == 2 ? "forced" : "source-initialization"));
    System.out.println("initial=" + new TreeMap<>(initial));
    System.out.println("owners=" + new TreeMap<>(coordinator.getBestTaskAssignmentSnapshot()));
    System.out.println("profit=" + plans.values().stream().mapToInt(plan -> plan.getTotalProfit()).sum());
    System.out.println("history=" + coordinator.getStrategyHistorySnapshot());
    System.out.println("observations=" + observations);
  }
}
