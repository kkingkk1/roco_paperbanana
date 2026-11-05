package method.runtime;

import model.ProblemInstance;
import model.ScheduledTask;
import utils.DataLoader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;

/** 子进程协议：先读取公共场景，再等待父进程开始实例计时。 */
public final class ForkedWorker {
  private ForkedWorker() { }

  public static void main(String[] args) throws Exception {
    if (args.length != 5) throw new IllegalArgumentException("invalid worker arguments");
    var data = DataLoader.loadAllData(args[0], Integer.parseInt(args[1]), Integer.parseInt(args[2]));
    var problem = new ProblemInstance(data.getTaskList(), data.getSatelliteList());
    long seed = Long.parseLong(args[4]);
    try (DataInputStream input = new DataInputStream(new BufferedInputStream(System.in));
         DataOutputStream output = new DataOutputStream(new BufferedOutputStream(System.out))) {
      output.writeUTF("READY");
      output.flush();
      if (input.readByte() != 1) throw new IllegalStateException("invalid start signal");
      System.setOut(System.err);
      System.setProperty("autohyb.quiet", "true");
      TwoStageSolver.Status status;
      Long profit;
      String detail;
      long elapsed;
      model.SchedulingPlan plan;
      if (args[3].contains("3")) {
        var result = RkhmaRuntime.solve(problem, RkhmaRuntime.combination(args[3]), RkhmaRuntime.Config.defaults(seed));
        status = result.status(); profit = result.profit(); detail = result.detail();
        elapsed = result.elapsedNanos(); plan = result.plan();
      } else {
        var combination = ManualStrategies.combinations().stream().filter(c -> c.name().equals(args[3]))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown combination"));
        var result = new TwoStageSolver().solve(problem, combination.a(), combination.b(), combination.c(), seed);
        status = result.status(); profit = result.totalProfit(); detail = result.detail();
        elapsed = result.elapsedNanos(); plan = result.plan();
      }
      output.writeUTF("RESULT");
      output.writeUTF(status.name());
      output.writeBoolean(profit != null);
      if (profit != null) output.writeLong(profit);
      output.writeUTF(detail);
      output.writeLong(elapsed);
      List<ScheduledTask> observations = plan == null ? List.of() : plan.observations();
      output.writeInt(observations.size());
      for (ScheduledTask observation : observations) {
        output.writeInt(observation.getTaskId());
        output.writeInt(observation.getSatelliteId());
        var windows = problem.satellite(observation.getSatelliteId()).getTimeWindows();
        int windowIndex = -1;
        for (int i = 0; i < windows.size(); i++) {
          if (windows.get(i) == observation.timeWindow()) { windowIndex = i; break; }
        }
        if (windowIndex < 0) throw new IllegalStateException("observation window not in instance");
        output.writeInt(windowIndex);
        output.writeDouble(observation.getStartTime());
        output.writeDouble(observation.getEndTime());
      }
      output.flush();
    }
  }
}
