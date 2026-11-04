package utils;

import model.ProblemInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DataLoaderTest {
  @TempDir Path directory;

  private void scene() throws IOException {
    Files.writeString(directory.resolve("tasklist.txt"), "0 10 20 7 5 99\n2 45 45 0\n");
    Files.writeString(directory.resolve("outputtimewindow_1.txt"),
        "s1\n0\n2013 04 20 00 00 00 2013 04 20 00 01 00 60\n");
    Files.writeString(directory.resolve("outputattitude_1.txt"),
        "2013 04 20 00 00 00 0 0\n2013 04 20 00 00 05 0 0\n");
  }

  @Test void readsOriginalOddLineFormatAndZeroTaskId() throws Exception {
    scene();
    DataLoader.DataSet data = DataLoader.loadAllData(directory.toString(), 1, 1);
    ProblemInstance instance = new ProblemInstance(data.getTaskList(), data.getSatelliteList());
    assertEquals(1, instance.tasks().size());
    assertEquals(7, instance.task(0).getPriority());
    assertEquals(5, instance.task(0).getImagingDuration());
    assertEquals(0, instance.satellite(0).getTimeWindows().getFirst().getTaskId());
    assertEquals(2, instance.satellite(0).getTimeWindows().getFirst().getAttitudeRecordCount());
    assertEquals(1, instance.task(0).getTimeWindowsForSatellite(0).size());
  }

  @Test void rejectsCountMismatch() throws Exception {
    scene();
    assertThrows(IOException.class, () -> DataLoader.loadAllData(directory.toString(), 2, 1));
  }

  @Test void rejectsBrokenNumericInput() throws Exception {
    scene();
    Files.writeString(directory.resolve("outputattitude_1.txt"), "2013 04 20 00 00 bad 0 0\n");
    assertThrows(IOException.class, () -> DataLoader.loadAllData(directory.toString(), 1, 1));
  }

  @Test void rejectsWindowAttitudeGroupMismatch() throws Exception {
    scene();
    Files.writeString(directory.resolve("outputattitude_1.txt"), "");
    assertThrows(IllegalStateException.class, () -> DataLoader.loadAllData(directory.toString(), 1, 1));
  }
}
