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
package uk.org.openseizuredetector;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.format.Time;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jtransforms.fft.DoubleFFT_1D;

import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;

interface SdDataReceiver {
    public void onSdDataReceived(SdData sdData);

    public void onSdDataFault(SdData sdData);
}

/**
 * Abstract class for a seizure detector data source.  Subclasses include a pebble smart watch data source and a
 * network data source.
 */
public abstract class SdDataSource {
    protected Handler mHandler = new Handler();
    private Timer mStatusTimer;
    private Timer mSettingsTimer;
    private Timer mFaultCheckTimer;
    protected Time mDataStatusTime;
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

    private short mDebug;
    private short mFreqCutoff = 12;
    private short mDisplaySpectrum;
    private short mDataUpdatePeriod;
    private short mMutePeriod;
    private short mManAlarmPeriod;
    private short mPebbleSdMode;
    private short mSampleFreq;
    private short mAlarmFreqMin;
    private short mAlarmFreqMax;
    private short mSamplePeriod;
    private short mWarnTime;
    private short mAlarmTime;
    private short mAlarmThresh;
    private short mAlarmRatioThresh;
    private boolean mFallActive;
    private short mFallThreshMin;
    private short mFallThreshMax;
    private short mFallWindow;
    private int mMute;  // !=0 means muted by keypress on watch.
    private SdAlgNn mSdAlgNn;

    // Values for SD_MODE
    private int SIMPLE_SPEC_FMAX = 10;

    private int ACCEL_SCALE_FACTOR = 1000;  // Amount by which to reduce analysis results to scale to be comparable to analysis on Pebble.


    private int mAlarmCount;
    protected String mBleDeviceAddr;
    protected String mBleDeviceName;


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

        if (mSdData.mCnnAlarmActive) {
            mSdAlgNn = new SdAlgNn(mContext);
        } else {
            mSdData.mPseizure = 0;
        }

        // Start timer to check status of watch regularly.
        mDataStatusTime = new Time(Time.getCurrentTimezone());
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

