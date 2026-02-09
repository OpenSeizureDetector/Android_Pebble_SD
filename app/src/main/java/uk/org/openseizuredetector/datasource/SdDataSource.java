/*
  Android_Pebble_sd - Android alarm client for openseizuredetector..

  See http://openseizuredetector.org for more information.

  Copyright Graham Jones, 2015.

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
import uk.org.openseizuredetector.R;

import uk.org.openseizuredetector.alg.SdAlgHr;
import uk.org.openseizuredetector.alg.SdAlgNn;
import uk.org.openseizuredetector.data.SdData;
import uk.org.openseizuredetector.datasource.SdDataSource;
import uk.org.openseizuredetector.utils.OsdUtil;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;


/**
 * Abstract class for a seizure detector data source.  Subclasses include a pebble smart watch data source and a
 * network data source.
 */
public abstract class SdDataSource {
    protected Handler mHandler = new Handler(Looper.getMainLooper());
    private Timer mStatusTimer;
    private Timer mSettingsTimer;
    private Timer mFaultCheckTimer;
    protected long mDataStatusTimeMillis;
    protected boolean mWatchAppRunningCheck = false;
    private int mAppRestartTimeout = 10;  // Timeout before re-starting watch app (sec) if we have not received
    // data after mDataUpdatePeriod
    private int mFaultTimerPeriod = 30;  // Fault Timer Period in sec
    private int mSettingsPeriod = 60;  // period between requesting settings in seconds.
    public SdData mSdData;
    public String mName = "undefined";
    protected OsdUtil mUtil;
    protected Context mContext;
    protected SdDataReceiver mSdDataReceiver;
    private String TAG = "SdDataSource";

    private short mDataUpdatePeriod;
    private short mMutePeriod;
    private short mManAlarmPeriod;
    private int mMute;  // !=0 means muted by keypress on watch.

    // Legacy: Keep for backwards compatibility during transition
    private SdAlgNn mSdAlgNn;
    public SdAlgHr mSdAlgHr;


    private int mAlarmCount;
    protected String mBleDeviceAddr;
    protected String mBleDeviceName;
    private double mLastHrValue;
    private long mHrStatusTimeMillis;
    private double mHrFrozenPeriod = 60; // seconds
    private boolean mHrFrozenAlarm;
    private boolean mFidgetDetectorEnabled;
    private double mFidgetPeriod;
    private double mFidgetThreshold;
    private long mLastFidgetTimeMillis = 0;


    public SdDataSource(Context context, Handler handler, SdDataReceiver sdDataReceiver) {
        Log.v(TAG, "SdDataSource() Constructor");
        mContext = context;
        mHandler = handler;
        mUtil = new OsdUtil(mContext, mHandler);
        mSdDataReceiver = sdDataReceiver;
        mSdData = new SdData();

    }

    /**
     * Returns the SdData object stored by this class.
     *
     * @return
     */
    public SdData getSdData() {
        return mSdData;
    }

