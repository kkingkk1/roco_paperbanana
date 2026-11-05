import java.io.DataOutputStream;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import model.AttitudeRecord;
import model.Satellite;
import model.Task;
import model.TimeWindow;
import utils.DataLoader;

/** 两个独立JVM分别从同一只读场景构造相同任务前缀。 */
public final class ComparisonFixture {
  private ComparisonFixture() { }

  public record Prepared(List<Task> tasks, List<Satellite> satellites, String fingerprint,
                         int windows, int records, boolean sourceCompatible) { }

  public static Prepared load(String scene, int count) throws Exception {
    var data = DataLoader.loadAllData(scene, 500, 5);
    if (count <= 0 || count > data.taskList.size()) throw new IllegalArgumentException("invalid task count");
    List<Task> tasks = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Task original = data.taskList.get(i);
      if (original.getId() != i) throw new IllegalArgumentException("fixture requires dense task ids");
      tasks.add(new Task(i, original.getLongitude(), original.getLatitude(),
          original.getPriority(), original.getImagingDuration()));
    }
    List<Satellite> satellites = new ArrayList<>();
    int windows = 0, records = 0;
    boolean compatible = true;
    for (int satelliteId = 0; satelliteId < 5; satelliteId++) {
      Satellite satellite = new Satellite(satelliteId);
      List<TimeWindow> copiedWindows = new ArrayList<>();
      Map<Integer, List<TimeWindow>> byTask = new HashMap<>();
      for (TimeWindow original : data.satelliteList.get(satelliteId).getTimeWindows()) {
        if (original.getTaskId() < 0 || original.getTaskId() >= count) continue;
        TimeWindow window = new TimeWindow(satelliteId, original.getTaskId(),
            0, 0, 0, 0, 0, original.getStartTime(), 0, 0, 0, 0, 0, original.getEndTime());
        List<AttitudeRecord> copiedRecords = new ArrayList<>();
        for (AttitudeRecord record : original.getAttitudeRecords()) {
          copiedRecords.add(new AttitudeRecord(0, 0, 0, 0, 0, record.getTotalSeconds(),
              record.getRollAngle(), record.getPitchAngle()));
        }
        window.setAttitudeRecords(copiedRecords);
        for (int i = 1; i < copiedRecords.size() - 1; i++) {
          double t = copiedRecords.get(i).getTotalSeconds();
          if (t < window.getStartTime() || t > window.getEndTime()
              || i > 1 && t - copiedRecords.get(i - 1).getTotalSeconds() != 1.0) compatible = false;
        }
        copiedWindows.add(window);
        byTask.computeIfAbsent(original.getTaskId(), ignored -> new ArrayList<>()).add(window);
        windows++;
        records += copiedRecords.size();
      }
      satellite.setTimeWindows(copiedWindows);
      satellites.add(satellite);
      for (Task task : tasks) task.addTimeWindowsForSatellite(satelliteId, byTask.getOrDefault(task.getId(), List.of()));
    }
    return new Prepared(List.copyOf(tasks), List.copyOf(satellites), fingerprint(tasks, satellites),
        windows, records, compatible);
  }

  private static String fingerprint(List<Task> tasks, List<Satellite> satellites) throws Exception {
    MessageDigest hash = MessageDigest.getInstance("SHA-256");
    try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), hash))) {
      out.writeInt(tasks.size());
      for (Task task : tasks) {
        out.writeInt(task.getId());
        out.writeDouble(task.getLongitude());
        out.writeDouble(task.getLatitude());
        out.writeInt(task.getPriority());
        out.writeInt(task.getImagingDuration());
      }
      out.writeInt(satellites.size());
      for (Satellite satellite : satellites) {
        out.writeInt(satellite.getId());
        out.writeInt(satellite.getTimeWindows().size());
        for (TimeWindow window : satellite.getTimeWindows()) {
          out.writeInt(window.getTaskId());
          out.writeDouble(window.getStartTime());
          out.writeDouble(window.getEndTime());
          out.writeInt(window.getAttitudeRecordCount());
          for (AttitudeRecord record : window.getAttitudeRecords()) {
            out.writeDouble(record.getTotalSeconds());
            out.writeDouble(record.getRollAngle());
            out.writeDouble(record.getPitchAngle());
          }
        }
      }
    }
    return HexFormat.of().formatHex(hash.digest());
  }
}
