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

import org.json.JSONObject;
import org.json.JSONArray;

/* based on http://stackoverflow.com/questions/2139134/how-to-send-an-object-from-one-android-activity-to-another-using-intents */

public class SdData implements Parcelable {
    private final static String TAG = "SdData";
    private final static int N_RAW_DATA = 500;  // 5 seconds at 100 Hz.
    /* Analysis settings */
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
    public long batteryPc;

    /* Heart Rate Alarm Settings */
    public boolean mHRAlarmActive = false;
    public boolean mHRNullAsAlarm = false;
    public double mHRThreshMin = 40.0;
    public double mHRThreshMax = 150.0;
    public double rawData[];
    int mNsamp = 0;

    /* Analysis results */
    public Time dataTime = null;
    public long alarmState;
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
    public double mHR = 0;

    public SdData() {
        simpleSpec = new int[10];
        rawData = new double[N_RAW_DATA];
        dataTime = new Time(Time.getCurrentTimezone());
    }

    /*
     * Intialise this SdData object from a JSON String
     */
    public boolean fromJSON(String jsonStr) {
        Log.v(TAG, "fromJSON() - parsing jsonString - " + jsonStr);
        try {
            JSONObject jo = new JSONObject(jsonStr);
            Log.v(TAG, "fromJSON(): jo = " + jo.toString());
            Log.v(TAG, "fromJSON(): dataTimeStr=" + jo.optString("dataTimeStr"));
            //Calendar cal = Calendar.getInstance();
            //SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddTHHmmss", Locale.UK);
            //cal.setTime(sdf.parse(jo.optString("dataTimeStr")));
            //dataTime = cal.getTime();
            // FIXME - this doesn't work!!!
            dataTime.setToNow();
            Log.v(TAG, "fromJSON(): dataTime = " + dataTime.toString());
            maxVal = jo.optInt("maxVal");
            maxFreq = jo.optInt("maxFreq");
            specPower = jo.optInt("specPower");
            roiPower = jo.optInt("roiPower");
            batteryPc = jo.optInt("batteryPc");
            watchConnected = jo.optBoolean("watchConnected");
            watchAppRunning = jo.optBoolean("watchAppRunning");
            alarmState = jo.optInt("alarmState");
            alarmPhrase = jo.optString("alarmPhrase");
            alarmThresh = jo.optInt("alarmThresh");
            alarmRatioThresh = jo.optInt("alarmRatioThresh");
            mHRAlarmActive=jo.optBoolean("hrAlarmActive");
            mHRAlarmStanding = jo.optBoolean("hrAlarmStanding");
            mHRThreshMin = jo.optDouble("hrThreshMin");
            mHRThreshMax = jo.optDouble("hrThreshMax");
            mHR = jo.optDouble("hr");
            if (mHR>=0.0) {
                mHRAlarmActive = true;
            }
            JSONArray specArr = jo.optJSONArray("simpleSpec");
            for (int i = 0; i < specArr.length(); i++) {
                simpleSpec[i] = specArr.optInt(i);
            }
            haveData = true;
            return true;
        } catch (Exception e) {
            Log.v(TAG, "fromJSON() - error parsing result");
            haveData = false;
            return false;
        }
    }

    @Override
    public String toString() {
        return toDataString(false);
    }

    public String toDataString(boolean includeRawData) {
        String retval;
        retval = "SdData.toDataString() Output";
        try {
            JSONObject jsonObj = new JSONObject();
            if (dataTime != null) {
                jsonObj.put("dataTime", dataTime.format("%d-%m-%Y %H:%M:%S"));
                jsonObj.put("dataTimeStr", dataTime.format("%Y%m%dT%H%M%S"));
            }else{
                jsonObj.put("dataTimeStr", "00000000T000000");
                jsonObj.put("dataTime", "00-00-00 00:00:00");
            }
            Log.v(TAG, "mSdData.dataTime = " + dataTime);
            jsonObj.put("maxVal", maxVal);
            jsonObj.put("maxFreq", maxFreq);
            jsonObj.put("specPower", specPower);
            jsonObj.put("roiPower", roiPower);
            jsonObj.put("batteryPc", batteryPc);
            jsonObj.put("watchConnected", watchConnected);
            jsonObj.put("watchAppRunning", watchAppRunning);
            jsonObj.put("haveSettings", haveSettings);
            jsonObj.put("alarmState", alarmState);
            jsonObj.put("alarmPhrase", alarmPhrase);
            jsonObj.put("sdMode",mSdMode);
            jsonObj.put("sampleFreq",mSampleFreq);
            jsonObj.put("analysisPeriod",analysisPeriod);
            jsonObj.put("alarmFreqMin",alarmFreqMin);
            jsonObj.put("alarmFreqMax",alarmFreqMax);
            jsonObj.put("alarmThresh", alarmThresh);
            jsonObj.put("alarmRatioThresh", alarmRatioThresh);
            jsonObj.put("hrAlarmActive", mHRAlarmActive);
            jsonObj.put("hrAlarmStanding", mHRAlarmStanding);
            jsonObj.put("hrThreshMin",mHRThreshMin);
            jsonObj.put("hrThreshMax", mHRThreshMax);
            jsonObj.put("hr",mHR);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < simpleSpec.length; i++) {
                arr.put(simpleSpec[i]);
            }
            jsonObj.put("simpleSpec", arr);
            if (includeRawData) {
                JSONArray rawArr = new JSONArray();
                for (int i = 0; i< rawData.length;i++) {
                    rawArr.put(rawData[i]);
                }
            }

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
        if (dataTime != null) {
            retval = dataTime.format("%d-%m-%Y %H:%M:%S");
        }else{
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
        if (includeRawData) {
            for (int i = 0; i< mNsamp;i++) {
                retval = retval + ", " + rawData[i];
            }
        }
        return(retval);
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
