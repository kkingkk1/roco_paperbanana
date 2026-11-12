package method.rkhma.legacy.utils;

import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.Task;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Initialization utility class
 * Provides generation of initial task-to-satellite assignment schemes
 */
public class Initialize {

  /**
   * Default assignment strategy index (for debugging; can be switched to random selection)
   * 0: assignRandomly
   * 1: assignBasedTaskNums
   * 2: assignBasedWindowNums
   * 3: assignBasedMinRollAngle
   * 4: assignBasedEarliestTime
   * 5: assignBasedLongestWindow
   * 6: assignBasedMinPriority
   * 7: assignComposite
   * 8: assignCompositeWithCapacity
   */
  private static final int DEFAULT_STRATEGY_INDEX = 8;

  /**
   * Whether to use random strategy selection (when false, use DEFAULT_STRATEGY_INDEX)
   */
  private static final boolean USE_RANDOM_STRATEGY = false;

  /**
   * Initialize task-to-satellite assignment
   * Execute only the selected assignment strategy and return its result
   *
   * @param allTasks All task list
   * @param allSatellites All satellite list
   * @return Task assignment scheme, format: List<[satelliteId, taskId]>
   * @throws IllegalArgumentException If parameters are invalid
   */
  public static List<List<Integer>> initialize(List<Task> allTasks, List<Satellite> allSatellites) {
    int selectedIndex = DEFAULT_STRATEGY_INDEX;
    if (USE_RANDOM_STRATEGY) {
      Random random = method.rkhma.legacy.common.RandomProvider.get();
      selectedIndex = random.nextInt(9);
    }

    if (selectedIndex < 0 || selectedIndex > 8) {
      selectedIndex = 0;
    }

    return initializeWithStrategy(allTasks, allSatellites, selectedIndex);
  }

  /**
   * Initialize task-to-satellite assignment using a specified strategy
   * (pure-semantic version - allows MFEA to optimize assignment strategy)
   *
   * @param allTasks All task list
   * @param allSatellites All satellite list
   * @param strategyIndex Assignment strategy index (0-8)
   *                      0: assignRandomly
   *                      1: assignBasedTaskNums
   *                      2: assignBasedWindowNums
   *                      3: assignBasedMinRollAngle
   *                      4: assignBasedEarliestTime
   *                      5: assignBasedLongestWindow
   *                      6: assignBasedMinPriority
   *                      7: assignComposite
   *                      8: assignCompositeWithCapacity
   * @return Task assignment scheme, format: List<[satelliteId, taskId]>
   * @throws IllegalArgumentException If parameters are invalid or strategyIndex out of range
   */
  public static List<List<Integer>> initializeWithStrategy(
    List<Task> allTasks,
    List<Satellite> allSatellites,
    int strategyIndex) {

    // Validate strategy index
    if (strategyIndex < 0 || strategyIndex > 8) {
      throw new IllegalArgumentException(
        "Invalid strategy index: " + strategyIndex + ". Must be in range [0, 8]");
    }

    List<Integer> taskSortedSequence = buildPriorityOrderedTaskIds(allTasks);

    AssignTaskOperator assignOperator = new AssignTaskOperator(
      allTasks, allSatellites, taskSortedSequence);
    if (Boolean.getBoolean(method.rkhma.legacy.main.TaskAllocationCoordinator.INIT_KNN_CACHE_PROPERTY)) {
      assignOperator.setPrecomputedCapacityMap(precomputeCapacityMap(allTasks, allSatellites, taskSortedSequence));
    }

    // Select and execute the specified strategy
    switch (strategyIndex) {
      case 0:
        return assignOperator.assignRandomly();
      case 1:
        return assignOperator.assignBasedTaskNums();
      case 2:
        return assignOperator.assignBasedWindowNums();
      case 3:
        return assignOperator.assignBasedMinRollAngle();
      case 4:
        return assignOperator.assignBasedEarliestTime();
      case 5:
        return assignOperator.assignBasedLongestWindow();
      case 6:
        return assignOperator.assignBasedMinPriority();
      case 7:
        return assignOperator.assignComposite();
      case 8:
        return assignOperator.assignCompositeWithCapacity();
      default:
        // Should never reach here due to validation above
        throw new IllegalArgumentException(
          "Invalid strategy index: " + strategyIndex);
    }
  }

