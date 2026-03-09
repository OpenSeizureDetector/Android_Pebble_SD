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

    private boolean mUseNextSample = true;


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
        // SENSOR_DELAY_GAME should give us 20 us delay, which is 50 Hz, so more frequent than we really want (OSD works at 25 Hz)
        mSensorManager.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_GAME);
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
                    mSdData.mSampleFreq = (int) (mSdData.mNsamp / dT);
                    mSdData.haveSettings = true;
                    Log.v(TAG, "onSensorChanged(): Collected data for " + dT + " sec - calculated sample rate as " + mSampleFreq + " Hz");
                    mMode = 1;
                    mSdData.mNsamp = 0;
                    mStartTs = event.timestamp;
                }
            } else if (mMode == 1) {
                // The phone gives us 50 Hz sample frequency so we do a crude factor of 2 downsampling to get 25 Hz.
                if (mUseNextSample) {
                    mUseNextSample = false;
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
                        // analysis - this is because sometimes you get a very long delay (e.g. when disconnecting debugger),
                        // which gives a very low frequency which can make us run off the end of arrays in doAnalysis().
                        // FIXME - we should do some sort of check and disregard samples with long delays in them.
                        double dT = 1e-9 * (event.timestamp - mStartTs);
                        int sampleFreq = (int) (mSdData.mNsamp / dT);
                        Log.v(TAG, "onSensorChanged(): Collected " + mSdData.mNsamp + " data points in " + dT + " sec (=" + sampleFreq + " Hz) - analysing...");

                        // Set HR and O2Sat values to fault value (-1) to avoid alarms if the user enables HR or O2Sat alarms.
                        mSdData.mHR = -1;
                        mSdData.mO2Sat = -1;
                        doAnalysis();
                        mSdData.mNsamp = 0;
                        mStartTs = event.timestamp;
                    } else if (mSdData.mNsamp > mSdData.rawData.length) {
                        Log.v(TAG, "onSensorChanged(): Received data during analysis - ignoring sample");
                    }

                } else {
                    mUseNextSample = true;
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
