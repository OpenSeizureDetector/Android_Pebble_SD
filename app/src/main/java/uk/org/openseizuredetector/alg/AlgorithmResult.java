package uk.org.openseizuredetector.alg;

/**
 * AlgorithmResult - Encapsulates the result from a single algorithm evaluation.
 * Used for voting across multiple algorithms.
 */
public class AlgorithmResult {
    public final String algorithmName;
    public final int alarmState;      // 0=OK, 1=WARNING, 2=ALARM
    public final float confidence;    // 0.0 to 1.0 (for ML models, this is the probability)
    public final float weight;        // Weight for weighted voting

    public AlgorithmResult(String algorithmName, int alarmState, float confidence, float weight) {
        this.algorithmName = algorithmName;
        this.alarmState = alarmState;
        this.confidence = confidence;
        this.weight = weight;
    }

    @Override
    public String toString() {
        return "AlgorithmResult{" +
                "name='" + algorithmName + '\'' +
                ", state=" + alarmState +
                ", confidence=" + confidence +
                ", weight=" + weight +
                '}';
    }
}

