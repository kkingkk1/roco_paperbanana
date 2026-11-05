package method.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import method.ProblemAdapter;
import model.AttitudeRecord;
import model.ProblemInstance;
import model.Satellite;
import model.ScheduledTask;
import model.SchedulingPlan;
import model.Task;
import model.TimeWindow;
import problem.ScheduleValidator;

/** 只枚举三个固定手工夹具；不作为通用精确求解器。 */
public final class P1EnumerationAudit {
  private static final long SEED = 42;
  private static final Set<String> COMBINATIONS = Set.of(
      "A1B1C1", "A1B1C2", "A1B2C1", "A1B2C2",
      "A2B1C1", "A2B1C2", "A2B2C1", "A2B2C2");

  private P1EnumerationAudit() { }

  private record Key(int task, int satellite, int window, int start, int end) { }

  private record Fixture(String name, ProblemInstance problem, List<Key> candidates,
                         int possible, int feasible, long optimum) { }

  public record Row(String fixture, String combination, int candidates, int possible,
                    int feasible, long optimum, String status, long profit,
                    int observations, String decisions, int reassignMoves, int acceptedMoves) { }

  private static final class Counts {
    int possible;
    int feasible;
    long optimum = -1;
  }

  public static List<Row> runAll() {
    List<Row> rows = new ArrayList<>();
    for (Fixture fixture : fixtures()) rows.addAll(run(fixture));
    require(rows.size() == 24, "expected 24 fixture/combination results");
    return List.copyOf(rows);
  }

  private static List<Row> run(Fixture fixture) {
    ProblemInstance problem = fixture.problem();
    ProblemAdapter adapter = new ProblemAdapter(problem);
    Set<Key> actual = new HashSet<>();
    int actualCount = 0;
    for (Satellite satellite : problem.satellites()) {
      for (TimeWindow window : satellite.getTimeWindows()) {
        for (ScheduledTask observation : adapter.candidates(window)) {
          actualCount++;
          require(actual.add(key(problem, observation)), fixture.name() + ": duplicate candidate");
        }
      }
    }
    Set<Key> expected = Set.copyOf(fixture.candidates());
    require(expected.size() == fixture.candidates().size(), fixture.name() + ": duplicate manual key");
    require(actualCount == expected.size() && actual.equals(expected),
        fixture.name() + ": candidate mismatch; expected=" + expected + ", actual=" + actual);

    List<List<ScheduledTask>> options = new ArrayList<>();
    for (Task task : problem.tasks()) {
      List<ScheduledTask> choices = new ArrayList<>();
      for (Key candidate : fixture.candidates()) {
        if (candidate.task() == task.getId()) choices.add(observation(problem, candidate));
      }
      options.add(List.copyOf(choices));
    }
    Counts counts = new Counts();
    enumerate(problem, options, 0, new ArrayList<>(), counts);
    require(counts.possible == fixture.possible(), fixture.name() + ": unexpected plan count " + counts.possible);
    require(counts.feasible == fixture.feasible(), fixture.name() + ": unexpected feasible count " + counts.feasible);
    require(counts.optimum == fixture.optimum(), fixture.name() + ": unexpected optimum " + counts.optimum);

    List<Row> rows = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (var combination : ManualStrategies.combinations()) {
      require(names.add(combination.name()), "duplicate combination " + combination.name());
      var solver = new TwoStageSolver(TwoStageSolver.Limits.defaults(), () -> 0L);
      var result = solver.solve(problem, combination.a(), combination.b(), combination.c(), SEED);
      require(result.status() == TwoStageSolver.Status.SUCCESS,
          fixture.name() + "/" + combination.name() + ": " + result.detail());
      List<ScheduledTask> observations = result.plan().observations();
      Set<Integer> seen = new HashSet<>();
      for (ScheduledTask observation : observations) {
        require(expected.contains(key(problem, observation)), fixture.name() + ": output outside manual candidates");
        require(seen.add(observation.getTaskId()), fixture.name() + ": repeated task");
      }
      require(independentlyFeasible(observations), fixture.name() + ": independently infeasible output");
      long profit = observations.stream().mapToLong(o -> o.task().getPriority()).sum();
      var checked = ScheduleValidator.evaluate(problem, new SchedulingPlan(observations));
      require(checked.feasible() && checked.totalProfit() == profit && result.totalProfit() == profit,
          fixture.name() + ": output validator or profit mismatch");
      require(profit <= counts.optimum, fixture.name() + ": profit exceeds enumerated optimum");
      String decisions = observations.stream()
          .sorted(Comparator.comparingInt(ScheduledTask::getTaskId))
          .map(o -> String.format(Locale.ROOT, "%d@%d:%.2f:%.2f", o.getTaskId(),
              o.getSatelliteId(), o.getStartTime(), o.getEndTime()))
          .reduce((a, b) -> a + "|" + b).orElse("");
      rows.add(new Row(fixture.name(), combination.name(), expected.size(), counts.possible,
          counts.feasible, counts.optimum, result.status().name(), profit, observations.size(), decisions,
          result.statistics().reassignMoves(), result.statistics().acceptedMoves()));
    }
    require(names.equals(COMBINATIONS), fixture.name() + ": incomplete combinations " + names);
    return rows;
  }

