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

import android.util.Log;
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
    public CircBuf mAccelMagStdDevHistBuf = new CircBuf(120, -1.0);     // 10 minute buffer (120 samples)
    public CircBuf mHrHistBuf = new CircBuf(120, -1.0);                 // 10 minute heart rate history (120 samples)

    public SdDataHistory() {
        Log.d(TAG, "SdDataHistory created - history buffers initialized");

        // Initialize seizure, accel, and heart rate buffers with zeros so we have initial data for charts
        for (int i = 0; i < 120; i++) {
            mPseizureHistBuf.add(0.0);
            mAccelMagStdDevHistBuf.add(0.0);
            mHrHistBuf.add(0.0);
        }
    }

    /**
     * Add a new data point to all history buffers.
     * Called every time new data arrives via SdData.
     */
    public void addDataPoint(long batteryPc, int phoneBatteryPc, double watchSignalStrength,
                             double pSeizure, double accelMagStdDev, double heartRate) {
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
    }
}
