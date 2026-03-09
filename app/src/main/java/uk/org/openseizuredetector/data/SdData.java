/*
  Pebble_sd - a simple accelerometer based seizure detector that runs on a
  Pebble smart watch (http://getpebble.com).

  See http://openseizuredetector.org for more information.

  Copyright Graham Jones, 2015.

  This file is part of pebble_sd.

  Pebble_sd is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.
  
  Pebble_sd is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.
  
  You should have received a copy of the GNU General Public License
  along with pebble_sd.  If not, see <http://www.gnu.org/licenses/>.

*/
package uk.org.openseizuredetector.data;

import uk.org.openseizuredetector.utils.CircBuf;
import android.os.Parcelable;
import android.os.Parcel;
import uk.org.openseizuredetector.data.logging.Log;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/* based on http://stackoverflow.com/questions/2139134/how-to-send-an-object-from-one-android-activity-to-another-using-intents */

public class SdData implements Parcelable {
    private final static String TAG = "SdData";
    private final static int N_RAW_DATA = 125;  // 5 seconds at 25 Hz.

    // Seizure Detection Algorithm Selection
    public boolean mOsdAlarmActive;
    public boolean mFlapAlarmActive;
    public boolean mCnnAlarmActive;

    /* Analysis settings */
    public String phoneAppVersion = "";
    public boolean haveSettings = false;   // flag to say if we have received settings or not.
    public boolean haveData = false; // flag to say we have received data.
    public short mDataUpdatePeriod;
    public short mMutePeriod;
    public short mManAlarmPeriod;
    public int mMute = 0;  // !=0 means muted by keypress on watch.
    public boolean mFallActive;
    public short mFallThreshMin;
    public short mFallThreshMax;
    public short mFallWindow;
    public long mSdMode;
    public long mSampleFreq;
    public long analysisPeriod;
    public long alarmFreqMin;
    public long alarmFreqMax;
    public long nMin;
    public long nMax;
    public long warnTime;
    public long alarmTime;
    public long alarmThresh;
    public long alarmRatioThresh;
    public long batteryPc;  // watch battery
    public int phoneBatteryPc;

    /* Heart Rate Alarm Settings */
    public boolean mHRAlarmActive = false;
    public boolean mHRNullAsAlarm = false;
    public double mHRThreshMin = 40.0;
    public double mHRThreshMax = 150.0;

    /* Oxygen Saturation Alarm Settings */
    public boolean mO2SatAlarmActive = false;
    public boolean mO2SatNullAsAlarm = false;
    public double mO2SatThreshMin = 80.0;

    /* Watch App Settings */
    public String dataSourceName = "";
    public String watchManuf = "";
    public String watchSerNo = "";
    public String watchPartNo = "";
    public String watchFwVersion = "";
    public String watchSdVersion = "";
    public String watchSdName = "";


    public double rawData[];
    public double rawData3D[];
    public boolean mAdaptiveHrAlarmActive;
    public double mAdaptiveHrAlarmWindowSecs;
    public double mAdaptiveHrAlarmThresh;
    public boolean mAverageHrAlarmActive;
    public double mAverageHrAlarmWindowSecs;
    public double mAverageHrAlarmThreshMin;
    public double mAverageHrAlarmThreshMax;
    public double mAverageHrAverage;
    public double mAdaptiveHrAverage;

    public CircBuf mAdaptiveHrBuf;
    public CircBuf mAverageHrBuf;
    public boolean mHrFrozenFaultStanding = false;
    public int mNsamp = 0;

    /* Analysis results */
    public long dataTimeMillis = 0;
    public float timeDiff = 0f;
    public long alarmState;
    public String alarmCause = "";
    public boolean alarmStanding = false;
    public boolean fallAlarmStanding = false;
    public long maxVal;
    public long maxFreq;
    public long specPower;
    public long roiPower;
    public String alarmPhrase;
    public int simpleSpec[];
    public boolean watchConnected = false;
    public boolean watchAppRunning = false;
    public boolean serverOK = false;