  private static void enumerate(ProblemInstance problem, List<List<ScheduledTask>> options, int taskIndex,
                                List<ScheduledTask> partial, Counts counts) {
    if (taskIndex == options.size()) {
      counts.possible++;
      boolean independent = independentlyFeasible(partial);
      var validated = ScheduleValidator.evaluate(problem, new SchedulingPlan(partial));
      require(independent == validated.feasible(), "validator disagrees on " + partial);
      if (independent) {
        long profit = partial.stream().mapToLong(o -> o.task().getPriority()).sum();
        require(validated.totalProfit() == profit, "validator profit mismatch on " + partial);
        counts.feasible++;
        counts.optimum = Math.max(counts.optimum, profit);
      }
      return;
    }
    enumerate(problem, options, taskIndex + 1, partial, counts);
    for (ScheduledTask candidate : options.get(taskIndex)) {
      partial.add(candidate);
      enumerate(problem, options, taskIndex + 1, partial, counts);
      partial.removeLast();
    }
  }

  /** 夹具仅用0°和30°；分别按问题合同手算转换时间11.66秒和25秒。 */
  private static boolean independentlyFeasible(List<ScheduledTask> observations) {
    Set<Integer> seen = new HashSet<>();
    for (ScheduledTask observation : observations) {
      if (!seen.add(observation.getTaskId())) return false;
    }
    for (int satellite : observations.stream().map(ScheduledTask::getSatelliteId).distinct().toList()) {
      var ordered = observations.stream().filter(o -> o.getSatelliteId() == satellite)
          .sorted(Comparator.comparingDouble(ScheduledTask::getStartTime)).toList();
      for (int i = 1; i < ordered.size(); i++) {
        var before = ordered.get(i - 1);
        var after = ordered.get(i);
        double angle = Math.abs(before.endRecord().getRollAngle() - after.startRecord().getRollAngle())
            + Math.abs(before.endRecord().getPitchAngle() - after.startRecord().getPitchAngle());
        double required;
        if (angle == 0) required = 11.66;
        else if (angle == 30) required = 25;
        else throw new IllegalStateException("fixture uses unexpected angle " + angle);
        if (after.getStartTime() - before.getEndTime() < required) return false;
      }
    }
    return true;
  }

  private static Key key(ProblemInstance problem, ScheduledTask observation) {
    require(problem.task(observation.getTaskId()) == observation.task(), "task identity mismatch");
    Satellite satellite = problem.satellite(observation.getSatelliteId());
    require(satellite != null, "unknown satellite");
    int window = identityIndex(satellite.getTimeWindows(), observation.timeWindow());
    require(window >= 0, "window identity mismatch");
    int start = identityIndex(observation.timeWindow().getAttitudeRecords(), observation.startRecord());
    int end = identityIndex(observation.timeWindow().getAttitudeRecords(), observation.endRecord());
    return new Key(observation.getTaskId(), observation.getSatelliteId(), window, start, end);
  }

  private static <T> int identityIndex(List<T> list, T value) {
    for (int i = 0; i < list.size(); i++) if (list.get(i) == value) return i;
    return -1;
  }

  private static ScheduledTask observation(ProblemInstance problem, Key key) {
    Task task = problem.task(key.task());
    TimeWindow window = problem.satellite(key.satellite()).getTimeWindows().get(key.window());
    require(task != null && window.getTaskId() == key.task(), "invalid manual candidate");
    require(key.start() > 0 && key.end() < window.getAttitudeRecordCount() - 1 && key.end() > key.start(),
        "manual candidate outside internal records");
    AttitudeRecord start = window.getAttitudeRecord(key.start());
    AttitudeRecord end = window.getAttitudeRecord(key.end());
    require(end.getTotalSeconds() - start.getTotalSeconds() == task.getImagingDuration()
        && start.getTotalSeconds() >= window.getStartTime() && end.getTotalSeconds() <= window.getEndTime(),
        "invalid duration or window in manual candidate");
    return new ScheduledTask(task, window, start, end);
  }

