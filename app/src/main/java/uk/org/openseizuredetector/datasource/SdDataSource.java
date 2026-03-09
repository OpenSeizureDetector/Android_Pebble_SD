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

import uk.org.openseizuredetector.data.SdData;
import uk.org.openseizuredetector.utils.OsdUtil;
import uk.org.openseizuredetector.utils.PreferenceUtils;
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
import uk.org.openseizuredetector.data.logging.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;


/**
 * Abstract class for a seizure detector data source.  Subclasses include a pebble smart watch data source and a
 * network data source.
 * Responsible only for data acquisition. Analysis is handled by SeizureDetector class.
 */
public abstract class SdDataSource {
    protected Handler mHandler = new Handler(Looper.getMainLooper());
    private Timer mStatusTimer;
    private Timer mSettingsTimer;
    private Timer mFaultCheckTimer;
    protected long mDataStatusTimeMillis;
    protected boolean mWatchAppRunningCheck = false;
    private int mAppRestartTimeout = 10;
    private int mFaultTimerPeriod = 30;
    private int mSettingsPeriod = 60;
    public SdData mSdData;
    public String mName = "undefined";
    protected OsdUtil mUtil;
    protected Context mContext;
    protected SdDataReceiver mSdDataReceiver;
    private String TAG = "SdDataSource";

    private short mDataUpdatePeriod;
    private short mMutePeriod;
    private short mManAlarmPeriod;
    private int mMute;

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

    // Lifecycle running flag - subclasses and callbacks should check this to avoid doing work after stop()
    protected volatile boolean mRunning = false;

    public boolean isRunning() { return mRunning; }
    protected void setRunning(boolean running) { mRunning = running; }

    public SdDataSource(Context context, Handler handler, SdDataReceiver sdDataReceiver) {
        Log.v(TAG, "SdDataSource() Constructor");
        mContext = context;
        mHandler = handler;
        mUtil = new OsdUtil(mContext, mHandler);
        mSdDataReceiver = sdDataReceiver;
        mSdData = new SdData();
    }

    public SdData getSdData() {
        return mSdData;
    }

