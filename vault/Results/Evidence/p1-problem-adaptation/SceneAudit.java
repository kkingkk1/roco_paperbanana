package audit;

import method.ProblemAdapter;
import model.AttitudeRecord;
import model.ProblemInstance;
import model.ScheduledTask;
import model.SchedulingPlan;
import model.Task;
import model.TimeWindow;
import problem.ScheduleValidator;
import utils.DataLoader;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

/** 对真实场景执行候选全量计数、抽窗穷举及改前验证器同输入对照。 */
public final class SceneAudit {
  private SceneAudit() { }

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException("Usage: SceneAudit <directory> <tasks> <satellites>");
    }
    var data = DataLoader.loadAllData(args[0], Integer.parseInt(args[1]), Integer.parseInt(args[2]));
    var instance = new ProblemInstance(data.getTaskList(), data.getSatelliteList());
    var adapter = new ProblemAdapter(instance);
    var fingerprint = MessageDigest.getInstance("SHA-256");
    var buffer = ByteBuffer.allocate(Long.BYTES);
    long windows = 0;
    long internalRecords = 0;
    long candidateCount = 0;
    long compared = 0;
    int sampled = 0;
    for (var satellite : instance.satellites()) {
      for (TimeWindow window : satellite.getTimeWindows()) {
        windows++;
        var grid = adapter.grid(window);
        var raw = window.getAttitudeRecords();
        internalRecords += grid.size();
        add(fingerprint, buffer, satellite.getId());
        add(fingerprint, buffer, window.getTaskId());
        add(fingerprint, buffer, Double.doubleToLongBits(window.getStartTime()));
        add(fingerprint, buffer, Double.doubleToLongBits(window.getEndTime()));
        add(fingerprint, buffer, grid.size());
        Map<AttitudeRecord, Integer> positions = new IdentityHashMap<>();
        for (int i = 0; i < grid.size(); i++) {
          AttitudeRecord record = grid.records().get(i);
          if (record != raw.get(i + 1)) {
            throw new AssertionError("内部记录与原始对象不一致");
          }
          positions.put(record, i + 1);
          add(fingerprint, buffer, Double.doubleToLongBits(record.getTotalSeconds()));
          add(fingerprint, buffer, Double.doubleToLongBits(record.getRollAngle()));
          add(fingerprint, buffer, Double.doubleToLongBits(record.getPitchAngle()));
        }
        List<ScheduledTask> actual = StreamSupport.stream(adapter.candidates(window).spliterator(), false).toList();
        candidateCount += actual.size();
        add(fingerprint, buffer, actual.size());
        for (ScheduledTask candidate : actual) {
          Integer first = positions.get(candidate.startRecord());
          Integer last = positions.get(candidate.endRecord());
          if (candidate.timeWindow() != window || candidate.task() != instance.task(window.getTaskId())
              || first == null || last == null) {
            throw new AssertionError("候选未引用原始任务、窗口或内部姿态对象");
          }
          add(fingerprint, buffer, first);
          add(fingerprint, buffer, last);
        }
        boolean sample = sampled < 30 || window.getEndTime() < window.getStartTime();
        if (sample) {
          sampled++;
          List<ScheduledTask> expected = new ArrayList<>();
          Task task = instance.task(window.getTaskId());
          for (int i = 1; i < raw.size() - 1; i++) {
            for (int j = i + 1; j < raw.size() - 1; j++) {
              AttitudeRecord first = raw.get(i);
              AttitudeRecord last = raw.get(j);
              double start = first.getTotalSeconds();
              double end = last.getTotalSeconds();
              if (start >= window.getStartTime() && end <= window.getEndTime()
                  && Math.abs(end - start - task.getImagingDuration()) <= 1e-6) {
                expected.add(new ScheduledTask(task, window, first, last));
              }
            }
          }
          if (!expected.equals(actual)) {
            throw new AssertionError("真实窗口的穷举候选与迭代结果不一致");
          }
          for (ScheduledTask candidate : actual) {
            compare(instance, candidate);
            compared++;
          }
        } else if (!actual.isEmpty()) {
          compare(instance, actual.getFirst());
          compared++;
        }
      }
    }
    System.out.printf("windows=%d internalRecords=%d candidates=%d exhaustiveWindows=%d comparedObservations=%d%n",
        windows, internalRecords, candidateCount, sampled, compared);
    System.out.println("adapterFingerprint=" + HexFormat.of().formatHex(fingerprint.digest()));
  }

  private static void add(MessageDigest digest, ByteBuffer buffer, long value) {
    buffer.clear();
    buffer.putLong(value);
    digest.update(buffer.array());
  }

  private static void compare(ProblemInstance instance, ScheduledTask candidate) {
    SchedulingPlan plan = new SchedulingPlan(List.of(candidate));
    var oldResult = baseline.problem.ScheduleValidator.evaluate(instance, plan);
    var newResult = ScheduleValidator.evaluate(instance, plan);
    if (!oldResult.feasible() || !newResult.feasible()
        || oldResult.totalProfit() != newResult.totalProfit()
        || !oldResult.violations().equals(newResult.violations())) {
      throw new AssertionError("真实候选在改前与当前验证器中的结果不同");
    }
  }
}
