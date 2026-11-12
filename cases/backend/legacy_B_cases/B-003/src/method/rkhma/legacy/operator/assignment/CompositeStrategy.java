package method.rkhma.legacy.operator.assignment;

import method.rkhma.legacy.common.TuningConfig;
import method.rkhma.legacy.common.TuningDefaults;
import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.model.TimeWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dependencies:
 *   common.TuningConfig — tunable weights
 *   common.TuningDefaults — weight defaults
 *   model.Satellite / Task / TimeWindow — domain model
 *
 * Multi-dimensional weighted + conflict-aware assignment strategy.
 * Scores satellites by combining window length, window count, load balance, and time window conflict dimensions.
 */
public class CompositeStrategy extends AbstractAssignmentStrategy {

    private static final double W_WINDOW_LENGTH = TuningConfig.getDouble("assign.compositeWindowLengthWeight", TuningDefaults.ASSIGN_COMPOSITE_WINDOW_LENGTH_WEIGHT);
    private static final double W_WINDOW_COUNT = TuningConfig.getDouble("assign.compositeWindowCountWeight", TuningDefaults.ASSIGN_COMPOSITE_WINDOW_COUNT_WEIGHT);
    private static final double W_LOAD_BALANCE = TuningConfig.getDouble("assign.compositeLoadBalanceWeight", TuningDefaults.ASSIGN_COMPOSITE_LOAD_BALANCE_WEIGHT);
    private static final double W_CONFLICT = TuningConfig.getDouble("assign.compositeConflictWeight", TuningDefaults.ASSIGN_COMPOSITE_CONFLICT_WEIGHT);
    private static final double SCARCITY_BOOST = TuningConfig.getDouble("assign.compositeScarcityBoost", TuningDefaults.ASSIGN_COMPOSITE_SCARCITY_BOOST);

    @Override
    public Satellite selectSatellite(Task task, List<Satellite> availableSatellites,
        List<Satellite> allSatellites,
        Map<Integer, Integer> satelliteIndexMap) {
        if (availableSatellites.isEmpty()) {
            return null;
        }

        // Collect raw metrics for each candidate satellite
        List<Satellite> candidates = new ArrayList<>();
        List<Integer> windowLengths = new ArrayList<>();
        List<Integer> windowCounts = new ArrayList<>();
        List<Integer> taskCounts = new ArrayList<>();

        for (Satellite sat : availableSatellites) {
            Integer satIndex = satelliteIndexMap.get(sat.getId());
            if (satIndex == null) continue;
            int length = calculateUsableWindowLength(task, satIndex);
            if (length <= 0) continue;
            candidates.add(sat);
            windowLengths.add(length);
            windowCounts.add(countAvailableWindows(task, satIndex));
            taskCounts.add(sat.getTaskCount());
        }

        if (candidates.isEmpty()) {
            return null;
        }

        // Normalization baselines
        int maxWindowLength = max(windowLengths);
        int maxWindowCount = max(windowCounts);
        int maxTaskCount = Math.max(1, max(taskCounts));

        // Task scarcity: fewer candidate satellites → higher scarcity factor
        double scarcityFactor = 1.0 / Math.max(1, availableSatellites.size());
        double effectiveWL = W_WINDOW_LENGTH * (1.0 + SCARCITY_BOOST * scarcityFactor);
        double effectiveLB = W_LOAD_BALANCE * (1.0 - SCARCITY_BOOST * scarcityFactor);

        // Scoring
        double bestScore = Double.NEGATIVE_INFINITY;
        Satellite bestSat = null;
        for (int i = 0; i < candidates.size(); i++) {
            double wlScore = (double) windowLengths.get(i) / Math.max(1, maxWindowLength);
            double wcScore = (double) windowCounts.get(i) / Math.max(1, maxWindowCount);
            double lbScore = 1.0 - (double) taskCounts.get(i) / maxTaskCount;
            double cfScore = computeConflictScore(task, candidates.get(i), satelliteIndexMap);

            double score = effectiveWL * wlScore
                + W_WINDOW_COUNT * wcScore
                + effectiveLB * lbScore
                - W_CONFLICT * cfScore;

            if (score > bestScore) {
                bestScore = score;
                bestSat = candidates.get(i);
            }
        }
        return bestSat;
    }

    /**
     * Compute the time window conflict score between a task and the assigned tasks on a satellite.
     * Returns [0,1]: overlapping window pairs / (task window count x total assigned task window count).
     */
    private double computeConflictScore(Task task, Satellite satellite, Map<Integer, Integer> satelliteIndexMap) {
        Integer satIndex = satelliteIndexMap.get(satellite.getId());
        if (satIndex == null) {
            return 0.0;
        }
        List<TimeWindow> taskWindows = task.getTimeWindowsForSatellite(satIndex);
        if (taskWindows.isEmpty()) {
            return 0.0;
        }
        List<Task> assignedTasks = satellite.getAssignedTasks();
        if (assignedTasks == null || assignedTasks.isEmpty()) {
            return 0.0;
        }
        int overlapCount = 0;
        int totalAssignedWindows = 0;
        for (Task assigned : assignedTasks) {
            List<TimeWindow> assignedWindows = assigned.getTimeWindowsForSatellite(satIndex);
            totalAssignedWindows += assignedWindows.size();
            for (TimeWindow tw : taskWindows) {
                for (TimeWindow aw : assignedWindows) {
                    if (tw.overlaps(aw)) {
                        overlapCount++;
                    }
                }
            }
        }
        int totalPairs = taskWindows.size() * totalAssignedWindows;
        return totalPairs == 0 ? 0.0 : (double) overlapCount / totalPairs;
    }

    private static int max(List<Integer> values) {
        int m = Integer.MIN_VALUE;
        for (int v : values) {
            if (v > m) m = v;
        }
        return m;
    }

    @Override
    public String getName() {
        return "Composite";
    }
}
