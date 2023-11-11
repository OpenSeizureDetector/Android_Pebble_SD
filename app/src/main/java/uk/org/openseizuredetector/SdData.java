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
package uk.org.openseizuredetector;

import android.os.Parcelable;
import android.os.Parcel;
import android.text.format.Time;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Arrays;

/* based on http://stackoverflow.com/questions/2139134/how-to-send-an-object-from-one-android-activity-to-another-using-intents */

public class SdData implements Parcelable {
    private final static String TAG = "SdData";
    private final static int N_RAW_DATA = 500;  // 5 seconds at 100 Hz.

    // Seizure Detection Algorithm Selection
    public boolean mOsdAlarmActive;
    public boolean mCnnAlarmActive;

    /* Analysis settings */
    public String phoneAppVersion = "";
    public boolean haveSettings = false;   // flag to say if we have received settings or not.
    public boolean haveData = false; // flag to say we have received data.
    public short mDataUpdatePeriod;
    public short mMutePeriod;
    public short mManAlarmPeriod;
    public boolean mFallActive;
    public short mFallThreshMin;
    public short mFallThreshMax;
    public short mFallWindow;
    public long mSdMode;
    public int mDefaultSampleCount;
    public long mSampleFreq;
    public long analysisPeriod;
    public long alarmFreqMin;
    public long alarmFreqMax;
    public long nMin;
    public long nMax;
    public long warnTime;
    // number of miliseconds of currentTime-date
    public long alarmTime;
    public long alarmThresh;
    public long alarmRatioThresh;
    public long batteryPc;
    private JSONArray arr;
    private JSONArray rawArr;
    private JSONArray raw3DArr;
    private JSONObject jo;
    private JSONObject jsonObj;
    private JSONArray specArr;

    /* Heart Rate Alarm Settings */
    public boolean mHRAlarmActive = false;
    public boolean mHRNullAsAlarm = false;
    public boolean mHRAlarmStanding = false;
    public boolean mHRFaultStanding = false;
    public boolean mAdaptiveHrAlarmStanding = false;
    public boolean mAverageHrAlarmStanding = false;
    public double mHRThreshMin = 40.0;
    public double mHRThreshMax = 150.0;
    public double mHRAvg = -1d;
    public double mHR = -1d;

    /* Oxygen Saturation Alarm Settings */
    public boolean mO2SatAlarmActive = false;
    public boolean mO2SatNullAsAlarm = false;
    public double mO2SatThreshMin = 80.0;
    public double mO2Sat = -1d;

    public boolean mO2SatAlarmStanding = false;
    public boolean mO2SatFaultStanding = false;

    /* Watch App Settings */
    public String dataSourceName = "";
    public String watchPartNo = "";
    public String watchFwVersion = "";
    public String watchSdVersion = "";
    public String watchSdName = "";



    public double dT = -1d;
    public boolean watchConnected = false;


    public int mNsamp = 0;
    public int NSAMP = 0;
    public int mNsampDefault = 250;
    public double[] rawData;
    public double[] rawData3D;
    public boolean mAdaptiveHrAlarmActive;
    public double mAdaptiveHrAlarmWindowSecs;
    public double mAdaptiveHrAlarmThresh;
    public boolean mAverageHrAlarmActive;
    public double mAverageHrAlarmWindowSecs;
    public double mAverageHrAlarmThreshMin;
    public double mAverageHrAlarmThreshMax;
    public double mAverageHrAverage;
    public double mAdaptiveHrAverage;

    public CircBuf mHistoricHrBuf;
    public CircBuf mAdaptiveHrBuf;
    public CircBuf mAverageHrBuf;
    public boolean mHRFrozenFaultStanding = false;

    /* Analysis results */
    public Time dataTime = null;
    public long alarmState;
    public boolean alarmStanding = false;
    public boolean fallAlarmStanding = false;
    public long maxVal;
    public long maxFreq;
    public long specPower;
    public long roiPower;
    public long roiRatio;
    public String alarmPhrase;
    public int[] simpleSpec;
    public boolean watchAppRunning = false;
    public boolean serverOK = false;

    public String mDataType;
    public String phoneName = "";

    public boolean mWatchOnBody = false;


    public double mPseizure = 0.;

    public SdData() {
        simpleSpec = new int[10];
        rawData = new double[N_RAW_DATA];
        rawData3D = new double[N_RAW_DATA * 3];
        dT = 0d;
        dataTime = new Time(Time.getCurrentTimezone());
    }