        if (mSdData.mCnnAlarmActive) {
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
                } catch (JSONException e) {
                    // if we get 'null' HR (For example if the heart rate is not working)
                    mMute = 0;
                }
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
                //Log.d(TAG,"accelVals[0]="+accelVals.getDouble(0)+", mSdData.rawData[0]="+mSdData.rawData[0]);
                try {
                    accelVals3D = dataObject.getJSONArray("data3D");
                    Log.v(TAG, "Received " + accelVals3D.length() + " acceleration 3D values, rawData Length is " + mSdData.rawData3D.length);
                    if (accelVals3D.length() > mSdData.rawData3D.length) {
                        mUtil.writeToSysLogFile("ERROR:  Received " + accelVals3D.length() + " 3D acceleration values, but rawData3D storage length is "
                                + mSdData.rawData3D.length);
                    }
                    for (i = 0; i < accelVals3D.length(); i++) {
                        mSdData.rawData3D[i] = accelVals3D.getDouble(i);
                    }
                } catch (JSONException e) {
                    // If we get an error, just set rawData3D to zero
                    Log.i(TAG,"updateFromJSON - error parsing 3D data - setting it to zero");
                    for (i = 0; i < mSdData.rawData3D.length; i++) {
                        mSdData.rawData3D[i] = 0.;
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
                mSamplePeriod = (short) dataObject.getInt("analysisPeriod");
                mSampleFreq = (short) dataObject.getInt("sampleFreq");
                mSdData.batteryPc = (short) dataObject.getInt("battery");
                Log.v(TAG, "updateFromJSON - mSamplePeriod=" + mSamplePeriod + " mSampleFreq=" + mSampleFreq);
                mUtil.writeToSysLogFile("SDDataSource.updateFromJSON - Settings Received");
                mUtil.writeToSysLogFile("    * mSamplePeriod=" + mSamplePeriod + " mSampleFreq=" + mSampleFreq);
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
                mSdData.mSampleFreq = mSampleFreq;
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

    /**
     * Calculate the magnitude of entry i in the fft array fft
     *
     * @param fft
     * @param i
     * @return magnitude ( Re*Re + Im*Im )
     */
    private double getMagnitude(double[] fft, int i) {
        double mag;
        mag = (fft[2 * i] * fft[2 * i] + fft[2 * i + 1] * fft[2 * i + 1]);
        return mag;
    }

    /**
     * doAnalysis() - analyse the data if the accelerometer data array mAccData
     * and populate the output data structure mSdData
     */
    protected void doAnalysis() {
        int nMin = 0;
        int nMax = 0;
        int nFreqCutoff = 0;
        double[] fft = null;
        try {
            // FIXME - Use specified sampleFreq, not this hard coded one
            mSampleFreq = 25;
            double freqRes = 1.0 * mSampleFreq / mSdData.mNsamp;
            Log.v(TAG, "doAnalysis(): mSampleFreq=" + mSampleFreq + " mNSamp=" + mSdData.mNsamp + ": freqRes=" + freqRes);
            Log.v(TAG,"doAnalysis(): rawData=" + Arrays.toString(mSdData.rawData));
            // Set the frequency bounds for the analysis in fft output bin numbers.
            nMin = (int) (mAlarmFreqMin / freqRes);
            nMax = (int) (mAlarmFreqMax / freqRes);
            Log.v(TAG, "doAnalysis(): mAlarmFreqMin=" + mAlarmFreqMin + ", nMin=" + nMin
                    + ", mAlarmFreqMax=" + mAlarmFreqMax + ", nMax=" + nMax);
            // Calculate the bin number of the cutoff frequency
            nFreqCutoff = (int) (mFreqCutoff / freqRes);
            Log.v(TAG, "mFreqCutoff = " + mFreqCutoff + ", nFreqCutoff=" + nFreqCutoff);

            DoubleFFT_1D fftDo = new DoubleFFT_1D(mSdData.mNsamp);
            fft = new double[mSdData.mNsamp * 2];
            ///System.arraycopy(mAccData, 0, fft, 0, mNsamp);
            System.arraycopy(mSdData.rawData, 0, fft, 0, mSdData.mNsamp);
            fftDo.realForward(fft);

            // Calculate the whole spectrum power (well a value equivalent to it that avoids square root calculations
            // and zero any readings that are above the frequency cutoff.
            double specPower = 0;
            for (int i = 1; i < mSdData.mNsamp / 2; i++) {
                if (i <= nFreqCutoff) {
                    specPower = specPower + getMagnitude(fft, i);
                } else {
                    fft[2 * i] = 0.;
                    fft[2 * i + 1] = 0.;
                }
            }
            //Log.v(TAG,"specPower = "+specPower);
            //specPower = specPower/(mSdData.mNsamp/2);
            specPower = specPower / mSdData.mNsamp / 2;
            //Log.v(TAG,"specPower = "+specPower);

            // Calculate the Region of Interest power and power ratio.
            double roiPower = 0;
            for (int i = nMin; i < nMax; i++) {
                roiPower = roiPower + getMagnitude(fft, i);
            }
            roiPower = roiPower / (nMax - nMin);
            double roiRatio = 10 * roiPower / specPower;

            // Calculate the simplified spectrum - power in 1Hz bins.
            double[] simpleSpec = new double[SIMPLE_SPEC_FMAX + 1];
            for (int ifreq = 0; ifreq < SIMPLE_SPEC_FMAX; ifreq++) {
                int binMin = (int) (1 + ifreq / freqRes);    // add 1 to loose dc component
                int binMax = (int) (1 + (ifreq + 1) / freqRes);
                simpleSpec[ifreq] = 0;
                for (int i = binMin; i < binMax; i++) {
                    simpleSpec[ifreq] = simpleSpec[ifreq] + getMagnitude(fft, i);
                }
                simpleSpec[ifreq] = simpleSpec[ifreq] / (binMax - binMin);
            }

            // Populate the mSdData structure to communicate with the main SdServer service.
            mDataStatusTime.setToNow();
            mSdData.specPower = (long) specPower / ACCEL_SCALE_FACTOR;
            mSdData.roiPower = (long) roiPower / ACCEL_SCALE_FACTOR;
            mSdData.dataTime.setToNow();
            mSdData.maxVal = 0;   // not used
            mSdData.maxFreq = 0;  // not used
            mSdData.haveData = true;
            mSdData.alarmThresh = mAlarmThresh;
            mSdData.alarmRatioThresh = mAlarmRatioThresh;
            mSdData.alarmFreqMin = mAlarmFreqMin;
            mSdData.alarmFreqMax = mAlarmFreqMax;
            // note mSdData.batteryPc is set from settings data in updateFromJSON()
            // FIXME - I haven't worked out why dividing by 1000 seems necessary to get the graph on scale - we don't seem to do that with the Pebble.
            for (int i = 0; i < SIMPLE_SPEC_FMAX; i++) {
                mSdData.simpleSpec[i] = (int) simpleSpec[i] / ACCEL_SCALE_FACTOR;
            }
            Log.v(TAG, "simpleSpec = " + Arrays.toString(mSdData.simpleSpec));

            // Because we have received data, set flag to show watch app running.
            mWatchAppRunningCheck = true;
        } catch (Exception e) {
            Log.e(TAG, "doAnalysis - Exception during Analysis");
            mUtil.writeToSysLogFile("doAnalysis - Exception during analysis - " + e.toString());
            mUtil.writeToSysLogFile("doAnalysis: Exception at Line Number: " + e.getCause().getStackTrace()[0].getLineNumber() + ", " + e.getCause().getStackTrace()[0].toString());
            mUtil.writeToSysLogFile("doAnalysis: mSdData.mNsamp="+mSdData.mNsamp);
            mUtil.writeToSysLogFile("doAnalysis: alarmFreqMin="+mAlarmFreqMin+" nMin="+nMin);
            mUtil.writeToSysLogFile("doAnalysis: alarmFreqMax="+mAlarmFreqMax+" nMax="+nMax);
            mUtil.writeToSysLogFile("doAnalysis: nFreqCutoff.="+nFreqCutoff);
            mUtil.writeToSysLogFile("doAnalysis: fft.length="+fft.length);
            mWatchAppRunningCheck = false;
        }

        // Use the neural network algorithm to calculate the probability of the data
        // being representative of a seizure (sets mSdData.mPseizure)
        if (mSdData.mCnnAlarmActive) {
            nnAnalysis();
        }

        // Check this data to see if it represents an alarm state.
        alarmCheck();
        hrCheck();
        o2SatCheck();
        fallCheck();
        muteCheck();
        Log.v(TAG,"after fallCheck, mSdData.fallAlarmStanding="+mSdData.fallAlarmStanding);

        mSdDataReceiver.onSdDataReceived(mSdData);  // and tell SdServer we have received data.
    }


    /****************************************************************
     * checkAlarm() - checks the current accelerometer data and uses
     * historical data to determine if we are in a fault, warning or ok
     * state.
     * Sets mSdData.alarmState and mSdData.hrAlarmStanding
     */
    private void alarmCheck() {
        boolean inAlarm = false;
        // Avoid potential divide by zero issue
        if (mSdData.specPower == 0)
            mSdData.specPower = 1;
        Log.v(TAG, "alarmCheck() - roiPower="+mSdData.roiPower+" specPower="+ mSdData.specPower+" ratio="+10*mSdData.roiPower/ mSdData.specPower);

        if (mSdData.mOsdAlarmActive) {
            // Is the current set of data representing an alarm state?
            if ((mSdData.roiPower > mAlarmThresh) && ((10 * mSdData.roiPower / mSdData.specPower) > mAlarmRatioThresh)) {
                inAlarm = true;
            }
        }

        if (mSdData.mCnnAlarmActive) {
            if (mSdData.mPseizure > 0.5) {
                inAlarm = true;
            }
        }

        // set the alarmState to Alarm, Warning or OK, depending on the current state and previous ones.
        if (inAlarm) {
            mAlarmCount += mSamplePeriod;
            if (mAlarmCount > mAlarmTime) {
                // full alarm
                mSdData.alarmState = 2;
            } else if (mAlarmCount > mWarnTime) {
                // warning
                mSdData.alarmState = 1;
            }
        } else {
            // If we are not in an ALARM state, revert back to WARNING, otherwise
            // revert back to OK.
            if (mSdData.alarmState == 2) {
                // revert to warning
                mSdData.alarmState = 1;
                mAlarmCount = mWarnTime + 1;  // pretend we have only just entered warning state.
            } else {
                // revert to OK
                mSdData.alarmState = 0;
                mAlarmCount = 0;
            }
        }

        Log.v(TAG, "alarmCheck(): inAlarm=" + inAlarm + ", alarmState = " + mSdData.alarmState + " alarmCount=" + mAlarmCount + " mWarnTime=" + mWarnTime+ " mAlarmTime=" + mAlarmTime);

    }

    public void muteCheck() {
        if (mMute != 0) {
            Log.v(TAG, "Mute Active - setting alarms to mute");
            mSdData.alarmState = 6;
            mSdData.alarmPhrase = "MUTE";
            mSdData.mHRAlarmStanding = false;
        }

    }

    /**
     * hrCheck - check the Heart rate data in mSdData to see if it represents an alarm condition.
     * Sets mSdData.mHRAlarmStanding
     */
    public void hrCheck() {
        Log.v(TAG, "hrCheck()");
        /* Check Heart Rate against alarm settings */
        if (mSdData.mHRAlarmActive) {
            if (mSdData.mHR < 0) {
                if (mSdData.mHRNullAsAlarm) {
                    Log.i(TAG, "Heart Rate Null - Alarming");
                    mSdData.mHRFaultStanding = false;
                    mSdData.mHRAlarmStanding = true;
                } else {
                    Log.i(TAG, "Heart Rate Fault (HR<0)");
                    mSdData.mHRFaultStanding = true;
                    mSdData.mHRAlarmStanding = false;
                }
            } else if ((mSdData.mHR > mSdData.mHRThreshMax) || (mSdData.mHR < mSdData.mHRThreshMin)) {
                Log.i(TAG, "Heart Rate Abnormal - " + mSdData.mHR + " bpm");
                mSdData.mHRFaultStanding = false;
                mSdData.mHRAlarmStanding = true;
            } else {
                mSdData.mHRFaultStanding = false;
                mSdData.mHRAlarmStanding = false;
            }
        }
    }

    /**
     * hrCheck - check the Heart rate data in mSdData to see if it represents an alarm condition.
     * Sets mSdData.mHRAlarmStanding
     */
    public void o2SatCheck() {
        Log.v(TAG, "o2SatCheck()");
        /* Check Oxygen Saturation against alarm settings */
        if (mSdData.mO2SatAlarmActive) {
            if (mSdData.mO2Sat < 0) {
                if (mSdData.mO2SatNullAsAlarm) {
                    Log.i(TAG, "Oxygen Saturation Null - Alarming");
                    mSdData.mO2SatFaultStanding = false;
                    mSdData.mO2SatAlarmStanding = true;
                } else {
                    Log.i(TAG, "Oxygen Saturation Fault (O2Sat<0)");
                    mSdData.mO2SatFaultStanding = true;
                    mSdData.mO2SatAlarmStanding = false;
                }
            } else if  (mSdData.mO2Sat < mSdData.mO2SatThreshMin) {
                Log.i(TAG, "Oxygen Saturation Abnormal - " + mSdData.mO2Sat + " %");
                mSdData.mO2SatFaultStanding = false;
                mSdData.mO2SatAlarmStanding = true;
            } else {
                mSdData.mO2SatFaultStanding = false;
                mSdData.mO2SatAlarmStanding = false;
            }
        }

    }


    /****************************************************************
     * Simple threshold analysis to chech for fall.
     * Called from clock_tick_handler()
     */
    public void fallCheck() {
        int i, j;
        double minAcc, maxAcc;

        long fallWindowSamp = (mFallWindow * mSdData.mSampleFreq) / 1000; // Convert ms to samples.
        Log.v(TAG, "check_fall() - fallWindowSamp=" + fallWindowSamp);
        // Move window through sample buffer, checking for fall.
        // Note - not resetting fallAlarmStanding means that fall alarms will always latch until the 'Accept Alarm' button
        // is pressed.
        //mSdData.fallAlarmStanding = false;
        if (mFallActive) {
            mSdData.mFallActive = true;
            for (i = 0; i < mSdData.mNsamp - fallWindowSamp; i++) {  // i = window start point
                // Find max and min acceleration within window.
                minAcc = mSdData.rawData[i];
                maxAcc = mSdData.rawData[i];
                for (j = 0; j < fallWindowSamp; j++) {  // j = position within window
                    if (mSdData.rawData[i + j] < minAcc) minAcc = mSdData.rawData[i + j];
                    if (mSdData.rawData[i + j] > maxAcc) maxAcc = mSdData.rawData[i + j];
                }
                Log.d(TAG, "check_fall() - minAcc=" + minAcc +" (mFallThreshMin="+mFallThreshMin+ "), maxAcc=" + maxAcc+" (mFallThreshMax="+mFallThreshMax+")") ;
                if ((minAcc < mFallThreshMin) && (maxAcc > mFallThreshMax)) {
                    Log.d(TAG, "check_fall() ****FALL DETECTED***** minAcc=" + minAcc + ", maxAcc=" + maxAcc);
                    Log.d(TAG, "check_fall() - ****FALL DETECTED****");
                    mSdData.fallAlarmStanding = true;
                    return;
                }
                if (mMute != 0) {
                    Log.v(TAG, "Mute Active - setting fall alarm to mute");
                    mSdData.fallAlarmStanding = false;
                }
            }
        } else {
            mSdData.mFallActive = false;
            Log.v(TAG, "check_fall - mFallActive is false - doing nothing");
        }
        //if (debug) APP_LOG(APP_LOG_LEVEL_DEBUG,"check_fall() - minAcc=%d, maxAcc=%d",
        //	  minAcc,maxAcc);

    }

    /**
     * Checks the status of the connection to the watch,
     * and sets class variables for use by other functions.
     */
    public void getStatus() {
        Time tnow = new Time(Time.getCurrentTimezone());
        long tdiff;
        tnow.setToNow();
        // get time since the last data was received from the Pebble watch.
        tdiff = (tnow.toMillis(false) - mDataStatusTime.toMillis(false));
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
        }

        // if we have confirmation that the app is running, reset the
        // status time to now and initiate another check.
        if (mWatchAppRunningCheck) {
            mWatchAppRunningCheck = false;
            mDataStatusTime.setToNow();
        }

        if (!mSdData.haveSettings) {
            Log.v(TAG, "getStatus() - no settings received yet");
        }
    }

    /**
     * faultCheck - determines alarm state based on seizure detector data SdData.   Called every second.
     */
    private void faultCheck() {
        Time tnow = new Time(Time.getCurrentTimezone());
        long tdiff;
        tnow.setToNow();

        // get time since the last data was received from the watch.
        tdiff = (tnow.toMillis(false) - mDataStatusTime.toMillis(false));
        //Log.v(TAG, "faultCheck() - tdiff=" + tdiff + ", mDataUpatePeriod=" + mDataUpdatePeriod + ", mAppRestartTimeout=" + mAppRestartTimeout
        //        + ", combined = " + (mDataUpdatePeriod + mAppRestartTimeout) * 1000);
        if (!mWatchAppRunningCheck &&
                (tdiff > (mDataUpdatePeriod + mAppRestartTimeout) * 1000)) {
            //Log.v(TAG, "faultCheck() - watch app not running so not doing anything");
            mAlarmCount = 0;
        }
    }

    void nnAnalysis() {
        //Check the current set of data using the neural network model to look for alarms.
        Log.d(TAG,"nnAnalysis");
        if (mSdData.mCnnAlarmActive) {
            float pSeizure = mSdAlgNn.getPseizure(mSdData);
            Log.d(TAG, "nnAnalysis - nnResult=" + pSeizure);
            mSdData.mPseizure = pSeizure;
        } else {
            Log.d(TAG, "nnAnalysis - mCnAlarmActive is false - not analysing");
            mSdData.mPseizure = 0;
        }
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
                mUtil.writeToSysLogFile( "updatePrefs() - mAppRestartTimeout = " + mAppRestartTimeout);
            } catch (Exception ex) {
                Log.v(TAG, "updatePrefs() - Problem with AppRestartTimeout preference!");
                mUtil.writeToSysLogFile( "updatePrefs() - Problem with AppRestartTimeout preference!");
                Toast toast = Toast.makeText(mContext, "Problem Parsing AppRestartTimeout Preference", Toast.LENGTH_SHORT);
                toast.show();
            }

            // Parse the FaultTimer period setting.
            try {
                String faultTimerPeriodStr = SP.getString("FaultTimerPeriod", "30");
                mFaultTimerPeriod = Integer.parseInt(faultTimerPeriodStr);
                Log.v(TAG, "updatePrefs() - mFaultTimerPeriod = " + mFaultTimerPeriod);
                mUtil.writeToSysLogFile( "updatePrefs() - mFaultTimerPeriod = " + mFaultTimerPeriod);
            } catch (Exception ex) {
                Log.v(TAG, "updatePrefs() - Problem with FaultTimerPeriod preference!");
                mUtil.writeToSysLogFile( "updatePrefs() - Problem with FaultTimerPeriod preference!");
                Toast toast = Toast.makeText(mContext, "Problem Parsing FaultTimerPeriod Preference", Toast.LENGTH_SHORT);
                toast.show();
            }


            // Watch Settings
            String prefStr;
            prefStr = SP.getString("BLE_Device_Addr", "SET_FROM_XML");
            mBleDeviceAddr = prefStr;
            Log.v(TAG, "mBLEDeviceAddr=" + mBleDeviceAddr);
            mUtil.writeToSysLogFile( "mBLEDeviceAddr=" + mBleDeviceAddr);
            prefStr = SP.getString("BLE_Device_Name", "SET_FROM_XML");
            mBleDeviceName = prefStr;
            Log.v(TAG, "mBLEDeviceName=" + mBleDeviceName);
            mUtil.writeToSysLogFile( "mBLEDeviceName=" + mBleDeviceName);

            prefStr = SP.getString("PebbleDebug", "SET_FROM_XML");
            if (prefStr != null) {
                mDebug = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() Debug = " + mDebug);
                mUtil.writeToSysLogFile( "updatePrefs() Debug = " + mDebug);

                prefStr = SP.getString("PebbleDisplaySpectrum", "SET_FROM_XML");
                mDisplaySpectrum = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() DisplaySpectrum = " + mDisplaySpectrum);
                mUtil.writeToSysLogFile( "updatePrefs() DisplaySpectrum = " + mDisplaySpectrum);

                prefStr = SP.getString("PebbleUpdatePeriod", "SET_FROM_XML");
                mDataUpdatePeriod = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() DataUpdatePeriod = " + mDataUpdatePeriod);
                mUtil.writeToSysLogFile( "updatePrefs() DataUpdatePeriod = " + mDataUpdatePeriod);

                prefStr = SP.getString("MutePeriod", "SET_FROM_XML");
                mMutePeriod = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() MutePeriod = " + mMutePeriod);
                mUtil.writeToSysLogFile( "updatePrefs() MutePeriod = " + mMutePeriod);

                prefStr = SP.getString("ManAlarmPeriod", "SET_FROM_XML");
                mManAlarmPeriod = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() ManAlarmPeriod = " + mManAlarmPeriod);
                mUtil.writeToSysLogFile( "updatePrefs() ManAlarmPeriod = " + mManAlarmPeriod);

                prefStr = SP.getString("PebbleSdMode", "SET_FROM_XML");
                mPebbleSdMode = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() PebbleSdMode = " + mPebbleSdMode);
                mUtil.writeToSysLogFile( "updatePrefs() PebbleSdMode = " + mPebbleSdMode);

                prefStr = SP.getString("SampleFreq", "SET_FROM_XML");
                mSampleFreq = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() SampleFreq = " + mSampleFreq);
                mUtil.writeToSysLogFile( "updatePrefs() SampleFreq = " + mSampleFreq);

                prefStr = SP.getString("SamplePeriod", "SET_FROM_XML");
                mSamplePeriod = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() AnalysisPeriod = " + mSamplePeriod);
                mUtil.writeToSysLogFile( "updatePrefs() AnalysisPeriod = " + mSamplePeriod);

                prefStr = SP.getString("AlarmFreqMin", "SET_FROM_XML");
                mAlarmFreqMin = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() AlarmFreqMin = " + mAlarmFreqMin);
                mUtil.writeToSysLogFile( "updatePrefs() AlarmFreqMin = " + mAlarmFreqMin);

                prefStr = SP.getString("AlarmFreqMax", "SET_FROM_XML");
                mAlarmFreqMax = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() AlarmFreqMax = " + mAlarmFreqMax);
                mUtil.writeToSysLogFile("updatePrefs() AlarmFreqMax = " + mAlarmFreqMax);

                prefStr = SP.getString("WarnTime", "SET_FROM_XML");
                mWarnTime = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() WarnTime = " + mWarnTime);
                mUtil.writeToSysLogFile( "updatePrefs() WarnTime = " + mWarnTime);

                prefStr = SP.getString("AlarmTime", "SET_FROM_XML");
                mAlarmTime = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() AlarmTime = " + mAlarmTime);
                mUtil.writeToSysLogFile( "updatePrefs() AlarmTime = " + mAlarmTime);

                prefStr = SP.getString("AlarmThresh", "SET_FROM_XML");
                mAlarmThresh = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() AlarmThresh = " + mAlarmThresh);
                mUtil.writeToSysLogFile( "updatePrefs() AlarmThresh = " + mAlarmThresh);

                prefStr = SP.getString("AlarmRatioThresh", "SET_FROM_XML");
                mAlarmRatioThresh = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() AlarmRatioThresh = " + mAlarmRatioThresh);
                mUtil.writeToSysLogFile( "updatePrefs() AlarmRatioThresh = " + mAlarmRatioThresh);

                mFallActive = SP.getBoolean("FallActive", false);
                Log.v(TAG, "updatePrefs() FallActive = " + mFallActive);
                mUtil.writeToSysLogFile( "updatePrefs() FallActive = " + mFallActive);

                prefStr = SP.getString("FallThreshMin", "SET_FROM_XML");
                mFallThreshMin = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() FallThreshMin = " + mFallThreshMin);
                mUtil.writeToSysLogFile( "updatePrefs() FallThreshMin = " + mFallThreshMin);

                prefStr = SP.getString("FallThreshMax", "SET_FROM_XML");
                mFallThreshMax = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() FallThreshMax = " + mFallThreshMax);
                mUtil.writeToSysLogFile( "updatePrefs() FallThreshMax = " + mFallThreshMax);

                prefStr = SP.getString("FallWindow", "SET_FROM_XML");
                mFallWindow = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() FallWindow = " + mFallWindow);
                mUtil.writeToSysLogFile( "updatePrefs() FallWindow = " + mFallWindow);

                mSdData.mOsdAlarmActive = SP.getBoolean("OsdAlarmActive", false);
                Log.v(TAG, "updatePrefs() OsdAlarmActive = " + mSdData.mOsdAlarmActive);
                mUtil.writeToSysLogFile( "updatePrefs() OsdAlarmActive = " + mSdData.mOsdAlarmActive);

                mSdData.mCnnAlarmActive = SP.getBoolean("CnnAlarmActive", false);
                Log.v(TAG, "updatePrefs() CnnAlarmActive = " + mSdData.mCnnAlarmActive);
                mUtil.writeToSysLogFile( "updatePrefs() CnnAlarmActive = " + mSdData.mCnnAlarmActive);


                mSdData.mHRAlarmActive = SP.getBoolean("HRAlarmActive", false);
                Log.v(TAG, "updatePrefs() HRAlarmActive = " + mSdData.mHRAlarmActive);
                mUtil.writeToSysLogFile( "updatePrefs() HRAlarmActive = " + mSdData.mHRAlarmActive);

                mSdData.mHRNullAsAlarm = SP.getBoolean("HRNullAsAlarm", false);
                Log.v(TAG, "updatePrefs() HRNullAsAlarm = " + mSdData.mHRNullAsAlarm);
                mUtil.writeToSysLogFile( "updatePrefs() HRNullAsAlarm = " + mSdData.mHRNullAsAlarm);

                prefStr = SP.getString("HRThreshMin", "SET_FROM_XML");
                mSdData.mHRThreshMin = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() HRThreshMin = " + mSdData.mHRThreshMin);
                mUtil.writeToSysLogFile( "updatePrefs() HRThreshMin = " + mSdData.mHRThreshMin);

                prefStr = SP.getString("HRThreshMax", "SET_FROM_XML");
                mSdData.mHRThreshMax = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() HRThreshMax = " + mSdData.mHRThreshMax);
                mUtil.writeToSysLogFile( "updatePrefs() HRThreshMax = " + mSdData.mHRThreshMax);

                mSdData.mO2SatAlarmActive = SP.getBoolean("O2SatAlarmActive", false);
                Log.v(TAG, "updatePrefs() O2SatAlarmActive = " + mSdData.mO2SatAlarmActive);
                mUtil.writeToSysLogFile( "updatePrefs() O2SatAlarmActive = " + mSdData.mO2SatAlarmActive);

                mSdData.mO2SatNullAsAlarm = SP.getBoolean("O2SatNullAsAlarm", false);
                Log.v(TAG, "updatePrefs() O2SatNullAsAlarm = " + mSdData.mO2SatNullAsAlarm);
                mUtil.writeToSysLogFile( "updatePrefs() O2SatNullAsAlarm = " + mSdData.mO2SatNullAsAlarm);

                prefStr = SP.getString("O2SatThreshMin", "SET_FROM_XML");
                mSdData.mO2SatThreshMin = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() O2SatThreshMin = " + mSdData.mO2SatThreshMin);
                mUtil.writeToSysLogFile( "updatePrefs() O2SatThreshMin = " + mSdData.mO2SatThreshMin);

            } else {
                Log.v(TAG, "updatePrefs() - prefStr is null - WHY????");
                mUtil.writeToSysLogFile("SDDataSource.updatePrefs() - prefStr is null - WHY??");
                Toast toast = Toast.makeText(mContext, "Problem Parsing Preferences - Something won't work - Please go back to Settings and correct it!", Toast.LENGTH_SHORT);
                toast.show();
            }

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
