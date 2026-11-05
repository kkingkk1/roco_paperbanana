import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import model.AttitudeRecord;
import model.ProblemInstance;
import model.ScheduledTask;
import model.SchedulingPlan;
import model.TimeWindow;
import problem.ScheduleValidator;

/** 独立重建来源输出，用本项目问题层验证。 */
public final class VerifyComparison {
  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("scene task-count output-file");
    var fixture = ComparisonFixture.load(args[0], Integer.parseInt(args[1]));
    var problem = new ProblemInstance(fixture.tasks(), fixture.satellites());
    List<String> lines = Files.readAllLines(Path.of(args[2]));
    if (!fixture.fingerprint().equals(value(lines, "input_sha256"))) throw new IllegalStateException("input mismatch");
    String encoded = value(lines, "observations");
    if (!encoded.startsWith("[") || !encoded.endsWith("]")) throw new IllegalStateException("bad observations");
    String content = encoded.substring(1, encoded.length() - 1);
    List<ScheduledTask> observations = new ArrayList<>();
    if (!content.isEmpty()) for (String item : content.split(", ")) {
      String[] fields = item.split(":");
      if (fields.length != 4) throw new IllegalStateException("bad observation: " + item);
      int taskId = Integer.parseInt(fields[0]);
      int satelliteId = Integer.parseInt(fields[1]);
      double start = Double.parseDouble(fields[2]);
      double end = Double.parseDouble(fields[3]);
      List<ScheduledTask> matches = new ArrayList<>();
      for (TimeWindow window : problem.satellite(satelliteId).getTimeWindows()) {
        if (window.getTaskId() != taskId) continue;
        AttitudeRecord first = null, last = null;
        for (int i = 1; i < window.getAttitudeRecordCount() - 1; i++) {
          AttitudeRecord record = window.getAttitudeRecord(i);
          if (record.getTotalSeconds() == start) first = record;
          if (record.getTotalSeconds() == end) last = record;
        }
        if (first != null && last != null) matches.add(new ScheduledTask(problem.task(taskId), window, first, last));
      }
      if (matches.size() != 1) throw new IllegalStateException("ambiguous or missing raw window: " + item);
      observations.add(matches.getFirst());
    }
    var checked = ScheduleValidator.evaluate(problem, new SchedulingPlan(observations));
    long expectedProfit = Long.parseLong(value(lines, "profit"));
    if (!checked.feasible() || checked.totalProfit() != expectedProfit) {
      throw new IllegalStateException("independent validation failed: " + checked.violations());
    }
    System.out.println("file=" + args[2] + " feasible=true profit=" + checked.totalProfit()
        + " observations=" + observations.size());
  }

  private static String value(List<String> lines, String key) {
    String prefix = key + "=";
    return lines.stream().filter(line -> line.startsWith(prefix)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("missing " + key)).substring(prefix.length());
  }
}