    /*
     * Initialise this SdData object from a JSON String
     * FIXME - add O2saturation with checking in case it is not included in the data
     */
    public boolean fromJSON(String jsonStr) {
        Log.v(TAG, "fromJSON() - parsing jsonString - " + jsonStr);
        try {
            jo = new JSONObject(jsonStr);
            Log.v(TAG, "fromJSON(): jo = " + jo.toString());
            Log.v(TAG, "fromJSON(): dataTimeStr=" + jo.optString("dataTimeStr"));
            //Calendar cal = Calendar.getInstance();
            //SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddTHHmmss", Locale.UK);
            //cal.setTime(sdf.parse(jo.optString("dataTimeStr")));
            //dataTime = cal.getTime();
            // FIXME - this doesn't work!!!
            dataTime.setToNow();
            Log.v(TAG, "fromJSON(): dataTime = " + dataTime.toString());
            try {
                mDataType = jo.optString("dataType");
            } catch (Exception e) {
                Log.d(TAG, "Error in FromJSon: ", e);
                mDataType = Constants.GLOBAL_CONSTANTS.dataTypeRaw;
            }
            if (Constants.GLOBAL_CONSTANTS.dataTypeSettings.equals(mDataType)) {
                mDefaultSampleCount = jo.optInt("defaultSampleCount");
                batteryPc = jo.optInt("batteryPc");
                watchConnected = jo.optBoolean("watchConnected");
                watchAppRunning = jo.optBoolean("watchAppRunning");
                haveSettings = jo.optBoolean("haveSettings");
                maxVal = jo.optInt("maxVal");
                maxFreq = jo.optInt("maxFreq");
                analysisPeriod = jo.optInt("analysisPeriod",Constants.SD_SERVICE_CONSTANTS.defaultSampleTime);
                mSampleFreq = jo.optLong("sampleFreq", Constants.SD_SERVICE_CONSTANTS.defaultSampleRate);
                alarmState = jo.optInt("alarmState");
                alarmPhrase = jo.optString("alarmPhrase");
                alarmThresh = jo.optInt("alarmThresh");
                alarmRatioThresh = jo.optInt("alarmRatioThresh");
                mHRAlarmActive = jo.optBoolean("hrAlarmActive");
                mHRAlarmStanding = jo.optBoolean("hrAlarmStanding");
                mHRThreshMax = jo.optDouble("hrThreshMax");
                mHRThreshMin = jo.optDouble("hrThreshMin");
                if (jo.has("adaptiveHrAlarmActive") &&
                        jo.has("averageHrAlarmActive") &&
                        jo.has("adaptiveHrAlarmStanding" )){
                    mAdaptiveHrAlarmActive = jo.optBoolean("adaptiveHrAlarmActive");
                    mAdaptiveHrAlarmWindowSecs = jo.optInt("adaptiveHrAlarmWindow",-1);
                    mAdaptiveHrAlarmStanding = jo.optBoolean("adaptiveHrAlarmStanding");
                    mAdaptiveHrAlarmThresh = jo.optInt("adaptiveHrAlarmThresh",-1);
                    mAdaptiveHrAlarmActive = jo.optBoolean("averageHrAlarmActive");
                    mAdaptiveHrAlarmStanding = jo.optBoolean("averageHrAlarmStanding");
                    mAverageHrAlarmThreshMin = jo.optInt("averageHrAlarmThreshMin", -1);
                    mAverageHrAlarmThreshMax = jo.optInt("averageHrAlarmThreshMax", -1);
                    mHRAlarmActive = (mAdaptiveHrAlarmActive||mAverageHrAlarmActive);
                }
                phoneName = jo.optString("phoneName");
                dT = jo.optDouble("dT",dT);//FIXME
                //dT = -2; //set -2 as Received, from mobile, pending first round of data.
                if (jo.has("serverOk"))serverOK = jo.optBoolean("serverOk");
            }
            if (Constants.GLOBAL_CONSTANTS.dataTypeRaw.equals(mDataType)) {
                specPower = jo.optInt("specPower");
                roiPower = jo.optInt("roiPower");
                mHR = jo.optDouble("hr");
            /*if (mHR >= 0.0) {
                mHRAlarmActive = true;
            }*/

                specArr = jo.optJSONArray("simpleSpec");
                if (!Objects.equals(specArr, null)) {
                    for (int i = 0; i < specArr.length(); i++) {
                        simpleSpec[i] = specArr.optInt(i);
                    }
                }
            }
            haveData = true;
            Log.v(TAG, "fromJSON(): sdData = " + this.toString());
            specArr = null;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "fromJSON() - error parsing result", e);
            haveData = false;
            specArr = null;
            return false;
        }
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
            jsonObj = new JSONObject();
            if (dataTime != null) {
                jsonObj.put("dataTime", dataTime.format("%d-%m-%Y %H:%M:%S"));
                jsonObj.put("dataTimeStr", dataTime.format("%Y%m%dT%H%M%S"));
            } else {
                jsonObj.put("dataTimeStr", "00000000T000000");
                jsonObj.put("dataTime", "00-00-00 00:00:00");
            }
            Log.v(TAG, "mSdData.dataTime = " + dataTime);
            jsonObj.put("maxVal", maxVal);
            jsonObj.put("maxFreq", maxFreq);
            jsonObj.put("sampleFreq", mSampleFreq);
            jsonObj.put("dT", dT);
            jsonObj.put("specPower", specPower);
            jsonObj.put("roiPower", roiPower);
            try {
                jsonObj.put("roiRatio", 10 * roiPower / specPower);
            }catch(ArithmeticException arithmeticException){
                jsonObj.put("roiRatio","-1");
                Log.e(TAG,"roiPower and specPower devision by zero" ,arithmeticException);
            }
            jsonObj.put("alarmState", alarmState);
            jsonObj.put("alarmPhrase", alarmPhrase);
            jsonObj.put("hr", mHR);
            jsonObj.put("adaptiveHrAv", mAdaptiveHrAverage);
            jsonObj.put("averageHrAv", mAverageHrAverage);
            jsonObj.put("o2Sat", mO2Sat);
            jsonObj.put("pSeizure", mPseizure);
            jsonObj.put("dataType", mDataType);
            jsonObj.put("sdName", watchSdName);
            jsonObj.put("sdVersion", watchSdVersion);
            jsonObj.put("watchFwVersion", watchFwVersion);
            jsonObj.put("watchPartNo", watchPartNo);
            jsonObj.put("serverOk",serverOK);
            arr = new JSONArray();
            for (int i = 0; i < simpleSpec.length; i++) {
                arr.put(simpleSpec[i]);
            }
            jsonObj.put("simpleSpec", arr);
            rawArr = new JSONArray();
            for (int i = 0; i < rawData.length; i++) {
                rawArr.put(rawData[i]);
            }
            //Log.v(TAG,"rawData[0]="+rawData[0]+", rawArr[0]="+rawArr.getDouble(0));
            jsonObj.put("data", rawArr);