  private static Map<Integer, Integer> precomputeCapacityMap(
    List<Task> allTasks,
    List<Satellite> allSatellites,
    List<Integer> candidateTaskSequence) {
    Map<Integer, Integer> capacityMap = new HashMap<>();
    if (allTasks.isEmpty() || allSatellites.isEmpty()) {
      return capacityMap;
    }
    double totalDuration = 0.0;
    int durationCount = 0;
    @SuppressWarnings("unchecked")
    List<double[]>[] windowsBySat = new List[allSatellites.size()];
    for (int i = 0; i < windowsBySat.length; i++) {
      windowsBySat[i] = new ArrayList<>();
    }
    for (int taskId : candidateTaskSequence) {
      if (taskId < 0 || taskId >= allTasks.size()) {
        continue;
      }
      Task task = allTasks.get(taskId);
      totalDuration += task.getImagingDuration();
      durationCount++;
      for (int satIndex = 0; satIndex < allSatellites.size(); satIndex++) {
        List<method.rkhma.legacy.model.TimeWindow> windows = task.getTimeWindowsForSatellite(allSatellites.get(satIndex).getId());
        if (windows == null) {
          continue;
        }
        for (method.rkhma.legacy.model.TimeWindow window : windows) {
          if (window == null) {
            continue;
          }
          windowsBySat[satIndex].add(new double[] {window.getStartTime(), window.getEndTime()});
        }
      }
    }
    double avgDuration = durationCount > 0 ? totalDuration / durationCount : 1.0;
    double overheadFactor = 2.0;
    for (int satIndex = 0; satIndex < allSatellites.size(); satIndex++) {
      List<double[]> windows = windowsBySat[satIndex];
      double totalPhysicalTime = mergeWindowLength(windows);
      int capacity = windows.isEmpty()
        ? 0
        : Math.max(1, (int) (totalPhysicalTime / (avgDuration * overheadFactor)));
      capacityMap.put(allSatellites.get(satIndex).getId(), capacity);
    }
    return capacityMap;
  }

  private static double mergeWindowLength(List<double[]> windows) {
    if (windows == null || windows.isEmpty()) {
      return 0.0;
    }
    windows.sort((a, b) -> Double.compare(a[0], b[0]));
    double total = 0.0;
    double mergedStart = windows.get(0)[0];
    double mergedEnd = windows.get(0)[1];
    for (int i = 1; i < windows.size(); i++) {
      double start = windows.get(i)[0];
      double end = windows.get(i)[1];
      if (start <= mergedEnd) {
        mergedEnd = Math.max(mergedEnd, end);
      } else {
        total += mergedEnd - mergedStart;
        mergedStart = start;
        mergedEnd = end;
      }
    }
    return total + mergedEnd - mergedStart;
  }

  private static List<Integer> buildPriorityOrderedTaskIds(List<Task> allTasks) {
    int n = allTasks.size();
    int[] order = new int[n];
    int[] scratch = new int[n];
    for (int i = 0; i < n; i++) {
      order[i] = i;
    }
    stableSortByPriorityDesc(order, scratch, 0, n, allTasks);
    List<Integer> taskIds = new ArrayList<>(n);
    for (int index : order) {
      taskIds.add(allTasks.get(index).getId());
    }
    return taskIds;
  }

  private static void stableSortByPriorityDesc(
    int[] order,
    int[] scratch,
    int from,
    int to,
    List<Task> allTasks) {
    int length = to - from;
    if (length <= 1) {
      return;
    }
    int mid = from + (length >>> 1);
    stableSortByPriorityDesc(order, scratch, from, mid, allTasks);
    stableSortByPriorityDesc(order, scratch, mid, to, allTasks);
    int left = from;
    int right = mid;
    int out = from;
    while (left < mid && right < to) {
      int leftIndex = order[left];
      int rightIndex = order[right];
      int leftPriority = allTasks.get(leftIndex).getPriority();
      int rightPriority = allTasks.get(rightIndex).getPriority();
      if (leftPriority >= rightPriority) {
        scratch[out++] = order[left++];
      } else {
        scratch[out++] = order[right++];
      }
    }
    while (left < mid) {
      scratch[out++] = order[left++];
    }
    while (right < to) {
      scratch[out++] = order[right++];
    }
    System.arraycopy(scratch, from, order, from, length);
  }
}
