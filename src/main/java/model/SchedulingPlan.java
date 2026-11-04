package model;

import java.util.List;

/** 多卫星候选观测集合；不含编码、生成策略或适应度缓存。 */
public record SchedulingPlan(List<ScheduledTask> observations) {
  public SchedulingPlan {
    observations = List.copyOf(observations);
  }
}
