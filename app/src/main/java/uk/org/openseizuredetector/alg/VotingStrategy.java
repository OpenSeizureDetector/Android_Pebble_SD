package uk.org.openseizuredetector.alg;

import android.util.Log;

import java.util.List;

import uk.org.openseizuredetector.data.AlarmState;

/**
 * VotingStrategy - Implements different voting strategies for combining multiple algorithm results.
 */
public class VotingStrategy {
    private static final String TAG = "VotingStrategy";

    public enum Strategy {
        ANY,        // Any algorithm triggers alarm (OR logic)
        MAJORITY,   // At least 50% must agree
        UNANIMOUS,  // All must agree (AND logic)
        WEIGHTED    // Weighted average based on algorithm weights
    }

    private final Strategy mStrategy;

    public VotingStrategy(Strategy strategy) {
        mStrategy = strategy;
    }

    /**
     * Apply the voting strategy to a list of algorithm results.
     *
     * @param results List of AlgorithmResult from different algorithms
     * @return Combined alarm state (use AlarmState constants)
     */
    public int vote(List<AlgorithmResult> results) {
        if (results == null || results.isEmpty()) {
            Log.w(TAG, "vote(): No results to vote on");
            return AlarmState.OK;
        }

        switch (mStrategy) {
            case ANY:
                return anyVote(results);
            case MAJORITY:
                return majorityVote(results);
            case UNANIMOUS:
                return unanimousVote(results);
            case WEIGHTED:
                return weightedVote(results);
            default:
                Log.w(TAG, "vote(): Unknown strategy, defaulting to ANY");
                return anyVote(results);
        }
    }

    /**
     * ANY strategy: If any algorithm reports ALARM, return ALARM.
     */
    private int anyVote(List<AlgorithmResult> results) {
        boolean hasAlarm = false;
        boolean hasWarning = false;

        for (AlgorithmResult result : results) {
            if (result.alarmState == AlarmState.ALARM) {
                hasAlarm = true;
                break;
            } else if (result.alarmState == AlarmState.WARNING) {
                hasWarning = true;
            }
        }

        if (hasAlarm) return AlarmState.ALARM;
        if (hasWarning) return AlarmState.WARNING;
        return AlarmState.OK;
    }

    /**
     * MAJORITY strategy: At least 50% of algorithms must report ALARM for overall ALARM.
     */
    private int majorityVote(List<AlgorithmResult> results) {
        int alarmCount = 0;
        int warningCount = 0;
        int total = results.size();

        for (AlgorithmResult result : results) {
            if (result.alarmState == AlarmState.ALARM) {
                alarmCount++;
            } else if (result.alarmState == AlarmState.WARNING) {
                warningCount++;
            }
        }

        // Need more than 50% for ALARM
        if (alarmCount > total / 2.0) {
            return AlarmState.ALARM;
        }
        // If we have any warnings and haven't reached alarm threshold
        if (warningCount > 0 || alarmCount > 0) {
            return AlarmState.WARNING;
        }
        return AlarmState.OK;
    }

    /**
     * UNANIMOUS strategy: ALL algorithms must report ALARM for overall ALARM (AND logic).
     */
    private int unanimousVote(List<AlgorithmResult> results) {
        boolean allAlarm = true;
        boolean anyWarning = false;

        for (AlgorithmResult result : results) {
            if (result.alarmState != AlarmState.ALARM) {
                allAlarm = false;
            }
            if (result.alarmState == AlarmState.WARNING) {
                anyWarning = true;
            }
        }

        if (allAlarm) return AlarmState.ALARM;
        if (anyWarning) return AlarmState.WARNING;
        return AlarmState.OK;
    }

    /**
     * WEIGHTED strategy: Calculate weighted average of alarm states.
     * Each algorithm contributes based on its weight and confidence.
     */
    private int weightedVote(List<AlgorithmResult> results) {
        double weightedSum = 0.0;
        double totalWeight = 0.0;

        for (AlgorithmResult result : results) {
            // Multiply alarm state by confidence and weight
            double contribution = result.alarmState * result.confidence * result.weight;
            weightedSum += contribution;
            totalWeight += result.weight;
        }

        if (totalWeight == 0.0) {
            Log.w(TAG, "weightedVote(): Total weight is zero");
            return AlarmState.OK;
        }

        double weightedAverage = weightedSum / totalWeight;

        Log.v(TAG, "weightedVote(): weightedSum=" + weightedSum +
                   ", totalWeight=" + totalWeight +
                   ", weightedAverage=" + weightedAverage);

        // Threshold for ALARM is > 1.5
        if (weightedAverage > 1.5) {
            return AlarmState.ALARM;
        }
        // Threshold for WARNING is > 0.5
        if (weightedAverage > 0.5) {
            return AlarmState.WARNING;
        }
        return AlarmState.OK;
    }

    public Strategy getStrategy() {
        return mStrategy;
    }

    public static Strategy fromString(String strategyStr) {
        if (strategyStr == null || strategyStr.isEmpty()) {
            return Strategy.ANY;
        }

        try {
            return Strategy.valueOf(strategyStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "fromString(): Unknown strategy '" + strategyStr + "', defaulting to ANY");
            return Strategy.ANY;
        }
    }
}

