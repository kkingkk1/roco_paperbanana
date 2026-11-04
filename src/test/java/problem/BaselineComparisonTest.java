package problem;

import model.AttitudeRecord;
import model.ProblemInstance;
import model.Satellite;
import model.ScheduledTask;
import model.SchedulingPlan;
import model.Task;
import model.TimeWindow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 使用保存的改前源码，在相同实例与观测上比较验证结果。 */
class BaselineComparisonTest {
  private static Method oldEvaluate;
  private static Class<?> oldInstanceClass;

  private record Fixture(ProblemInstance instance, Task a, Task b, TimeWindow wa,
                         TimeWindow wb, TimeWindow otherSatellite) { }

  private record Outcome(boolean feasible, long profit, List<String> violations) { }

  @BeforeAll static void compileBaseline() throws Exception {
    Path snapshots = Path.of("vault", "Results", "Evidence", "p1-problem-adaptation", "baseline");
    Path output = Path.of("target", "p1-baseline-comparator");
    Path validatorSource = output.resolve("src/baseline/problem/ScheduleValidator.java");
    Path instanceSource = output.resolve("src/baseline/model/ProblemInstance.java");
    Path classes = output.resolve("classes");
    Files.createDirectories(validatorSource.getParent());
    Files.createDirectories(instanceSource.getParent());
    Files.createDirectories(classes);
    String validator = Files.readString(snapshots.resolve("ScheduleValidator.java"), StandardCharsets.UTF_8)
        .replaceFirst("package problem;", "package baseline.problem;");
    String instance = Files.readString(snapshots.resolve("ProblemInstance.java"), StandardCharsets.UTF_8)
        .replaceFirst("package model;", "package baseline.model;\nimport model.*;");
    Files.writeString(validatorSource, validator, StandardCharsets.UTF_8);
    Files.writeString(instanceSource, instance, StandardCharsets.UTF_8);
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "对照需要 JDK 编译器");
    assertEquals(0, compiler.run(null, null, null, "-encoding", "UTF-8", "-classpath",
        Path.of("target", "classes").toString(), "-d", classes.toString(),
        validatorSource.toString(), instanceSource.toString()));
    var loader = new URLClassLoader(new java.net.URL[] {classes.toUri().toURL()},
        BaselineComparisonTest.class.getClassLoader());
    oldEvaluate = loader.loadClass("baseline.problem.ScheduleValidator")
        .getMethod("evaluate", ProblemInstance.class, SchedulingPlan.class);
    oldInstanceClass = loader.loadClass("baseline.model.ProblemInstance");
  }

  private TimeWindow window(int satellite, int task, double start) {
    TimeWindow window = new TimeWindow(satellite, task, 2013, 4, 20, 0, 0, start,
        2013, 4, 20, 0, 0, 200);
    for (int i = 0; i <= 200; i++) {
      window.addAttitudeRecord(new AttitudeRecord(2013, 4, 20, 0, 0, i, 0, 0));
    }
    return window;
  }

  private Fixture fixture(double windowStart) {
    Task a = new Task(0, 0, 0, 7, 5);
    Task b = new Task(1, 0, 0, 3, 5);
    TimeWindow wa = window(0, 0, windowStart);
    TimeWindow wb = window(0, 1, windowStart);
    TimeWindow other = window(1, 0, windowStart);
    Satellite s0 = new Satellite(0);
    s0.setTimeWindows(List.of(wa, wb));
    Satellite s1 = new Satellite(1);
    s1.setTimeWindows(List.of(other));
    return new Fixture(new ProblemInstance(List.of(a, b), List.of(s0, s1)), a, b, wa, wb, other);
  }

  private ScheduledTask observation(Task task, TimeWindow window, int start) {
    return new ScheduledTask(task, window, window.getAttitudeRecord(start),
        window.getAttitudeRecord(start + 5));
  }

  @SuppressWarnings("unchecked")
  private Outcome oldResult(ProblemInstance instance, SchedulingPlan plan) throws Exception {
    Object result = oldEvaluate.invoke(null, instance, plan);
    return new Outcome((boolean) result.getClass().getMethod("feasible").invoke(result),
        (long) result.getClass().getMethod("totalProfit").invoke(result),
        (List<String>) result.getClass().getMethod("violations").invoke(result));
  }

  private Outcome currentResult(ProblemInstance instance, SchedulingPlan plan) {
    var result = ScheduleValidator.evaluate(instance, plan);
    return new Outcome(result.feasible(), result.totalProfit(), result.violations());
  }

  @Test void keepsOriginalDecisionsAndProfitForInternalObservations() throws Exception {
    Fixture f = fixture(0);
    ScheduledTask a = observation(f.a(), f.wa(), 1);
    ScheduledTask b = observation(f.b(), f.wb(), 30);
    Task forgedTask = new Task(0, 0, 0, 999, 5);
    AttitudeRecord fakeStart = new AttitudeRecord(2013, 4, 20, 0, 0, 1, 0, 0);
    List<SchedulingPlan> plans = new ArrayList<>();
    plans.add(new SchedulingPlan(List.of()));
    plans.add(new SchedulingPlan(List.of(a)));
    plans.add(new SchedulingPlan(List.of(b, a)));
    plans.add(new SchedulingPlan(List.of(a, observation(f.a(), f.otherSatellite(), 1))));
    plans.add(new SchedulingPlan(List.of(a, observation(f.b(), f.wb(), 3))));
    plans.add(new SchedulingPlan(List.of(a, observation(f.b(), f.wb(), 16))));
    plans.add(new SchedulingPlan(List.of(new ScheduledTask(f.a(), f.wa(),
        f.wa().getAttitudeRecord(1), f.wa().getAttitudeRecord(5)))));
    plans.add(new SchedulingPlan(List.of(observation(forgedTask, f.wa(), 1))));
    plans.add(new SchedulingPlan(List.of(observation(f.a(), window(0, 0, 0), 1))));
    plans.add(new SchedulingPlan(List.of(new ScheduledTask(f.a(), f.wa(), fakeStart,
        f.wa().getAttitudeRecord(6)))));
    for (SchedulingPlan plan : plans) {
      assertEquals(oldResult(f.instance(), plan), currentResult(f.instance(), plan), plan.toString());
    }
    Fixture outside = fixture(20);
    SchedulingPlan outsidePlan = new SchedulingPlan(List.of(observation(outside.a(), outside.wa(), 1)));
    assertEquals(oldResult(outside.instance(), outsidePlan), currentResult(outside.instance(), outsidePlan));
  }

  @Test void rejectsBothOriginalBoundaryRecordsAsTheIntendedChange() throws Exception {
    Fixture f = fixture(0);
    for (int start : List.of(0, 195)) {
      SchedulingPlan plan = new SchedulingPlan(List.of(observation(f.a(), f.wa(), start)));
      assertTrue(oldResult(f.instance(), plan).feasible());
      Outcome current = currentResult(f.instance(), plan);
      assertFalse(current.feasible());
      assertEquals(0, current.profit());
      assertTrue(current.violations().stream().anyMatch(v -> v.contains("ATTITUDE_OUTSIDE_INTERNAL_GRID")));
    }
  }

  @Test void acceptsRegularInputAndNowRejectsUnequalInternalSpacing() throws Exception {
    Fixture f = fixture(0);
    Object oldInstance = oldInstanceClass.getConstructor(List.class, List.class)
        .newInstance(f.instance().tasks(), f.instance().satellites());
    assertSame(f.a(), oldInstanceClass.getMethod("task", int.class).invoke(oldInstance, 0));
    Task task = new Task(0, 0, 0, 1, 1);
    TimeWindow uneven = new TimeWindow(0, 0, 2013, 4, 20, 0, 0, 0,
        2013, 4, 20, 0, 0, 10);
    for (double time : new double[] {0, 1, 2, 3.1, 5}) {
      uneven.addAttitudeRecord(new AttitudeRecord(2013, 4, 20, 0, 0, time, 0, 0));
    }
    Satellite satellite = new Satellite(0);
    satellite.setTimeWindows(List.of(uneven));
    assertNotNull(oldInstanceClass.getConstructor(List.class, List.class)
        .newInstance(List.of(task), List.of(satellite)));
    assertThrows(IllegalArgumentException.class,
        () -> new ProblemInstance(List.of(task), List.of(satellite)));
  }
}
