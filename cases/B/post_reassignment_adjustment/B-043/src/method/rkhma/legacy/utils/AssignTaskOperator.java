
package method.rkhma.legacy.utils;

import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.SatelliteTaskPair;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.model.TimeWindow;
import method.rkhma.legacy.operator.assignment.*;

import java.util.*;

/**
 * Task assignment operator for satellite scheduling.
 * Provides multiple assignment strategies to allocate tasks to satellites.
 *
 * Refactored to use strategy pattern for better maintainability and to eliminate code duplication.
 */
public class AssignTaskOperator {

  private final List<Task> allTasks;
  private final List<Satellite> allSatellites;
  private final List<Integer> candidateTaskSequence;
  private final Random random;
  private final Map<Integer, Integer> satelliteIndexMap;
  private Map<Integer, Integer> precomputedCapacityMap;


  /**
   * Constructor
   *
   * @param allTasks List of all tasks
   * @param allSatellites List of all satellites
   * @param candidateTaskSequence Sequence of candidate task IDs to assign
   */
  public AssignTaskOperator(List<Task> allTasks, List<Satellite> allSatellites,
    List<Integer> candidateTaskSequence) {
    if (allTasks == null || allSatellites == null || candidateTaskSequence == null) {
      throw new IllegalArgumentException("Input parameters cannot be null");
    }

    this.allTasks = allTasks;
    this.allSatellites = allSatellites;
    this.candidateTaskSequence = candidateTaskSequence;
    // Use shared Random from RandomProvider for reproducibility
    this.random = method.rkhma.legacy.common.RandomProvider.get();
    this.satelliteIndexMap = buildSatelliteIndexMap();
  }

  /**
   * Build a map from satellite ID to its index in the allSatellites list.
   * This improves performance by avoiding repeated linear searches.
   *
   * @return Map from satellite ID to index
   */
  private Map<Integer, Integer> buildSatelliteIndexMap() {
    Map<Integer, Integer> map = new HashMap<>();
    for (int i = 0; i < allSatellites.size(); i++) {
      map.put(allSatellites.get(i).getId(), i);
    }
    return map;
  }

  public void setPrecomputedCapacityMap(Map<Integer, Integer> capacityMap) {
    this.precomputedCapacityMap = capacityMap;
  }

  /**
   * Find satellites that can accommodate the task.
   *
   * @param task The task to check
   * @return List of available satellites
   */
  private List<Satellite> findAvailableSatellites(Task task) {
    List<Satellite> availableSatellites = new ArrayList<>();
    for (int j = 0; j < allSatellites.size(); j++) {
      for (TimeWindow tw : task.getTimeWindowsForSatellite(j)) {
        if (tw.canAccommodateTask(task.getImagingDuration())) {
          availableSatellites.add(allSatellites.get(j));
          break;
        }
      }
    }
    return availableSatellites;
  }

  /**
   * Assign tasks using a given strategy.
   *
   * @param strategy The assignment strategy to use
   * @return List of satellite-task pairs
   */
  private List<SatelliteTaskPair> assignWithStrategy(AssignmentStrategy strategy) {
    return assignWithStrategy(strategy, candidateTaskSequence);
  }

