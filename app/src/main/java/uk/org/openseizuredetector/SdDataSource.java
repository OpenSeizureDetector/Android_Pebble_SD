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
import android.os.Build;
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
    void onSdDataReceived(SdData sdData);

    void onSdDataFault(SdData sdData);
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
    private final String TAG = "SdDataSource";
    private int mAppRestartTimeout = 10;  // Timeout before re-starting watch app (sec) if we have not received
    // data after mDataUpdatePeriod
    private int mFaultTimerPeriod = 30;  // Fault Timer Period in sec
    public SdData mSdData;
    public String mName = "undefined";
    protected OsdUtil mUtil;
    protected Context mContext;
    protected static SdDataReceiver mSdDataReceiver;
    protected boolean mWatchAppRunningCheck;

    private short mDataUpdatePeriod;
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
    boolean prefValmHrAlarmActive;


    private int mAlarmCount;
    protected String mBleDeviceAddr;
    protected String mBleDeviceName;


    public SdDataSource(final Context context, final Handler handler, final SdDataReceiver sdDataReceiver) {
        Log.v(this.TAG, "SdDataSource() Constructor");
        this.mContext = context;
        this.mHandler = handler;
        this.mUtil = new OsdUtil(this.mContext, this.mHandler);
        SdDataSource.mSdDataReceiver = sdDataReceiver;
        SdDataSource.mSdDataReceiver.toString();
        this.mSdData = new SdData();

    }

    /**
     * Returns the SdData object stored by this class.
     *
     * @return
     */
    public SdData getSdData() {
        return this.mSdData;
    }

    /**
     * Start the datasource updating - initialises from sharedpreferences first to
     * make sure any changes to preferences are taken into account.
     */
    public void start() {
        Log.v(this.TAG, "start()");
        this.mUtil.writeToSysLogFile("SdDataSource.start()");
        this.updatePrefs();
        // Start timer to check status of watch regularly.
        if (this.mSdData.dataTime == null) this.mSdData.dataTime = new Time();
        this.mSdData.phoneName = Build.HOST;
        this.mSdData.dataTime.setToNow();
        this.mDataStatusTime = this.mSdData.dataTime;
        // use a timer to check the status of the pebble app on the same frequency
        // as we get app data.
        if (this.mStatusTimer == null) {
            Log.v(this.TAG, "start(): starting status timer");
            this.mUtil.writeToSysLogFile("SdDataSource.start() - starting status timer");
            this.mStatusTimer = new Timer();
            this.mStatusTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    SdDataSource.this.getStatus();
                }
            }, 0, this.mDataUpdatePeriod * 1000);
        } else {
            Log.v(this.TAG, "start(): status timer already running.");
            this.mUtil.writeToSysLogFile("SdDataSource.start() - status timer already running??");
        }
        if (this.mFaultCheckTimer == null) {
            Log.v(this.TAG, "start(): starting alarm check timer");
            this.mUtil.writeToSysLogFile("SdDataSource.start() - starting alarm check timer");
            this.mFaultCheckTimer = new Timer();
            this.mFaultCheckTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    SdDataSource.this.faultCheck();
                }
            }, 0, 1000);
        } else {
            Log.v(this.TAG, "start(): alarm check timer already running.");
            this.mUtil.writeToSysLogFile("SDDataSource.start() - alarm check timer already running??");
        }

        if (this.mSettingsTimer == null) {
            Log.v(this.TAG, "start(): starting settings timer");
            this.mUtil.writeToSysLogFile("SDDataSource.start() - starting settings timer");
            this.mSettingsTimer = new Timer();
            // period between requesting settings in seconds.
            final int mSettingsPeriod = 60;
            this.mSettingsTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    SdDataSource.this.mSdData.haveSettings = false;
                }
            }, 0, 1000L * mSettingsPeriod);  // ask for settings less frequently than we get data
        } else {
            Log.v(this.TAG, "start(): settings timer already running.");
            this.mUtil.writeToSysLogFile("SDDataSource.start() - settings timer already running??");
        }

    }

    /**
     * Stop the datasource from updating
     */
    public void stop() {
        Log.v(this.TAG, "stop()");
        this.mUtil.writeToSysLogFile("SDDataSource.stop()");
        try {
            // Stop the status timer
            if (this.mStatusTimer != null) {
                Log.v(this.TAG, "stop(): cancelling status timer");
                this.mUtil.writeToSysLogFile("SDDataSource.stop() - cancelling status timer");
                this.mStatusTimer.cancel();
                this.mStatusTimer.purge();
                this.mStatusTimer = null;
            }
            // Stop the settings timer
            if (this.mSettingsTimer != null) {
                Log.v(this.TAG, "stop(): cancelling settings timer");
                this.mUtil.writeToSysLogFile("SDDataSource.stop() - cancelling settings timer");
                this.mSettingsTimer.cancel();
                this.mSettingsTimer.purge();
                this.mSettingsTimer = null;
            }
            // Stop the alarm check timer
            if (this.mFaultCheckTimer != null) {
                Log.v(this.TAG, "stop(): cancelling alarm check timer");
                this.mUtil.writeToSysLogFile("SDDataSource.stop() - cancelling alarm check timer");
                this.mFaultCheckTimer.cancel();
                this.mFaultCheckTimer.purge();
                this.mFaultCheckTimer = null;
            }

        } catch (final Exception e) {
            Log.v(this.TAG, "Error in stop() - " + e);
            this.mUtil.writeToSysLogFile("SDDataSource.stop() - error - " + e);
        }

    }

    /**
     * Install the watch app on the watch.
     */
    public void installWatchApp() {
        Log.v(this.TAG, "installWatchApp");
        try {
            final String url = "http://www.openseizuredetector.org.uk/?page_id=1207";
            final Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse(url));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            this.mContext.startActivity(i);
        } catch (final Exception ex) {
            Log.i(this.TAG, "exception starting install watch app activity " + ex);
            this.showToast("Error Displaying Installation Instructions - try http://www.openseizuredetector.org.uk/?page_id=1207 instead");
        }
    }

    public void startPebbleApp() {
        Log.v(this.TAG, "startPebbleApp()");
    }

    public void acceptAlarm() {
        Log.v(this.TAG, "acceptAlarm()");
    }

    // Force the data stored in this datasource to update in line with the JSON string encoded data provided.
    // Used by webServer to update the GarminDatasource.
    // Returns a message string that is passed back to the watch.
    public String updateFromJSON(final String jsonStr) {
        String retVal = "undefined";
        final String watchPartNo;
        final String watchFwVersion;
        final String sdVersion;
        final String sdName;
        JSONArray accelVals = null;
        JSONArray accelVals3D = null;
        Log.v(this.TAG, "updateFromJSON - " + jsonStr);

        try {
            final JSONObject mainObject = new JSONObject(jsonStr);
            //JSONObject dataObject = mainObject.getJSONObject("dataObj");
            final JSONObject dataObject = mainObject;

            final String dataTypeStr = dataObject.getString("dataType");
            Log.v(this.TAG, "updateFromJSON - dataType=" + dataTypeStr);
            if (dataTypeStr.equals("raw")) {
                Log.v(this.TAG, "updateFromJSON - processing raw data");
                try {
                    this.mSdData.mHR = (short) dataObject.getInt("hr");
                    this.mSdData.curHeartAvg = (short) dataObject.getInt("curHeartAvg");
                    if (this.mSdData.mHR >= 0d) {
                        if (!prefValmHrAlarmActive)
                            Log.v(TAG, "updateFromJSON(): prefValmHrAlarmActive unset!");
                        this.mSdData.mHRAlarmActive = prefValmHrAlarmActive;
                    }
                } catch (final JSONException e) {
                    // if we get 'null' HR (For example if the heart rate is not working)
                    this.mSdData.mHR = -1;
                    this.mSdData.mHRAlarmActive = false;
                }
                try {
                    this.mSdData.mO2Sat = dataObject.getDouble("O2sat");
                } catch (final JSONException e) {
                    // if we get 'null' O2 Saturation (For example if the oxygen sensor is not working)
                    this.mSdData.mO2Sat = -1;
                }
                try {
                    this.mMute = dataObject.getInt("Mute");
                } catch (final JSONException e) {
                    // if we get 'null' HR (For example if the heart rate is not working)
                    this.mMute = 0;
                }
                accelVals = dataObject.getJSONArray("rawData");
                Log.v(this.TAG, "Received " + accelVals.length() + " acceleration values, rawData Length is " + this.mSdData.rawData.length);
                if (accelVals.length() > this.mSdData.rawData.length)
                    this.mUtil.writeToSysLogFile("ERROR:  Received " + accelVals.length() + " acceleration values, but rawData storage length is "
                            + this.mSdData.rawData.length);
                int i;
                for (i = 0; i < accelVals.length(); i++)
                    this.mSdData.rawData[i] = accelVals.getDouble(i);
                this.mSdData.mNsamp = accelVals.length();
                //Log.d(TAG,"accelVals[0]="+accelVals.getDouble(0)+", mSdData.rawData[0]="+mSdData.rawData[0]);
                try {
                    accelVals3D = dataObject.getJSONArray("rawData3D");
                    Log.v(this.TAG, "Received " + accelVals3D.length() + " acceleration 3D values, rawData Length is " + this.mSdData.rawData3D.length);
                    if (accelVals3D.length() > this.mSdData.rawData3D.length)
                        this.mUtil.writeToSysLogFile("ERROR:  Received " + accelVals3D.length() + " 3D acceleration values, but rawData3D storage length is "
                                + this.mSdData.rawData3D.length);
                    for (i = 0; i < accelVals3D.length(); i++)
                        this.mSdData.rawData3D[i] = accelVals3D.getDouble(i);
                } catch (final JSONException e) {
                    // If we get an error, just set rawData3D to zero
                    Log.i(this.TAG, "updateFromJSON - error parsing 3D data - setting it to zero");
                    for (i = 0; i < this.mSdData.rawData3D.length; i++)
                        this.mSdData.rawData3D[i] = 0d;
                }
                try {
                    this.mSdData.watchConnected = dataObject.getBoolean("watchConnected");
                    this.mSdData.watchAppRunning = dataObject.getBoolean("watchAppRunning");
                    this.mSdData.batteryPc = (short) dataObject.getInt("batteryPc");
                } catch (final Exception e) {
                    Log.e(this.TAG, "UpdateFromJSON()", e);
                }
                this.mWatchAppRunningCheck = true;
                this.doAnalysis();
                if (this.mSdData.mHR != 0d || dataTypeStr == "settings")
                    this.mSdData.haveSettings = true;
                if (!this.mSdData.haveSettings) retVal = "sendSettings";
                else retVal = "OK";
            } else if (dataTypeStr.equals("settings")) {
                Log.v(this.TAG, "updateFromJSON - processing settings");
                this.mSamplePeriod = (short) dataObject.getInt("analysisPeriod");
                this.mSampleFreq = (short) dataObject.getInt("sampleFreq");
                this.mSdData.batteryPc = (short) dataObject.getInt("batteryPc");
                Log.v(this.TAG, "updateFromJSON - mSamplePeriod=" + this.mSamplePeriod + " mSampleFreq=" + this.mSampleFreq);
                this.mUtil.writeToSysLogFile("SDDataSource.updateFromJSON - Settings Received");
                this.mUtil.writeToSysLogFile("    * mSamplePeriod=" + this.mSamplePeriod + " mSampleFreq=" + this.mSampleFreq);
                this.mUtil.writeToSysLogFile("    * batteryPc = " + this.mSdData.batteryPc);

                try {
                    watchPartNo = dataObject.getString("watchPartNo");
                    watchFwVersion = dataObject.getString("watchFwVersion");
                    sdVersion = dataObject.getString("sdVersion");
                    sdName = dataObject.getString("sdName");
                    this.mUtil.writeToSysLogFile("    * sdName = " + sdName + " version " + sdVersion);
                    this.mUtil.writeToSysLogFile("    * watchPartNo = " + watchPartNo + " fwVersion " + watchFwVersion);
                    this.mSdData.watchPartNo = watchPartNo;
                    this.mSdData.watchFwVersion = watchFwVersion;
                    this.mSdData.watchSdVersion = sdVersion;
                    this.mSdData.watchSdName = sdName;
                } catch (final Exception e) {
                    Log.e(this.TAG, "updateFromJSON - Error Parsing V3.2 JSON String - " + e, e);
                    this.mUtil.writeToSysLogFile("updateFromJSON - Error Parsing V3.2 JSON String - " + jsonStr + " - " + e);
                    this.mUtil.writeToSysLogFile("          This is probably because of an out of date watch app - please upgrade!");
                    e.printStackTrace();
                }
                this.mSdData.haveSettings = true;
                this.mSdData.mSampleFreq = this.mSampleFreq;
                this.mWatchAppRunningCheck = true;
                try {
                    this.mSdData.watchConnected = dataObject.getBoolean("watchConnected");
                    this.mSdData.watchAppRunning = dataObject.getBoolean("watchAppRunning");
                } catch (final Exception e) {
                    Log.e(this.TAG, "UpdateFromJSON()", e);
                }
                updatePrefs();
                retVal = "OK";
            } else if (dataTypeStr.equals("watchConnect")) {
                this.mSdData.watchConnected = dataObject.getBoolean("watchConnected");
                this.mSdData.watchAppRunning = dataObject.getBoolean("watchAppRunning");
                retVal = dataTypeStr;
                // TODO: give me here a question: reconnect or quit
                //       Let me give here give a update to StartActivity or MainActivity
            } else if (dataTypeStr.equals("watchDisconnect")) {
                this.mSdData.watchConnected = dataObject.getBoolean("watchConnected");
                this.mSdData.watchAppRunning = dataObject.getBoolean("watchAppRunning");
                retVal = dataTypeStr;

                // TODO: give me here a question: reconnect or quit
                //       Let me give here give a update to StartActivity or MainActivity
            } else {
                Log.e(this.TAG, "updateFromJSON - unrecognised dataType " + dataTypeStr);
                retVal = "ERROR";
            }
        } catch (final Exception e) {
            Log.e(this.TAG, "updateFromJSON - Error Parsing JSON String - " + jsonStr + " - " + e, e);
            this.mUtil.writeToSysLogFile("updateFromJSON - Error Parsing JSON String - " + jsonStr + " - " + e);
            //mUtil.writeToSysLogFile("updateFromJSON: Exception at Line Number: " + e.getCause().getStackTrace()[0].getLineNumber() + ", " + e.getCause().getStackTrace()[0].toString());
            if (accelVals == null)
                this.mUtil.writeToSysLogFile("updateFromJSON: accelVals is null when exception thrown");
            else
                this.mUtil.writeToSysLogFile("updateFromJSON: Received " + accelVals.length() + " acceleration values");
            e.printStackTrace();
            retVal = "ERROR";
        }
        return retVal;
    }

    /**
     * Calculate the magnitude of entry i in the fft array fft
     *
     * @param fft
     * @param i
     * @return magnitude ( Re*Re + Im*Im )
     */
    private double getMagnitude(final double[] fft, final int i) {
        final double mag;
        mag = fft[2 * i] * fft[2 * i] + fft[2 * i + 1] * fft[2 * i + 1];
        return mag;
    }

    /**
     * doAnalysis() - analyse the data if the accelerometer data array mAccData
     * and populate the output data structure mSdData
     */
    protected void doAnalysis() {
        double nMin = 0;
        double nMax = 0;
        double nFreqCutoff = 0;
        double[] fft = null;
        try {
            // FIXME - Use specified sampleFreq, not this hard coded one
            final int sampleFreq;
            if (this.mSdData.mSampleFreq != 0) this.mSampleFreq = (short) this.mSdData.mSampleFreq;
            else sampleFreq = (int) (this.mSdData.mNsamp / this.mSdData.dT);
            final double freqRes = 1.0 * this.mSampleFreq / this.mSdData.mNsamp;
            Log.v(this.TAG, "doAnalysis(): mSampleFreq=" + this.mSampleFreq + " mNSamp=" + this.mSdData.mNsamp + ": freqRes=" + freqRes);
            // Set the frequency bounds for the analysis in fft output bin numbers.
            nMin = this.mAlarmFreqMin / freqRes;
            nMax = this.mAlarmFreqMax / freqRes;
            Log.v(this.TAG, "doAnalysis(): mAlarmFreqMin=" + this.mAlarmFreqMin + ", nMin=" + nMin
                    + ", mAlarmFreqMax=" + this.mAlarmFreqMax + ", nMax=" + nMax);
            // Calculate the bin number of the cutoff frequency
            final short mFreqCutoff = 12;
            nFreqCutoff = mFreqCutoff / freqRes;
            Log.v(this.TAG, "mFreqCutoff = " + mFreqCutoff + ", nFreqCutoff=" + nFreqCutoff);

            final DoubleFFT_1D fftDo = new DoubleFFT_1D(this.mSdData.mNsamp);
            fft = new double[this.mSdData.mNsamp * 2];
            ///System.arraycopy(mAccData, 0, fft, 0, mNsamp);
            System.arraycopy(this.mSdData.rawData, 0, fft, 0, this.mSdData.mNsamp);
            fftDo.realForward(fft);

            // Calculate the whole spectrum power (well a value equivalent to it that avoids square root calculations
            // and zero any readings that are above the frequency cutoff.
            double specPower = 0;
            for (int i = 1; i < this.mSdData.mNsamp / 2; i++)
                if (i <= nFreqCutoff) specPower = specPower + this.getMagnitude(fft, i);
                else {
                    fft[2 * i] = 0.;
                    fft[2 * i + 1] = 0.;
                }
            //Log.v(TAG,"specPower = "+specPower);
            //specPower = specPower/(mSdData.mNsamp/2);
            specPower = specPower / this.mSdData.mNsamp / 2;
            //Log.v(TAG,"specPower = "+specPower);

            // Calculate the Region of Interest power and power ratio.
            double roiPower = 0;
            for (int i = (int) Math.floor(nMin); i < (int) Math.ceil(nMax); i++)
                roiPower = roiPower + this.getMagnitude(fft, i);
            roiPower = roiPower / (nMax - nMin);
            final double roiRatio = 10 * roiPower / specPower;

            // Calculate the simplified spectrum - power in 1Hz bins.
            // Values for SD_MODE
            final int SIMPLE_SPEC_FMAX = 10;
            final double[] simpleSpec = new double[SIMPLE_SPEC_FMAX + 1];
            for (int ifreq = 0; ifreq < SIMPLE_SPEC_FMAX; ifreq++) {
                final int binMin = (int) (1 + ifreq / freqRes);    // add 1 to loose dc component
                final int binMax = (int) (1 + (ifreq + 1) / freqRes);
                simpleSpec[ifreq] = 0;
                for (int i = binMin; i < binMax; i++)
                    simpleSpec[ifreq] = simpleSpec[ifreq] + this.getMagnitude(fft, i);
                simpleSpec[ifreq] = simpleSpec[ifreq] / (binMax - binMin);
            }

            // Populate the mSdData structure to communicate with the main SdServer service.
            if (this.mSdData.dataTime == null) this.mSdData.dataTime = new Time();
            this.mSdData.dataTime.setToNow();
            this.mDataStatusTime = this.mSdData.dataTime;
            // Amount by which to reduce analysis results to scale to be comparable to analysis on Pebble.
            final int ACCEL_SCALE_FACTOR = 1000;
            this.mSdData.specPower = (long) specPower / ACCEL_SCALE_FACTOR;
            this.mSdData.roiPower = (long) roiPower / ACCEL_SCALE_FACTOR;
            this.mSdData.dataTime.setToNow();
            this.mSdData.maxVal = 0;   // not used
            this.mSdData.maxFreq = 0;  // not used
            this.mSdData.haveData = true;
            this.mSdData.alarmThresh = this.mAlarmThresh;
            this.mSdData.alarmRatioThresh = this.mAlarmRatioThresh;
            this.mSdData.alarmFreqMin = this.mAlarmFreqMin;
            this.mSdData.alarmFreqMax = this.mAlarmFreqMax;
            // note mSdData.batteryPc is set from settings data in updateFromJSON()
            // FIXME - I haven't worked out why dividing by 1000 seems necessary to get the graph on scale - we don't seem to do that with the Pebble.
            for (int i = 0; i < SIMPLE_SPEC_FMAX; i++)
                this.mSdData.simpleSpec[i] = (int) simpleSpec[i] / ACCEL_SCALE_FACTOR;
            Log.v(this.TAG, "simpleSpec = " + Arrays.toString(this.mSdData.simpleSpec));

            // Because we have received data, set flag to show watch app running.
            this.mWatchAppRunningCheck = true;
        } catch (final Exception e) {
            Log.e(this.TAG, "doAnalysis - Exception during Analysis", e);
            this.mUtil.writeToSysLogFile("doAnalysis - Exception during analysis - " + e);
            this.mUtil.writeToSysLogFile("doAnalysis: Exception at Line Number: " + e.getCause().getStackTrace()[0].getLineNumber() + ", " + e.getCause().getStackTrace()[0].toString());
            this.mUtil.writeToSysLogFile("doAnalysis: mSdData.mNsamp=" + this.mSdData.mNsamp);
            this.mUtil.writeToSysLogFile("doAnalysis: alarmFreqMin=" + this.mAlarmFreqMin + " nMin=" + nMin);
            this.mUtil.writeToSysLogFile("doAnalysis: alarmFreqMax=" + this.mAlarmFreqMax + " nMax=" + nMax);
            this.mUtil.writeToSysLogFile("doAnalysis: nFreqCutoff.=" + nFreqCutoff);
            this.mUtil.writeToSysLogFile("doAnalysis: fft.length=" + fft.length);
            this.mWatchAppRunningCheck = false;
        }

        // Check this data to see if it represents an alarm state.
        this.alarmCheck();
        this.hrCheck();
        this.o2SatCheck();
        this.fallCheck();
        this.muteCheck();
        Log.v(this.TAG, "after fallCheck, mSdData.fallAlarmStanding=" + this.mSdData.fallAlarmStanding);

        SdDataSource.mSdDataReceiver.onSdDataReceived(this.mSdData);  // and tell SdServer we have received data.
    }


    /****************************************************************
     * checkAlarm() - checks the current accelerometer data and uses
     * historical data to determine if we are in a fault, warning or ok
     * state.
     * Sets mSdData.alarmState and mSdData.hrAlarmStanding
     */
    private void alarmCheck() {
        final boolean inAlarm;
        // Avoid potential divide by zero issue
        if (this.mSdData.specPower == 0)
            this.mSdData.specPower = 1;
        Log.v(this.TAG, "alarmCheck() - roiPower=" + this.mSdData.roiPower + " specPower=" + this.mSdData.specPower + " ratio=" + 10 * this.mSdData.roiPower / this.mSdData.specPower);
        // Is the current set of data representing an alarm state?
        inAlarm = this.mSdData.roiPower > this.mAlarmThresh && 10 * this.mSdData.roiPower / this.mSdData.specPower > this.mAlarmRatioThresh;

        // set the alarmState to Alarm, Warning or OK, depending on the current state and previous ones.
        // If we are not in an ALARM state, revert back to WARNING, otherwise
        // revert back to OK.
        if (inAlarm) {
            this.mAlarmCount += this.mSamplePeriod;
            // full alarm
            if (this.mAlarmCount > this.mAlarmTime) this.mSdData.alarmState = 2;
            else // warning
                if (this.mAlarmCount > this.mWarnTime) this.mSdData.alarmState = 1;
        } else if (this.mSdData.alarmState == 2) {
            // revert to warning
            this.mSdData.alarmState = 1;
            this.mAlarmCount = this.mWarnTime + 1;  // pretend we have only just entered warning state.
        } else {
            // revert to OK
            this.mSdData.alarmState = 0;
            this.mAlarmCount = 0;
        }

        Log.v(this.TAG, "alarmCheck(): inAlarm=" + inAlarm + ", alarmState = " + this.mSdData.alarmState + " alarmCount=" + this.mAlarmCount + " mWarnTime=" + this.mWarnTime + " mAlarmTime=" + this.mAlarmTime);

    }

    public void muteCheck() {
        if (this.mMute != 0) {
            Log.v(this.TAG, "Mute Active - setting alarms to mute");
            this.mSdData.alarmState = 6;
            this.mSdData.alarmPhrase = "MUTE";
            this.mSdData.mHRAlarmStanding = false;
        }

    }

    /**
     * hrCheck - check the Heart rate data in mSdData to see if it represents an alarm condition.
     * Sets mSdData.mHRAlarmStanding
     */
    public void hrCheck() {
        Log.v(this.TAG, "hrCheck()");
        /* Check Heart Rate against alarm settings */
        if (this.mSdData.mHRAlarmActive)
            if ((short) this.mSdData.mHR < 0) if (this.mSdData.mHRNullAsAlarm) {
                Log.i(this.TAG, "Heart Rate Null - Alarming");
                this.mSdData.mHRFaultStanding = false;
                this.mSdData.mHRAlarmStanding = true;
            } else {
                Log.i(this.TAG, "Heart Rate Fault (HR<0)");
                this.mSdData.mHRFaultStanding = true;
                this.mSdData.mHRAlarmStanding = false;
            }
            else if (this.mSdData.mHR < this.mSdData.mHRThreshMin || this.mSdData.mHR > this.mSdData.mHRThreshMax) {
                Log.i(this.TAG, "Heart Rate Abnormal - " + (short) this.mSdData.mHR + " bpm");
                this.mSdData.mHRFaultStanding = false;
                this.mSdData.mHRAlarmStanding = true;
            } else {
                this.mSdData.mHRFaultStanding = false;
                this.mSdData.mHRAlarmStanding = false;
            }
    }

    /**
     * hrCheck - check the Heart rate data in mSdData to see if it represents an alarm condition.
     * Sets mSdData.mHRAlarmStanding
     */
    public void o2SatCheck() {
        Log.v(this.TAG, "o2SatCheck()");
        /* Check Oxygen Saturation against alarm settings */
        if (this.mSdData.mO2SatAlarmActive)
            if (this.mSdData.mO2Sat < 0) if (this.mSdData.mO2SatNullAsAlarm) {
                Log.i(this.TAG, "Oxygen Saturation Null - Alarming");
                this.mSdData.mO2SatFaultStanding = false;
                this.mSdData.mO2SatAlarmStanding = true;
            } else {
                Log.i(this.TAG, "Oxygen Saturation Fault (O2Sat<0)");
                this.mSdData.mO2SatFaultStanding = true;
                this.mSdData.mO2SatAlarmStanding = false;
            }
            else if (this.mSdData.mO2Sat < this.mSdData.mO2SatThreshMin) {
                Log.i(this.TAG, "Oxygen Saturation Abnormal - " + this.mSdData.mO2Sat + " %");
                this.mSdData.mO2SatFaultStanding = false;
                this.mSdData.mO2SatAlarmStanding = true;
            } else {
                this.mSdData.mO2SatFaultStanding = false;
                this.mSdData.mO2SatAlarmStanding = false;
            }

    }


    /****************************************************************
     * Simple threshold analysis to chech for fall.
     * Called from clock_tick_handler()
     */
    public void fallCheck() {
        int i, j;
        double minAcc, maxAcc;

        final long fallWindowSamp = this.mFallWindow * this.mSdData.mSampleFreq / 1000; // Convert ms to samples.
        Log.v(this.TAG, "check_fall() - fallWindowSamp=" + fallWindowSamp);
        // Move window through sample buffer, checking for fall.
        // Note - not resetting fallAlarmStanding means that fall alarms will always latch until the 'Accept Alarm' button
        // is pressed.
        //mSdData.fallAlarmStanding = false;
        if (this.mFallActive) {
            this.mSdData.mFallActive = true;
            for (i = 0; i < this.mSdData.mNsamp - fallWindowSamp; i++) {  // i = window start point
                // Find max and min acceleration within window.
                minAcc = this.mSdData.rawData[i];
                maxAcc = this.mSdData.rawData[i];
                for (j = 0; j < fallWindowSamp; j++) {  // j = position within window
                    if (this.mSdData.rawData[i + j] < minAcc) minAcc = this.mSdData.rawData[i + j];
                    if (this.mSdData.rawData[i + j] > maxAcc) maxAcc = this.mSdData.rawData[i + j];
                }
                Log.d(this.TAG, "check_fall() - minAcc=" + minAcc + " (mFallThreshMin=" + this.mFallThreshMin + "), maxAcc=" + maxAcc + " (mFallThreshMax=" + this.mFallThreshMax + ")");
                if (minAcc < this.mFallThreshMin && maxAcc > this.mFallThreshMax) {
                    Log.d(this.TAG, "check_fall() ****FALL DETECTED***** minAcc=" + minAcc + ", maxAcc=" + maxAcc);
                    Log.d(this.TAG, "check_fall() - ****FALL DETECTED****");
                    this.mSdData.fallAlarmStanding = true;
                    return;
                }
                if (this.mMute != 0) {
                    Log.v(this.TAG, "Mute Active - setting fall alarm to mute");
                    this.mSdData.fallAlarmStanding = false;
                }
            }
        } else {
            this.mSdData.mFallActive = false;
            Log.v(this.TAG, "check_fall - mFallActive is false - doing nothing");
        }
        //if (debug) APP_LOG(APP_LOG_LEVEL_DEBUG,"check_fall() - minAcc=%d, maxAcc=%d",
        //	  minAcc,maxAcc);

    }

    /**
     * Checks the status of the connection to the watch,
     * and sets class variables for use by other functions.
     */
    public void getStatus() {
        final Time tnow = new Time(Time.getCurrentTimezone());
        final long tdiff;
        tnow.setToNow();
        // get time since the last data was received from the Pebble watch.
        tdiff = tnow.toMillis(false) - this.mDataStatusTime.toMillis(false);
        Log.v(this.TAG, "getStatus() - mWatchAppRunningCheck=" + this.mWatchAppRunningCheck + " tdiff=" + tdiff);
        Log.v(this.TAG, "getStatus() - tdiff=" + tdiff + ", mDataUpatePeriod=" + this.mDataUpdatePeriod + ", mAppRestartTimeout=" + this.mAppRestartTimeout);

        this.mSdData.watchConnected = true;  // We can't check connection for passive network connection, so set it to true to avoid errors.
        // And is the watch app running?
        // set mWatchAppRunningCheck has been false for more than 10 seconds
        // the app is not talking to us
        // mWatchAppRunningCheck is set to true in the receiveData handler.
        if (!this.mWatchAppRunningCheck &&
                tdiff > (this.mDataUpdatePeriod + this.mAppRestartTimeout) * 1000L) {
            Log.v(this.TAG, "getStatus() - tdiff = " + tdiff);
            this.mSdData.watchAppRunning = false;
            // Only make audible warning beep if we have not received data for more than mFaultTimerPeriod seconds.
            if (tdiff > (this.mDataUpdatePeriod + this.mFaultTimerPeriod) * 1000L) {
                Log.v(this.TAG, "getStatus() - Watch App Not Running");
                this.mUtil.writeToSysLogFile("SDDataSource.getStatus() - Watch App not Running");
                //mDataStatusTime.setToNow();
                this.mSdData.roiPower = -1;
                this.mSdData.specPower = -1;
                SdDataSource.mSdDataReceiver.onSdDataFault(this.mSdData);
            } else
                Log.v(this.TAG, "getStatus() - Waiting for mFaultTimerPeriod before issuing audible warning...");
        } else this.mSdData.watchAppRunning = true;

        // if we have confirmation that the app is running, reset the
        // status time to now and initiate another check.
        if (this.mWatchAppRunningCheck) {
            this.mWatchAppRunningCheck = false;
            this.mDataStatusTime.setToNow();
        }

        if (!this.mSdData.haveSettings) Log.v(this.TAG, "getStatus() - no settings received yet");
    }

    /**
     * faultCheck - determines alarm state based on seizure detector data SdData.   Called every second.
     */
    private void faultCheck() {
        final Time tnow = new Time(Time.getCurrentTimezone());
        final long tdiff;
        tnow.setToNow();

        // get time since the last data was received from the watch.
        tdiff = tnow.toMillis(false) - this.mDataStatusTime.toMillis(false);
        //Log.v(TAG, "faultCheck() - tdiff=" + tdiff + ", mDataUpatePeriod=" + mDataUpdatePeriod + ", mAppRestartTimeout=" + mAppRestartTimeout
        //        + ", combined = " + (mDataUpdatePeriod + mAppRestartTimeout) * 1000);
        //Log.v(TAG, "faultCheck() - watch app not running so not doing anything");
        if (!this.mWatchAppRunningCheck &&
                tdiff > (this.mDataUpdatePeriod + this.mAppRestartTimeout) * 1000L)
            this.mAlarmCount = 0;
    }

    /**
     * updatePrefs() - update basic settings from the SharedPreferences
     * - defined in res/xml/SdDataSourceNetworkPassivePrefs.xml
     */
    public void updatePrefs() {
        Log.v(this.TAG, "updatePrefs()");
        this.mUtil.writeToSysLogFile("SDDataSource.updatePrefs()");
        final SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(this.mContext);
        try {
            // Parse the AppRestartTimeout period setting.
            try {
                final String appRestartTimeoutStr = SP.getString("AppRestartTimeout", "10");
                this.mAppRestartTimeout = Integer.parseInt(appRestartTimeoutStr);
                Log.v(this.TAG, "updatePrefs() - mAppRestartTimeout = " + this.mAppRestartTimeout);
                this.mUtil.writeToSysLogFile("updatePrefs() - mAppRestartTimeout = " + this.mAppRestartTimeout);
            } catch (final Exception ex) {
                Log.v(this.TAG, "updatePrefs() - Problem with AppRestartTimeout preference!");
                this.mUtil.writeToSysLogFile("updatePrefs() - Problem with AppRestartTimeout preference!");
                final Toast toast = Toast.makeText(this.mContext, "Problem Parsing AppRestartTimeout Preference", Toast.LENGTH_SHORT);
                toast.show();
            }

            // Parse the FaultTimer period setting.
            try {
                final String faultTimerPeriodStr = SP.getString("FaultTimerPeriod", "30");
                this.mFaultTimerPeriod = Integer.parseInt(faultTimerPeriodStr);
                Log.v(this.TAG, "updatePrefs() - mFaultTimerPeriod = " + this.mFaultTimerPeriod);
                this.mUtil.writeToSysLogFile("updatePrefs() - mFaultTimerPeriod = " + this.mFaultTimerPeriod);
            } catch (final Exception ex) {
                Log.v(this.TAG, "updatePrefs() - Problem with FaultTimerPeriod preference!");
                this.mUtil.writeToSysLogFile("updatePrefs() - Problem with FaultTimerPeriod preference!");
                final Toast toast = Toast.makeText(this.mContext, "Problem Parsing FaultTimerPeriod Preference", Toast.LENGTH_SHORT);
                toast.show();
            }


            // Watch Settings
            String prefStr;
            prefStr = SP.getString("BLE_Device_Addr", "SET_FROM_XML");
            this.mBleDeviceAddr = prefStr;
            Log.v(this.TAG, "mBLEDeviceAddr=" + this.mBleDeviceAddr);
            this.mUtil.writeToSysLogFile("mBLEDeviceAddr=" + this.mBleDeviceAddr);
            prefStr = SP.getString("BLE_Device_Name", "SET_FROM_XML");
            this.mBleDeviceName = prefStr;
            Log.v(this.TAG, "mBLEDeviceName=" + this.mBleDeviceName);
            this.mUtil.writeToSysLogFile("mBLEDeviceName=" + this.mBleDeviceName);

            prefStr = SP.getString("PebbleDebug", "SET_FROM_XML");
            if (prefStr != null) {
                final short mDebug = (short) Integer.parseInt(prefStr);
                Log.v(this.TAG, "updatePrefs() Debug = " + mDebug);
                this.mUtil.writeToSysLogFile("updatePrefs() Debug = " + mDebug);

                prefStr = SP.getString("PebbleDisplaySpectrum", "SET_FROM_XML");
                final short mDisplaySpectrum = (short) Integer.parseInt(prefStr);
                Log.v(this.TAG, "updatePrefs() DisplaySpectrum = " + mDisplaySpectrum);
                this.mUtil.writeToSysLogFile("updatePrefs() DisplaySpectrum = " + mDisplaySpectrum);

                prefStr = SP.getString("PebbleUpdatePeriod", "SET_FROM_XML");
                this.mDataUpdatePeriod = (short) Integer.parseInt(prefStr);
                Log.v(this.TAG, "updatePrefs() DataUpdatePeriod = " + this.mDataUpdatePeriod);
                this.mUtil.writeToSysLogFile("updatePrefs() DataUpdatePeriod = " + this.mDataUpdatePeriod);

                prefStr = SP.getString("MutePeriod", "SET_FROM_XML");
                final short mMutePeriod = (short) Integer.parseInt(prefStr);
                Log.v(this.TAG, "updatePrefs() MutePeriod = " + mMutePeriod);
                this.mUtil.writeToSysLogFile("updatePrefs() MutePeriod = " + mMutePeriod);

                prefStr = SP.getString("ManAlarmPeriod", "SET_FROM_XML");
                final short mManAlarmPeriod = (short) Integer.parseInt(prefStr);
                Log.v(this.TAG, "updatePrefs() ManAlarmPeriod = " + mManAlarmPeriod);
                this.mUtil.writeToSysLogFile("updatePrefs() ManAlarmPeriod = " + mManAlarmPeriod);

                prefStr = SP.getString("PebbleSdMode", "SET_FROM_XML");
                final short mPebbleSdMode = (short) Integer.parseInt(prefStr);
                Log.v(this.TAG, "updatePrefs() PebbleSdMode = " + mPebbleSdMode);
                this.mUtil.writeToSysLogFile("updatePrefs() PebbleSdMode = " + mPebbleSdMode);

                prefStr = SP.getString("SampleFreq", "SET_FROM_XML");
                this.mSampleFreq = (short) Integer.parseInt(prefStr);
                Log.v(this.TAG, "updatePrefs() SampleFreq = " + this.mSampleFreq);
                this.mUtil.writeToSysLogFile("updatePrefs() SampleFreq = " + this.mSampleFreq);

                prefStr = SP.getString("SamplePeriod", "SET_FROM_XML");
                this.mSamplePeriod = (short) Integer.parseInt(prefStr);
                Log.v(this.TAG, "updatePrefs() AnalysisPeriod = " + this.mSamplePeriod);
                this.mUtil.writeToSysLogFile("updatePrefs() AnalysisPeriod = " + this.mSamplePeriod);

                prefStr = SP.getString("AlarmFreqMin", "SET_FROM_XML");
                this.mAlarmFreqMin = (short) Integer.parseInt(prefStr);
                Log.v(this.TAG, "updatePrefs() AlarmFreqMin = " + this.mAlarmFreqMin);
                this.mUtil.writeToSysLogFile("updatePrefs() AlarmFreqMin = " + this.mAlarmFreqMin);

                prefStr = SP.getString("AlarmFreqMax", "SET_FROM_XML");
                this.mAlarmFreqMax = (short) Integer.parseInt(prefStr);
                Log.v(this.TAG, "updatePrefs() AlarmFreqMax = " + this.mAlarmFreqMax);
                this.mUtil.writeToSysLogFile("updatePrefs() AlarmFreqMax = " + this.mAlarmFreqMax);

                prefStr = SP.getString("WarnTime", "SET_FROM_XML");
                this.mWarnTime = (short) Integer.parseInt(prefStr);
                Log.v(this.TAG, "updatePrefs() WarnTime = " + this.mWarnTime);
                this.mUtil.writeToSysLogFile("updatePrefs() WarnTime = " + this.mWarnTime);

                prefStr = SP.getString("AlarmTime", "SET_FROM_XML");
                this.mAlarmTime = (short) Integer.parseInt(prefStr);
                Log.v(this.TAG, "updatePrefs() AlarmTime = " + this.mAlarmTime);
                this.mUtil.writeToSysLogFile("updatePrefs() AlarmTime = " + this.mAlarmTime);

                prefStr = SP.getString("AlarmThresh", "SET_FROM_XML");
                this.mAlarmThresh = (short) Integer.parseInt(prefStr);
                Log.v(this.TAG, "updatePrefs() AlarmThresh = " + this.mAlarmThresh);
                this.mUtil.writeToSysLogFile("updatePrefs() AlarmThresh = " + this.mAlarmThresh);

                prefStr = SP.getString("AlarmRatioThresh", "SET_FROM_XML");
                this.mAlarmRatioThresh = (short) Integer.parseInt(prefStr);
                Log.v(this.TAG, "updatePrefs() AlarmRatioThresh = " + this.mAlarmRatioThresh);
                this.mUtil.writeToSysLogFile("updatePrefs() AlarmRatioThresh = " + this.mAlarmRatioThresh);

                this.mFallActive = SP.getBoolean("FallActive", false);
                Log.v(this.TAG, "updatePrefs() FallActive = " + this.mFallActive);
                this.mUtil.writeToSysLogFile("updatePrefs() FallActive = " + this.mFallActive);

                prefStr = SP.getString("FallThreshMin", "SET_FROM_XML");
                this.mFallThreshMin = (short) Integer.parseInt(prefStr);
                Log.v(this.TAG, "updatePrefs() FallThreshMin = " + this.mFallThreshMin);
                this.mUtil.writeToSysLogFile("updatePrefs() FallThreshMin = " + this.mFallThreshMin);

                prefStr = SP.getString("FallThreshMax", "SET_FROM_XML");
                this.mFallThreshMax = (short) Integer.parseInt(prefStr);
                Log.v(this.TAG, "updatePrefs() FallThreshMax = " + this.mFallThreshMax);
                this.mUtil.writeToSysLogFile("updatePrefs() FallThreshMax = " + this.mFallThreshMax);

                prefStr = SP.getString("FallWindow", "SET_FROM_XML");
                this.mFallWindow = (short) Integer.parseInt(prefStr);
                Log.v(this.TAG, "updatePrefs() FallWindow = " + this.mFallWindow);
                this.mUtil.writeToSysLogFile("updatePrefs() FallWindow = " + this.mFallWindow);

                this.prefValmHrAlarmActive = SP.getBoolean("HRAlarmActive", false);
                // this.mSdData.mHRAlarmActive = prefValmHrAlarmActive;
                Log.v(this.TAG, "updatePrefs() HRAlarmActive = " + this.mSdData.mHRAlarmActive);
                this.mUtil.writeToSysLogFile("updatePrefs() HRAlarmActive = " + this.mSdData.mHRAlarmActive);

                this.mSdData.mHRNullAsAlarm = SP.getBoolean("HRNullAsAlarm", false);
                Log.v(this.TAG, "updatePrefs() HRNullAsAlarm = " + this.mSdData.mHRNullAsAlarm);
                this.mUtil.writeToSysLogFile("updatePrefs() HRNullAsAlarm = " + this.mSdData.mHRNullAsAlarm);

                prefStr = SP.getString("HRThreshMin", "SET_FROM_XML");
                this.mSdData.mHRThreshMin = (short) Integer.parseInt(prefStr);
                Log.v(this.TAG, "updatePrefs() HRThreshMin = " + this.mSdData.mHRThreshMin);
                this.mUtil.writeToSysLogFile("updatePrefs() HRThreshMin = " + this.mSdData.mHRThreshMin);

                prefStr = SP.getString("HRThreshMax", "SET_FROM_XML");
                this.mSdData.mHRThreshMax = (short) Integer.parseInt(prefStr);
                Log.v(this.TAG, "updatePrefs() HRThreshMax = " + this.mSdData.mHRThreshMax);
                this.mUtil.writeToSysLogFile("updatePrefs() HRThreshMax = " + this.mSdData.mHRThreshMax);

                this.mSdData.mO2SatAlarmActive = SP.getBoolean("O2SatAlarmActive", false);
                Log.v(this.TAG, "updatePrefs() O2SatAlarmActive = " + this.mSdData.mO2SatAlarmActive);
                this.mUtil.writeToSysLogFile("updatePrefs() O2SatAlarmActive = " + this.mSdData.mO2SatAlarmActive);

                this.mSdData.mO2SatNullAsAlarm = SP.getBoolean("O2SatNullAsAlarm", false);
                Log.v(this.TAG, "updatePrefs() O2SatNullAsAlarm = " + this.mSdData.mO2SatNullAsAlarm);
                this.mUtil.writeToSysLogFile("updatePrefs() O2SatNullAsAlarm = " + this.mSdData.mO2SatNullAsAlarm);

                prefStr = SP.getString("O2SatThreshMin", "SET_FROM_XML");
                this.mSdData.mO2SatThreshMin = (short) Integer.parseInt(prefStr);
                Log.v(this.TAG, "updatePrefs() O2SatThreshMin = " + this.mSdData.mO2SatThreshMin);
                this.mUtil.writeToSysLogFile("updatePrefs() O2SatThreshMin = " + this.mSdData.mO2SatThreshMin);

            } else {
                Log.v(this.TAG, "updatePrefs() - prefStr is null - WHY????");
                this.mUtil.writeToSysLogFile("SDDataSource.updatePrefs() - prefStr is null - WHY??");
                final Toast toast = Toast.makeText(this.mContext, "Problem Parsing Preferences - Something won't work - Please go back to Settings and correct it!", Toast.LENGTH_SHORT);
                toast.show();
            }

        } catch (final Exception ex) {
            Log.v(this.TAG, "updatePrefs() - Problem parsing preferences!");
            this.mUtil.writeToSysLogFile("SDDataSource.updatePrefs() - ERROR " + ex);
            final Toast toast = Toast.makeText(this.mContext, "Problem Parsing Preferences - Something won't work - Please go back to Settings and correct it!", Toast.LENGTH_SHORT);
            toast.show();
        }
    }


    /**
     * Display a Toast message on screen.
     *
     * @param msg - message to display.
     */
    public void showToast(final String msg) {
        Toast.makeText(this.mContext, msg,
                Toast.LENGTH_LONG).show();
    }


    public class SdDataBroadcastReceiver extends BroadcastReceiver {
        //private String TAG = "SdDataBroadcastReceiver";

        @Override
        public void onReceive(final Context context, final Intent intent) {
            Log.v(SdDataSource.this.TAG, "SdDataBroadcastReceiver.onReceive()");
            final String jsonStr = intent.getStringExtra("data");
            Log.v(SdDataSource.this.TAG, "SdDataBroadcastReceiver.onReceive() - data=" + jsonStr);
            SdDataSource.this.updateFromJSON(jsonStr);
        }
    }


}