            raw3DArr = new JSONArray();
            for (int i = 0; i < rawData3D.length; i++) {
                raw3DArr.put(rawData3D[i]);
            }
            jsonObj.put("data3D", raw3DArr);

            retval = jsonObj.toString();
            Log.v(TAG, "retval rawData=" + retval);
        } catch (Exception ex) {
            Log.v(TAG, "Error Creating Data Object - " + ex.toString());
            retval = "Error Creating Data Object - " + ex.toString();
        }
        arr = null;
        rawArr = null;
        raw3DArr = null;
        return (retval);
    }


    public String toSettingsJSON() {
        String retval;
        retval = "SdData.toSettingsJSON() Output";
        jsonObj = new JSONObject();
        try {
            if (dataTime != null) {
                jsonObj.put("dataTime", dataTime.format("%d-%m-%Y %H:%M:%S"));
                jsonObj.put("dataTimeStr", dataTime.format("%Y%m%dT%H%M%S"));
            } else {
                jsonObj.put("dataTimeStr", "00000000T000000");
                jsonObj.put("dataTime", "00-00-00 00:00:00");
            }
            jsonObj.put("dataType", "settings");
            jsonObj.put("defaultSampleCount", mDefaultSampleCount);
            jsonObj.put("batteryPc", batteryPc);
            jsonObj.put("watchConnected", watchConnected);
            jsonObj.put("watchAppRunning", watchAppRunning);
            jsonObj.put("haveSettings", haveSettings);
            jsonObj.put("alarmState", alarmState);
            jsonObj.put("alarmPhrase", alarmPhrase);
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
            jsonObj.put("watchPartNo", watchPartNo);
            jsonObj.put("watchSdName", watchSdName);
            jsonObj.put("watchFwVersion", watchFwVersion);
            jsonObj.put("watchSdVersion", watchSdVersion);
            jsonObj.put("phoneName", phoneName);
            Log.v(TAG, "phoneAppVersion=" + phoneAppVersion);
            jsonObj.put("serverOk",serverOK);

            retval = jsonObj.toString();
        } catch (Exception ex) {
            Log.e(TAG, "toSettingsJSON(): Error Creating Data Object - " + ex.toString(),ex);

            Log.v(TAG, "Error Creating Data Object - " + ex.toString(),ex);

            try {
                jsonObj.put("dataType", "ErrorType");

                jsonObj.put("Exception", "Error Creating Data Object - " + ex.toString());
            } catch (JSONException jsonException) {
                Log.e(TAG, "toSettingsJSON() catched ex in JSON handling failed!", jsonException);
            }
            retval = jsonObj.toString();
        }
        jsonObj = null;
        return (retval);
    }

    public String toHeartRatesArrayString(){
        String retval = "";
        retval = "SdData.toDataString() Output";
        try {
            jsonObj = new JSONObject();
            if (dataTime != null) {
                jsonObj.put("dataTime", dataTime.format("%d-%m-%Y %H:%M:%S"));
                jsonObj.put("dataTimeStr", dataTime.format("%Y%m%dT%H%M%S"));
            } else {
                jsonObj.put("dataTimeStr", "00000000T000000");
                jsonObj.put("dataTime", "00-00-00 00:00:00");
            }
            Log.v(TAG, "mSdData.dataTime = " + dataTime);

            if (Double.isNaN(mHR)||Double.isInfinite(mHR)||mHR < 30d)
                mHR = -1d;
            jsonObj.put("hrAlarmActive", mHRAlarmActive);
            jsonObj.put("hrAlarmStanding", mHRAlarmStanding);
            jsonObj.put("adaptiveHrAlarmStanding", mAdaptiveHrAlarmStanding);
            jsonObj.put("averageHrAlarmStanding", mAverageHrAlarmStanding);
            jsonObj.put("hrAlarmStanding", mHRAlarmStanding);
            jsonObj.put("hrThreshMin", mHRThreshMin);
            jsonObj.put("hrThreshMax", mHRThreshMax);
            jsonObj.put("hr", mHR);
            jsonObj.put("adaptiveHrAv", mAdaptiveHrAverage);
            jsonObj.put("averageHrAv", mAverageHrAverage);
            jsonObj.put("o2SatAlarmActive", mO2SatAlarmActive);
            jsonObj.put("o2SatAlarmStanding", mO2SatAlarmStanding);
            jsonObj.put("o2SatThreshMin", mO2SatThreshMin);
            if (Double.isNaN(mO2Sat)||Double.isInfinite(mO2Sat)||mO2Sat < 30d)
                mO2Sat = -1d;
            jsonObj.put("o2Sat", mO2Sat);
            if (Objects.nonNull(mHistoricHrBuf)) {
                if (mHistoricHrBuf.getNumVals()!=0) {
                    jsonObj.put(Constants.GLOBAL_CONSTANTS.heartRateList, Arrays.toString(mHistoricHrBuf.getVals()));
                }
            }
        } catch (JSONException jsonException){
            Log.e(TAG,"toHeartRatesArray(): failure in composing",jsonException);
        }
        retval = jsonObj.toString();
        jsonObj = null;
        arr = null;
        rawArr = null;
        raw3DArr = null;
        return retval;
    }

    public String toDataString(boolean includeRawData) {
        String retval;
        retval = "SdData.toDataString() Output";
        try {

            //if (! includeRawData) mDataType = "data"; datatype set before usage of toDataString
            // at the end of toDataString if includeRawData: set to raw.
            jsonObj = new JSONObject();
            if (dataTime != null) {
                jsonObj.put("dataTime", dataTime.format("%d-%m-%Y %H:%M:%S"));
                jsonObj.put("dataTimeStr", dataTime.format("%Y%m%dT%H%M%S"));
            } else {
                jsonObj.put("dataTimeStr", "00000000T000000");
                jsonObj.put("dataTime", "00-00-00 00:00:00");
            }
            Log.v(TAG, "mSdData.dataTime = " + dataTime);
            jsonObj.put("maxVal", maxVal);
            jsonObj.put("maxFreq", maxFreq);
            jsonObj.put("specPower", specPower);
            jsonObj.put("roiPower", roiPower);
            jsonObj.put("roiRatio", roiRatio);
            jsonObj.put("batteryPc", batteryPc);
            jsonObj.put("serverOk",serverOK);
            jsonObj.put("watchConnected", watchConnected);
            jsonObj.put("watchAppRunning", watchAppRunning);
            jsonObj.put("haveSettings", haveSettings);
            jsonObj.put("alarmState", alarmState);
            jsonObj.put("alarmPhrase", alarmPhrase);
            jsonObj.put("sdMode", mSdMode);
            jsonObj.put("dT", dT);
            jsonObj.put("sampleFreq", mSampleFreq);
            jsonObj.put("analysisPeriod", analysisPeriod);
            if (Double.isNaN(dT)||Double.isInfinite(dT)||dT < 30d)
                dT = analysisPeriod;
            jsonObj.put("dT",dT);
            jsonObj.put("defaultSampleCount", mDefaultSampleCount);
            jsonObj.put("alarmFreqMin", alarmFreqMin);
            jsonObj.put("alarmFreqMax", alarmFreqMax);
            jsonObj.put("alarmThresh", alarmThresh);
            jsonObj.put("alarmRatioThresh", alarmRatioThresh);
            jsonObj.put("hrAlarmActive", mHRAlarmActive);
            jsonObj.put("hrAlarmStanding", mHRAlarmStanding);
            jsonObj.put("adaptiveHrAlarmStanding", mAdaptiveHrAlarmStanding);
            jsonObj.put("averageHrAlarmStanding", mAverageHrAlarmStanding);
            jsonObj.put("hrAlarmStanding", mHRAlarmStanding);
            jsonObj.put("hrThreshMin", mHRThreshMin);
            jsonObj.put("hrThreshMax", mHRThreshMax);
            if (Double.isNaN(mHR)||Double.isInfinite(mHR)||mHR < 30d)
                mHR = -1d;
            jsonObj.put("hr", mHR);
            jsonObj.put("adaptiveHrAv", mAdaptiveHrAverage);
            jsonObj.put("averageHrAv", mAverageHrAverage);
            jsonObj.put("o2SatAlarmActive", mO2SatAlarmActive);
            jsonObj.put("o2SatAlarmStanding", mO2SatAlarmStanding);
            jsonObj.put("o2SatThreshMin", mO2SatThreshMin);
            if (Double.isNaN(mO2Sat)||Double.isInfinite(mO2Sat)||mO2Sat < 30d)
                mO2Sat = -1d;
            jsonObj.put("o2Sat", mO2Sat);
            jsonObj.put("cnnAlarmActive", mCnnAlarmActive);
            jsonObj.put("pSeizure", mPseizure);
            jsonObj.put("sdName", watchSdName);
            jsonObj.put("sdVersion", watchSdVersion);
            jsonObj.put("watchFwVersion", watchFwVersion);
            jsonObj.put("watchPartNo", watchPartNo);
            jsonObj.put("phoneName", phoneName);

            arr = new JSONArray();
            for (int i = 0; i < simpleSpec.length; i++) {
                arr.put(simpleSpec[i]);
            }

            jsonObj.put("simpleSpec", arr);
            if (includeRawData) {
                mDataType = "raw";
                rawArr = new JSONArray(rawData);
//                for (int i = 0; i < rawData.length; i++) {
//                    rawArr.put(rawData[i]);
//                }
                jsonObj.put("data", rawArr);

                raw3DArr = new JSONArray(rawData3D);
                /*for (int i = 0; i < rawData3D.length; i++) {
                    raw3DArr.put(rawData3D[i]);
                }*/
                jsonObj.put("data3D", raw3DArr);

            }
            jsonObj.put("dataType", mDataType);

            retval = jsonObj.toString();
        } catch (Exception ex) {
            Log.e(TAG, "Error Creating Data Object - " + ex.toString(), ex);
            retval = "Error Creating Data Object - " + ex.toString();
        }
        jsonObj = null;
        arr = null;
        rawArr = null;
        raw3DArr = null;

        return (retval);
    }


    public String toCSVString(boolean includeRawData) {
        String retval;
        retval = "";
        if (dataTime != null) {
            retval = dataTime.format("%d-%m-%Y %H:%M:%S");
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