  private List<SatelliteTaskPair> assignWithStrategy(AssignmentStrategy strategy,
      List<Integer> taskSequence) {
    // If strategy supports capacity, pre-compute and provide it
    if (strategy instanceof CompositeWithCapacityStrategy) {
      Map<Integer, Integer> capacityMap = precomputedCapacityMap != null
        ? precomputedCapacityMap
        : precomputeCapacityMap();
      ((CompositeWithCapacityStrategy) strategy).setCapacityMap(capacityMap);
    }

    List<SatelliteTaskPair> satelliteTaskPairs = new ArrayList<>();

    // Save initial task counts so we can restore satellite state after assignment
    Map<Integer, Integer> initialTaskCounts = new HashMap<>();
    for (Satellite sat : allSatellites) {
      initialTaskCounts.put(sat.getId(), sat.getTaskCount());
    }

    for (int taskId : taskSequence) {
      if (taskId < 0 || taskId >= allTasks.size()) {
        continue;
      }

      Task task = allTasks.get(taskId);
      List<Satellite> availableSatellites = findAvailableSatellites(task);

      if (!availableSatellites.isEmpty()) {
        Satellite selectedSatellite = strategy.selectSatellite(
          task, availableSatellites, allSatellites, satelliteIndexMap
        );

        if (selectedSatellite != null) {
          satelliteTaskPairs.add(new SatelliteTaskPair(selectedSatellite.getId(), taskId));
          // Temporarily add task so strategies see accurate counts/state
          selectedSatellite.addTask(task);
        }
      }
    }

    // Restore satellites to their original state
    for (Satellite sat : allSatellites) {
      int originalCount = initialTaskCounts.getOrDefault(sat.getId(), 0);
      List<Task> tasks = sat.getAssignedTasks();
      while (tasks.size() > originalCount) {
        tasks.remove(tasks.size() - 1);
      }
    }

    return satelliteTaskPairs;
  }

  /**
   * Convert SatelliteTaskPair list to List<List<Integer>> format.
   *
   * @param pairs List of satellite-task pairs
   * @return List of [satelliteId, taskId] pairs
   */
  private List<List<Integer>> convertToTaskIdPairs(List<SatelliteTaskPair> pairs) {
    List<List<Integer>> result = new ArrayList<>();
    for (SatelliteTaskPair pair : pairs) {
      result.add(Arrays.asList(pair.getSatelliteId(), pair.getTaskId()));
    }
    return result;
  }

  /**
   * Assign tasks randomly.
   *
   * @return List of [satelliteId, taskId] pairs
   */
  public List<List<Integer>> assignRandomly() {
    return convertToTaskIdPairs(assignWithStrategy(new RandomStrategy(random)));
  }

  /**
   * Assign tasks based on minimizing task count per satellite.
   * Selects satellites with fewer assigned tasks to balance the load.
   *
   * @return List of [satelliteId, taskId] pairs
   */
  public List<List<Integer>> assignBasedTaskNums() {
    return convertToTaskIdPairs(assignWithStrategy(new MinTaskCountStrategy()));
  }

  /**
   * Assign tasks based on maximizing the number of available time windows.
   * Selects satellites with more time windows for flexibility.
   *
   * @return List of [satelliteId, taskId] pairs
   */
  public List<List<Integer>> assignBasedWindowNums() {
    return convertToTaskIdPairs(assignWithStrategy(new MaxWindowCountStrategy()));
  }

  /**
   * Assign tasks based on minimizing roll angle.
   * Selects satellites with smaller attitude adjustments.
   *
   * @return List of [satelliteId, taskId] pairs
   */
  public List<List<Integer>> assignBasedMinRollAngle() {
    return convertToTaskIdPairs(assignWithStrategy(new MinRollAngleStrategy()));
  }

  /**
   * Assign tasks based on earliest available time.
   * Selects satellites with the earliest time windows.
   *
   * @return List of [satelliteId, taskId] pairs
   */
  public List<List<Integer>> assignBasedEarliestTime() {
    return convertToTaskIdPairs(assignWithStrategy(new EarliestTimeStrategy()));
  }

  /**
   * Assign tasks based on longest window duration.
   * Selects satellites with longer total usable window lengths.
   *
   * @return List of [satelliteId, taskId] pairs
   */
  public List<List<Integer>> assignBasedLongestWindow() {
    return convertToTaskIdPairs(assignWithStrategy(new MaxWindowLengthStrategy()));
  }

  /**
   * Assign tasks based on minimum average priority.
   * Selects satellites with lower average priority to balance priority distribution.
   *
   * @return List of [satelliteId, taskId] pairs
   */
  public List<List<Integer>> assignBasedMinPriority() {
    return convertToTaskIdPairs(assignWithStrategy(new MinPriorityStrategy()));
  }

