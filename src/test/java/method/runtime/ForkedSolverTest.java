package method.runtime;

import model.ProblemInstance;
import model.ScheduledTask;
import model.SchedulingPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import problem.ScheduleValidator;
import utils.DataLoader;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ForkedSolverTest {
  @TempDir Path scene;

  @Test void childRunsReassembledRkhmaOnNonunitRecords() throws Exception {
    Files.writeString(scene.resolve("tasklist.txt"), "0 10 20 7 5 99\n2 45 45 0\n");
    Files.writeString(scene.resolve("outputtimewindow_1.txt"),
        "s1\n0\n2013 04 20 00 00 00 2013 04 20 00 01 00 60\n");
    Files.writeString(scene.resolve("outputattitude_1.txt"),
        "2013 04 20 00 00 00 0 0\n2013 04 20 00 00 01 0 0\n"
            + "2013 04 20 00 00 06 0 0\n2013 04 20 00 00 07 0 0\n");
    var result = ForkedSolver.run(scene, 1, 1, "A3B3C3", 42);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    assertEquals(7L, result.totalProfit());
    assertEquals(1, result.observations().size());
    assertEquals(1.0, result.observations().getFirst().startTime());
    assertEquals(6.0, result.observations().getFirst().endTime());
  }

  @Test void childReturnsAPlanThatCanBeIndependentlyReconstructedAndValidated() throws Exception {
    Files.writeString(scene.resolve("tasklist.txt"), "0 10 20 7 5 99\n2 45 45 0\n");
    Files.writeString(scene.resolve("outputtimewindow_1.txt"),
        "s1\n0\n2013 04 20 00 00 00 2013 04 20 00 01 00 60\n");
    Files.writeString(scene.resolve("outputattitude_1.txt"),
        "2013 04 20 00 00 00 0 0\n2013 04 20 00 00 01 0 0\n"
            + "2013 04 20 00 00 06 0 0\n2013 04 20 00 00 07 0 0\n");
    var result = ForkedSolver.run(scene, 1, 1, "A1B1C1", 42);
    assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), result.detail());
    assertEquals(7L, result.totalProfit());
    assertEquals(1, result.observations().size());
    var data = DataLoader.loadAllData(scene.toString(), 1, 1);
    var problem = new ProblemInstance(data.getTaskList(), data.getSatelliteList());
    var value = result.observations().getFirst();
    var window = problem.satellite(value.satelliteId()).getTimeWindows().get(value.windowIndex());
    var start = window.getAttitudeRecords().stream().filter(r -> r.getTotalSeconds() == value.startTime())
        .findFirst().orElseThrow();
    var end = window.getAttitudeRecords().stream().filter(r -> r.getTotalSeconds() == value.endTime())
        .findFirst().orElseThrow();
    var independent = ScheduleValidator.evaluate(problem, new SchedulingPlan(List.of(
        new ScheduledTask(problem.task(value.taskId()), window, start, end))));
    assertTrue(independent.feasible(), independent.violations().toString());
    assertEquals(result.totalProfit(), independent.totalProfit());
  }

  @Test void hardTimeoutTerminatesAnUncooperativeWorker() throws Exception {
    String binary = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
        ? "java.exe" : "java";
    String java = Path.of(System.getProperty("java.home"), "bin", binary).toString();
    Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
        StuckWorker.class.getName()).start();
    var result = ForkedSolver.supervise(process, TimeUnit.MILLISECONDS.toNanos(100), 0);
    assertEquals(TwoStageSolver.Status.HARD_TIMEOUT, result.status());
    assertNull(result.totalProfit());
    assertFalse(process.isAlive());
  }

  @Test void commandLineOutputPreservesFractionalRecordTimes() throws Exception {
    Files.writeString(scene.resolve("tasklist.txt"), "0 10 20 7 5 99\n2 45 45 0\n");
    Files.writeString(scene.resolve("outputtimewindow_1.txt"),
        "s1\n0\n2013 04 20 00 00 00 2013 04 20 00 01 00 60\n");
    Files.writeString(scene.resolve("outputattitude_1.txt"),
        "2013 04 20 00 00 00 0 0\n2013 04 20 00 00 01.123456789 0 0\n"
            + "2013 04 20 00 00 06.123456789 0 0\n2013 04 20 00 00 07 0 0\n");
    String binary = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
        ? "java.exe" : "java";
    String java = Path.of(System.getProperty("java.home"), "bin", binary).toString();
    ProcessBuilder command = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
        "main.RunManualSolver", scene.toString(), "1", "1", "A1B1C1", "42").redirectErrorStream(true);
    command.environment().put("P1_RESOURCE_JOB_NAME", "test-only");
    Process process = command.start();
    try {
      var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
      assertEquals("P1_INSTANCE_READY", reader.readLine());
      process.getOutputStream().write(1);
      process.getOutputStream().flush();
      StringBuilder output = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) output.append(line).append('\n');
      assertEquals(0, process.waitFor(), output.toString());
      assertTrue(output.toString().contains("start=1.123456789 end=6.123456789"), output.toString());
    } finally {
      if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); }
    }
  }

  @Test void reportsWorkerStartupOutputInsteadOfMaskingItAsEof() throws Exception {
    String binary = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
        ? "java.exe" : "java";
    String java = Path.of(System.getProperty("java.home"), "bin", binary).toString();
    Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
        FailedWorker.class.getName()).start();
    IOException error = assertThrows(IOException.class,
        () -> ForkedSolver.supervise(process, TimeUnit.SECONDS.toNanos(1), 0));
    assertTrue(error.getMessage().startsWith("worker startup output: Er"), error.getMessage());
    assertFalse(process.isAlive());
  }

  @Test void commandLineRefusesToRunWithoutTheResourceLauncher() throws Exception {
    String binary = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
        ? "java.exe" : "java";
    String java = Path.of(System.getProperty("java.home"), "bin", binary).toString();
    ProcessBuilder command = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
        "main.RunManualSolver", scene.toString(), "1", "1", "A1B1C1", "42").redirectErrorStream(true);
    command.environment().remove("P1_RESOURCE_JOB_NAME");
    Process process = command.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertNotEquals(0, process.waitFor());
    assertTrue(output.contains("scripts/run-manual.ps1"), output);
  }

  @Test @EnabledOnOs(OS.WINDOWS)
  void windowsLauncherRunsInsideTheResourceJob() throws Exception {
    Files.writeString(scene.resolve("tasklist.txt"), "0 10 20 7 5 99\n2 45 45 0\n");
    Files.writeString(scene.resolve("outputtimewindow_1.txt"),
        "s1\n0\n2013 04 20 00 00 00 2013 04 20 00 01 00 60\n");
    Files.writeString(scene.resolve("outputattitude_1.txt"),
        "2013 04 20 00 00 00 0 0\n2013 04 20 00 00 01.123456789 0 0\n"
            + "2013 04 20 00 00 06.123456789 0 0\n2013 04 20 00 00 07 0 0\n");
    ProcessBuilder command = new ProcessBuilder("pwsh.exe", "-NoProfile", "-NonInteractive", "-File",
        Path.of("scripts", "run-manual.ps1").toAbsolutePath().toString(), "-Scene", scene.toString(),
        "-Tasks", "1", "-Satellites", "1", "-Combination", "A1B1C1", "-Seed", "42")
        .redirectErrorStream(true);
    command.environment().remove("P1_RESOURCE_JOB_NAME");
    Process process = command.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
    assertTrue(output.contains("status=SUCCESS profit=7 observations=1"), output);
    assertTrue(output.contains("start=1.123456789 end=6.123456789"), output);
  }

  @Test @EnabledOnOs(OS.WINDOWS)
  void jobRejectsAChildHeapLargerThanTheWholeProcessGroupLimit() throws Exception {
    String script = "$ErrorActionPreference='Stop'; Add-Type -Path $env:P1_TEST_JOB_HELPER; "
        + "try { [P1ResourceJob]::Attach([UInt64]536870912); "
        + "$msg = & java -Xms768m -Xmx768m -XX:+UseSerialGC -version 2>&1; "
        + "if ($LASTEXITCODE -eq 0 -or (($msg -join ' ') -notmatch 'Could not reserve enough space')) { exit 3 }; "
        + "'memory_limit_enforced' } finally { [P1ResourceJob]::Close() }";
    ProcessBuilder command = new ProcessBuilder("pwsh.exe", "-NoProfile", "-NonInteractive", "-Command", script)
        .redirectErrorStream(true);
    command.environment().put("P1_TEST_JOB_HELPER", Path.of("scripts", "P1ResourceJob.cs").toAbsolutePath().toString());
    Process process = command.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
    assertTrue(output.contains("memory_limit_enforced"), output);
  }

  public static final class StuckWorker {
    public static void main(String[] args) throws Exception {
      var output = new DataOutputStream(System.out);
      output.writeUTF("READY");
      output.flush();
      if (new DataInputStream(System.in).readByte() != 1) return;
      while (true) {
        try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
      }
    }
  }

  public static final class FailedWorker {
    public static void main(String[] args) {
      System.out.println("Error occurred during worker initialization");
    }
  }
}
