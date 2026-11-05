package audit;

import model.AttitudeRecord;
import model.ProblemInstance;
import model.ScheduledTask;
import model.SchedulingPlan;
import model.TimeWindow;
import problem.ScheduleValidator;
import utils.DataLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 从命令行输出重新定位原始对象，并独立验证完整调度。 */
public final class P1SolverCheck {
  private static final Pattern HEADER = Pattern.compile(
      "status=SUCCESS profit=(\\d+) observations=(\\d+) elapsedMs=([0-9.]+) detail=(\\S+)");
  private static final Pattern OBSERVATION = Pattern.compile(
      "task=(\\d+) satellite=(\\d+) window=(\\d+) start=(\\S+) end=(\\S+)");
  private static final Pattern CSV_OBSERVATION = Pattern.compile("(\\d+),(\\d+),(\\d+),([^,]+),([^,]+)");

  private P1SolverCheck() { }

  public static void main(String[] args) throws Exception {
    if (args.length < 4 || args.length > 5) {
      throw new IllegalArgumentException("Usage: P1SolverCheck <scene> <tasks> <satellites> <output-or-csv> [profit]");
    }
    var data = DataLoader.loadAllData(args[0], Integer.parseInt(args[1]), Integer.parseInt(args[2]));
    var problem = new ProblemInstance(data.getTaskList(), data.getSatelliteList());
    List<String> lines = Files.readAllLines(Path.of(args[3]), StandardCharsets.UTF_8);
    if (lines.isEmpty()) throw new IllegalArgumentException("empty solver output");
    boolean csv = lines.getFirst().equals("task_id,satellite_id,window_index,start_time,end_time");
    var header = HEADER.matcher(lines.getFirst());
    if (!csv && !header.matches()) throw new IllegalArgumentException("invalid result header");
    if (csv && args.length != 5) throw new IllegalArgumentException("CSV requires the expected profit");
    long expectedProfit = csv ? Long.parseLong(args[4]) : Long.parseLong(header.group(1));
    int expectedCount = csv ? lines.size() - 1 : Integer.parseInt(header.group(2));
    List<ScheduledTask> observations = new ArrayList<>();
    for (int i = 1; i < lines.size(); i++) {
      var match = (csv ? CSV_OBSERVATION : OBSERVATION).matcher(lines.get(i));
      if (!match.matches()) throw new IllegalArgumentException("invalid observation line " + (i + 1));
      int taskId = Integer.parseInt(match.group(1));
      int satelliteId = Integer.parseInt(match.group(2));
      TimeWindow window = problem.satellite(satelliteId).getTimeWindows().get(Integer.parseInt(match.group(3)));
      AttitudeRecord start = find(window, Double.parseDouble(match.group(4)));
      AttitudeRecord end = find(window, Double.parseDouble(match.group(5)));
      observations.add(new ScheduledTask(problem.task(taskId), window, start, end));
    }
    if (observations.size() != expectedCount) throw new IllegalStateException("observation count mismatch");
    var result = ScheduleValidator.evaluate(problem, new SchedulingPlan(observations));
    if (!result.feasible() || result.totalProfit() != expectedProfit) {
      throw new IllegalStateException("independent validation failed: " + result.violations());
    }
    System.out.printf("feasible=%s profit=%d observations=%d%n", result.feasible(),
        result.totalProfit(), observations.size());
  }

  private static AttitudeRecord find(TimeWindow window, double time) {
    return window.getAttitudeRecords().stream()
        .filter(record -> Double.doubleToLongBits(record.getTotalSeconds()) == Double.doubleToLongBits(time))
        .findFirst().orElseThrow(() -> new IllegalArgumentException("time is not a raw attitude record"));
  }
}