  /**
   * Assign tasks using composite strategy (multi-dimensional weighted + conflict-aware).
   *
   * @return List of [satelliteId, taskId] pairs
   */
  public List<List<Integer>> assignComposite() {
    return convertToTaskIdPairs(assignWithStrategy(new CompositeStrategy()));
  }

  /**
   * Assign tasks using composite strategy with post-processing steal.
   * Phase 1: CompositeStrategy (same as strategy 7) for all tasks.
   * Phase 2: Scan 0-task satellites with feasible windows, steal the
   * highest-priority feasible task from a loaded source satellite.
   *
   * @return List of [satelliteId, taskId] pairs
   */
  public List<List<Integer>> assignCompositeWithCapacity() {
    // Diagnostic: feasible satellite count per task
    Map<Integer, Integer> feasibleCountMap = new HashMap<>();
    for (int taskId : candidateTaskSequence) {
      if (taskId < 0 || taskId >= allTasks.size()) continue;
      Task task = allTasks.get(taskId);
      int count = 0;
      for (int j = 0; j < allSatellites.size(); j++) {
        for (TimeWindow tw : task.getTimeWindowsForSatellite(j)) {
          if (tw.canAccommodateTask(task.getImagingDuration())) {
            count++;
            break;
          }
        }
      }
      feasibleCountMap.put(taskId, count);
    }
    // Histogram (diagnostic only — suppressed)
    // Reorder: 1-feasible tasks first (forced assignments), then rest, priority desc within each group
    List<Integer> reorderedSequence = new ArrayList<>(candidateTaskSequence);
    reorderedSequence.sort((a, b) -> {
      boolean forcedA = (feasibleCountMap.getOrDefault(a, 0) == 1);
      boolean forcedB = (feasibleCountMap.getOrDefault(b, 0) == 1);
      if (forcedA != forcedB) return forcedA ? -1 : 1;
      return Integer.compare(allTasks.get(b).getPriority(), allTasks.get(a).getPriority());
    });

    // Phase 1: Main assignment using CompositeStrategy (forced-first ordering)
    List<SatelliteTaskPair> allPairs = assignWithStrategy(new CompositeStrategy(), reorderedSequence);

    // Phase 2: Post-processing steal for 0-task satellites
    Map<Integer, List<Integer>> satTaskMap = new HashMap<>();
    for (SatelliteTaskPair pair : allPairs) {
      satTaskMap.computeIfAbsent(pair.getSatelliteId(), k -> new ArrayList<>())
          .add(pair.getTaskId());
    }
    Set<Integer> assignedTaskIds = new HashSet<>();
    for (SatelliteTaskPair pair : allPairs) {
      assignedTaskIds.add(pair.getTaskId());
    }

    int stealCount = 0;
    for (Satellite sat : allSatellites) {
      List<Integer> existing = satTaskMap.get(sat.getId());
      if (existing != null && !existing.isEmpty()) continue;

      // Find feasible tasks for this satellite
      int satIdx = satelliteIndexMap.get(sat.getId());
      List<Integer> feasible = new ArrayList<>();
      for (int taskId : candidateTaskSequence) {
        if (taskId < 0 || taskId >= allTasks.size()) continue;
        Task task = allTasks.get(taskId);
        for (TimeWindow tw : task.getTimeWindowsForSatellite(satIdx)) {
          if (tw.canAccommodateTask(task.getImagingDuration())) {
            feasible.add(taskId);
            break;
          }
        }
      }
      if (feasible.isEmpty()) continue;

      // Sort by priority descending
      feasible.sort((a, b) -> Integer.compare(
          allTasks.get(b).getPriority(), allTasks.get(a).getPriority()));

      // Try unassigned first, then steal from loaded source
      for (int taskId : feasible) {
        if (!assignedTaskIds.contains(taskId)) {
          allPairs.add(new SatelliteTaskPair(sat.getId(), taskId));
          satTaskMap.computeIfAbsent(sat.getId(), k -> new ArrayList<>()).add(taskId);
          assignedTaskIds.add(taskId);
          stealCount++;
          break;
        }

        // Find source satellite holding this task
        int sourceSatId = -1;
        for (Map.Entry<Integer, List<Integer>> entry : satTaskMap.entrySet()) {
          if (entry.getValue().contains(taskId)) {
            sourceSatId = entry.getKey();
            break;
          }
        }
        if (sourceSatId < 0) continue;

        List<Integer> sourceTasks = satTaskMap.get(sourceSatId);
        if (sourceTasks != null && sourceTasks.size() > 1) {
          sourceTasks.remove(Integer.valueOf(taskId));
          final int src = sourceSatId;
          allPairs.removeIf(p -> p.getSatelliteId() == src && p.getTaskId() == taskId);
          allPairs.add(new SatelliteTaskPair(sat.getId(), taskId));
          satTaskMap.computeIfAbsent(sat.getId(), k -> new ArrayList<>()).add(taskId);
          stealCount++;
          break;
        }
      }
    }
    if (stealCount > 0) {
    }

    return convertToTaskIdPairs(allPairs);
  }