    public boolean mHRAlarmStanding = false;
    public boolean mHRFaultStanding = false;
    public boolean mAdaptiveHrAlarmStanding = false;
    public boolean mAverageHrAlarmStanding = false;
    public double mHR = 0;

    public boolean mO2SatAlarmStanding = false;
    public boolean mO2SatFaultStanding = false;
    public double mO2Sat = 0;

    public double mPseizure = 0.;
    public double mAccelMagStdDev = 0.;  // Standard deviation of acceleration magnitude (0-100%)
    public float watchSignalStrength;

    // Individual ML Model Results (for multi-model voting)
    public String[] mlModelNames = new String[5];     // Model names
    public double[] mlModelProbs = new double[5];     // Probabilities (0-1)
    public int[] mlModelStates = new int[5];          // States (0=OK, 1=WARNING, 2=ALARM)
    public boolean[] mlModelActive = new boolean[5];   // Which models are active
    public int mlNumModels = 0;                        // Number of active models

    // Individual Algorithm States (for UI display)
    public int osdAlgState = 0;    // 0=OK, 1=WARNING, 2=ALARM
    public int flapAlgState = 0;
    public int fallAlgState = 0;
    public int hrAlgState = 0;
    public int cnnAlgState = 0;    // Combined ML state (for backward compatibility)

    public SdData() {
        simpleSpec = new int[10];
        rawData = new double[N_RAW_DATA];
        rawData3D = new double[N_RAW_DATA * 3];
        dataTimeMillis = System.currentTimeMillis();
        timeDiff = 0f;

        // Initialize ML model arrays
        for (int i = 0; i < 5; i++) {
            mlModelNames[i] = "";
            mlModelProbs[i] = 0.0;
            mlModelStates[i] = 0;
            mlModelActive[i] = false;
        }
    }

