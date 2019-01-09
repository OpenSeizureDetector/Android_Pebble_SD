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
package uk.org.openseizuredetector;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.format.Time;
import android.util.Log;
import android.widget.Toast;

import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jtransforms.fft.DoubleFFT_1D;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import static java.lang.Math.sqrt;


/**
 * A Passive data source that expects a device to send it data periodically by sending a POST request.
 * The POST network request is handled in the SDWebServer class, which calls the 'updateFrom JSON()'
 * function to send the data to this datasource.
 * SdWebServer expects POST requests to /data and /settings URLs to send data or watch settings.
 */
public class SdDataSourceNetworkPassive extends SdDataSource {
    private Handler mHandler = new Handler();
    private Timer mStatusTimer;
    private Timer mSettingsTimer;
    private Timer mAlarmCheckTimer;
    private Time mDataStatusTime;
    private boolean mWatchAppRunningCheck = false;
    private int mAppRestartTimeout = 10;  // Timeout before re-starting watch app (sec) if we have not received
                                            // data after mDataUpdatePeriod
    private int mFaultTimerPeriod = 30;  // Fault Timer Period in sec
    private int mSettingsPeriod = 60;  // period between requesting settings in seconds.
    private SdDataBroadcastReceiver mSdDataBroadcastReceiver;


    private String TAG = "SdDataSourceNetPassive";

    // Values for SD_MODE
    private int SD_MODE_FFT = 0;     // The original OpenSeizureDetector mode (FFT based)
    private int SD_MODE_RAW = 1;     // Send raw, unprocessed data to the phone.
    private int SD_MODE_FILTER = 2;  // Use digital filter rather than FFT.
    private int SIMPLE_SPEC_FMAX = 10;

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

    private int mAlarmCount;

    // raw data storage for SD_MODE_RAW
    private int MAX_RAW_DATA = 500;
    public double[] mAccData = new double[MAX_RAW_DATA];
    int mNSamp = 0;


    public SdDataSourceNetworkPassive(Context context, Handler handler,
                                      SdDataReceiver sdDataReceiver) {
        super(context, handler, sdDataReceiver);
        mName = "NetworkPassive";
        // Set default settings from XML files (mContext is set by super().
        PreferenceManager.setDefaultValues(mContext,
                R.xml.network_passive_datasource_prefs, true);
    }


