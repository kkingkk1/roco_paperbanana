package method.rkhma.legacy.main;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import method.rkhma.legacy.common.TuningConfig;
import method.rkhma.legacy.common.TuningDefaults;
import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.SchedulingPlan;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.model.TimeWindow;

final class BundleMoveEvaluator {
  private static final int BUNDLE_MAX_SIZE = TuningConfig.getInt("coord.bundleMaxSize", TuningDefaults.COORD_BUNDLE_MAX_SIZE);
  private static final double BUNDLE_TIME_PROXIMITY_THRESHOLD = TuningConfig.getDouble("coord.bundleTimeProximityThreshold", TuningDefaults.COORD_BUNDLE_TIME_PROXIMITY_THRESHOLD);
  private static final int BUNDLE_MAX_ATTEMPTS_PER_ROUND = TuningConfig.getInt("coord.bundleMaxAttemptsPerRound", TuningDefaults.COORD_BUNDLE_MAX_ATTEMPTS_PER_ROUND);

  private final ReassignmentMoveServices services;

  BundleMoveEvaluator(ReassignmentMoveServices services) {
    this.services = services;
  }

  List<List<Task>> buildBundleCandidates(
    List<Task> failedTasks,
    Map<Integer, Integer> currentAssignment,
    int currentIter
  ) {
    if (failedTasks.size() < 2) {
      return Collections.emptyList();
    }
    Map<Integer, Map<Integer, Double>> taskSatEarliestStart = new HashMap<>();
    for (Task task : failedTasks) {
      Map<Integer, Double> satelliteStarts = new HashMap<>();
      for (Satellite satellite : services.satellites()) {
        List<TimeWindow> windows = task.getTimeWindowsForSatellite(satellite.getId());
        if (windows == null || windows.isEmpty()) {
          continue;
        }
        double earliest = Double.MAX_VALUE;
        for (TimeWindow window : windows) {
          if (window.getStartTime() < earliest) {
            earliest = window.getStartTime();
          }
        }
        satelliteStarts.put(satellite.getId(), earliest);
      }
      if (!satelliteStarts.isEmpty()) {
        taskSatEarliestStart.put(task.getId(), satelliteStarts);
      }
    }
    Set<String> usedBundleKeys = new HashSet<>();
    List<List<Task>> bundles = new ArrayList<>();
    for (int i = 0; i < failedTasks.size() && bundles.size() < BUNDLE_MAX_ATTEMPTS_PER_ROUND * 2; i++) {
      Task taskA = failedTasks.get(i);
      Map<Integer, Double> startsA = taskSatEarliestStart.get(taskA.getId());
      if (startsA == null) {
        continue;
      }
      for (int j = i + 1; j < failedTasks.size() && bundles.size() < BUNDLE_MAX_ATTEMPTS_PER_ROUND * 2; j++) {
        Task taskB = failedTasks.get(j);
        Map<Integer, Double> startsB = taskSatEarliestStart.get(taskB.getId());
        if (startsB == null) {
          continue;
        }
        for (Map.Entry<Integer, Double> entryA : startsA.entrySet()) {
          int satelliteId = entryA.getKey();
          Double startB = startsB.get(satelliteId);
          if (startB == null) {
            continue;
          }
          if (Math.abs(entryA.getValue() - startB) <= BUNDLE_TIME_PROXIMITY_THRESHOLD) {
            String key2 = Math.min(taskA.getId(), taskB.getId()) + "-"
              + Math.max(taskA.getId(), taskB.getId());
            if (usedBundleKeys.contains(key2)) {
              break;
            }
            usedBundleKeys.add(key2);
            List<Task> pair = new ArrayList<>();
            pair.add(taskA);
            pair.add(taskB);
            if (pair.size() < BUNDLE_MAX_SIZE) {
              for (int k = j + 1; k < failedTasks.size(); k++) {
                Task taskC = failedTasks.get(k);
                Map<Integer, Double> startsC = taskSatEarliestStart.get(taskC.getId());
                if (startsC == null) {
                  continue;
                }
                Double startC = startsC.get(satelliteId);
                if (startC != null
                  && Math.abs(entryA.getValue() - startC) <= BUNDLE_TIME_PROXIMITY_THRESHOLD
                  && Math.abs(startB - startC) <= BUNDLE_TIME_PROXIMITY_THRESHOLD) {
                  pair.add(taskC);
                  break;
                }
              }
            }
            bundles.add(pair);
            break;
          }
        }
      }
    }
    bundles.sort((a, b) -> {
      int sizeComparison = Integer.compare(b.size(), a.size());
      if (sizeComparison != 0) {
        return sizeComparison;
      }
      int sumA = a.stream().mapToInt(Task::getPriority).sum();
      int sumB = b.stream().mapToInt(Task::getPriority).sum();
      return Integer.compare(sumB, sumA);
    });
    return bundles.size() > BUNDLE_MAX_ATTEMPTS_PER_ROUND
      ? bundles.subList(0, BUNDLE_MAX_ATTEMPTS_PER_ROUND)
      : bundles;
  }