    /*
     * Intialise this SdData object from a JSON String
     * Strict: throw JSONException on missing/invalid fields.
     */
    public void fromJSON(String jsonStr) throws JSONException {
        Log.v(TAG, "fromJSON() - parsing jsonString - " + jsonStr);
        JSONObject jo = new JSONObject(jsonStr);

        // Required fields - use get* so JSONException thrown if missing or wrong type
        dataTimeMillis = System.currentTimeMillis();

        maxVal = jo.getInt("maxVal");
        maxFreq = jo.getInt("maxFreq");
        specPower = jo.getInt("specPower");
        roiPower = jo.getInt("roiPower");
        batteryPc = jo.getLong("batteryPc");
        phoneBatteryPc = jo.getInt("phoneBatteryPc");
        watchConnected = jo.getBoolean("watchConnected");
        watchAppRunning = jo.getBoolean("watchAppRunning");
        haveSettings = jo.getBoolean("haveSettings");

        alarmState = jo.getLong("alarmState");
        alarmPhrase = jo.getString("alarmPhrase");
        alarmCause = jo.getString("alarmCause");

        mSdMode = jo.getLong("sdMode");
        mSampleFreq = jo.getLong("sampleFreq");
        analysisPeriod = jo.getLong("analysisPeriod");
        alarmFreqMin = jo.getLong("alarmFreqMin");
        alarmFreqMax = jo.getLong("alarmFreqMax");
        alarmThresh = jo.getLong("alarmThresh");
        alarmRatioThresh = jo.getLong("alarmRatioThresh");

        mHRAlarmActive = jo.getBoolean("hrAlarmActive");
        mHRAlarmStanding = jo.getBoolean("hrAlarmStanding");
        mHRThreshMin = jo.getDouble("hrThreshMin");
        mHRThreshMax = jo.getDouble("hrThreshMax");
        mHR = jo.getDouble("hr");
        mAdaptiveHrAverage = jo.getDouble("adaptiveHrAv");
        mAverageHrAverage = jo.getDouble("averageHrAv");

        mO2SatAlarmActive = jo.getBoolean("o2SatAlarmActive");
        mO2SatAlarmStanding = jo.getBoolean("o2SatAlarmStanding");
        mO2SatThreshMin = jo.getDouble("o2SatThreshMin");
        mO2Sat = jo.getDouble("o2Sat");

        mCnnAlarmActive = jo.getBoolean("cnnAlarmActive");
        mPseizure = jo.getDouble("pSeizure");

        // Algorithm flags
        mOsdAlarmActive = jo.getBoolean("OsdAlarmActive");
        mFlapAlarmActive = jo.getBoolean("FlapAlarmActive");
        mCnnAlarmActive = jo.getBoolean("CnnAlarmActive");

        // per-algorithm states
        osdAlgState = jo.getInt("osdAlgState");
        flapAlgState = jo.getInt("flapAlgState");
        fallAlgState = jo.getInt("fallAlgState");
        hrAlgState = jo.getInt("hrAlgState");
        cnnAlgState = jo.getInt("cnnAlgState");

        // ML arrays: mlNumModels and arrays must be present and consistent
        mlNumModels = jo.getInt("mlNumModels");
        if (mlNumModels < 0 || mlNumModels > mlModelNames.length) {
            throw new JSONException("Invalid mlNumModels: " + mlNumModels);
        }
        JSONArray namesArr = jo.getJSONArray("mlModelNames");
        JSONArray probsArr = jo.getJSONArray("mlModelProbs");
        JSONArray statesArr = jo.getJSONArray("mlModelStates");
        JSONArray activeArr = jo.getJSONArray("mlModelActive");
        if (namesArr.length() < mlNumModels || probsArr.length() < mlNumModels || statesArr.length() < mlNumModels || activeArr.length() < mlNumModels) {
            throw new JSONException("ML arrays shorter than mlNumModels");
        }
        for (int i = 0; i < mlNumModels; i++) {
            mlModelNames[i] = namesArr.getString(i);
            mlModelProbs[i] = probsArr.getDouble(i);
            mlModelStates[i] = statesArr.getInt(i);
            mlModelActive[i] = activeArr.getBoolean(i);
        }

        // simpleSpec
        JSONArray specArr = jo.getJSONArray("simpleSpec");
        for (int i = 0; i < specArr.length() && i < simpleSpec.length; i++) {
            simpleSpec[i] = specArr.getInt(i);
        }

        // rawData and rawData3D if present (optional)
        if (jo.has("rawData")) {
            JSONArray rawArr = jo.getJSONArray("rawData");
            for (int i = 0; i < rawArr.length() && i < rawData.length; i++) rawData[i] = rawArr.getDouble(i);
        }
        if (jo.has("rawData3D")) {
            JSONArray raw3DArr = jo.getJSONArray("rawData3D");
            for (int i = 0; i < raw3DArr.length() && i < rawData3D.length; i++) rawData3D[i] = raw3DArr.getDouble(i);
        }

        haveData = true;
    }

    @Override
    public String toString() {
        return toDataString(false);
    }

    public String toJSON(boolean includeRawData) {
        return toDataString(includeRawData);
    }