    public void start() {
        Log.v(TAG, "start()");
        Log.i(TAG, "SdDataSource.start()");
        updatePrefs();
        setRunning(true);

        // Start timer to check status of watch regularly.
        mDataStatusTimeMillis = Calendar.getInstance().getTimeInMillis();
        if (mStatusTimer == null) {
            Log.v(TAG, "start(): starting status timer");
            mStatusTimer = new Timer();
            mStatusTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    getStatus();
                }
            }, 0, mDataUpdatePeriod * 1000);
        }

        mHrStatusTimeMillis = Calendar.getInstance().getTimeInMillis();
        mLastHrValue = -1;

        if (mFaultCheckTimer == null) {
            Log.v(TAG, "start(): starting alarm check timer");
            mFaultCheckTimer = new Timer();
            mFaultCheckTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    faultCheck();
                }
            }, 0, 1000);
        }

        if (mSettingsTimer == null) {
            Log.v(TAG, "start(): starting settings timer");
            mSettingsTimer = new Timer();
            mSettingsTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    mSdData.haveSettings = false;
                }
            }, 0, 1000 * mSettingsPeriod);
        }
    }

    public void stop() {
        Log.i(TAG, "stop()");
        try {
            if (mStatusTimer != null) {
                mStatusTimer.cancel();
                mStatusTimer.purge();
                mStatusTimer = null;
            }
            if (mSettingsTimer != null) {
                mSettingsTimer.cancel();
                mSettingsTimer.purge();
                mSettingsTimer = null;
            }
            if (mFaultCheckTimer != null) {
                mFaultCheckTimer.cancel();
                mFaultCheckTimer.purge();
                mFaultCheckTimer = null;
            }
            // Mark as stopped so background callbacks know not to process further
            setRunning(false);
        } catch (Exception e) {
            Log.v(TAG, "Error in stop() - " + e.toString());
        }
    }

    public void installWatchApp() {
        Log.v(TAG, "installWatchApp");
        try {
            String url = "http://www.openseizuredetector.org.uk/?page_id=1207";
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse(url));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(i);
        } catch (Exception ex) {
            showToast("Error Displaying Installation Instructions");
        }
    }

    public void startPebbleApp() {}

    public void acceptAlarm() {}

    public String updateFromJSON(String jsonStr) {
        String retVal = "undefined";
        boolean have3dData = false;
        Log.v(TAG, "updateFromJSON - " + jsonStr);

        try {
            JSONObject mainObject = new JSONObject(jsonStr);
            JSONObject dataObject = mainObject;
            String dataTypeStr = dataObject.getString("dataType");
            if (dataTypeStr.equals("raw")) {
                try {
                    mSdData.mHR = dataObject.getDouble("HR");
                } catch (JSONException e) { mSdData.mHR = -1; }
                try {
                    mSdData.mO2Sat = dataObject.getDouble("O2sat");
                } catch (JSONException e) { mSdData.mO2Sat = -1; }
                try {
                    mMute = dataObject.getInt("Mute");
                    mSdData.mMute = mMute;
                } catch (JSONException e) { mSdData.mMute = 0; }
                
                try {
                    JSONArray accelVals3D = dataObject.getJSONArray("data3D");
                    for (int i = 0; i < accelVals3D.length() && i < mSdData.rawData3D.length; i++) {
                        mSdData.rawData3D[i] = accelVals3D.getDouble(i);
                    }
                    have3dData = true;
                } catch (JSONException e) {
                    for (int i = 0; i < mSdData.rawData3D.length; i++) mSdData.rawData3D[i] = 0.;
                    have3dData = false;
                }
                
                try {
                    JSONArray accelVals = dataObject.getJSONArray("data");
                    for (int i = 0; i < accelVals.length() && i < mSdData.rawData.length; i++) {
                        mSdData.rawData[i] = accelVals.getDouble(i);
                    }
                    mSdData.mNsamp = accelVals.length();
                } catch (JSONException e) {
                    if (have3dData) {
                        for (int i = 0; i < 125; i++) {
                            double x = mSdData.rawData3D[i*3 + 0];
                            double y = mSdData.rawData3D[i*3 + 1];
                            double z = mSdData.rawData3D[i*3 + 2];
                            mSdData.rawData[i] = Math.sqrt(x*x + y*y + z*z);
                        }
                        mSdData.mNsamp = 125;
                    } else {
                        for (int i = 0; i < 125; i++) mSdData.rawData[i] = 0.0;
                        mSdData.mNsamp = 125;
                    }
                }

                mWatchAppRunningCheck = true;
                doAnalysis();
                retVal = mSdData.haveSettings ? "OK" : "sendSettings";
            } else if (dataTypeStr.equals("settings")) {
                mSdData.analysisPeriod = (short) dataObject.getInt("analysisPeriod");
                mSdData.mSampleFreq = dataObject.getInt("sampleFreq");
                mSdData.batteryPc = (short) dataObject.getInt("battery");

                try {
                    mSdData.watchPartNo = dataObject.getString("watchPartNo");
                    mSdData.watchFwVersion = dataObject.getString("watchFwVersion");
                    mSdData.watchSdVersion = dataObject.getString("sdVersion");
                    mSdData.watchSdName = dataObject.getString("sdName");
                } catch (Exception e) {
                    Log.e(TAG, "updateFromJSON - Error Parsing V3.2 JSON String");
                }
                mSdData.haveSettings = true;
                mWatchAppRunningCheck = true;
                retVal = "OK";
            } else {
                retVal = "ERROR";
            }
        } catch (Exception e) {
            Log.e(TAG, "updateFromJSON - Error Parsing JSON String");
            retVal = "ERROR";
        }
        return retVal;
    }

    private int getPhoneBatteryLevel() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = mContext.registerReceiver(null, ifilter);
        if (batteryStatus == null) return -1;
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return (int) (level * 100 / (float) scale);
    }

    protected void doAnalysis() {
        mSdData.phoneBatteryPc = getPhoneBatteryLevel();
        try {
            mDataStatusTimeMillis = System.currentTimeMillis();
            long tnow = System.currentTimeMillis();
            if (mSdData.dataTimeMillis != 0) {
                mSdData.timeDiff = (tnow - mSdData.dataTimeMillis) / 1000f;
            } else {
                mSdData.timeDiff = 0f;
            }
            mSdData.dataTimeMillis = tnow;
            mSdData.haveData = true;
            mWatchAppRunningCheck = true;
            mSdData.mAccelMagStdDev = calcAccelMagStdDev(mSdData);
        } catch (Exception e) {
            Log.e(TAG, "doAnalysis - Exception");
            mWatchAppRunningCheck = false;
        }
        mSdDataReceiver.onSdDataReceived(mSdData);
    }

    private double calcRawDataStd(SdData sdData) {
        double sum = 0.0;
        for (int j = 0; j < 125; j++) sum += sdData.rawData[j];
        double mean = sum / 125;
        double sumSq = 0.0;
        for (int j = 0; j < 125; j++) sumSq += Math.pow(sdData.rawData[j] - mean, 2);
        return 100. * Math.sqrt(sumSq / 125) / mean;
    }

    private double calcAccelMagStdDev(SdData sdData) {
        int numSamples = 125;
        double sum = 0.0;
        double[] magnitudes = new double[numSamples];
        for (int i = 0; i < numSamples; i++) {
            double x = sdData.rawData3D[i * 3];
            double y = sdData.rawData3D[i * 3 + 1];
            double z = sdData.rawData3D[i * 3 + 2];
            magnitudes[i] = Math.sqrt(x * x + y * y + z * z);
            sum += magnitudes[i];
        }
        double mean = sum / numSamples;
        if (mean == 0) return 0.0;
        double sumSq = 0.0;
        for (int i = 0; i < numSamples; i++) sumSq += Math.pow(magnitudes[i] - mean, 2);
        return 100.0 * Math.sqrt(sumSq / numSamples) / mean;
    }

    public void getStatus() {
        try {
            long tnow = System.currentTimeMillis();
            long tdiff = tnow - mDataStatusTimeMillis;
            mSdData.watchConnected = true;
            if (!mWatchAppRunningCheck && (tdiff > (mDataUpdatePeriod + mAppRestartTimeout) * 1000)) {
                mSdData.watchAppRunning = false;
                if (tdiff > (mDataUpdatePeriod + mFaultTimerPeriod) * 1000) {
                    mSdData.roiPower = -1;
                    mSdData.specPower = -1;
                    mSdDataReceiver.onSdDataFault(mSdData);
                }
            } else {
                mSdData.watchAppRunning = true;
                if (mFidgetDetectorEnabled) {
                    if (mLastFidgetTimeMillis == 0) mLastFidgetTimeMillis = tnow;
                    if (calcRawDataStd(mSdData) > mFidgetThreshold) {
                        mLastFidgetTimeMillis = tnow;
                    } else if (tnow - mLastFidgetTimeMillis > (mFidgetPeriod) * 60 * 1000) {
                        mSdDataReceiver.onSdDataFault(mSdData);
                    }
                }
            }
            if (mWatchAppRunningCheck) {
                mWatchAppRunningCheck = false;
                mDataStatusTimeMillis = System.currentTimeMillis();
            }
        } catch(Exception e) {
            mSdData.watchAppRunning = false;
            mSdData.roiPower = -1;
            mSdData.specPower = -1;
            mSdDataReceiver.onSdDataFault(mSdData);
        }
    }

    private void faultCheck() {
        try {
            long tnow = System.currentTimeMillis();
            if (mSdData.mHRAlarmActive && mHrFrozenAlarm) {
                if (mSdData.mHR != mLastHrValue) {
                    mLastHrValue = mSdData.mHR;
                    mHrStatusTimeMillis = tnow;
                    mSdData.mHrFrozenFaultStanding = false;
                } else if (tnow - mHrStatusTimeMillis > mHrFrozenPeriod * 1000.) {
                    mSdData.mHrFrozenFaultStanding = true;
                }
            }
        } catch(Exception e) {
            mSdDataReceiver.onSdDataFault(mSdData);
        }
    }

    private double readDoublePref(SharedPreferences SP, String prefName, String defVal) {
        try {
            return Double.parseDouble(SP.getString(prefName, defVal));
        } catch (Exception ex) { return -1; }
    }

    public void updatePrefs() {
        Log.i(TAG, "SDDataSource.updatePrefs()");
        SharedPreferences SP = PreferenceManager.getDefaultSharedPreferences(mContext);
        try {
            mAppRestartTimeout = Integer.parseInt(SP.getString("AppRestartTimeout", "SET_FROM_XML"));
            mFaultTimerPeriod = Integer.parseInt(SP.getString("FaultTimerPeriod", "SET_FROM_XML"));
            mFidgetDetectorEnabled = PreferenceUtils.getBooleanFromXml(SP, "FidgetDetectorEnabled");
            mFidgetPeriod = readDoublePref(SP, "FidgetDetectorPeriod", "SET_FROM_XML");
            mFidgetThreshold = readDoublePref(SP, "FidgetDetectorThreshold", "SET_FROM_XML");
            mBleDeviceAddr = SP.getString("BLE_Device_Addr", "SET_FROM_XML");
            mBleDeviceName = SP.getString("BLE_Device_Name", "SET_FROM_XML");
            mDataUpdatePeriod = (short) Integer.parseInt(SP.getString("PebbleUpdatePeriod", "SET_FROM_XML"));
            mMutePeriod = (short) Integer.parseInt(SP.getString("MutePeriod", "SET_FROM_XML"));
            mManAlarmPeriod = (short) Integer.parseInt(SP.getString("ManAlarmPeriod", "SET_FROM_XML"));

            mSdData.mOsdAlarmActive = PreferenceUtils.getBooleanFromXml(SP, "OsdAlarmActive");
            mSdData.mFlapAlarmActive = PreferenceUtils.getBooleanFromXml(SP, "FlapAlarmActive");
            mSdData.mCnnAlarmActive = PreferenceUtils.getBooleanFromXml(SP, "CnnAlarmActive");
            mSdData.mHRAlarmActive = PreferenceUtils.getBooleanFromXml(SP, "HRAlarmActive");
            mSdData.mO2SatAlarmActive = PreferenceUtils.getBooleanFromXml(SP, "O2SatAlarmActive");
            mSdData.mFallActive = PreferenceUtils.getBooleanFromXml(SP, "FallActive");
        } catch (Exception ex) {
            Log.e(TAG, "updatePrefs() exception:  " + ex.toString());
            mUtil.showToast("SdDataSource: Problem parsing preferences! ");
        }
    }

    public void showToast(String msg) {
        Toast.makeText(mContext, msg, Toast.LENGTH_LONG).show();
    }

    public class SdDataBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Default no-op receiver. Concrete datasources may register receivers and
            // override behaviour if needed. Keeping this to avoid parsing errors
            // and to provide a safe default.
            Log.v(TAG, "SdDataBroadcastReceiver.onReceive() - received: " + intent);
        }
    }
}
