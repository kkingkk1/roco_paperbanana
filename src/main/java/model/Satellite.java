package model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 卫星输入数据。任务分配属于候选解，不存入卫星对象。 */
public final class Satellite {
  private final int id;
  private List<TimeWindow> timeWindows = List.of();

  public Satellite(int id) {
    this.id = id;
  }

  public int getId() {
    return id;
  }

  public List<TimeWindow> getTimeWindows() {
    return Collections.unmodifiableList(timeWindows);
  }

  public void setTimeWindows(List<TimeWindow> windows) {
    timeWindows = new ArrayList<>(windows);
  }
}