    /**
     * Start the datasource updating - initialises from sharedpreferences first to
     * make sure any changes to preferences are taken into account.
     */
    public void start() {

        Log.v(TAG, "start()");
        mUtil.writeToSysLogFile("SdDataSource.start()");
        updatePrefs();

        // Legacy: Keep for now during transition
        mSdAlgHr = new SdAlgHr(mContext);

        if (mSdData.mCnnAlarmActive) {
            mSdAlgNn = new SdAlgNn(mContext);
        } else {
            mSdData.mPseizure = 0;
        }


        // Start timer to check status of watch regularly.
        mDataStatusTimeMillis = Calendar.getInstance().getTimeInMillis();
        // use a timer to check the status of the pebble app on the same frequency
        // as we get app data.
        if (mStatusTimer == null) {
            Log.v(TAG, "start(): starting status timer");
            mUtil.writeToSysLogFile("SdDataSource.start() - starting status timer");
            mStatusTimer = new Timer();
            mStatusTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    getStatus();
                }
            }, 0, mDataUpdatePeriod * 1000);
        } else {
            Log.v(TAG, "start(): status timer already running.");
            mUtil.writeToSysLogFile("SdDataSource.start() - status timer already running??");
        }

        // Initialise time we last received a change in HR value.
        mHrStatusTimeMillis = Calendar.getInstance().getTimeInMillis();
        mLastHrValue = -1;

        if (mFaultCheckTimer == null) {
            Log.v(TAG, "start(): starting alarm check timer");
            mUtil.writeToSysLogFile("SdDataSource.start() - starting alarm check timer");
            mFaultCheckTimer = new Timer();
            mFaultCheckTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    faultCheck();
                }
            }, 0, 1000);
        } else {
            Log.v(TAG, "start(): alarm check timer already running.");
            mUtil.writeToSysLogFile("SDDataSource.start() - alarm check timer already running??");
        }

        if (mSettingsTimer == null) {
            Log.v(TAG, "start(): starting settings timer");
            mUtil.writeToSysLogFile("SDDataSource.start() - starting settings timer");
            mSettingsTimer = new Timer();
            mSettingsTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    mSdData.haveSettings = false;
                }
            }, 0, 1000 * mSettingsPeriod);  // ask for settings less frequently than we get data
        } else {
            Log.v(TAG, "start(): settings timer already running.");
            mUtil.writeToSysLogFile("SDDataSource.start() - settings timer already running??");
        }

    }

    /**
     * Stop the datasource from updating
     */
    public void stop() {
        Log.v(TAG, "stop()");
        mUtil.writeToSysLogFile("SDDataSource.stop()");
        try {
            // Stop the status timer
            if (mStatusTimer != null) {
                Log.v(TAG, "stop(): cancelling status timer");
                mUtil.writeToSysLogFile("SDDataSource.stop() - cancelling status timer");
                mStatusTimer.cancel();
                mStatusTimer.purge();
                mStatusTimer = null;
            }
            // Stop the settings timer
            if (mSettingsTimer != null) {
                Log.v(TAG, "stop(): cancelling settings timer");
                mUtil.writeToSysLogFile("SDDataSource.stop() - cancelling settings timer");
                mSettingsTimer.cancel();
                mSettingsTimer.purge();
                mSettingsTimer = null;
            }
            // Stop the alarm check timer
            if (mFaultCheckTimer != null) {
                Log.v(TAG, "stop(): cancelling alarm check timer");
                mUtil.writeToSysLogFile("SDDataSource.stop() - cancelling alarm check timer");
                mFaultCheckTimer.cancel();
                mFaultCheckTimer.purge();
                mFaultCheckTimer = null;
            }

        } catch (Exception e) {
            Log.v(TAG, "Error in stop() - " + e.toString());
            mUtil.writeToSysLogFile("SDDataSource.stop() - error - " + e.toString());
        }

        // Legacy cleanup
        if (mSdData.mCnnAlarmActive && mSdAlgNn != null) {
            mSdAlgNn.close();
        }

    }

    /**
     * Install the watch app on the watch.
     */
    public void installWatchApp() {
        Log.v(TAG, "installWatchApp");
        try {
            String url = "http://www.openseizuredetector.org.uk/?page_id=1207";
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse(url));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(i);
        } catch (Exception ex) {
            Log.i(TAG, "exception starting install watch app activity " + ex.toString());
            showToast("Error Displaying Installation Instructions - try http://www.openseizuredetector.org.uk/?page_id=1207 instead");
        }
    }

    public void startPebbleApp() {
        Log.v(TAG, "startPebbleApp()");
    }

    public void acceptAlarm() {
        Log.v(TAG, "acceptAlarm()");
    }

    // Force the data stored in this datasource to update in line with the JSON string encoded data provided.
    // Used by webServer to update the GarminDatasource.
    // Returns a message string that is passed back to the watch.
    public String updateFromJSON(String jsonStr) {
        String retVal = "undefined";
        String watchPartNo;
        String watchFwVersion;
        String sdVersion;
        String sdName;
        boolean have3dData = false;
        JSONArray accelVals = null;
        JSONArray accelVals3D = null;
        Log.v(TAG, "updateFromJSON - " + jsonStr);

        try {
            JSONObject mainObject = new JSONObject(jsonStr);
            //JSONObject dataObject = mainObject.getJSONObject("dataObj");
            JSONObject dataObject = mainObject;
            String dataTypeStr = dataObject.getString("dataType");
            Log.v(TAG, "updateFromJSON - dataType=" + dataTypeStr);
            if (dataTypeStr.equals("raw")) {
                Log.v(TAG, "updateFromJSON - processing raw data");
                try {
                    mSdData.mHR = dataObject.getDouble("HR");
                } catch (JSONException e) {
                    // if we get 'null' HR (For example if the heart rate is not working)
                    mSdData.mHR = -1;
                }
                try {
                    mSdData.mO2Sat = dataObject.getDouble("O2sat");
                } catch (JSONException e) {
                    // if we get 'null' O2 Saturation (For example if the oxygen sensor is not working)
                    mSdData.mO2Sat = -1;
                }
                try {
                    mMute = dataObject.getInt("Mute");
                    mSdData.mMute = mMute;  // Pass mute state to SdData for SdServer to handle
                } catch (JSONException e) {
                    // if we get 'null' Mute status
                    mMute = 0;
                    mSdData.mMute = 0;
                }
                //Log.d(TAG,"accelVals[0]="+accelVals.getDouble(0)+", mSdData.rawData[0]="+mSdData.rawData[0]);
                try {
                    accelVals3D = dataObject.getJSONArray("data3D");
                    Log.v(TAG, "Received " + accelVals3D.length() + " acceleration 3D values, rawData Length is " + mSdData.rawData3D.length);
                    if (accelVals3D.length() > mSdData.rawData3D.length) {
                        mUtil.writeToSysLogFile("ERROR:  Received " + accelVals3D.length() + " 3D acceleration values, but rawData3D storage length is "
                                + mSdData.rawData3D.length);
                    }
                    for (int i = 0; i < accelVals3D.length(); i++) {
                        mSdData.rawData3D[i] = accelVals3D.getDouble(i);
                    }
                    have3dData = true;
                } catch (JSONException e) {
                    // If we get an error, just set rawData3D to zero
                    Log.i(TAG, "updateFromJSON - error parsing 3D data - setting it to zero");
                    for (int i = 0; i < mSdData.rawData3D.length; i++) {
                        mSdData.rawData3D[i] = 0.;
                    }
                    have3dData = false;
                }
                // Try to read the vector magnitude data from the JSON string.
                try {
                    accelVals = dataObject.getJSONArray("data");
                    Log.v(TAG, "Received " + accelVals.length() + " acceleration values, rawData Length is " + mSdData.rawData.length);
                    if (accelVals.length() > mSdData.rawData.length) {
                        mUtil.writeToSysLogFile("ERROR:  Received " + accelVals.length() + " acceleration values, but rawData storage length is "
                                + mSdData.rawData.length);
                    }
                    int i;
                    for (i = 0; i < accelVals.length(); i++) {
                        mSdData.rawData[i] = accelVals.getDouble(i);
                    }
                    mSdData.mNsamp = accelVals.length();
                } catch (JSONException e) {
                    // If we do not have vector magnitude data, calculate it from  the 3d data.
                    if (have3dData) {
                        Log.i(TAG,"Deriving Vector Magnitudes from 3d accelerometer data");
                        int i;
                        for (i = 0; i < 125; i++) {
                            double x, y, z;
                            x = mSdData.rawData3D[i*3 + 0];
                            y = mSdData.rawData3D[i*3 + 1];
                            z = mSdData.rawData3D[i*3 + 2];
                            mSdData.rawData[i] = Math.sqrt(x*x + y*y + z*z);
                        }
                        mSdData.mNsamp = 125;
                    } else {
                        // If we do not have vector magnitude or 3d data, set the vector magnitude array to zero.
                        Log.e(TAG, "ERROR - no accelerometer data received - setting it to zero");
                        int i;
                        // FIXME - assumed fixed length of array!!
                        for (i = 0; i < 125; i++) {
                            mSdData.rawData[i] = 0.0;
                        }
                        mSdData.mNsamp = 125;
                    }
                }


                mWatchAppRunningCheck = true;
                doAnalysis();

                if (mSdData.haveSettings == false) {
                    retVal = "sendSettings";
                } else {
                    retVal = "OK";
                }
            } else if (dataTypeStr.equals("settings")) {
                Log.v(TAG, "updateFromJSON - processing settings");
                // Store sample period and frequency directly in SdData (not in datasource fields)
                mSdData.analysisPeriod = (short) dataObject.getInt("analysisPeriod");
                mSdData.mSampleFreq = dataObject.getInt("sampleFreq");
                mSdData.batteryPc = (short) dataObject.getInt("battery");

                Log.v(TAG, "updateFromJSON - analysisPeriod=" + mSdData.analysisPeriod + " mSampleFreq=" + mSdData.mSampleFreq);
                mUtil.writeToSysLogFile("SDDataSource.updateFromJSON - Settings Received");
                mUtil.writeToSysLogFile("    * analysisPeriod=" + mSdData.analysisPeriod + " mSampleFreq=" + mSdData.mSampleFreq);
                mUtil.writeToSysLogFile("    * batteryPc = " + mSdData.batteryPc);

                try {
                    watchPartNo = dataObject.getString("watchPartNo");
                    watchFwVersion = dataObject.getString("watchFwVersion");
                    sdVersion = dataObject.getString("sdVersion");
                    sdName = dataObject.getString("sdName");
                    mUtil.writeToSysLogFile("    * sdName = " + sdName + " version " + sdVersion);
                    mUtil.writeToSysLogFile("    * watchPartNo = " + watchPartNo + " fwVersion " + watchFwVersion);
                    mSdData.watchPartNo = watchPartNo;
                    mSdData.watchFwVersion = watchFwVersion;
                    mSdData.watchSdVersion = sdVersion;
                    mSdData.watchSdName = sdName;
                } catch (Exception e) {
                    Log.e(TAG, "updateFromJSON - Error Parsing V3.2 JSON String - " + e.toString());
                    mUtil.writeToSysLogFile("updateFromJSON - Error Parsing V3.2 JSON String - " + jsonStr + " - " + e.toString());
                    mUtil.writeToSysLogFile("          This is probably because of an out of date watch app - please upgrade!");
                    e.printStackTrace();
                }
                mSdData.haveSettings = true;
                mWatchAppRunningCheck = true;
                retVal = "OK";
            } else {
                Log.e(TAG, "updateFromJSON - unrecognised dataType " + dataTypeStr);
                retVal = "ERROR";
            }
        } catch (Exception e) {
            Log.e(TAG, "updateFromJSON - Error Parsing JSON String - " + jsonStr + " - " + e.toString());
            mUtil.writeToSysLogFile("updateFromJSON - Error Parsing JSON String - " + jsonStr + " - " + e.toString());
            mUtil.writeToSysLogFile("updateFromJSON: Exception at Line Number: " + e.getCause().getStackTrace()[0].getLineNumber() + ", " + e.getCause().getStackTrace()[0].toString());
            if (accelVals == null) {
                mUtil.writeToSysLogFile("updateFromJSON: accelVals is null when exception thrown");
            } else {
                mUtil.writeToSysLogFile("updateFromJSON: Received " + accelVals.length() + " acceleration values");
            }
            e.printStackTrace();
            retVal = "ERROR";
        }
        return (retVal);
    }

    private int getPhoneBatteryLevel() {
        /* Returns the current phone battery level in percent */
        // Check phone battery level
        int batPc;
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = mContext.registerReceiver(null, ifilter);
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        batPc = (int) (level * 100 / (float) scale);
        Log.v(TAG, "SdDataSource.getPhoneBatteryLevel - Phone Bat = " + level + ", scale=" + scale + ", phoneBatteryPc=" + batPc);
        return batPc;
    }

    /**
     * doAnalysis() - populate SdData with received data and metadata.
     * All actual analysis (FFT, alarm detection) is done by SdServer via SeizureDetector.
     */
    protected void doAnalysis() {
        // Update phone battery level
        mSdData.phoneBatteryPc = getPhoneBatteryLevel();

        try {
            // Populate metadata
            mDataStatusTimeMillis = System.currentTimeMillis();
            long tnow = System.currentTimeMillis();
            if (mSdData.dataTimeMillis != 0) {
                mSdData.timeDiff = (tnow - mSdData.dataTimeMillis) / 1000f;
            } else {
                mSdData.timeDiff = 0f;
            }
            mSdData.dataTimeMillis = tnow;
            mSdData.haveData = true;

            // Because we have received data, set flag to show watch app running
            mWatchAppRunningCheck = true;

            // Calculate acceleration magnitude standard deviation from 3D data
            mSdData.mAccelMagStdDev = calcAccelMagStdDev(mSdData);

        } catch (Exception e) {
            Log.e(TAG, "doAnalysis - Exception during data population");
            mUtil.writeToSysLogFile("doAnalysis - Exception: " + e.toString());
            mWatchAppRunningCheck = false;
        }

        Log.v(TAG, "after doAnalysis, mSdData.fallAlarmStanding=" + mSdData.fallAlarmStanding);

        // Pass data to SdServer for seizure detection analysis and mute handling
        mSdDataReceiver.onSdDataReceived(mSdData);
    }


    /**
     * NOTE: muteCheck() has been moved to SdServer since mute handling is about alarm output,
     * not data reception. SdServer should call this method after SeizureDetector processing.
     *
     * Original implementation (now in SdServer):
     * if (mMute != 0) {
     *     sdData.alarmState = 6;
     *     sdData.alarmPhrase = "MUTE";
     *     sdData.mHRAlarmStanding = false;
     * }
     */

    private double calcRawDataStd(SdData sdData) {
        /**
         * Calculate the standard deviation in % of the rawData array in the SdData instance provided.
         * It assumes that rawdata will contain 125 samples.
         * Returns the standard deviation in %.
         */
        // FIXME - assumes length of rawdata array is 125 data points
        int j;
        double sum = 0.0;
        for (j = 0; j < 125; j++) { // FIXME - assumed length!
            sum += sdData.rawData[j];
        }
        double mean = sum / 125;

        double standardDeviation = 0.0;
        for (j = 0; j < 125; j++) { // FIXME - assumed length!
            standardDeviation += Math.pow(sdData.rawData[j] - mean, 2);
        }
        standardDeviation = Math.sqrt(standardDeviation / 125);  // FIXME - assumed length!

        // Convert standard deviation from milli-g to %
        standardDeviation = 100. * standardDeviation / mean;
        return (standardDeviation);
    }

    /**
     * Calculate the standard deviation of acceleration magnitude from 3D data.
     * rawData3D contains X,Y,Z values as: [x0, y0, z0, x1, y1, z1, ...]
     * Calculates magnitude for each 3D point, then returns the standard deviation as a percentage (0-100).
     * @param sdData The SdData instance containing rawData3D
     * @return Standard deviation as a percentage (0-100)
     */
    private double calcAccelMagStdDev(SdData sdData) {
        // Calculate magnitude for each 3D point
        // rawData3D has 125 samples * 3 axes = 375 values
        int numSamples = 125; // FIXME - should use mSdData.mNsamp / 3 or get from rawData3D.length
        double[] magnitudes = new double[numSamples];

        for (int i = 0; i < numSamples; i++) {
            double x = sdData.rawData3D[i * 3];
            double y = sdData.rawData3D[i * 3 + 1];
            double z = sdData.rawData3D[i * 3 + 2];
            magnitudes[i] = Math.sqrt(x * x + y * y + z * z);
        }

        // Calculate mean magnitude
        double sum = 0.0;
        for (int i = 0; i < numSamples; i++) {
            sum += magnitudes[i];
        }
        double mean = sum / numSamples;

        if (mean == 0) {
            return 0.0;
        }

        // Calculate standard deviation
        double sumSquaredDiff = 0.0;
        for (int i = 0; i < numSamples; i++) {
            double diff = magnitudes[i] - mean;
            sumSquaredDiff += diff * diff;
        }
        double stdDev = Math.sqrt(sumSquaredDiff / numSamples);

        // Convert to percentage (0-100)
        double stdDevPercent = 100.0 * stdDev / mean;

        Log.d(TAG, "calcAccelMagStdDev: mean=" + mean + ", stdDev=" + stdDev + ", stdDevPercent=" + stdDevPercent);
        return stdDevPercent;
    }

    /**
     * Checks the status of the connection to the watch,
     * and sets class variables for use by other functions.
     */
    public void getStatus() {
        try {
            long tnow = System.currentTimeMillis();
            long tdiff;
            // get time since the last data was received from the Pebble watch.
            tdiff = tnow - mDataStatusTimeMillis;
            Log.v(TAG, "getStatus() - mWatchAppRunningCheck=" + mWatchAppRunningCheck + " tdiff=" + tdiff);
            Log.v(TAG, "getStatus() - tdiff=" + tdiff + ", mDataUpatePeriod=" + mDataUpdatePeriod + ", mAppRestartTimeout=" + mAppRestartTimeout);

            mSdData.watchConnected = true;  // We can't check connection for passive network connection, so set it to true to avoid errors.
            // And is the watch app running?
            // set mWatchAppRunningCheck has been false for more than 10 seconds
            // the app is not talking to us
            // mWatchAppRunningCheck is set to true in the receiveData handler.
            if (!mWatchAppRunningCheck &&
                    (tdiff > (mDataUpdatePeriod + mAppRestartTimeout) * 1000)) {
                Log.v(TAG, "getStatus() - tdiff = " + tdiff);
                mSdData.watchAppRunning = false;
                // Only make audible warning beep if we have not received data for more than mFaultTimerPeriod seconds.
                if (tdiff > (mDataUpdatePeriod + mFaultTimerPeriod) * 1000) {
                    Log.v(TAG, "getStatus() - Watch App Not Running");
                    mUtil.writeToSysLogFile("SDDataSource.getStatus() - Watch App not Running");
                    //mDataStatusTime.setToNow();
                    mSdData.roiPower = -1;
                    mSdData.specPower = -1;
                    mSdDataReceiver.onSdDataFault(mSdData);
                } else {
                    Log.v(TAG, "getStatus() - Waiting for mFaultTimerPeriod before issuing audible warning...");
                }
            } else {
                mSdData.watchAppRunning = true;

                // Check we have seen a fidget within the required period, or else assume a fault because watch is not being worn
                if (mFidgetDetectorEnabled) {
                    if (mLastFidgetTimeMillis == 0)
                        mLastFidgetTimeMillis = tnow;   // Initialise last fidget time on startup.

                    double accStd = calcRawDataStd(mSdData);
                    if (accStd > mFidgetThreshold) {
                        mLastFidgetTimeMillis = tnow;
                    } else {
                        Log.d(TAG, "onStatus() - Fidget Detector - low movement - is watch being worn?");
                        tdiff = tnow - mLastFidgetTimeMillis;
                        if (tdiff > (mFidgetPeriod) * 60 * 1000) {
                            Log.e(TAG, "onStatus() - Fidget Not Detected - is watch being worn?");
                            mSdDataReceiver.onSdDataFault(mSdData);
                        }
                    }
                }
            }

            // if we have confirmation that the app is running, reset the
            // status time to now and initiate another check.
            if (mWatchAppRunningCheck) {
                mWatchAppRunningCheck = false;
                mDataStatusTimeMillis = System.currentTimeMillis();
            }

            if (!mSdData.haveSettings) {
                Log.v(TAG, "getStatus() - no settings received yet");
            }
        } catch(Exception e) {
            Log.e(TAG,"getStatus - Exception: "+e.toString());
            Log.e(TAG,e.getMessage());
            mSdData.watchAppRunning = false;
            mSdData.roiPower = -1;
            mSdData.specPower = -1;
            mSdDataReceiver.onSdDataFault(mSdData);
        }
    }

    /**
     * faultCheck - determines alarm state based on seizure detector data SdData.   Called every second.
     */
    private void faultCheck() {
        try {
            long tnow = System.currentTimeMillis();
            long tdiff;

            // get time since the last data was received from the watch.
            tdiff = tnow - mDataStatusTimeMillis;
            //Log.v(TAG, "faultCheck() - tdiff=" + tdiff + ", mDataUpatePeriod=" + mDataUpdatePeriod + ", mAppRestartTimeout=" + mAppRestartTimeout
            //        + ", combined = " + (mDataUpdatePeriod + mAppRestartTimeout) * 1000);
            // Note: mAlarmCount is now managed by SeizureDetector, not here

            if (mSdData.mHRAlarmActive && mHrFrozenAlarm) {
                if (mSdData.mHR != mLastHrValue) {
                    mLastHrValue = mSdData.mHR;
                    mHrStatusTimeMillis = tnow;
                    mSdData.mHrFrozenFaultStanding = false;
                } else {
                    tdiff = (tnow - mHrStatusTimeMillis);
                    if (tdiff > mHrFrozenPeriod * 1000.) {
                        mSdData.mHrFrozenFaultStanding = true;
                    } else {
                        mSdData.mHrFrozenFaultStanding = false;
                    }
                }
            }
        } catch(Exception e) {
        Log.e(TAG,"faultCheck - Exception: "+e.toString());
        Log.e(TAG,e.getMessage());
        mSdData.watchAppRunning = false;
        mSdData.roiPower = -1;
        mSdData.specPower = -1;
        mSdDataReceiver.onSdDataFault(mSdData);
    }

}

    void nnAnalysis() {
        //Check the current set of data using the neural network model to look for alarms.
        Log.d(TAG, "nnAnalysis");
        if (mSdData.mCnnAlarmActive) {
            try {
                float pSeizure = mSdAlgNn.getPseizure(mSdData);
                Log.d(TAG, "nnAnalysis - nnResult=" + pSeizure);
                mSdData.mPseizure = pSeizure;
            } catch(Exception e) {
                Log.e(TAG,"nnAnalysis - Error running Analysis - "+e.getMessage());
            }

        } else {
            Log.d(TAG, "nnAnalysis - mCnAlarmActive is false - not analysing");
            mSdData.mPseizure = 0;
        }
    }

    /**
     * Read a preference value, and return it as a double.
     * FIXME - this should be in osdUtil so other classes can use it.
     *
     * @param SP       - Shared Preferences object
     * @param prefName - name of preference to read.
     * @param defVal   - default value if it is not stored.
     * @return double value of the stored specified preference, or the default value.
     */
    private double readDoublePref(SharedPreferences SP, String prefName, String defVal) {
        String prefValStr;
        double retVal = -1;
        try {
            prefValStr = SP.getString(prefName, defVal);
            retVal = Double.parseDouble(prefValStr);
        } catch (Exception ex) {
            Log.v(TAG, "readDoublePref() - Problem with preference!");
            //mUtil.showToast(TAG+":"+mContext.getString(R.string.problem_parsing_preferences));
        }
        return retVal;
    }

    /**
     * updatePrefs() - update basic settings from the SharedPreferences
     * - defined in res/xml/SdDataSourceNetworkPassivePrefs.xml
     */
    public void updatePrefs() {
        Log.v(TAG, "updatePrefs()");
        mUtil.writeToSysLogFile("SDDataSource.updatePrefs()");
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(mContext);
        try {
            // Parse the AppRestartTimeout period setting.
            try {
                String appRestartTimeoutStr = SP.getString("AppRestartTimeout", "10");
                mAppRestartTimeout = Integer.parseInt(appRestartTimeoutStr);
                Log.v(TAG, "updatePrefs() - mAppRestartTimeout = " + mAppRestartTimeout);
                mUtil.writeToSysLogFile("updatePrefs() - mAppRestartTimeout = " + mAppRestartTimeout);
            } catch (Exception ex) {
                Log.v(TAG, "updatePrefs() - Problem with AppRestartTimeout preference!");
                mUtil.writeToSysLogFile("updatePrefs() - Problem with AppRestartTimeout preference!");
                Toast toast = Toast.makeText(mContext, "Problem Parsing AppRestartTimeout Preference", Toast.LENGTH_SHORT);
                toast.show();
            }

            // Parse the FaultTimer period setting.
            try {
                String faultTimerPeriodStr = SP.getString("FaultTimerPeriod", "30");
                mFaultTimerPeriod = Integer.parseInt(faultTimerPeriodStr);
                Log.v(TAG, "updatePrefs() - mFaultTimerPeriod = " + mFaultTimerPeriod);
                mUtil.writeToSysLogFile("updatePrefs() - mFaultTimerPeriod = " + mFaultTimerPeriod);
            } catch (Exception ex) {
                Log.v(TAG, "updatePrefs() - Problem with FaultTimerPeriod preference!");
                mUtil.writeToSysLogFile("updatePrefs() - Problem with FaultTimerPeriod preference!");
                Toast toast = Toast.makeText(mContext, "Problem Parsing FaultTimerPeriod Preference", Toast.LENGTH_SHORT);
                toast.show();
            }

            // Parse the Fidget Detector settings.
            try {
                mFidgetDetectorEnabled = SP.getBoolean("FidgetDetectorEnabled", false);
                mFidgetPeriod = readDoublePref(SP, "FidgetDetectorPeriod", "20"); // minutes
                Log.v(TAG, "updatePrefs() - mFidgetPeriod = " + mFidgetPeriod);
                mFidgetThreshold = readDoublePref(SP, "FidgetDetectorThreshold", "0.6 ");
                Log.d(TAG, "updatePrefs(): mFidgetThreshold=" + mFidgetThreshold);

            } catch (Exception ex) {
                Log.v(TAG, "updatePrefs() - Problem with FidgetDetector preferences!");
                Toast toast = Toast.makeText(mContext, "Problem Parsing FidgetPeriod Preference", Toast.LENGTH_SHORT);
                toast.show();
            }


            // Watch Settings
            String prefStr;
            prefStr = SP.getString("BLE_Device_Addr", "SET_FROM_XML");
            mBleDeviceAddr = prefStr;
            Log.v(TAG, "mBLEDeviceAddr=" + mBleDeviceAddr);
            mUtil.writeToSysLogFile("mBLEDeviceAddr=" + mBleDeviceAddr);
            prefStr = SP.getString("BLE_Device_Name", "SET_FROM_XML");
            mBleDeviceName = prefStr;
            Log.v(TAG, "mBLEDeviceName=" + mBleDeviceName);
            mUtil.writeToSysLogFile("mBLEDeviceName=" + mBleDeviceName);

            // Load data source settings (critical for timer scheduling)
            prefStr = SP.getString("PebbleUpdatePeriod", "5");
            if (prefStr != null && !prefStr.isEmpty()) {
                try {
                    mDataUpdatePeriod = Short.parseShort(prefStr);
                    if (mDataUpdatePeriod <= 0) mDataUpdatePeriod = 5;
                } catch (Exception e) {
                    mDataUpdatePeriod = 5;
                }
            } else {
                mDataUpdatePeriod = 5;
            }
            Log.v(TAG, "updatePrefs() DataUpdatePeriod = " + mDataUpdatePeriod);
            mUtil.writeToSysLogFile("updatePrefs() DataUpdatePeriod = " + mDataUpdatePeriod);

            prefStr = SP.getString("MutePeriod", "60");
            if (prefStr != null && !prefStr.isEmpty()) {
                try {
                    mMutePeriod = Short.parseShort(prefStr);
                    if (mMutePeriod <= 0) mMutePeriod = 60;
                } catch (Exception e) {
                    mMutePeriod = 60;
                }
            } else {
                mMutePeriod = 60;
            }
            Log.v(TAG, "updatePrefs() MutePeriod = " + mMutePeriod);
            mUtil.writeToSysLogFile("updatePrefs() MutePeriod = " + mMutePeriod);

            prefStr = SP.getString("ManAlarmPeriod", "5");
            if (prefStr != null && !prefStr.isEmpty()) {
                try {
                    mManAlarmPeriod = Short.parseShort(prefStr);
                    if (mManAlarmPeriod <= 0) mManAlarmPeriod = 5;
                } catch (Exception e) {
                    mManAlarmPeriod = 5;
                }
            } else {
                mManAlarmPeriod = 5;
            }
            Log.v(TAG, "updatePrefs() ManAlarmPeriod = " + mManAlarmPeriod);
            mUtil.writeToSysLogFile("updatePrefs() ManAlarmPeriod = " + mManAlarmPeriod);

            // Load algorithm active flags into SdData for UI/backward compatibility
            mSdData.mOsdAlarmActive = SP.getBoolean("OsdAlgActive", true);
            mSdData.mFlapAlarmActive = SP.getBoolean("FlapAlgActive", false);
            mSdData.mCnnAlarmActive = SP.getBoolean("CnnAlgActive", false);
            mSdData.mHRAlarmActive = SP.getBoolean("HRAlarmActive", false);
            mSdData.mO2SatAlarmActive = SP.getBoolean("O2SatAlarmActive", false);

            Log.v(TAG, "updatePrefs() - OsdAlarmActive=" + mSdData.mOsdAlarmActive);
            Log.v(TAG, "updatePrefs() - FlapAlarmActive=" + mSdData.mFlapAlarmActive);
            Log.v(TAG, "updatePrefs() - CnnAlarmActive=" + mSdData.mCnnAlarmActive);
            Log.v(TAG, "updatePrefs() - HRAlarmActive=" + mSdData.mHRAlarmActive);

            // Fidget detector settings
            mFidgetDetectorEnabled = SP.getBoolean("FidgetDetectorEnabled", false);
            Log.v(TAG, "updatePrefs() FidgetDetectorEnabled = " + mFidgetDetectorEnabled);
            mUtil.writeToSysLogFile("updatePrefs() FidgetDetectorEnabled = " + mFidgetDetectorEnabled);

            prefStr = SP.getString("FidgetPeriod", "10");
            if (prefStr != null && !prefStr.isEmpty()) {
                try {
                    mFidgetPeriod = Double.parseDouble(prefStr);
                } catch (Exception ex) {
                    Log.v(TAG, "updatePrefs() - Problem Parsing FidgetPeriod Preference");
                    mFidgetPeriod = 10.0;
                }
            } else {
                mFidgetPeriod = 10.0;
            }
            Log.v(TAG, "updatePrefs() FidgetPeriod = " + mFidgetPeriod);
            mUtil.writeToSysLogFile("updatePrefs() FidgetPeriod = " + mFidgetPeriod);

            prefStr = SP.getString("FidgetThreshold", "10");
            if (prefStr != null && !prefStr.isEmpty()) {
                try {
                    mFidgetThreshold = Double.parseDouble(prefStr);
                } catch (Exception ex) {
                    Log.v(TAG, "updatePrefs() - Problem Parsing FidgetThreshold Preference");
                    mFidgetThreshold = 10.0;
                }
            } else {
                mFidgetThreshold = 10.0;
            }
            Log.v(TAG, "updatePrefs() FidgetThreshold = " + mFidgetThreshold);
            mUtil.writeToSysLogFile("updatePrefs() FidgetThreshold = " + mFidgetThreshold);

            // Load algorithm active flags into SdData for logging/UI (using correct keys from seizure_detector_prefs.xml)
            mSdData.mOsdAlarmActive = SP.getBoolean("OsdAlarmActive", true);
            mSdData.mFlapAlarmActive = SP.getBoolean("FlapAlarmActive", false);
            mSdData.mCnnAlarmActive = SP.getBoolean("CnnAlarmActive", false);
            mSdData.mHRAlarmActive = SP.getBoolean("HRAlarmActive", false);
            mSdData.mO2SatAlarmActive = SP.getBoolean("O2SatAlarmActive", false);
            mSdData.mFallActive = SP.getBoolean("FallActive", false);

            Log.v(TAG, "updatePrefs() - OsdAlarmActive=" + mSdData.mOsdAlarmActive);
            Log.v(TAG, "updatePrefs() - FlapAlarmActive=" + mSdData.mFlapAlarmActive);
            Log.v(TAG, "updatePrefs() - CnnAlarmActive=" + mSdData.mCnnAlarmActive);
            Log.v(TAG, "updatePrefs() - HRAlarmActive=" + mSdData.mHRAlarmActive);
            Log.v(TAG, "updatePrefs() - O2SatAlarmActive=" + mSdData.mO2SatAlarmActive);
            Log.v(TAG, "updatePrefs() - FallActive=" + mSdData.mFallActive);

            /* REMOVED: Algorithm-specific preference loading - algorithms load their own preferences now
             * This includes: mDebug, mDisplaySpectrum, mPebbleSdMode, mSampleFreq, mSamplePeriod,
             * mAlarmFreqMin, mAlarmFreqMax, mWarnTime, mAlarmTime, mAlarmThresh, mAlarmRatioThresh,
             * mFallActive, mFallThreshMin, mFallThreshMax, mFallWindow,
             * mFlapThresh, mFlapRatioThresh, mFlapFreqMin, mFlapFreqMax, etc.
             * Each algorithm class (SdAlgOsd, SdAlgFlap, SdAlgFall, SdAlgNn, SdAlgHr) loads its own settings.
             */

        } catch (Exception ex) {
            Log.v(TAG, "updatePrefs() - Problem parsing preferences!");
            mUtil.writeToSysLogFile("SDDataSource.updatePrefs() - ERROR " + ex.toString());
            Toast toast = Toast.makeText(mContext, "Problem Parsing Preferences - Something won't work - Please go back to Settings and correct it!", Toast.LENGTH_SHORT);
            toast.show();
        }
    }


    /**
     * Display a Toast message on screen.
     *
     * @param msg - message to display.
     */
    public void showToast(String msg) {
        Toast.makeText(mContext, msg,
                Toast.LENGTH_LONG).show();
    }


    public class SdDataBroadcastReceiver extends BroadcastReceiver {
        //private String TAG = "SdDataBroadcastReceiver";

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.v(TAG, "SdDataBroadcastReceiver.onReceive()");
            String jsonStr = intent.getStringExtra("data");
            Log.v(TAG, "SdDataBroadcastReceiver.onReceive() - data=" + jsonStr);
            updateFromJSON(jsonStr);
        }
    }


}
