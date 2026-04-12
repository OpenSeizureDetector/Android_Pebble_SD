/*
  Android_SD - Android host for Garmin or Pebble watch based seizure detectors.
  See http://openseizuredetector.org for more information.

  Copyright Graham Jones, 2026.

  This file is part of Android_SD.

  Android_SD is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.
  
  Android_SD is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.
  
  You should have received a copy of the GNU General Public License
  along with Android_SD.  If not, see <http://www.gnu.org/licenses/>.
*/
package uk.org.openseizuredetector.data;

import uk.org.openseizuredetector.data.logging.Log;
import uk.org.openseizuredetector.utils.CircBuf;

/**
 * SdDataHistory - Maintains persistent history buffers across SdData object replacements.
 *
 * SdData is constantly replaced with new objects as data arrives from the data source.
 * However, we need to preserve the accumulated history in CircBufs (battery, signal strength,
 * seizure probability, etc.). This class holds those shared history buffers that persist
 * while SdData comes and goes.
 *
 * This keeps the architecture clean: SdData represents CURRENT state, SdDataHistory maintains
 * HISTORICAL state. The UI uses both to render graphs with history context.
 */
public class SdDataHistory {
    private static final String TAG = "SdDataHistory";

    // History buffers that persist across SdData replacements
    public CircBuf watchBattBuff = new CircBuf(24 * 3600 / 5, -1);      // 24 hour buffer
    public CircBuf phoneBattBuff = new CircBuf(24 * 3600 / 5, -1);      // 24 hour buffer
    public CircBuf watchSignalStrengthBuff = new CircBuf(10 * 60 / 5, -1); // 10 minute buffer
    public CircBuf mPseizureHistBuf = new CircBuf(120, -1.0);           // 10 minute buffer (120 samples)
    
    // Separate buffers for up to 5 individual ML models
    public CircBuf[] mlModelProbBuffs = new CircBuf[5];
    
    public CircBuf mAccelMagStdDevHistBuf = new CircBuf(120, -1.0);     // 10 minute buffer (120 samples)
    public CircBuf mHrHistBuf = new CircBuf(120, -1.0);                 // 10 minute heart rate history (120 samples)

    // Fall detection window statistics history - 120 samples at 5s intervals = 10 minutes.
    // mFallWindowMinBuf: rolling lowest "window-minimum" acceleration (free-fall phase indicator).
    // mFallWindowMaxBuf: rolling highest "window-maximum" acceleration (impact phase indicator).
    // mFallEventBuf:     1.0 at times when a fall was detected; 0.0 otherwise.
    public CircBuf mFallWindowMinBuf = new CircBuf(120, -1.0);
    public CircBuf mFallWindowMaxBuf = new CircBuf(120, -1.0);
    public CircBuf mFallEventBuf = new CircBuf(120, 0.0);

    public SdDataHistory() {
        Log.d(TAG, "SdDataHistory created - history buffers initialized");

        // Initialize seizure, accel, heart rate, and fall buffers with zeros so charts have
        // initial data and don't appear empty on first render.
        for (int i = 0; i < 120; i++) {
            mPseizureHistBuf.add(0.0);
            mAccelMagStdDevHistBuf.add(0.0);
            mHrHistBuf.add(0.0);
            mFallWindowMinBuf.add(0.0);
            mFallWindowMaxBuf.add(0.0);
            mFallEventBuf.add(0.0);
        }
        
        // Initialize individual ML model buffers
        for (int i = 0; i < 5; i++) {
            mlModelProbBuffs[i] = new CircBuf(120, -1.0);
            for (int j = 0; j < 120; j++) {
                mlModelProbBuffs[i].add(0.0);
            }
        }
    }

    /**
     * Add a new data point to all history buffers.
     * Including individual ML model probabilities and fall detection window statistics.
     * Called every time new data arrives via SdData.
     *
     * @param fallWindowMin  Lowest window-minimum acceleration this period (-1.0 = no data)
     * @param fallWindowMax  Highest window-maximum acceleration this period (-1.0 = no data)
     * @param fallDetected   True if a fall was detected this analysis period
     */
    public void addDataPoint(long batteryPc, int phoneBatteryPc, double watchSignalStrength,
                             double pSeizure, double accelMagStdDev, double heartRate,
                             double[] mlModelProbs,
                             double fallWindowMin, double fallWindowMax, boolean fallDetected) {
        if (batteryPc >= 0) {
            watchBattBuff.add(batteryPc);
        }
        if (phoneBatteryPc >= 0) {
            phoneBattBuff.add(phoneBatteryPc);
        }
        if (watchSignalStrength >= -100) {
            watchSignalStrengthBuff.add(watchSignalStrength);
        }
        if (pSeizure >= 0) {
            mPseizureHistBuf.add(pSeizure);
        }
        if (accelMagStdDev >= 0) {
            mAccelMagStdDevHistBuf.add(accelMagStdDev);
        }
        if (heartRate >= 0) {
            mHrHistBuf.add(heartRate);
        }

        // Add individual ML model probabilities
        if (mlModelProbs != null) {
            for (int i = 0; i < Math.min(mlModelProbs.length, 5); i++) {
                mlModelProbBuffs[i].add(mlModelProbs[i]);
            }
        }

        // Add fall detection window statistics.
        // When no valid data is available (-1.0), record 0.0 so the graph still has a data point
        // at the correct time position (gap-free display).
        mFallWindowMinBuf.add(fallWindowMin >= 0 ? fallWindowMin : 0.0);
        mFallWindowMaxBuf.add(fallWindowMax >= 0 ? fallWindowMax : 0.0);
        // Record fall events as 1.0 (fall) or 0.0 (no fall) for overlay on the graph.
        mFallEventBuf.add(fallDetected ? 1.0 : 0.0);
    }
}
