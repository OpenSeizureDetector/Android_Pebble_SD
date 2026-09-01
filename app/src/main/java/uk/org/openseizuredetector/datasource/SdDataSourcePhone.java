/*
  Android_Pebble_sd - Android alarm client for openseizuredetector..

  See http://openseizuredetector.org for more information.

  Copyright Graham Jones, 2015, 2016

  This file is part of pebble_sd.

  Android_Pebble_sd is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  Android_Pebble_sd is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with Android_pebble_sd.  If not, see <http://www.gnu.org/licenses/>.

*/
package uk.org.openseizuredetector.datasource;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import uk.org.openseizuredetector.data.logging.Log;

import static java.lang.Math.sqrt;


/**
 * A data source that uses the accelerometer built into the phone to provide seizure detector data for testing purposes.
 * Note that this is unlikely to be useable as a viable seizure detector because the phone must be firmly attached to the part of the body that
 * will shake during a seizure.
 */
public class SdDataSourcePhone extends SdDataSource implements SensorEventListener {
    private String TAG = "SdDataSourcePhone";

    private SensorManager mSensorManager;
    private Sensor mSensor;
    private int mMode = 0;   // 0=check data rate, 1=running
    private SensorEvent mStartEvent = null;
    private long mStartTs = 0;
    public double mSampleFreq = 0;

    // Target sample period for 25 Hz (40 ms).
    // We request a faster rate (20ms / 50Hz) from the sensor manager to ensure we
    // have enough samples to downsample to 25 Hz accurately even with jitter.
    private static final long TARGET_SAMPLE_PERIOD_US = 40_000L;           // µs
    private static final long TARGET_SAMPLE_PERIOD_NS = TARGET_SAMPLE_PERIOD_US * 1_000L; // ns
    
    // Request 20ms period (50Hz) as a hint to the sensor manager.
    private static final long REQUESTED_SAMPLE_PERIOD_US = 20_000L;        // µs
    
    private long mNextSampleTs = 0;


    public SdDataSourcePhone(Context context, Handler handler,
                             SdDataReceiver sdDataReceiver) {
        super(context, handler, sdDataReceiver);
        mName = "Phone";
    }


    /**
     * Start the datasource updating - initialises from sharedpreferences first to
     * make sure any changes to preferences are taken into account.
     */
    public void start() {
        Log.i(TAG, "start()");
        Log.i(TAG, "SdDataSourcePhone.start()");
        mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        // Request 50 Hz (20,000 µs) to ensure we can downsample to 25 Hz reliably.
        mSensorManager.registerListener(this, mSensor, (int) REQUESTED_SAMPLE_PERIOD_US);
        super.start();
    }

    /**
     * Stop the datasource from updating
     */
    public void stop() {
        Log.i(TAG, "stop()");
        Log.i(TAG, "SdDataSourcePhone.stop()");
        mSensorManager.unregisterListener(this);

        super.stop();
    }