    public String toDatapointJSON() {
        String retval;
        retval = "SdData.toDatapointJSON() Output";
        try {
            JSONObject jsonObj = new JSONObject();
            if (dataTimeMillis != 0) {
                SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault());
                SimpleDateFormat dateStrFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.getDefault());
                Date date = new Date(dataTimeMillis);
                jsonObj.put("dataTime", dateFormat.format(date));
                jsonObj.put("dataTimeStr", dateStrFormat.format(date));
            } else {
                jsonObj.put("dataTimeStr", "00000000T000000");
                jsonObj.put("dataTime", "00-00-00 00:00:00");
            }
            Log.v(TAG, "mSdData.dataTimeMillis = " + dataTimeMillis);
            jsonObj.put("maxVal", maxVal);
            jsonObj.put("maxFreq", maxFreq);
            jsonObj.put("specPower", specPower);
            jsonObj.put("roiPower", roiPower);
            if (specPower != 0) jsonObj.put("roiRatio", 10 * roiPower / specPower);
            else jsonObj.put("roiRatio", 0);
            jsonObj.put("alarmState", alarmState);
            jsonObj.put("alarmPhrase", alarmPhrase);
            jsonObj.put("alarmCause", alarmCause);
            jsonObj.put("hr", mHR);
            jsonObj.put("adaptiveHrAv", mAdaptiveHrAverage);
            jsonObj.put("averageHrAv", mAverageHrAverage);
            jsonObj.put("o2Sat", mO2Sat);
            jsonObj.put("pSeizure", mPseizure);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < simpleSpec.length; i++) {
                arr.put(simpleSpec[i]);
            }
            jsonObj.put("simpleSpec", arr);
            JSONArray rawArr = new JSONArray();
            for (int i = 0; i < rawData.length; i++) {
                rawArr.put(rawData[i]);
            }
            //Log.v(TAG,"rawData[0]="+rawData[0]+", rawArr[0]="+rawArr.getDouble(0));
            jsonObj.put("rawData", rawArr);

            JSONArray raw3DArr = new JSONArray();
            for (int i = 0; i < rawData3D.length; i++) {
                raw3DArr.put(rawData3D[i]);
            }
            jsonObj.put("rawData3D", raw3DArr);

            retval = jsonObj.toString();
            Log.v(TAG, "retval rawData=" + retval);
        } catch (Exception ex) {
            Log.v(TAG, "Error Creating Data Object - " + ex.toString());
            retval = "Error Creating Data Object - " + ex.toString();
        }

        return (retval);
    }


    public String toSettingsJSON() {
        String retval;
        retval = "SdData.toSettingsJSON() Output";
        try {
            JSONObject jsonObj = new JSONObject();
            if (dataTimeMillis != 0) {
                SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault());
                SimpleDateFormat dateStrFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.getDefault());
                Date date = new Date(dataTimeMillis);
                jsonObj.put("dataTime", dateFormat.format(date));
                jsonObj.put("dataTimeStr", dateStrFormat.format(date));
            } else {
                jsonObj.put("dataTimeStr", "00000000T000000");
                jsonObj.put("dataTime", "00-00-00 00:00:00");
            }
            jsonObj.put("batteryPc", batteryPc);
            jsonObj.put("phoneBatteryPc", phoneBatteryPc);
            jsonObj.put("alarmState", alarmState);
            jsonObj.put("alarmPhrase", alarmPhrase);
            jsonObj.put("alarmCause", alarmCause);
            jsonObj.put("sdMode", mSdMode);
            jsonObj.put("sampleFreq", mSampleFreq);
            jsonObj.put("analysisPeriod", analysisPeriod);
            jsonObj.put("alarmFreqMin", alarmFreqMin);
            jsonObj.put("alarmFreqMax", alarmFreqMax);
            jsonObj.put("alarmThresh", alarmThresh);
            jsonObj.put("alarmRatioThresh", alarmRatioThresh);
            jsonObj.put("osdAlarmActive", mOsdAlarmActive);
            jsonObj.put("cnnAlarmActive", mCnnAlarmActive);
            jsonObj.put("hrAlarmActive", mHRAlarmActive);
            jsonObj.put("hrAlarmStanding", mHRAlarmStanding);
            jsonObj.put("hrThreshMin", mHRThreshMin);
            jsonObj.put("hrThreshMax", mHRThreshMax);
            jsonObj.put("adaptiveHrAlarmActive", mAdaptiveHrAlarmActive);
            jsonObj.put("adaptiveHrAlarmStanding", mAdaptiveHrAlarmStanding);
            jsonObj.put("adaptiveHrAlarmWindow", mAdaptiveHrAlarmWindowSecs);
            jsonObj.put("adaptiveHrAlarmThresh", mAdaptiveHrAlarmThresh);
            jsonObj.put("averageHrAlarmActive", mAverageHrAlarmActive);
            jsonObj.put("averageHrAlarmStanding", mAverageHrAlarmStanding);
            jsonObj.put("averageHrAlarmThreshMin", mAverageHrAlarmThreshMin);
            jsonObj.put("averageHrAlarmThreshMax", mAverageHrAlarmThreshMax);

            jsonObj.put("o2SatAlarmActive", mO2SatAlarmActive);
            jsonObj.put("o2SatAlarmStanding", mO2SatAlarmStanding);
            jsonObj.put("o2SatThreshMin", mO2SatThreshMin);
            jsonObj.put("dataSourceName", dataSourceName);
            Log.v(TAG, "phoneAppVersion=" + phoneAppVersion);
            jsonObj.put("phoneAppVersion", phoneAppVersion);
            jsonObj.put("watchManuf", watchManuf);
            jsonObj.put("watchPartNo", watchPartNo);
            jsonObj.put("watchSerNo", watchSerNo);
            jsonObj.put("watchSdName", watchSdName);
            jsonObj.put("watchFwVersion", watchFwVersion);
            jsonObj.put("watchSdVersion", watchSdVersion);
            jsonObj.put("watchSignalStrength", watchSignalStrength);

            retval = jsonObj.toString();
        } catch (Exception ex) {
            Log.e(TAG, "toSettingsJSON(): Error Creating Data Object - " + ex.toString());
            retval = "Error Creating Data Object - " + ex.toString();
        }
        return (retval);
    }

    private double safeDouble(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return v;
    }

    public String toDataString(boolean includeRawData) {
        String retval;
        retval = "SdData.toDataString() Output";
        try {
            JSONObject jsonObj = new JSONObject();
            if (dataTimeMillis != 0) {
                SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault());
                SimpleDateFormat dateStrFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.getDefault());
                Date date = new Date(dataTimeMillis);
                jsonObj.put("dataTime", dateFormat.format(date));
                jsonObj.put("dataTimeStr", dateStrFormat.format(date));
            } else {
                jsonObj.put("dataTimeStr", "00000000T000000");
                jsonObj.put("dataTime", "00-00-00 00:00:00");
            }
            Log.v(TAG, "mSdData.dataTimeMillis = " + dataTimeMillis);
            jsonObj.put("maxVal", maxVal);
            jsonObj.put("maxFreq", maxFreq);
            jsonObj.put("specPower", specPower);
            jsonObj.put("roiPower", roiPower);
            jsonObj.put("batteryPc", batteryPc);
            jsonObj.put("phoneBatteryPc", phoneBatteryPc);
            jsonObj.put("watchConnected", watchConnected);
            jsonObj.put("watchAppRunning", watchAppRunning);
            jsonObj.put("haveSettings", haveSettings);
            jsonObj.put("alarmState", alarmState);
            jsonObj.put("alarmPhrase", alarmPhrase);
            jsonObj.put("alarmCause", alarmCause);
            jsonObj.put("sdMode", mSdMode);
            jsonObj.put("sampleFreq", mSampleFreq);
            jsonObj.put("analysisPeriod", analysisPeriod);
            jsonObj.put("alarmFreqMin", alarmFreqMin);
            jsonObj.put("alarmFreqMax", alarmFreqMax);
            jsonObj.put("alarmThresh", alarmThresh);
            jsonObj.put("alarmRatioThresh", alarmRatioThresh);
            jsonObj.put("hrAlarmActive", mHRAlarmActive);
            jsonObj.put("hrAlarmStanding", mHRAlarmStanding);
            jsonObj.put("adaptiveHrAlarmStanding", mAdaptiveHrAlarmStanding);
            jsonObj.put("averageHrAlarmStanding", mAverageHrAlarmStanding);
            jsonObj.put("hrAlarmStanding", mHRAlarmStanding);
            jsonObj.put("hrThreshMin", safeDouble(mHRThreshMin));
            jsonObj.put("hrThreshMax", safeDouble(mHRThreshMax));
            jsonObj.put("hr", safeDouble(mHR));
            jsonObj.put("adaptiveHrAv", safeDouble(mAdaptiveHrAverage));
            jsonObj.put("averageHrAv", safeDouble(mAverageHrAverage));
            jsonObj.put("o2SatAlarmActive", mO2SatAlarmActive);
            jsonObj.put("o2SatAlarmStanding", mO2SatAlarmStanding);
            jsonObj.put("o2SatThreshMin", safeDouble(mO2SatThreshMin));
            jsonObj.put("o2Sat", safeDouble(mO2Sat));
            jsonObj.put("cnnAlarmActive", mCnnAlarmActive);
            jsonObj.put("pSeizure", safeDouble(mPseizure));

            // simpleSpec (spectral summary)
            JSONArray specArr = new JSONArray();
            for (int i = 0; i < simpleSpec.length; i++) specArr.put(simpleSpec[i]);
            jsonObj.put("simpleSpec", specArr);

             // Algorithm flags
             jsonObj.put("OsdAlarmActive", mOsdAlarmActive);
             jsonObj.put("FlapAlarmActive", mFlapAlarmActive);
             jsonObj.put("CnnAlarmActive", mCnnAlarmActive);

            // per-algorithm states
            jsonObj.put("osdAlgState", osdAlgState);
            jsonObj.put("flapAlgState", flapAlgState);
            jsonObj.put("fallAlgState", fallAlgState);
            jsonObj.put("hrAlgState", hrAlgState);
            jsonObj.put("cnnAlgState", cnnAlgState);

            // ML arrays
            jsonObj.put("mlNumModels", mlNumModels);
            JSONArray names = new JSONArray();
            JSONArray probs = new JSONArray();
            JSONArray states = new JSONArray();
            JSONArray active = new JSONArray();
            for (int i = 0; i < mlNumModels; i++) {
                names.put(i, mlModelNames != null && i < mlModelNames.length ? mlModelNames[i] : "");
                probs.put(i, safeDouble(mlModelProbs != null && i < mlModelProbs.length ? mlModelProbs[i] : 0.0));
                states.put(i, mlModelStates != null && i < mlModelStates.length ? mlModelStates[i] : 0);
                active.put(i, mlModelActive != null && i < mlModelActive.length ? mlModelActive[i] : false);
            }
            jsonObj.put("mlModelNames", names);
            jsonObj.put("mlModelProbs", probs);
            jsonObj.put("mlModelStates", states);
            jsonObj.put("mlModelActive", active);

            retval = jsonObj.toString();
        } catch (Exception ex) {
            Log.v(TAG, "Error Creating Data Object - " + ex.toString());
            retval = "Error Creating Data Object - " + ex.toString();
        }

        return (retval);
    }


    public String toCSVString(boolean includeRawData) {
        String retval;
        retval = "";
        if (dataTimeMillis != 0) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault());
            retval = dateFormat.format(new Date(dataTimeMillis));
        } else {
            retval = "00-00-00 00:00:00";
        }
        for (int i = 0; i < simpleSpec.length; i++) {
            retval = retval + ", " + simpleSpec[i];
        }
        retval = retval + ", " + specPower;
        retval = retval + ", " + roiPower;
        retval = retval + ", " + mSampleFreq;
        retval = retval + ", " + alarmPhrase;
        retval = retval + ", " + mHR;
        retval = retval + ", " + mO2Sat;
        if (includeRawData) {
            for (int i = 0; i < mNsamp; i++) {
                retval = retval + ", " + rawData[i];
            }
        }
        return (retval);
    }

    /**
     * Return the average acceleration value in the dataset
     */
    public double getAvAcc() {
        double sumAcc = 0.0;
        for (int i = 0; i < mNsamp; i++) {
            sumAcc += rawData[i];
        }
        return (sumAcc / mNsamp);
    }

    /**
     * Return the standard deviation of the acceleration values
     */
    public double getSdAcc() {
        double avAcc = 0.0;
        double varAcc = 0.0;
        avAcc = getAvAcc();
        for (int i = 0; i < mNsamp; i++) {
            varAcc += Math.pow(rawData[i] - avAcc, 2);
        }
        return (Math.sqrt(varAcc / (mNsamp - 1)));
    }


    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel outParcel, int flags) {
        //outParcel.writeInt(fMin);
        //outParcel.writeInt(fMax);
    }

    private SdData(Parcel in) {
        //fMin = in.readInt();
        //fMax = in.readInt();
    }

    public static final Parcelable.Creator<SdData> CREATOR = new Parcelable.Creator<SdData>() {
        public SdData createFromParcel(Parcel in) {
            return new SdData(in);
        }

        public SdData[] newArray(int size) {
            return new SdData[size];
        }
    };

}