    /**
     * Start the datasource updating - initialises from sharedpreferences first to
     * make sure any changes to preferences are taken into account.
     */
    public void start() {
        Log.v(TAG, "start()");
        mUtil.writeToSysLogFile("SdDataSourceNetworkPassive.start()");
        updatePrefs();
        // Start timer to check status of watch regularly.
        mDataStatusTime = new Time(Time.getCurrentTimezone());
        // use a timer to check the status of the pebble app on the same frequency
        // as we get app data.
        if (mStatusTimer == null) {
            Log.v(TAG, "start(): starting status timer");
            mUtil.writeToSysLogFile("SdDataSourceNetworkPassive.start() - starting status timer");
            mStatusTimer = new Timer();
            mStatusTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    getStatus();
                }
            }, 0, mDataUpdatePeriod * 1000);
        } else {
            Log.v(TAG, "start(): status timer already running.");
            mUtil.writeToSysLogFile("SdDataSourceNetworkPassive.start() - status timer already running??");
        }
        if (mAlarmCheckTimer == null) {
            Log.v(TAG, "start(): starting alarm check timer");
            mUtil.writeToSysLogFile("SdDataSourceNetworkPassive.start() - starting alarm check timer");
            mAlarmCheckTimer = new Timer();
            mAlarmCheckTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    alarmCheck();
                }
            }, 0, 1000);
        } else {
            Log.v(TAG, "start(): alarm check timer already running.");
            mUtil.writeToSysLogFile("SdDataSourceNetworkPassive.start() - alarm check timer already running??");
        }

        if (mSettingsTimer == null) {
            Log.v(TAG, "start(): starting settings timer");
            mUtil.writeToSysLogFile("SdDataSourceNetworkPassive.start() - starting settings timer");
            mSettingsTimer = new Timer();
            mSettingsTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    mSdData.haveSettings = false;
                }
            }, 0, 1000 * mSettingsPeriod);  // ask for settings less frequently than we get data
        } else {
            Log.v(TAG, "start(): settings timer already running.");
            mUtil.writeToSysLogFile("SdDataSourceNetworkPassive.start() - settings timer already running??");
        }

        mSdDataBroadcastReceiver = new SdDataBroadcastReceiver();
        //uk.org.openseizuredetector.SdDataReceived
        IntentFilter filter = new IntentFilter("uk.org.openseizuredetector.SdDataReceived");
        mContext.registerReceiver(mSdDataBroadcastReceiver, filter);

    }

    /**
     * Stop the datasource from updating
     */
    public void stop() {
        Log.v(TAG, "stop()");
        mUtil.writeToSysLogFile("SdDataSourceNetworkPassive.stop()");
        try {
            // Stop the status timer
            if (mStatusTimer != null) {
                Log.v(TAG, "stop(): cancelling status timer");
                mUtil.writeToSysLogFile("SdDataSourceNetworkPassive.stop() - cancelling status timer");
                mStatusTimer.cancel();
                mStatusTimer.purge();
                mStatusTimer = null;
            }
        } catch (Exception e) {
            Log.v(TAG, "Error in stop() - " + e.toString());
            mUtil.writeToSysLogFile("SdDataSourceNetworkPassive.stop() - error - "+e.toString());
        }
        mContext.unregisterReceiver(mSdDataBroadcastReceiver);
    }

    /**
     * updatePrefs() - update basic settings from the SharedPreferences
     * - defined in res/xml/SdDataSourceNetworkPassivePrefs.xml
     */
    public void updatePrefs() {
        Log.v(TAG, "updatePrefs()");
        mUtil.writeToSysLogFile("SdDataSourceNetworkPassive.updatePrefs()");
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(mContext);
        try {
            // Parse the AppRestartTimeout period setting.
            try {
                String appRestartTimeoutStr = SP.getString("AppRestartTimeout", "10");
                mAppRestartTimeout = Integer.parseInt(appRestartTimeoutStr);
                Log.v(TAG, "updatePrefs() - mAppRestartTimeout = " + mAppRestartTimeout);
            } catch (Exception ex) {
                Log.v(TAG, "updatePrefs() - Problem with AppRestartTimeout preference!");
                Toast toast = Toast.makeText(mContext, "Problem Parsing AppRestartTimeout Preference", Toast.LENGTH_SHORT);
                toast.show();
            }

            // Parse the FaultTimer period setting.
            try {
                String faultTimerPeriodStr = SP.getString("FaultTimerPeriod", "30");
                mFaultTimerPeriod = Integer.parseInt(faultTimerPeriodStr);
                Log.v(TAG, "updatePrefs() - mFaultTimerPeriod = " + mFaultTimerPeriod);
            } catch (Exception ex) {
                Log.v(TAG, "updatePrefs() - Problem with FaultTimerPeriod preference!");
                Toast toast = Toast.makeText(mContext, "Problem Parsing FaultTimerPeriod Preference", Toast.LENGTH_SHORT);
                toast.show();
            }


            // Watch Settings
            String prefStr;

            prefStr = SP.getString("PebbleDebug", "SET_FROM_XML");
            mDebug = (short) Integer.parseInt(prefStr);
            Log.v(TAG, "updatePrefs() Debug = " + mDebug);

            prefStr = SP.getString("PebbleDisplaySpectrum", "SET_FROM_XML");
            mDisplaySpectrum = (short) Integer.parseInt(prefStr);
            Log.v(TAG, "updatePrefs() DisplaySpectrum = " + mDisplaySpectrum);

            prefStr = SP.getString("PebbleUpdatePeriod", "SET_FROM_XML");
            mDataUpdatePeriod = (short) Integer.parseInt(prefStr);
            Log.v(TAG, "updatePrefs() DataUpdatePeriod = " + mDataUpdatePeriod);

            prefStr = SP.getString("MutePeriod", "SET_FROM_XML");
            mMutePeriod = (short) Integer.parseInt(prefStr);
            Log.v(TAG, "updatePrefs() MutePeriod = " + mMutePeriod);

            prefStr = SP.getString("ManAlarmPeriod", "SET_FROM_XML");
            mManAlarmPeriod = (short) Integer.parseInt(prefStr);
            Log.v(TAG, "updatePrefs() ManAlarmPeriod = " + mManAlarmPeriod);

            prefStr = SP.getString("PebbleSdMode", "SET_FROM_XML");
            mPebbleSdMode = (short) Integer.parseInt(prefStr);
            Log.v(TAG, "updatePrefs() PebbleSdMode = " + mPebbleSdMode);

            prefStr = SP.getString("SampleFreq", "SET_FROM_XML");
            mSampleFreq = (short) Integer.parseInt(prefStr);
            Log.v(TAG, "updatePrefs() SampleFreq = " + mSampleFreq);

            prefStr = SP.getString("SamplePeriod", "SET_FROM_XML");
            mSamplePeriod = (short) Integer.parseInt(prefStr);
            Log.v(TAG, "updatePrefs() AnalysisPeriod = " + mSamplePeriod);

            prefStr = SP.getString("AlarmFreqMin", "SET_FROM_XML");
            mAlarmFreqMin = (short) Integer.parseInt(prefStr);
            Log.v(TAG, "updatePrefs() AlarmFreqMin = " + mAlarmFreqMin);

            prefStr = SP.getString("AlarmFreqMax", "SET_FROM_XML");
            mAlarmFreqMax = (short) Integer.parseInt(prefStr);
            Log.v(TAG, "updatePrefs() AlarmFreqMax = " + mAlarmFreqMax);

            prefStr = SP.getString("WarnTime", "SET_FROM_XML");
            mWarnTime = (short) Integer.parseInt(prefStr);
            Log.v(TAG, "updatePrefs() WarnTime = " + mWarnTime);

            prefStr = SP.getString("AlarmTime", "SET_FROM_XML");
            mAlarmTime = (short) Integer.parseInt(prefStr);
            Log.v(TAG, "updatePrefs() AlarmTime = " + mAlarmTime);

            prefStr = SP.getString("AlarmThresh", "SET_FROM_XML");
            mAlarmThresh = (short) Integer.parseInt(prefStr);
            Log.v(TAG, "updatePrefs() AlarmThresh = " + mAlarmThresh);

            prefStr = SP.getString("AlarmRatioThresh", "SET_FROM_XML");
            mAlarmRatioThresh = (short) Integer.parseInt(prefStr);
            Log.v(TAG, "updatePrefs() AlarmRatioThresh = " + mAlarmRatioThresh);

            mFallActive = SP.getBoolean("FallActive", false);
            Log.v(TAG, "updatePrefs() FallActive = " + mFallActive);

            prefStr = SP.getString("FallThreshMin", "SET_FROM_XML");
            mFallThreshMin = (short) Integer.parseInt(prefStr);
            Log.v(TAG, "updatePrefs() FallThreshMin = " + mFallThreshMin);

            prefStr = SP.getString("FallThreshMax", "SET_FROM_XML");
            mFallThreshMax = (short) Integer.parseInt(prefStr);
            Log.v(TAG, "updatePrefs() FallThreshMax = " + mFallThreshMax);

            prefStr = SP.getString("FallWindow", "SET_FROM_XML");
            mFallWindow = (short) Integer.parseInt(prefStr);
            Log.v(TAG, "updatePrefs() FallWindow = " + mFallWindow);

        } catch (Exception ex) {
            Log.v(TAG, "updatePrefs() - Problem parsing preferences!");
            mUtil.writeToSysLogFile("SdDataSourceNetworkPassive.updatePrefs() - ERROR "+ex.toString());
            Toast toast = Toast.makeText(mContext, "Problem Parsing Preferences - Something won't work - Please go back to Settings and correct it!", Toast.LENGTH_SHORT);
            toast.show();
        }
    }


    // Force the data stored in this datasource to update in line with the JSON string encoded data provided.
    // Used by webServer to update the NetworkPassiveDatasource
    public void updateFromJSON(String jsonStr) {
        Log.v(TAG,"updateFromJSON - "+jsonStr);

        try {
            JSONObject mainObject = new JSONObject(jsonStr);
            //JSONObject dataObject = mainObject.getJSONObject("dataObj");
            JSONObject dataObject = mainObject;
            String dataTypeStr = dataObject.getString("dataType");
            Log.v(TAG,"updateFromJSON - dataType="+dataTypeStr);
            if (dataTypeStr.equals("raw")) {
                Log.v(TAG,"updateFromJSON - processing raw data");
                JSONArray accelVals = dataObject.getJSONArray("data");
                Log.v(TAG, "Received " + accelVals.length() + " acceleration values");
                int i;
                for (i = 0; i < accelVals.length(); i++) {
                    mAccData[i] = accelVals.getInt(i);
                }
                mNSamp = accelVals.length();
                mWatchAppRunningCheck = true;
                doAnalysis();
            } else if (dataTypeStr.equals("settings")){
                Log.v(TAG,"updateFromJSON - processing settings");
                mSamplePeriod = (short)dataObject.getInt("analysisPeriod");
                mSampleFreq = (short)dataObject.getInt("sampleFreq");
                mSdData.batteryPc = (short)dataObject.getInt("battery");
                Log.v(TAG,"updateFromJSON - mSamplePeriod="+mSamplePeriod+" mSampleFreq="+mSampleFreq);
                mSdData.haveSettings = true;
                mSdData.mSampleFreq = mSampleFreq;
                mWatchAppRunningCheck = true;
            } else {
                Log.v(TAG,"updateFromJSON - unrecognised dataType "+dataTypeStr);
            }
        } catch (Exception e) {
            Log.v(TAG,"updateFromJSON - Error Parsing JSON String - "+e.toString());
            e.printStackTrace();
        }
    }

    /**
     * Calculate the magnitude of entry i in the fft array fft
     * @param fft
     * @param i
     * @return magnitude ( Re*Re + Im*Im )
     */
    private double getMagnitude(double[] fft, int i) {
        double mag;
        mag = (fft[2*i]*fft[2*i] + fft[2*i + 1] * fft[2*i +1]);
        return mag;
    }

    /**
     * doAnalysis() - analyse the data if the accelerometer data array mAccData
     * and populate the output data structure mSdData
     */
    private void doAnalysis() {
        // ******* POPULATE WITH DUMMY DATA *****
        //makeTestData();
        // **************************************
        // FIXME - Use specified sampleFreq, not this hard coded one
        mSampleFreq = 25;
        double freqRes = 1.0*mSampleFreq/mNSamp;
        Log.v(TAG,"doAnalysis(): mSampleFreq="+mSampleFreq+" mNSamp="+mNSamp+": freqRes="+freqRes);
        // Set the frequency bounds for the analysis in fft output bin numbers.
        int nMin = (int)(mAlarmFreqMin/freqRes);
        int nMax = (int)(mAlarmFreqMax /freqRes);
        Log.v(TAG,"doAnalysis(): mAlarmFreqMin="+mAlarmFreqMin+", nMin="+nMin
                +", mAlarmFreqMax="+mAlarmFreqMax+", nMax="+nMax);
        // Calculate the bin number of the cutoff frequency
        int nFreqCutoff = (int)(mFreqCutoff /freqRes);
        Log.v(TAG,"mFreqCutoff = "+mFreqCutoff+", nFreqCutoff="+nFreqCutoff);

        DoubleFFT_1D fftDo = new DoubleFFT_1D(mNSamp);
        double[] fft = new double[mNSamp * 2];
        System.arraycopy(mAccData, 0, fft, 0, mNSamp);
        fftDo.realForward(fft);

        // Calculate the whole spectrum power (well a value equivalent to it that avoids square root calculations
        // and zero any readings that are above the frequency cutoff.
        double specPower = 0;
        for (int i = 1; i < mNSamp / 2; i++) {
            if (i <= nFreqCutoff) {
                specPower = specPower + getMagnitude(fft,i);
            } else {
                fft[2*i] = 0.;
                fft[2*i+1] = 0.;
            }
        }
        specPower = specPower/mNSamp/2;

        // Calculate the Region of Interest power and power ratio.
        double roiPower = 0;
        for (int i=nMin;i<nMax;i++) {
            roiPower = roiPower + getMagnitude(fft,i);
        }
        roiPower = roiPower/(nMax - nMin);
        double roiRatio = 10 * roiPower / specPower;

        // Calculate the simplified spectrum - power in 1Hz bins.
        double[] simpleSpec = new double[SIMPLE_SPEC_FMAX+1];
        for (int ifreq=0;ifreq<SIMPLE_SPEC_FMAX;ifreq++) {
            int binMin = (int)(1 + ifreq/freqRes);    // add 1 to loose dc component
            int binMax = (int)(1 + (ifreq+1)/freqRes);
            simpleSpec[ifreq]=0;
            for (int i=binMin;i<binMax;i++) {
                simpleSpec[ifreq] = simpleSpec[ifreq] + getMagnitude(fft,i);
            }
            simpleSpec[ifreq] = simpleSpec[ifreq] / (binMax-binMin);
        }

        // Populate the mSdData structure to communicate with the main SdServer service.
        mDataStatusTime.setToNow();
        mSdData.specPower = (long)specPower;
        mSdData.roiPower = (long)roiPower;
        mSdData.dataTime.setToNow();
        mSdData.maxVal = 0;   // not used
        mSdData.maxFreq = 0;  // not used
        mSdData.haveData = true;
        mSdData.alarmThresh = mAlarmThresh;
        mSdData.alarmRatioThresh = mAlarmRatioThresh;
        //mSdData.batteryPc = 50;  // FIXME we should get the watch to send us battery status.
        // FIXME - I haven't worked out why dividing by 1000 seems necessary to get the graph on scale - we don't seem to do that with the Pebble.
        for(int i=0;i<SIMPLE_SPEC_FMAX;i++) {
            mSdData.simpleSpec[i] = (int)simpleSpec[i]/1000;
        }
        Log.v(TAG, "simpleSpec = " + Arrays.toString(mSdData.simpleSpec));
        // Because we have received data, set flag to show watch app running.
        mWatchAppRunningCheck = true;
        mSdDataReceiver.onSdDataReceived(mSdData);  // and tell SdServer we have received data.
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
        Log.v(TAG,"getStatus() - tdiff="+tdiff+", mDataUpatePeriod="+mDataUpdatePeriod+", mAppRestartTimeout="+mAppRestartTimeout);

        mSdData.pebbleConnected = true;  // We can't check connection for passive network connection, so set it to true to avoid errors.
        // And is the watch app running?
        // set mWatchAppRunningCheck has been false for more than 10 seconds
        // the app is not talking to us
        // mWatchAppRunningCheck is set to true in the receiveData handler.
        if (!mWatchAppRunningCheck &&
                (tdiff > (mDataUpdatePeriod + mAppRestartTimeout) * 1000)) {
            Log.v(TAG, "getStatus() - tdiff = " + tdiff);
            mSdData.pebbleAppRunning = false;
            // Only make audible warning beep if we have not received data for more than mFaultTimerPeriod seconds.
            if (tdiff > (mDataUpdatePeriod + mFaultTimerPeriod) * 1000) {
                Log.v(TAG, "getStatus() - Watch App Not Running");
                mUtil.writeToSysLogFile("SdDataSourceNetworkPassive.getStatus() - Watch App not Running");
                //mDataStatusTime.setToNow();
                mSdData.roiPower = -1;
                mSdData.specPower = -1;
                mSdDataReceiver.onSdDataFault(mSdData);
            } else {
                Log.v(TAG, "getStatus() - Waiting for mFaultTimerPeriod before issuing audible warning...");
            }
        } else {
            mSdData.pebbleAppRunning = true;
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
     * alarmCheck - determines alarm state based on seizure detector data SdData.   Called every second.
     */
    private void alarmCheck() {
        boolean inAlarm;
        Time tnow = new Time(Time.getCurrentTimezone());
        long tdiff;
        tnow.setToNow();

        // get time since the last data was received from the watch.
        tdiff = (tnow.toMillis(false) - mDataStatusTime.toMillis(false));
        Log.v(TAG, "alarmCheck() - tdiff=" + tdiff + ", mDataUpatePeriod=" + mDataUpdatePeriod + ", mAppRestartTimeout=" + mAppRestartTimeout
                + ", combined = " + (mDataUpdatePeriod + mAppRestartTimeout) * 1000);
        if (!mWatchAppRunningCheck &&
                (tdiff > (mDataUpdatePeriod + mAppRestartTimeout) * 1000)) {
            Log.v(TAG, "alarmCheck() - watch app not running so not doing anything");
            mAlarmCount = 0;
        } else {
            Log.v(TAG, "alarmCheck()");
            if ((mSdData.roiPower > mAlarmThresh) && (10 * (mSdData.roiPower / mSdData.specPower) > mAlarmRatioThresh)) {
                inAlarm = true;
            } else {
                inAlarm = false;
            }

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
            Log.v(TAG, "inAlarm=" + inAlarm + ", alarmState = " + mSdData.alarmState + " alarmCount=" + mAlarmCount + " mAlarmTime=" + mAlarmTime);
        }
    }

    private void makeTestData() {
        int sampleFreq = 25; // Hz
        int samplePeriod = 5; // sec
        int accDataPos = 0;

        double signalFreq = 5; // Hz
        double signalAmp = 500; // mG

        for (int i = 0; i < samplePeriod * sampleFreq; i++) {
            double t = 1.0*i / sampleFreq;
            double r = 2.0*Math.PI*t*signalFreq;
            mAccData[accDataPos] = (signalAmp*(Math.sin(r)));
            Log.v(TAG, "i=" + i + ", t="+t+", r="+r+", a="+ mAccData[accDataPos]);
            accDataPos++;
        }
    }

    public class SdDataBroadcastReceiver extends BroadcastReceiver {
        //private String TAG = "SdDataBroadcastReceiver";

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.v(TAG,"SdDataBroadcastReceiver.onReceive()");
            String jsonStr = intent.getStringExtra("data");
            Log.v(TAG,"SdDataBroadcastReceiver.onReceive() - data="+jsonStr);
            updateFromJSON(jsonStr);
        }
    }

}