    @Override
    public void onSensorChanged(SensorEvent event) {
        /*
         * EXPLANATION OF DATA ACQUISITION AND DOWNSAMPLING:
         *
         * 1. Hardware Input:
         *    The Android SensorManager calls this function for EVERY accelerometer sample provided
         *    by the hardware. Each 'event' contains a single X, Y, Z measurement and a high-resolution
         *    nanosecond timestamp (event.timestamp).
         *
         * 2. Target Rate (25 Hz):
         *    OpenSeizureDetector algorithms require a steady 25 Hz stream (one sample every 40ms).
         *    However, Android hardware delivery is often jittery or fixed at higher rates (e.g., 50Hz, 100Hz).
         *
         * 3. Downsampling Strategy (Nearest-Neighbor / Target-Based):
         *    Instead of complex interpolation (which could add lag or artifacts), we use a "Target
         *    Timestamp" approach to pick the best available hardware samples:
         *
         *    - We maintain 'mNextSampleTs', which is the timestamp of the NEXT ideal 25Hz sample.
         *    - We ignore all hardware samples that arrive BEFORE 'mNextSampleTs'.
         *    - We accept the VERY FIRST hardware sample that arrives AT or AFTER 'mNextSampleTs'.
         *    - Once a sample is accepted, we advance 'mNextSampleTs' by exactly 40ms (TARGET_SAMPLE_PERIOD_NS).
         *
         * 4. Handling Jitter:
         *    By advancing the target timestamp by a fixed 40ms from the PREVIOUS TARGET (rather than
         *    the current time), we prevent timing errors from accumulating. If one sample arrives
         *    slightly late (e.g. at 41ms), the next target remains at 80ms, so the system
         *    automatically corrects itself over time to maintain a perfect 25Hz average.
         */
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            // we initially start in mMode=0, which calculates the sample frequency returned by the sensor, then enters mMode=1, which is normal operation.
            if (mMode == 0) {
                if (mStartEvent == null) {
                    Log.v(TAG, "onSensorChanged(): mMode=0 - Starting Sample Rate Check - mNSamp = " + mSdData.mNsamp);
                    Log.v(TAG, "onSensorChanged(): saving initial event data");
                    mStartEvent = event;
                    mStartTs = event.timestamp;
                    mSdData.mNsamp = 0;
                } else {
                    mSdData.mNsamp++;
                }
                Log.v(TAG, "onSensorChanged - mMode=" + mMode + " mNSamp=" + mSdData.mNsamp);
                if (mSdData.mNsamp >= mSdData.rawData.length) {
                    Log.v(TAG, "onSensorChanged(): Collected Data = final TimeStamp=" + event.timestamp + ", initial TimeStamp=" + mStartTs);
                    double dT = 1e-9 * (event.timestamp - mStartTs);
                    int hwSampleFreq = (int) (mSdData.mNsamp / dT);
                    
                    // If hardware provides at least 25Hz, we will downsample to 25Hz in Mode 1.
                    if (hwSampleFreq >= 25) {
                        mSdData.mSampleFreq = 25;
                    } else {
                        mSdData.mSampleFreq = hwSampleFreq;
                    }
                    
                    mSdData.haveSettings = true;
                    Log.v(TAG, "onSensorChanged(): Collected hardware data for " + dT + " sec - hardware rate " + hwSampleFreq + " Hz. Setting mSampleFreq to " + mSdData.mSampleFreq + " Hz");
                    mMode = 1;
                    mSdData.mNsamp = 0;
                    mStartTs = event.timestamp;
                    mNextSampleTs = 0; // reset so first sample in mode 1 is always accepted
                }
            } else if (mMode == 1) {
                // Downsampling logic: Maintain a target 25 Hz rate by accepting the first sample
                // that arrives at or after the next expected timestamp.
                if (mNextSampleTs == 0) {
                    mNextSampleTs = event.timestamp;
                }

                if (event.timestamp >= mNextSampleTs) {
                    // Accept this sample.
                    mNextSampleTs += TARGET_SAMPLE_PERIOD_NS;
                    
                    // If hardware is significantly slower than our target, reset target to current
                    // timestamp to avoid getting stuck or accepting a burst of samples.
                    if (mNextSampleTs < event.timestamp) {
                        mNextSampleTs = event.timestamp + TARGET_SAMPLE_PERIOD_NS;
                    }
                    
                    // mMode=1 is normal operation - collect NSAMP accelerometer data samples, then analyse them by calling doAnalysis().
                    float x = event.values[0];
                    float y = event.values[1];
                    float z = event.values[2];
                    
                    // Convert m/s^2 to mg (milli-g)
                    double scale = 1000.0 / 9.81;
                    mSdData.rawData[mSdData.mNsamp] = scale * sqrt(x * x + y * y + z * z);
                    mSdData.rawData3D[3 * mSdData.mNsamp] = scale * x;
                    mSdData.rawData3D[3 * mSdData.mNsamp + 1] = scale * y;
                    mSdData.rawData3D[3 * mSdData.mNsamp + 2] = scale * z;
                    
                    mSdData.mNsamp++;
                    if (mSdData.mNsamp == mSdData.rawData.length) {
                        // Calculate the sample frequency for this sample, but do not change mSampleFreq, which is used for
                        // analysis.
                        double dT = 1e-9 * (event.timestamp - mStartTs);
                        int sampleFreq = (int) (mSdData.mNsamp / dT);
                        Log.v(TAG, "onSensorChanged(): Collected " + mSdData.mNsamp + " data points in " + dT + " sec (=" + sampleFreq + " Hz) - analysing...");

                        // Set mSampleFreq to 25 explicitly for analysis, as this is our target downsampled rate.
                        mSdData.mSampleFreq = 25;

                        // Set HR and O2Sat values to fault value (-1) to avoid alarms if the user enables HR or O2Sat alarms.
                        mSdData.mHR = -1;
                        mSdData.mO2Sat = -1;
                        doAnalysis();
                        // Re-affirm haveSettings after every analysis cycle.
                        mSdData.haveSettings = true;
                        mSdData.mNsamp = 0;
                        mStartTs = event.timestamp;
                    } else if (mSdData.mNsamp > mSdData.rawData.length) {
                        Log.v(TAG, "onSensorChanged(): Received data during analysis - ignoring sample");
                    }

                } else {
                    // Sample arrived too soon - discard it to maintain 25 Hz average rate.
                    Log.v(TAG, "onSensorChanged(): discarding sample - before next target timestamp");
                }
            } else {
                Log.v(TAG, "onSensorChanged(): ERROR - Mode " + mMode + " unrecognised");
            }

        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.v(TAG, "onAccuracyChanged()");
    }


}