  /**
   * Pre-compute scheduling capacity for each satellite based on physical time window resources.
   * Merges overlapping windows per satellite to estimate actual available observation time,
   * then divides by average imaging duration to estimate task capacity.
   *
   * @return map from satellite ID to estimated scheduling capacity
   */
  private Map<Integer, Integer> precomputeCapacityMap() {
    int nSat = allSatellites.size();

    // Collect all windows per satellite and compute average imaging duration
    @SuppressWarnings("unchecked")
    List<double[]>[] windowsBySat = new List[nSat];
    for (int j = 0; j < nSat; j++) {
      windowsBySat[j] = new ArrayList<>();
    }

    double totalImagingDuration = 0;
    int validTaskCount = 0;
    for (int taskId : candidateTaskSequence) {
      if (taskId < 0 || taskId >= allTasks.size()) continue;
      Task task = allTasks.get(taskId);
      totalImagingDuration += task.getImagingDuration();
      validTaskCount++;
      for (int j = 0; j < nSat; j++) {
        for (TimeWindow tw : task.getTimeWindowsForSatellite(j)) {
          windowsBySat[j].add(new double[]{tw.getStartTime(), tw.getEndTime()});
        }
      }
    }

    double avgDuration = validTaskCount > 0 ? totalImagingDuration / validTaskCount : 1.0;
    // Overhead factor accounts for transition time + non-perfect packing
    double overheadFactor = 2.0;

    Map<Integer, Integer> capacityMap = new HashMap<>();
    int[] rawWindowCounts = new int[nSat];
    double[] physicalTimes = new double[nSat];
    for (int j = 0; j < nSat; j++) {
      List<double[]> windows = windowsBySat[j];
      if (windows.isEmpty()) {
        capacityMap.put(allSatellites.get(j).getId(), 0);
        continue;
      }

      // Sort by start time, merge overlapping intervals
      windows.sort((a, b) -> Double.compare(a[0], b[0]));
      double totalPhysicalTime = 0;
      double mergedStart = windows.get(0)[0];
      double mergedEnd = windows.get(0)[1];
      for (int i = 1; i < windows.size(); i++) {
        if (windows.get(i)[0] <= mergedEnd) {
          mergedEnd = Math.max(mergedEnd, windows.get(i)[1]);
        } else {
          totalPhysicalTime += mergedEnd - mergedStart;
          mergedStart = windows.get(i)[0];
          mergedEnd = windows.get(i)[1];
        }
      }
      totalPhysicalTime += mergedEnd - mergedStart;

      int capacity = Math.max(1, (int) (totalPhysicalTime / (avgDuration * overheadFactor)));
      capacityMap.put(allSatellites.get(j).getId(), capacity);
      rawWindowCounts[j] = windows.size();
      physicalTimes[j] = totalPhysicalTime;
    }

    // Diagnostic: print capacity summary
    System.out.printf("[CapacityMap] avgDuration=%.2f  overheadFactor=%.1f%n", avgDuration, overheadFactor);
    // Collect and sort by capacity descending
    int[][] entries = new int[nSat][2]; // [satId, capacity]
    for (int j = 0; j < nSat; j++) {
      entries[j][0] = allSatellites.get(j).getId();
      entries[j][1] = capacityMap.getOrDefault(entries[j][0], 0);
    }
    java.util.Arrays.sort(entries, (a, b) -> Integer.compare(b[1], a[1]));
    System.out.println("[CapacityMap]  SatId | rawWindows | physicalTime(s) | capacity");
    for (int k = 0; k < Math.min(20, nSat); k++) {
      int satId = entries[k][0];
      int cap = entries[k][1];
      // Find original index for this satellite
      int origIdx = -1;
      for (int j = 0; j < nSat; j++) {
        if (allSatellites.get(j).getId() == satId) { origIdx = j; break; }
      }
      if (origIdx >= 0) {
        System.out.printf("[CapacityMap]  %5d | %10d | %15.1f | %8d%n",
            satId, rawWindowCounts[origIdx], physicalTimes[origIdx], cap);
      }
    }
    // Also print satellites 40-42 explicitly if not in top 20
    for (int satId = 40; satId <= 42; satId++) {
      boolean alreadyPrinted = false;
      for (int k = 0; k < Math.min(20, nSat); k++) {
        if (entries[k][0] == satId) { alreadyPrinted = true; break; }
      }
      if (!alreadyPrinted) {
        int origIdx = -1;
        for (int j = 0; j < nSat; j++) {
          if (allSatellites.get(j).getId() == satId) { origIdx = j; break; }
        }
        if (origIdx >= 0) {
          System.out.printf("[CapacityMap]  %5d | %10d | %15.1f | %8d  (extra)%n",
              satId, rawWindowCounts[origIdx], physicalTimes[origIdx],
              capacityMap.getOrDefault(satId, 0));
        }
      }
    }
    // Print zero-capacity satellites
    StringBuilder zeroCap = new StringBuilder("[CapacityMap] zero-capacity satellites: ");
    int zeroCount = 0;
    for (int j = 0; j < nSat; j++) {
      int satId = allSatellites.get(j).getId();
      if (capacityMap.getOrDefault(satId, 0) == 0) {
        if (zeroCount > 0) zeroCap.append(", ");
        zeroCap.append(satId);
        zeroCount++;
      }
    }
    System.out.println(zeroCap.toString() + " (total=" + zeroCount + ")");
    System.out.println("[CapacityMap] -------- end --------");

    return capacityMap;
  }

  /**
   * Apply the satellite-task pairs to actually assign tasks to satellites.
   *
   * @param satelliteTaskPairList List of [satelliteId, taskId] pairs
   * @param allTasks List of all tasks
   * @param allSatellites List of all satellites
   */
  public static void assignTaskToSatellites(List<List<Integer>> satelliteTaskPairList,
    List<Task> allTasks,
    List<Satellite> allSatellites) {
    if (satelliteTaskPairList == null || allTasks == null || allSatellites == null) {
      throw new IllegalArgumentException("Input parameters cannot be null");
    }

    // Create a map of satellite ID to satellite object for faster lookup
    Map<Integer, Satellite> satelliteMap = new HashMap<>();
    for (Satellite sat : allSatellites) {
      satelliteMap.put(sat.getId(), sat);
    }

    for (List<Integer> pair : satelliteTaskPairList) {
      if (pair == null || pair.size() < 2) {
        continue;
      }

      int satelliteId = pair.get(0);
      int taskId = pair.get(1);

      if (taskId < 0 || taskId >= allTasks.size()) {
        continue;
      }

      Satellite sat = satelliteMap.get(satelliteId);
      if (sat != null) {
        sat.addTask(allTasks.get(taskId));
      }
    }
  }
}