  private static List<Fixture> fixtures() {
    List<Task> contestTasks = List.of(task(0, 9, 5), task(1, 5, 5), task(2, 3, 5));
    List<TimeWindow> contest0 = List.of(window(0, 0, 10, 5, 2, 0),
        window(0, 1, 10, 5, 2, 0), window(0, 2, 10, 5, 2, 0));
    List<TimeWindow> contest1 = List.of(window(1, 0, 10, 5, 2, 0),
        window(1, 1, 10, 5, 2, 0), window(1, 2, 10, 5, 2, 0));
    Fixture contest = new Fixture("contest", instance(contestTasks, List.of(contest0, contest1)), List.of(
        new Key(0, 0, 0, 1, 2), new Key(1, 0, 1, 1, 2), new Key(2, 0, 2, 1, 2),
        new Key(0, 1, 0, 1, 2), new Key(1, 1, 1, 1, 2), new Key(2, 1, 2, 1, 2)), 27, 13, 14);

    List<Task> gridTasks = List.of(task(0, 10, 2), task(1, 6, 2), task(2, 8, 2), task(3, 7, 3));
    List<TimeWindow> grid0 = List.of(window(0, 0, 10, .5, 7, 0),
        window(0, 1, 24, .5, 7, 0), window(0, 2, 10, .5, 5, 30));
    List<TimeWindow> grid1 = List.of(window(1, 2, 40, .5, 5, 0),
        window(1, 0, 40, .5, 5, 0), window(1, 3, 50, .5, 3, 0));
    Fixture grid = new Fixture("half_second", instance(gridTasks, List.of(grid0, grid1)), List.of(
        new Key(0, 0, 0, 1, 5), new Key(0, 0, 0, 2, 6), new Key(0, 0, 0, 3, 7),
        new Key(1, 0, 1, 1, 5), new Key(1, 0, 1, 2, 6), new Key(1, 0, 1, 3, 7),
        new Key(2, 0, 2, 1, 5), new Key(2, 1, 0, 1, 5), new Key(0, 1, 1, 1, 5)), 60, 32, 24);

    List<Task> slewTasks = List.of(task(0, 5, 2), task(1, 9, 2), task(2, 7, 2), task(3, 3, 2));
    List<TimeWindow> slew0 = List.of(window(0, 0, 10, 2, 2, 0), window(0, 1, 36, 2, 2, 30),
        window(0, 2, 37, 2, 2, 30), window(0, 3, 64, 2, 2, 0));
    Fixture slew = new Fixture("slew_boundary", instance(slewTasks, List.of(slew0)), List.of(
        new Key(0, 0, 0, 1, 2), new Key(1, 0, 1, 1, 2),
        new Key(2, 0, 2, 1, 2), new Key(3, 0, 3, 1, 2)), 16, 10, 15);
    return List.of(contest, grid, slew);
  }

  private static Task task(int id, int profit, int duration) {
    return new Task(id, 0, 0, profit, duration);
  }

  private static ProblemInstance instance(List<Task> tasks, List<List<TimeWindow>> windows) {
    List<Satellite> satellites = new ArrayList<>();
    for (int i = 0; i < windows.size(); i++) {
      Satellite satellite = new Satellite(i);
      satellite.setTimeWindows(windows.get(i));
      satellites.add(satellite);
    }
    return new ProblemInstance(tasks, satellites);
  }

  private static TimeWindow window(int satellite, int task, double first, double step, int count, double roll) {
    double rawStart = first - .25;
    double rawEnd = first + step * (count - 1) + .25;
    TimeWindow window = new TimeWindow(satellite, task, 2013, 4, 20,
        0, minute(rawStart), second(rawStart), 2013, 4, 20, 0, minute(rawEnd), second(rawEnd));
    List<AttitudeRecord> records = new ArrayList<>();
    records.add(record(rawStart, roll));
    for (int i = 0; i < count; i++) records.add(record(first + step * i, roll));
    records.add(record(rawEnd, roll));
    window.setAttitudeRecords(records);
    return window;
  }

  private static AttitudeRecord record(double time, double roll) {
    return new AttitudeRecord(2013, 4, 20, 0, minute(time), second(time), roll, 0);
  }

  private static int minute(double time) { return (int) (time / 60); }
  private static double second(double time) { return time - 60 * minute(time); }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  public static void main(String[] args) throws IOException {
    if (args.length > 1) throw new IllegalArgumentException("usage: P1EnumerationAudit [new-output.csv]");
    StringBuilder csv = new StringBuilder("fixture,combination,candidates,possible,feasible,optimum,status,profit,observations,decisions,reassign_moves,accepted_moves\n");
    for (Row row : runAll()) {
      csv.append(row.fixture()).append(',').append(row.combination()).append(',').append(row.candidates()).append(',')
          .append(row.possible()).append(',').append(row.feasible()).append(',').append(row.optimum()).append(',')
          .append(row.status()).append(',').append(row.profit()).append(',').append(row.observations()).append(',')
          .append(row.decisions()).append(',').append(row.reassignMoves()).append(',').append(row.acceptedMoves()).append('\n');
    }
    if (args.length == 0) System.out.print(csv);
    else Files.writeString(Path.of(args[0]), csv, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
  }
}
