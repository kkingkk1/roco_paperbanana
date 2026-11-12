package method.rkhma.bridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** 工作副本与原始对象的一一映射；不修改共享问题实例。 */
public final class ProblemBridge {
  private final model.ProblemInstance problem;
  private final List<method.rkhma.legacy.model.Task> tasks = new ArrayList<>();
  private final List<method.rkhma.legacy.model.Satellite> satellites = new ArrayList<>();
  private final Map<Integer, Integer> taskIndices = new HashMap<>();
  private final Map<Integer, Integer> satelliteIndices = new HashMap<>();
  private final IdentityHashMap<model.TimeWindow, method.rkhma.legacy.model.TimeWindow> windows = new IdentityHashMap<>();
  private final IdentityHashMap<method.rkhma.legacy.model.TimeWindow, model.TimeWindow> originalWindows = new IdentityHashMap<>();
  private final IdentityHashMap<model.TimeWindow, IdentityHashMap<model.AttitudeRecord, method.rkhma.legacy.model.AttitudeRecord>> records = new IdentityHashMap<>();
  private final IdentityHashMap<method.rkhma.legacy.model.AttitudeRecord, model.AttitudeRecord> originalRecords = new IdentityHashMap<>();

  public ProblemBridge(model.ProblemInstance problem) {
    this(problem, () -> { });
  }

  public ProblemBridge(model.ProblemInstance problem, Runnable check) {
    this.problem = java.util.Objects.requireNonNull(problem);
    java.util.Objects.requireNonNull(check);
    var adapter = new method.ProblemAdapter(problem);
    for (model.Task task : problem.tasks()) {
      check.run();
      int id = tasks.size();
      taskIndices.put(task.getId(), id);
      tasks.add(new method.rkhma.legacy.model.Task(id, task.getLongitude(), task.getLatitude(),
          task.getPriority(), task.getImagingDuration()));
    }
    for (model.Satellite satellite : problem.satellites()) {
      check.run();
      int id = satellites.size();
      satelliteIndices.put(satellite.getId(), id);
      satellites.add(new method.rkhma.legacy.model.Satellite(id));
      List<method.rkhma.legacy.model.TimeWindow> satelliteWindows = new ArrayList<>();
      Map<Integer, List<method.rkhma.legacy.model.TimeWindow>> byTask = new HashMap<>();
      for (model.TimeWindow window : satellite.getTimeWindows()) {
        check.run();
        int task = taskIndices.get(window.getTaskId());
        var copy = new method.rkhma.legacy.model.TimeWindow(id, task, 0, 0, 0, 0, 0, window.getStartTime(),
            0, 0, 0, 0, 0, window.getEndTime());
        copy.setValidatedCandidate(problem.task(window.getTaskId()).getImagingDuration(),
            adapter.candidates(window).iterator().hasNext());
        var windowRecords = new IdentityHashMap<model.AttitudeRecord, method.rkhma.legacy.model.AttitudeRecord>();
        int recordCount = 0;
        for (model.AttitudeRecord record : window.getAttitudeRecords()) {
          if ((recordCount++ & 127) == 0) check.run();
          var recordCopy = new method.rkhma.legacy.model.AttitudeRecord(0, 0, 0, 0, 0,
              record.getTotalSeconds(), record.getRollAngle(), record.getPitchAngle());
          copy.addAttitudeRecord(recordCopy);
          windowRecords.put(record, recordCopy);
          originalRecords.put(recordCopy, record);
        }
        records.put(window, windowRecords);
        windows.put(window, copy);
        originalWindows.put(copy, window);
        satelliteWindows.add(copy);
        byTask.computeIfAbsent(task, ignored -> new ArrayList<>()).add(copy);
      }
      satellites.get(id).setTimeWindows(satelliteWindows);
      for (int task = 0; task < tasks.size(); task++) {
        tasks.get(task).addTimeWindowsForSatellite(id, byTask.getOrDefault(task, List.of()));
      }
    }
  }

  public List<method.rkhma.legacy.model.Task> tasks() { return List.copyOf(tasks); }
  public List<method.rkhma.legacy.model.Satellite> satellites() { return List.copyOf(satellites); }
  public model.ProblemInstance problem() { return problem; }
  public model.TimeWindow originalWindow(method.rkhma.legacy.model.TimeWindow window) {
    return originalWindows.get(window);
  }

  public boolean sourceCompatible(int satelliteIndex) {
    for (var window : problem.satellites().get(satelliteIndex).getTimeWindows()) {
      var raw = window.getAttitudeRecords();
      for (int i = 1; i < raw.size() - 1; i++) {
        double time = raw.get(i).getTotalSeconds();
        if (time < window.getStartTime() || time > window.getEndTime()) return false;
        if (i > 1 && time - raw.get(i - 1).getTotalSeconds() != 1.0) return false;
      }
    }
    return true;
  }
  public int taskIndex(int id) { return taskIndices.get(id); }
  public int satelliteIndex(int id) { return satelliteIndices.get(id); }
  public int taskId(int index) { return problem.tasks().get(index).getId(); }
  public int satelliteId(int index) { return problem.satellites().get(index).getId(); }

  public model.ScheduledTask fromLegacy(method.rkhma.legacy.model.ScheduledTask observation) {
    var window = originalWindows.get(observation.getTimeWindow());
    var start = originalRecords.get(observation.getStartRecord());
    var end = originalRecords.get(observation.getEndRecord());
    if (window == null || start == null || end == null) throw new IllegalArgumentException("foreign observation");
    return new model.ScheduledTask(problem.task(taskId(observation.getTaskId())), window, start, end);
  }

  public method.rkhma.legacy.model.ScheduledTask toLegacy(model.ScheduledTask observation) {
    var window = windows.get(observation.timeWindow());
    var windowRecords = records.get(observation.timeWindow());
    var start = windowRecords == null ? null : windowRecords.get(observation.startRecord());
    var end = windowRecords == null ? null : windowRecords.get(observation.endRecord());
    Integer task = taskIndices.get(observation.getTaskId());
    if (window == null || start == null || end == null || task == null) throw new IllegalArgumentException("foreign observation");
    var copy = new method.rkhma.legacy.model.ScheduledTask(tasks.get(task));
    copy.setSchedule(window, start, end);
    return copy;
  }
}
