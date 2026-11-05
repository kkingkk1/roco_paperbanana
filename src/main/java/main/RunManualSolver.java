package main;

import method.runtime.ForkedSolver;
import method.runtime.TwoStageSolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Locale;

/** 单个手工组合的独立进程运行入口。 */
public final class RunManualSolver {
  private RunManualSolver() { }

  public static void main(String[] args) throws Exception {
    if (args.length != 5) {
      throw new IllegalArgumentException("Usage: RunManualSolver <scene> <tasks> <satellites> <A1B1C1..A3B3C3> <seed>");
    }
    if (System.getenv("P1_RESOURCE_JOB_NAME") == null) {
      throw new IllegalStateException("请使用 scripts/run-manual.ps1 启动，以执行 CPU 和内存限制");
    }
    Runnable ready = () -> {
      System.err.println("P1_INSTANCE_READY");
      System.err.flush();
      try {
        if (System.in.read() != 1) throw new IOException("invalid resource supervisor start signal");
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    };
    var result = ForkedSolver.run(Path.of(args[0]), Integer.parseInt(args[1]), Integer.parseInt(args[2]),
        args[3], Long.parseLong(args[4]), ready);
    StringBuilder output = new StringBuilder();
    output.append(String.format(Locale.ROOT, "status=%s profit=%s observations=%d elapsedMs=%.3f detail=%s%n",
        result.status(), result.totalProfit(), result.observations().size(), result.elapsedNanos() / 1_000_000.0,
        result.detail()));
    for (var observation : result.observations()) {
      output.append("task=").append(observation.taskId()).append(" satellite=").append(observation.satelliteId())
          .append(" window=").append(observation.windowIndex()).append(" start=")
          .append(Double.toString(observation.startTime())).append(" end=")
          .append(Double.toString(observation.endTime())).append(System.lineSeparator());
    }
    System.out.print(output);
    System.out.flush();
    if (result.status() != TwoStageSolver.Status.SUCCESS) System.exit(1);
  }
}