  TrialMoveResult tryBundleMove(
    List<Task> bundle,
    int currentIter,
    Map<Integer, Integer> baseAssignment,
    Map<Integer, SchedulingPlan> basePlans,
    double baseProfit,
    TaskAllocationCoordinator.HybCecConfig config
  ) throws Exception {
    Set<Integer> commonSatellites = collectCommonTargetSatellites(bundle, currentIter);
    if (commonSatellites == null || commonSatellites.isEmpty()) {
      return null;
    }
    TrialMoveResult bestResult = null;
    double bestProfitGain = 0.0;
    for (int targetSatId : commonSatellites) {
      TrialMoveResult trialResult = evaluateBundleTrial(
        bundle,
        targetSatId,
        baseAssignment,
        basePlans,
        baseProfit,
        config
      );
      if (trialResult.profitGain > bestProfitGain) {
        bestProfitGain = trialResult.profitGain;
        bestResult = trialResult;
      }
    }
    return bestResult;
  }

  private Set<Integer> collectCommonTargetSatellites(List<Task> bundle, int currentIter) {
    Set<Integer> commonSatellites = null;
    for (Task task : bundle) {
      Set<Integer> taskSatellites = collectTaskTargetSatellites(task, currentIter);
      if (commonSatellites == null) {
        commonSatellites = taskSatellites;
      } else {
        commonSatellites.retainAll(taskSatellites);
      }
      if (commonSatellites.isEmpty()) {
        return Collections.emptySet();
      }
    }
    return commonSatellites;
  }

  private Set<Integer> collectTaskTargetSatellites(Task task, int currentIter) {
    Set<Integer> taskSatellites = new HashSet<>();
    for (Satellite satellite : services.satellites()) {
      List<TimeWindow> windows = task.getTimeWindowsForSatellite(satellite.getId());
      if (windows != null && !windows.isEmpty()) {
        taskSatellites.add(satellite.getId());
      }
    }
    return taskSatellites;
  }

  private TrialMoveResult evaluateBundleTrial(
    List<Task> bundle,
    int targetSatelliteId,
    Map<Integer, Integer> baseAssignment,
    Map<Integer, SchedulingPlan> basePlans,
    double baseProfit,
    TaskAllocationCoordinator.HybCecConfig config
  ) throws Exception {
    AssignmentOverlayMap trialAssignment = AssignmentOverlayMap.over(baseAssignment);
    Set<Integer> affectedSatelliteIds = new LinkedHashSet<>();
    for (Task task : bundle) {
      Integer sourceSatId = baseAssignment.get(task.getId());
      if (sourceSatId != null) {
        affectedSatelliteIds.add(sourceSatId);
      }
      trialAssignment = trialAssignment.withAssigned(task.getId(), targetSatelliteId);
    }
    affectedSatelliteIds.add(targetSatelliteId);
    return services.evaluateTrialAssignment(
      basePlans,
      baseAssignment,
      trialAssignment,
      affectedSatelliteIds,
      baseProfit,
      config
    );
  }
}
