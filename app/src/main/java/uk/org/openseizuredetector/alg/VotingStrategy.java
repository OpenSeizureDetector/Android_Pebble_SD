package uk.org.openseizuredetector.alg;

import android.util.Log;

import java.util.List;

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
     * @return Combined alarm state (0=OK, 1=WARNING, 2=ALARM)
     */
    public int vote(List<AlgorithmResult> results) {
        if (results == null || results.isEmpty()) {
            Log.w(TAG, "vote(): No results to vote on");
            return 0;
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
     * ANY strategy: If any algorithm reports ALARM (2), return ALARM.
     * This is the current default behavior (OR logic).
     */
    private int anyVote(List<AlgorithmResult> results) {
        boolean hasAlarm = false;
        boolean hasWarning = false;

        for (AlgorithmResult result : results) {
            if (result.alarmState == 2) {
                hasAlarm = true;
                break;
            } else if (result.alarmState == 1) {
                hasWarning = true;
            }
        }

        if (hasAlarm) return 2;
        if (hasWarning) return 1;
        return 0;
    }

    /**
     * MAJORITY strategy: At least 50% of algorithms must report ALARM for overall ALARM.
     */
    private int majorityVote(List<AlgorithmResult> results) {
        int alarmCount = 0;
        int warningCount = 0;
        int total = results.size();

        for (AlgorithmResult result : results) {
            if (result.alarmState == 2) {
                alarmCount++;
            } else if (result.alarmState == 1) {
                warningCount++;
            }
        }

        // Need more than 50% for ALARM
        if (alarmCount > total / 2.0) {
            return 2;
        }
        // If we have any warnings and haven't reached alarm threshold
        if (warningCount > 0 || alarmCount > 0) {
            return 1;
        }
        return 0;
    }

    /**
     * UNANIMOUS strategy: ALL algorithms must report ALARM for overall ALARM (AND logic).
     */
    private int unanimousVote(List<AlgorithmResult> results) {
        boolean allAlarm = true;
        boolean anyWarning = false;

        for (AlgorithmResult result : results) {
            if (result.alarmState != 2) {
                allAlarm = false;
            }
            if (result.alarmState == 1) {
                anyWarning = true;
            }
        }

        if (allAlarm) return 2;
        if (anyWarning) return 1;
        return 0;
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
            return 0;
        }

        double weightedAverage = weightedSum / totalWeight;

        Log.v(TAG, "weightedVote(): weightedSum=" + weightedSum +
                   ", totalWeight=" + totalWeight +
                   ", weightedAverage=" + weightedAverage);

        // Threshold for ALARM (2) is > 1.5
        if (weightedAverage > 1.5) {
            return 2;
        }
        // Threshold for WARNING (1) is > 0.5
        if (weightedAverage > 0.5) {
            return 1;
        }
        return 0;
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

