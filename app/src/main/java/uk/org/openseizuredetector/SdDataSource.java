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

import static java.lang.Math.sqrt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import androidx.preference.PreferenceManager;
import android.text.format.Time;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jtransforms.fft.DoubleFFT_1D;

import java.sql.Date;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterators;
import java.util.StringJoiner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

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
    protected long mDataStatusTime;
    protected boolean mWatchAppRunningCheck = false;
    private int mAppRestartTimeout = 10;  // Timeout before re-starting watch app (sec) if we have not received
    // data after mDataUpdatePeriod
    private int mFaultTimerPeriod = 30;  // Fault Timer Period in sec
    private int mSettingsPeriod = 60;  // period between requesting settings in seconds.
    public SdData mSdData;
    public String mName = "undefined";
    protected OsdUtil mUtil;
    protected SdDataReceiver mSdDataReceiver;
    private String TAG = this.getClass().getName();
    protected List<Double> initialBuffer = new ArrayList<>(0);

    protected boolean mIsRunning = false;

    private short mDebug;
    private short mFreqCutoff = 12;
    private short mDisplaySpectrum;
    private short mDataUpdatePeriod;
    private short mMutePeriod;
    private short mManAlarmPeriod;
    private short mPebbleSdMode;
    private int mDefaultSampleCount;
    public short mSampleFreq;
    private short mAlarmFreqMin;
    private short mAlarmFreqMax;
    private short mSamplePeriod;
    private short mWarnTime;
    private short mAlarmTime;
    private short mAlarmThresh;
    private short mAlarmRatioThresh;
    protected double accelerationCombined = -1d;
    protected double gravityScaleFactor  = 1d;
    protected double miliGravityScaleFactor;
    private boolean mFallActive;
    private short mFallThreshMin;
    private short mFallThreshMax;
    private short mFallWindow;
    private int mMute;  // !=0 means muted by keypress on watch.
    private SdAlgNn mSdAlgNn;
    public SdAlgHr mSdAlgHr;
    double[] fft = null;
    double[] simpleSpec;
    private SharedPreferences sharedPreferences;

    // Values for SD_MODE
    private int SIMPLE_SPEC_FMAX = 10;

    private int ACCEL_SCALE_FACTOR = 1000;  // Amount by which to reduce analysis results to scale to be comparable to analysis on Pebble.


    private int mAlarmCount;
    protected String mBleDeviceAddr;
    protected String mBleDeviceName;
    double mConversionSampleFactor = 1d;
    double mSampleTimeUs;
    int mCurrentMaxSampleCount = -1;
    private SdData mSdDataSettings;


    private PowerManager.WakeLock mWakeLock;

    private SdServer runningSdServer;
    private int mChargingState = 0;
    private boolean mIsCharging = false;
    private int chargePlug = 0;
    private boolean usbCharge = false;
    private boolean acCharge = false;
    private float batteryPct = -1f;
    private IntentFilter batteryStatusIntentFilter = null;
    private Intent batteryStatusIntent;
    private JSONArray hrHistoricVals;
    private JSONArray accelVals;
    private JSONArray accelVals3D;
    private JSONObject mainObject;
    private JSONObject dataObject;
    private String dataTypeStr;
    private double mLastHrValue;
    private long mHRStatusTime;
    private double mHRFrozenPeriod = 60; // seconds
    private boolean mHRFrozenAlarm;
    private boolean mFidgetDetectorEnabled;
    private double mFidgetPeriod;
    private double mFidgetThreshold;
    private long mLastFidget ;
    private double accelerationCombinedCubeRoot;
    private List<JSONObject> jsonObjectList;
    private JSONArray jsonArrayHeartRate;
    private String jsonStr;


    public SdDataSource(Context context, Handler handler, SdDataReceiver sdDataReceiver) {
        Log.v(TAG, "SdDataSource() Constructor");
        mHandler = handler;
        mSdDataReceiver = sdDataReceiver;
        mUtil = new OsdUtil(useSdServerBinding(), mHandler);
        if(Objects.isNull((mSdData)))
            mSdData = getSdData();
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(useSdServerBinding());
        if (sharedPreferences.contains(Constants.GLOBAL_CONSTANTS.destroyReasonOf+TAG))
        {
            Log.d(TAG, "(re)Constructed after being closed with reason: \n+" +
                    sharedPreferences.getString(Constants.GLOBAL_CONSTANTS.destroyReasonOf + TAG, "") );
        }
    }


    SdServer useSdServerBinding(){
        return (((SdServer)mSdDataReceiver));
    }
    protected SdData pullSdData() {
        if (Objects.isNull(useSdServerBinding().mSdData))
            useSdServerBinding().mSdData = new SdData();
        return useSdServerBinding().mSdData; }
    /**
     * Returns the SdData object stored by this class.
     *
     * @return
     */
    public SdData getSdData() {
        return useSdServerBinding().mSdData;
    }

    /**
     * Calculate the static values of requested mSdData.mSampleFreq, mSampleTimeUs and factorDownSampling  through
     * mSdData.analysisPeriod and mSdData.mDefaultSampleCount .
     */
    protected void calculateStaticTimings(){
        double averageFromInit = 0d;
        // default sampleCount : mSdData.mDefaultSampleCount
        // default sampleTime  : mSdData.analysisPeriod
        // sampleFrequency = sampleCount / sampleTime:
        if (mSdData.dT > 0d && mSdData.mNsamp >0)
        {
            mSdData.mSampleFreq = (long)( (double) mSdData.mNsamp / mSdData.dT);
        }
        else{
            if (mSdData.mNsamp == 0)
                mSdData.mSampleFreq = (long) mSdData.mDefaultSampleCount / mSdDataSettings.analysisPeriod;
            else
                mSdData.mSampleFreq = (long) mSdData.mNsamp / mSdData.analysisPeriod;
        }
        // now we have mSampleFreq in number samples / second (Hz) as default.
        // to calculate sampleTimeUs: (1 / mSampleFreq) * 1000 [1s == 1000000us]
        mSampleTimeUs = OsdUtil.convertTimeUnit(1d / (double) mSdData.mSampleFreq,TimeUnit.SECONDS,TimeUnit.MICROSECONDS);
        accelerationCombinedCubeRoot = Math.pow(mSdData.rawData3D[0]*mSdData.rawData3D[0]+mSdData.rawData3D[1]*mSdData.rawData3D[1]+mSdData.rawData3D[2]*mSdData.rawData3D[2],1/3);
        accelerationCombined = sqrt(mSdData.rawData3D[0] * mSdData.rawData3D[0] + mSdData.rawData3D[1] * mSdData.rawData3D[1] + mSdData.rawData3D[2] * mSdData.rawData3D[2]);

        // num samples == fixed final 250 (NSAMP)
        // time seconds in default == 10 (SIMPLE_SPEC_FMAX)
        // count samples / time = 25 samples / second == 25 Hz max.
        // 1 Hz == 1 /s
        // 25 Hz == 0,04s
        // 1s == 1.000.000 us (sample interval)
        // sampleTime = 40.000 uS == (SampleTime (s) * 1000)
        if (getSdData().rawData.length>0 && getSdData().dT >0d){
            double mSDDataSampleTimeUs = OsdUtil.convertTimeUnit(1d/(double) (Constants.SD_SERVICE_CONSTANTS.defaultSampleCount / Constants.SD_SERVICE_CONSTANTS.defaultSampleTime),TimeUnit.SECONDS,TimeUnit.MICROSECONDS);
            mConversionSampleFactor = mSampleTimeUs / mSDDataSampleTimeUs;

        }
        else
            mConversionSampleFactor = 1d;
        if (accelerationCombined != -1d) {
            gravityScaleFactor = accelerationCombined / SensorManager.GRAVITY_EARTH;

        } else {
            gravityScaleFactor = 1d;
        }

        miliGravityScaleFactor = gravityScaleFactor * 1e3;

    }

    /**
     * Start the datasource updating - initialises from sharedpreferences first to
     * make sure any changes to preferences are taken into account.
     */
    public void start() {
        Log.v(TAG, "start()");
        mUtil.writeToSysLogFile("SdDataSource.start()");
        if (!Objects.equals(mSdData,pullSdData())) mSdData = pullSdData();
        updatePrefs();
        mSdDataSettings = mSdData;
        mSdAlgHr = new SdAlgHr(useSdServerBinding());

        if (mSdData.mCnnAlarmActive) {
            mSdAlgNn = new SdAlgNn(useSdServerBinding());
        } else {
            mSdData.mPseizure = 0;
        }



        // Start timer to check status of watch regularly.
        mDataStatusTime = Calendar.getInstance().getTimeInMillis();
        // use a timer to check the status of the pebble app on the same frequency
        // as we get app data.
        if (mStatusTimer == null) {
            Log.v(TAG, "start(): starting status timer");
            mUtil.writeToSysLogFile("SdDataSource.start() - starting status timer");
            mStatusTimer = new Timer();
            /*mStatusTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    getStatus();
                }
            }, 0, mDataUpdatePeriod * 1000);*/
        } else {
            Log.v(TAG, "start(): status timer already running.");
            mUtil.writeToSysLogFile("SdDataSource.start() - status timer already running??");
        }

        // Initialise time we last received a change in HR value.
        mHRStatusTime = Calendar.getInstance().getTimeInMillis();
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
            }, 0, TimeUnit.SECONDS.toMillis(mSettingsPeriod));  // ask for settings less frequently than we get data
        } else {
            Log.v(TAG, "start(): settings timer already running.");
            mUtil.writeToSysLogFile("SDDataSource.start() - settings timer already running??");
        }

        if (!useSdServerBinding().mSdDataSourceName.equals("phone"))
            mSdData.mHRAlarmActive = mSdAlgHr.mSimpleHrAlarmActive||mSdAlgHr.mAverageHrAlarmActive||mSdAlgHr.mAdaptiveHrAlarmActive;
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
            Log.e(TAG, "Error in stop() - ${e.toString()}" ,e);
            mUtil.writeToSysLogFile("SDDataSource.stop() - error -${ e.toString()}" );
        }

        if (mSdData.mCnnAlarmActive) {
            mSdAlgNn.close();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sharedPreferences.edit().putString(Constants.GLOBAL_CONSTANTS.destroyReasonOf+TAG,
                            Arrays.toString(Thread.currentThread().getStackTrace()))
                    .apply();
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
            useSdServerBinding().startActivity(i);
        } catch (Exception ex) {
            Log.i(TAG, "installWatchApp(): exception starting install watch app activity " + ex.toString(),ex);
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
        accelVals = null;
        accelVals3D = null;
        Log.v(TAG, "updateFromJSON - " + jsonStr);

        try {
            mainObject = new JSONObject(jsonStr);
            //JSONObject dataObject = mainObject.getJSONObject("dataObj");
            dataObject = mainObject;
            if (dataObject.has("watchPartNo")) watchPartNo = dataObject.getString("watchPartNo");
            if (dataObject.has("watchFwVersion")) watchFwVersion = dataObject.getString("watchFwVersion");
            if (dataObject.has("sdVersion")) sdVersion = dataObject.getString("sdVersion");
            if (dataObject.has("sdName")) sdName = dataObject.getString("sdName");
            if (!getSdData().dataSourceName.equals("AndroidWear")) mSdData.watchConnected = true;  // we must be connected to receive data.
            if (dataObject.has(Constants.GLOBAL_CONSTANTS.JSON_TYPE_BATTERY)) mSdData.batteryPc = (short) dataObject.getInt(Constants.GLOBAL_CONSTANTS.JSON_TYPE_BATTERY);
            if (dataObject.has(Constants.GLOBAL_CONSTANTS.heartRateList)) {
                Log.v(TAG, "updateFromJSON - processing raw data");
                try {
                    hrHistoricVals =  dataObject.getJSONArray(Constants.GLOBAL_CONSTANTS.heartRateList);
                    for (int i = 0; i < hrHistoricVals.length(); i++) {
                        mSdData.mHR = hrHistoricVals.getDouble(i);
                        hrCheck();
                    }
                } catch (JSONException e) {
                    // if we get 'null' HR (For example if the heart rate is not working)
                    mSdData.mHR = -1;
                }
            }else if (dataObject.has("hr")) {
                try {
                    mSdData.mHR = dataObject.getDouble(Constants.GLOBAL_CONSTANTS.DATA_VALUE_HR);
                } catch (JSONException e) {
                    // if we get 'null' HR (For example if the heart rate is not working)
                    mSdData.mHR = -1;
                }
            }
            if (dataObject.has("O2satIncluded"))
                try {
                    mSdData.mO2Sat = dataObject.getDouble("O2sat");
                } catch (JSONException e) {
                    // if we get 'null' O2 Saturation (For example if the oxygen sensor is not working)
                    mSdData.mO2Sat = -1;
                }
            if (dataObject.has(Constants.GLOBAL_CONSTANTS.DATA_TYPE)) {
                dataTypeStr = dataObject.getString(Constants.GLOBAL_CONSTANTS.DATA_TYPE);
                Log.v(TAG, "updateFromJSON - dataType=" + dataTypeStr);
                if (dataTypeStr.equals(Constants.GLOBAL_CONSTANTS.DATA_TYPE_RAW)) {
                    try {
                        mMute = dataObject.getInt("Mute");
                    } catch (JSONException e) {
                        // if we get 'null' HR (For example if the heart rate is not working)
                        mMute = 0;
                    }
                    //TODO: assert if this block is deprecated,duplicate or ok.
                    if (dataObject.has(Constants.GLOBAL_CONSTANTS.BATTERY_PC)){
                        try {
                            mSdData.batteryPc = (short) dataObject.getInt(Constants.GLOBAL_CONSTANTS.BATTERY_PC);
                        } catch (Exception e) {
                            Log.e(TAG, "Error in getting battery percentage", e);
                        }
                    }
                    if (dataObject.has(Constants.GLOBAL_CONSTANTS.JSON_TYPE_DATA)) accelVals = dataObject.getJSONArray(Constants.GLOBAL_CONSTANTS.JSON_TYPE_DATA); //upstream version has data.
                    try {
                        mSdData.dT = dataObject.getDouble("dT");
                    } catch (JSONException e) {
                        Log.e(TAG, "updateFromJSON(): import dT: ", e);
                        mSdData.dT = dataObject.getInt("analysisPeriod");
                    }
                    //TODO change rawData in WEAR sddata class to data
                    Log.v(TAG, "Received " + accelVals.length() + " acceleration values, rawData Length is " + mSdData.rawData.length);
                    if (accelVals.length() > mSdData.rawData.length) {
                        mUtil.writeToSysLogFile("ERROR:  Received " + accelVals.length() + " acceleration values, but rawData storage length is "
                                + mSdData.rawData.length);
                    }
                    int i;
                    for (i = 0; i < accelVals.length(); i++) {
                        mSdData.rawData[i] = accelVals.getDouble(i);
                        if (initialBuffer.size() <= mSdData.mDefaultSampleCount)
                            initialBuffer.add(accelVals.getDouble(i));
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
                            if (i == 3) {
                                calculateStaticTimings();
                            }
                        }
                    } catch (JSONException e) {
                        // If we get an error, just set rawData3D to zero
                        Log.i(TAG, "updateFromJSON - error parsing 3D data - setting it to zero", e);
                        for (i = 0; i < mSdData.rawData3D.length; i++) {
                            mSdData.rawData3D[i] = 0.;
                        }
                    }

                    try {
                        mSdData.mSampleFreq = dataObject.getInt("sampleFreq");
                    } catch (JSONException e) {
                        mSdData.mSampleFreq = mSdData.rawData.length / (int) mSdData.dT;
                    }
                    if (initialBuffer.size() == mSdDataSettings.mDefaultSampleCount)
                        calculateStaticTimings();
                    mWatchAppRunningCheck = true;
                    boolean incorrectmNSamp = mSdData.mNsamp < 1.0;
                    boolean incorrectmSampleFreq = mSdData.mSampleFreq < 1.0;
                    boolean incorrectGravityScaleFactor = gravityScaleFactor == 0d;
                    if (incorrectmSampleFreq || incorrectmNSamp || incorrectGravityScaleFactor) {
                        if (incorrectmNSamp) mSdData.mNsamp = mSdData.rawData.length;
                        if (incorrectmSampleFreq)
                            mSdData.mSampleFreq = mSdData.mNsamp / mSdData.analysisPeriod;
                        calculateStaticTimings();
                    }

                    doAnalysis();

                    if (!mSdData.haveSettings) {
                        retVal = "sendSettings";
                    } else {
                        retVal = "OK";
                    }
                } else if (dataTypeStr.equals("settings")) {
                    Log.v(TAG, "updateFromJSON - processing settings");
                    mSdData.analysisPeriod = (short) dataObject.getInt("analysisPeriod");
                    mSdData.mSampleFreq = (short) dataObject.getInt("sampleFreq");
                    Log.v(TAG, "updateFromJSON - mSamplePeriod=" + mSamplePeriod + " mSampleFreq=" + mSampleFreq);
                    mUtil.writeToSysLogFile("SDDataSource.updateFromJSON - Settings Received");
                    mUtil.writeToSysLogFile("    * mSamplePeriod=" + mSamplePeriod + " mSampleFreq=" + mSampleFreq);
                    mUtil.writeToSysLogFile("    * batteryPc = " + mSdData.batteryPc);

                    try {
                        mSdData.watchPartNo = dataObject.getString("watchPartNo");
                        mSdData.watchFwVersion = dataObject.getString("watchFwVersion");
                        mSdData.watchSdVersion = dataObject.getString("sdVersion");
                        mSdData.watchSdName = dataObject.getString("sdName");
                        mUtil.writeToSysLogFile("    * sdName = " + mSdData.watchSdName + " version " + mSdData.watchSdVersion);
                        mUtil.writeToSysLogFile("    * watchPartNo = " + mSdData.watchPartNo + " fwVersion " + mSdData.watchFwVersion);

                    } catch (Exception e) {
                        Log.e(TAG, "updateFromJSON - Error Parsing V3.2 JSON String - " + e.toString(), e);
                        mUtil.writeToSysLogFile("updateFromJSON - Error Parsing V3.2 JSON String - " + jsonStr + " - " + e.toString());
                        mUtil.writeToSysLogFile("          This is probably because of an out of date watch app - please upgrade!");
                        e.printStackTrace();
                    }
                    mSdData.haveSettings = true;
                    mWatchAppRunningCheck = true;
                    retVal = "OK";
                } else if (dataTypeStr.equals("watchConnect")) {
                    retVal = dataTypeStr;
                } else {
                    Log.e(TAG, "updateFromJSON - unrecognised dataType " + dataTypeStr);
                    retVal = "ERROR";
                }
            } else{
                Log.e(TAG, "updateFromJSON - unrecognised string received: " + jsonStr);
            }
        } catch (Exception e) {
            Log.e(TAG, "updateFromJSON - Error Parsing JSON String - " + jsonStr + " - " , e);
            mUtil.writeToSysLogFile("updateFromJSON - Error Parsing JSON String - " + jsonStr + " - " + e.toString());
            if (Objects.nonNull(e.getCause()))
                if (Objects.nonNull(e.getCause().getStackTrace()))
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
            mSampleFreq = Constants.SD_SERVICE_CONSTANTS.defaultSampleRate;
            double freqRes = 1.0 * mSdData.mSampleFreq / mSdData.mNsamp;
            Log.v(TAG, "doAnalysis(): mSampleFreq=" + mSdData.mSampleFreq + " mNSamp=" + mSdData.mNsamp + ": freqRes=" + freqRes);
            Log.v(TAG, "doAnalysis(): rawData=" + Arrays.toString(mSdData.rawData));
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

            if (gravityScaleFactor == 0) calculateStaticTimings();
            // Populate the mSdData structure to communicate with the main SdServer service.
            mDataStatusTime = Calendar.getInstance().getTimeInMillis();
            mSdData.specPower = (long) (specPower /OsdUtil.convertMetresPerSecondSquaredToMilliG(1));
            mSdData.roiPower = (long) (roiPower /OsdUtil.convertMetresPerSecondSquaredToMilliG(1));
            //mSdData.dataTime = new Date(mDataStatusTime); invalid, need to change to Date
            mSdData.maxVal = 0;   // not used
            mSdData.maxFreq = 0;  // not used
            mSdData.haveData = true;
            useSdServerBinding().mSdData.haveData = true;
            useSdServerBinding().mSdData.haveSettings = true;
            mSdData.alarmThresh = mAlarmThresh;
            mSdData.alarmRatioThresh = mAlarmRatioThresh;
            mSdData.alarmFreqMin = mAlarmFreqMin;
            mSdData.alarmFreqMax = mAlarmFreqMax;
            // note mSdData.batteryPc is set from settings data in updateFromJSON()
            // FIXME - I haven't worked out why dividing by 1000 seems necessary to get the graph on scale - we don't seem to do that with the Pebble.
            // DoubleFFT_1D has from 1G values 1mG
            for (int i = 0; i < SIMPLE_SPEC_FMAX; i++) {
                mSdData.simpleSpec[i] = (int) (simpleSpec[i] /OsdUtil.convertMetresPerSecondSquaredToMilliG(1));
            }
            Log.v(TAG, "simpleSpec = " + Arrays.toString(mSdData.simpleSpec));

            // Because we have received data, set flag to show watch app running.
            mWatchAppRunningCheck = true;
        } catch (Exception e) {
            Log.e(TAG, "doAnalysis - Exception during Analysis", e);
            if (Objects.nonNull(e.getCause())) {
                if (Objects.nonNull(Objects.requireNonNull(e.getCause()).getStackTrace())) {
                    mUtil.writeToSysLogFile("doAnalysis - Exception during analysis - " + e.toString());
                    mUtil.writeToSysLogFile("doAnalysis: Exception at Line Number: " + e.getCause().getStackTrace()[0].getLineNumber() + ", " + e.getCause().getStackTrace()[0].toString());
                    mUtil.writeToSysLogFile("doAnalysis: mSdData.mNsamp=" + mSdData.mNsamp);
                    mUtil.writeToSysLogFile("doAnalysis: alarmFreqMin=" + mAlarmFreqMin + " nMin=" + nMin);
                    mUtil.writeToSysLogFile("doAnalysis: alarmFreqMax=" + mAlarmFreqMax + " nMax=" + nMax);
                    mUtil.writeToSysLogFile("doAnalysis: nFreqCutoff.=" + nFreqCutoff);
                    mUtil.writeToSysLogFile("doAnalysis: fft.length=" + fft.length);
                    mWatchAppRunningCheck = false;
                }
            }
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

        signalUpdateUI();
        if (Objects.nonNull(useSdServerBinding().mLm)){
            useSdServerBinding().mLm.writeToRemoteServer();
        }

    }

    public void signalUpdateUI() {
        //and signal update UI
        if (Objects.nonNull(useSdServerBinding())){
            if (Objects.nonNull(useSdServerBinding().uiLiveData)){
                if (useSdServerBinding().uiLiveData.hasActiveObservers()) {
                    useSdServerBinding().uiLiveData.signalChangedData();
                }
            }
        }

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
                mSdData.alarmPhrase = "roiPower passed threshold or roiPower/specPower passed AlarmTreshold";
                inAlarm = true;
            }
        }

        if (mSdData.mCnnAlarmActive) {
            if (mSdData.mPseizure > 0.5) {
                mSdData.alarmPhrase = "Probability of Seizure passed threshold of 50%";
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
                mSdData.alarmPhrase = "";
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
        ArrayList<Boolean> checkResults;
        checkResults = mSdAlgHr.checkHr(mSdData.mHR);

        // Populate mSdData so that the heart rate data is logged and is accessible to user interface components.
        mSdData.mHistoricHrBuf = mSdAlgHr.getmHistoricHrBuff();
        mSdData.mHRAvg = mSdAlgHr.getSimpleHrAverage();
        mSdData.mAdaptiveHrAverage = mSdAlgHr.getAdaptiveHrAverage();
        mSdData.mAverageHrAverage = mSdAlgHr.getAverageHrAverage();
        mSdData.mAdaptiveHrBuf = mSdAlgHr.getAdaptiveHrBuff();
        mSdData.mAverageHrBuf = mSdAlgHr.getAverageHrBuff();

        /* Check for heart rate fault condition */
        if (mSdData.mHRAlarmActive) {
            if (mSdData.mHR < 0) {
                if (mSdData.mHRNullAsAlarm) {
                    Log.i(TAG, "Heart Rate Null - Alarming");
                    mSdData.alarmPhrase = "Heart Rate Null - Alarming";
                    mSdData.mHRFaultStanding = false;
                    mSdData.mHRAlarmStanding = true;
                } else {
                    mSdData.alarmPhrase = "Heart Rate Fault (HR<0)";
                    Log.i(TAG, "Heart Rate Fault (HR<0)");
                    mSdData.mHRFaultStanding = true;
                    mSdData.mHRAlarmStanding = false;
                }
                mSdData.mAdaptiveHrAlarmStanding = false;
                mSdData.mAverageHrAlarmStanding = false;
            } else {
                mSdData.mHRFaultStanding = false;
                mSdData.mHRAlarmStanding = checkResults.get(0);
                mSdData.mAdaptiveHrAlarmStanding = checkResults.get(1);
                mSdData.mAverageHrAlarmStanding = checkResults.get(2);
                // Show an ALARM state if any of the HR alarms is standing.
                if (mSdData.mHRAlarmStanding | mSdData.mAdaptiveHrAlarmStanding | mSdData.mAverageHrAlarmStanding) {
                    mSdData.alarmState = 2;
                }
            }
        } else {
            mSdData.mHRFaultStanding = false;
            mSdData.mHRAlarmStanding = false;
            mSdData.mAdaptiveHrAlarmStanding = false;
            mSdData.mAverageHrAlarmStanding = false;

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
                    mSdData.alarmPhrase = "Oxygen Saturation Fault (O2Sat<0)";
                    mSdData.mO2SatFaultStanding = true;
                    mSdData.mO2SatAlarmStanding = false;
                }
            } else if  (mSdData.mO2Sat < mSdData.mO2SatThreshMin) {
                Log.i(TAG, "Oxygen Saturation Abnormal - " + mSdData.mO2Sat + " %");
                mSdData.alarmPhrase = "Oxygen Saturation Abnormal - " + mSdData.mO2Sat + " %";
                mSdData.mO2SatFaultStanding = false;
                mSdData.mO2SatAlarmStanding = true;
            } else {
                mSdData.mO2SatFaultStanding = false;
                mSdData.mO2SatAlarmStanding = false;
            }
        } else {
            mSdData.mO2SatFaultStanding = false;
            mSdData.mO2SatAlarmStanding = false;
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
     * Checks the status of the connection to the watch,
     * and sets class variables for use by other functions.
     */
    public void getStatus() {
        long tnow = Calendar.getInstance().getTimeInMillis();
        long tdiff;
        // get time since the last data was received from the Pebble watch.
        tdiff = tnow - mDataStatusTime;
        Log.v(TAG, "getStatus() - mWatchAppRunningCheck=" + mWatchAppRunningCheck + " tdiff=" + tdiff);
        Log.v(TAG, "getStatus() - tdiff=" + tdiff + ", mDataUpatePeriod=" + mDataUpdatePeriod + ", mAppRestartTimeout=" + mAppRestartTimeout);

        if (!((SdServer)mSdDataReceiver).mSdDataSourceName.equals("AndroidWear")) {
            mSdData.watchConnected = true;  // We can't check connection for passive network connection, so set it to true to avoid errors.
        } else {
            Log.d(TAG,"getStatus - setting watchConnected to false - datasourceName="+((SdServer)mSdDataReceiver).mSdDataSourceName);
            mSdData.watchConnected = false;
        }
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
                mLastFidget = tnow;   // Initialise last fidget time on startup.

                double accStd = calcRawDataStd(mSdData);
                if (accStd > mFidgetThreshold) {
                    mLastFidget = tnow;
                } else {
                    Log.d(TAG,"onStatus() - Fidget Detector - low movement - is watch being worn?");
                    tdiff = tnow- mLastFidget;
                    if (tdiff > OsdUtil.convertTimeUnit(mFidgetPeriod,TimeUnit.SECONDS,TimeUnit.MILLISECONDS)) {
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
            mDataStatusTime = Calendar.getInstance().getTimeInMillis();
        }

        if (!mSdData.haveSettings) {
            Log.v(TAG, "getStatus() - no settings received yet");
        }
    }

    /**
     * faultCheck - determines alarm state based on seizure detector data SdData.   Called every second.
     */
    private void faultCheck() {
        long tnow = Calendar.getInstance().getTimeInMillis();
        long tdiff;

        // get time since the last data was received from the watch.
        tdiff = (tnow - mDataStatusTime);
        //Log.v(TAG, "faultCheck() - tdiff=" + tdiff + ", mDataUpatePeriod=" + mDataUpdatePeriod + ", mAppRestartTimeout=" + mAppRestartTimeout
        //        + ", combined = " + (mDataUpdatePeriod + mAppRestartTimeout) * 1000);
        if (!mWatchAppRunningCheck &&
                (tdiff > (mDataUpdatePeriod + mAppRestartTimeout) * 1000)) {
            //Log.v(TAG, "faultCheck() - watch app not running so not doing anything");
            mAlarmCount = 0;
        }

        if (mSdData.mHRAlarmActive && mHRFrozenAlarm) {
            if (mSdData.mHR != mLastHrValue) {
                mLastHrValue = mSdData.mHR;
                mHRStatusTime = tnow;
                mSdData.mHRFrozenFaultStanding = false;
            } else {
                tdiff = (tnow - mHRStatusTime);
                if (tdiff > OsdUtil.convertTimeUnit(mHRFrozenPeriod,TimeUnit.SECONDS,TimeUnit.MILLISECONDS)) {
                    mSdData.mHRFrozenFaultStanding = true;
                } else {
                    mSdData.mHRFrozenFaultStanding = false;
                }
            }
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
     * Read a preference value, and return it as a double.
     * FIXME - this should be in osdUtil so other classes can use it.
     *
      * @param SP - Shared Preferences object
     * @param prefName - name of preference to read.
     * @param defVal - default value if it is not stored.
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
                .getDefaultSharedPreferences(useSdServerBinding());
        try {
            // Parse the AppRestartTimeout period setting.
            try {
                String appRestartTimeoutStr = SP.getString("AppRestartTimeout", "10");
                mAppRestartTimeout = Integer.parseInt(appRestartTimeoutStr);
                Log.v(TAG, "updatePrefs() - mAppRestartTimeout = " + mAppRestartTimeout);
                mUtil.writeToSysLogFile( "updatePrefs() - mAppRestartTimeout = " + mAppRestartTimeout);
            } catch (Exception ex) {
                Log.e(TAG, "updatePrefs() - Problem with AppRestartTimeout preference!",ex);
                mUtil.writeToSysLogFile( "updatePrefs() - Problem with AppRestartTimeout preference!");
                Toast toast = Toast.makeText(useSdServerBinding(), "Problem Parsing AppRestartTimeout Preference", Toast.LENGTH_SHORT);
                toast.show();
            }

            // Parse the FaultTimer period setting.
            try {
                String faultTimerPeriodStr = SP.getString("FaultTimerPeriod", "30");
                mFaultTimerPeriod = Integer.parseInt(faultTimerPeriodStr);
                Log.v(TAG, "updatePrefs() - mFaultTimerPeriod = " + mFaultTimerPeriod);
                mUtil.writeToSysLogFile( "updatePrefs() - mFaultTimerPeriod = " + mFaultTimerPeriod);
            } catch (Exception ex) {
                Log.e(TAG, "updatePrefs() - Problem with FaultTimerPeriod preference!",ex);
                mUtil.writeToSysLogFile( "updatePrefs() - Problem with FaultTimerPeriod preference!");
                Toast toast = Toast.makeText(useSdServerBinding(), "Problem Parsing FaultTimerPeriod Preference", Toast.LENGTH_SHORT);
                toast.show();
            }

            // Parse the Fidget Detector settings.
            try {
                mFidgetDetectorEnabled = SP.getBoolean("FidgetDetectorEnabled", false);
                mFidgetPeriod = readDoublePref(SP, "FidgetDetectorPeriod", "20"); // minutes
                Log.v(TAG, "updatePrefs() - mFidgetPeriod = " + mFidgetPeriod);
                mFidgetThreshold = readDoublePref(SP, "FidgetDetectorThreshold", "0.6 ");
                Log.d(TAG,"updatePrefs(): mFidgetThreshold="+mFidgetThreshold);

            } catch (Exception ex) {
                Log.v(TAG, "updatePrefs() - Problem with FidgetDetector preferences!");
                Toast toast = Toast.makeText(useSdServerBinding(), "Problem Parsing FidgetPeriod Preference", Toast.LENGTH_SHORT);
                toast.show();
            }

            // Start with loading SdServer version of SdData:
            if (Objects.nonNull(useSdServerBinding()))
                if (Objects.nonNull(useSdServerBinding().mSdData))
                    mSdData = useSdServerBinding().mSdData;

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
                mUtil.writeToSysLogFile("updatePrefs() Debug = " + mDebug);

                prefStr = SP.getString("PebbleDisplaySpectrum", "SET_FROM_XML");
                mDisplaySpectrum = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() DisplaySpectrum = " + mDisplaySpectrum);
                mUtil.writeToSysLogFile("updatePrefs() DisplaySpectrum = " + mDisplaySpectrum);

                prefStr = SP.getString("DefaultSampleCount", "250");
                mDefaultSampleCount = Integer.parseInt(prefStr);
                Log.v(TAG, "mDefaultSampleCount=" + mDefaultSampleCount);

                prefStr = SP.getString("PebbleUpdatePeriod", "SET_FROM_XML");
                mDataUpdatePeriod = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() DataUpdatePeriod = " + mDataUpdatePeriod);
                mUtil.writeToSysLogFile("updatePrefs() DataUpdatePeriod = " + mDataUpdatePeriod);

                prefStr = SP.getString("MutePeriod", "SET_FROM_XML");
                mMutePeriod = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() MutePeriod = " + mMutePeriod);
                mUtil.writeToSysLogFile("updatePrefs() MutePeriod = " + mMutePeriod);

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

                mHRFrozenAlarm = SP.getBoolean("HrFrozenAlarm", true);
                Log.v(TAG, "updatePrefs() - mHRFrozenAlarm = " + mHRFrozenAlarm);
                mUtil.writeToSysLogFile("updatePrefs() - mHRFrozenAlarm = " + mHRFrozenAlarm);

                prefStr = SP.getString("HRThreshMin", "SET_FROM_XML");
                mSdData.mHRThreshMin = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() HRThreshMin = " + mSdData.mHRThreshMin);
                mUtil.writeToSysLogFile( "updatePrefs() HRThreshMin = " + mSdData.mHRThreshMin);

                prefStr = SP.getString("HRThreshMax", "SET_FROM_XML");
                mSdData.mHRThreshMax = (short) Integer.parseInt(prefStr);
                Log.v(TAG, "updatePrefs() HRThreshMax = " + mSdData.mHRThreshMax);
                mUtil.writeToSysLogFile( "updatePrefs() HRThreshMax = " + mSdData.mHRThreshMax);

                mSdData.mAdaptiveHrAlarmActive = SP.getBoolean("HRAdaptiveAlarmActive", false);
                mSdData.mAdaptiveHrAlarmWindowSecs = readDoublePref(SP, "HRAdaptiveAlarmWindowSecs", "30");
                mSdData.mAdaptiveHrAlarmThresh = readDoublePref(SP, "HRAdaptiveAlarmThresh", "20");
                Log.d(TAG,"updatePrefs(): mAdaptiveHrAlarmActive="+mSdData.mAdaptiveHrAlarmActive);
                Log.d(TAG,"updatePrefs(): mAdaptiveHrWindowSecs="+mSdData.mAdaptiveHrAlarmWindowSecs);
                Log.d(TAG,"updatePrefs(): mAdaptiveHrAlarmThresh="+mSdData.mAdaptiveHrAlarmThresh);

                mSdData.mAverageHrAlarmActive = SP.getBoolean("HRAverageAlarmActive", false);
                mSdData.mAverageHrAlarmWindowSecs = readDoublePref(SP, "HRAverageAlarmWindowSecs", "120");
                mSdData.mAverageHrAlarmThreshMin = readDoublePref(SP, "HRAverageAlarmThreshMin", "40");
                mSdData.mAverageHrAlarmThreshMax = readDoublePref(SP, "HRAverageAlarmThreshMax", "120");
                Log.d(TAG,"updatePrefs(): mAverageHrAlarmActive="+mSdData.mAverageHrAlarmActive);
                Log.d(TAG,"updatePrefs(): mAverageHrAlarmWindowSecs="+mSdData.mAverageHrAlarmWindowSecs);
                Log.d(TAG,"updatePrefs(): mAverageHrAlarmThreshMin="+mSdData.mAverageHrAlarmThreshMin);
                Log.d(TAG,"updatePrefs(): mAverageHrAlarmThreshMax="+mSdData.mAverageHrAlarmThreshMax);


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

                if ((mSdData.mO2SatNullAsAlarm||mSdData.mO2SatAlarmActive||
                        mSdData.mHRAlarmActive ||mSdData.mHRNullAsAlarm)
                        && useSdServerBinding().mSdDataSourceName.equals("phone")) {
                    mSdData.mHRAlarmActive = false;
                    mSdData.mHRNullAsAlarm = false;
                    mSdData.mO2SatNullAsAlarm = false;
                    mSdData.mO2SatAlarmActive = false;
                    useSdServerBinding().mSdData.mHRAlarmActive = false;
                    useSdServerBinding().mSdData.mHRNullAsAlarm = false;
                    useSdServerBinding().mSdData.mO2SatNullAsAlarm = false;
                    useSdServerBinding().mSdData.mO2SatAlarmActive = false;
                }

            } else {
                Log.v(TAG, "updatePrefs() - prefStr is null - WHY????");
                mUtil.writeToSysLogFile("SDDataSource.updatePrefs() - prefStr is null - WHY??");
                Toast toast = Toast.makeText(useSdServerBinding(), "Problem Parsing Preferences - Something won't work - Please go back to Settings and correct it!", Toast.LENGTH_SHORT);
                toast.show();
            }

        } catch (Exception ex) {
            Log.e(TAG, "updatePrefs() - Problem parsing preferences!",ex);
            mUtil.writeToSysLogFile("SDDataSource.updatePrefs() - ERROR " + ex.toString());
            Toast toast = Toast.makeText(useSdServerBinding(), "Problem Parsing Preferences - Something won't work - Please go back to Settings and correct it!", Toast.LENGTH_SHORT);
            toast.show();
        }
        mSdDataSettings = mSdData;
        if(Objects.nonNull(((SdServer)mSdDataReceiver).mLm))
            mSdDataReceiver.onSdDataReceived(mSdData);


    }


    /**
     * Display a Toast message on screen.
     *
     * @param msg - message to display.
     */
    public void showToast(String msg) {
        Toast.makeText(useSdServerBinding(), msg,
                Toast.LENGTH_LONG).show();
    }

    public void initSdServerBindPowerBroadcastComplete() {

    }

    public class SdDataBroadcastReceiver extends BroadcastReceiver {
        //private String TAG = "SdDataBroadcastReceiver";

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.v(TAG, "SdDataBroadcastReceiver.onReceive()");
            if (intent.hasExtra(Constants.GLOBAL_CONSTANTS.JSON_TYPE_DATA)){
                jsonStr = intent.getStringExtra(Constants.GLOBAL_CONSTANTS.JSON_TYPE_DATA);
                Log.v(TAG, "SdDataBroadcastReceiver.onReceive() - data=" + jsonStr);
                updateFromJSON(jsonStr);
                jsonStr = null;
            }else{
                Log.e(TAG, "SdDataBroadcastReceiver: onReceive(): Empty broadcast received");
            }
        }

    }



}
