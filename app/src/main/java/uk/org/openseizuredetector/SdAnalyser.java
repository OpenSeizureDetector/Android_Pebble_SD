/*
  Android_Pebble_SD - a simple accelerometer based seizure detector that runs on a
  Pebble smart watch (http://getpebble.com).

  See http://openseizuredetector.org for more information.

  Copyright Graham Jones, 2015, 2016, 2017, 2018, 2019.

  This file is part of android_pebble_sd.

  Android_pebble_sd is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  Android_pebble_sd is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with android_pebble_sd.  If not, see <http://www.gnu.org/licenses/>.

*/
package uk.org.openseizuredetector;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jtransforms.fft.DoubleFFT_1D;

/**
 * SdAnalyser implements the seizure detection algorithm on an Android Phone - it is used by SdDatasourceGarmin
 */
public class SdAnalyser {
    private String TAG = "SdAnalyser";
    private double mSampleFreq;
    private double mAlarmFreqMin;
    private double mAlarmFreqMax;
    private double mSamplePeriod;
    private double mWarnTime;
    private double mAlarmTime;
    private double mAlarmThresh;
    private double mAlarmRatioThresh;
    private double mFreqRes;
    private int mAlarmCount;
    private double mFreqCutoff;
    private int mNSamp;

    double roiPower;
    double specPower;
    double roiRatio;

    SdAnalyser(double sampleFreq,
               double alarmFreqMin,
               double alarmFreqMax,
               double samplePeriod,
               double warnTime,
               double alarmThresh,
               double alarmRatioThresh) {
        mSampleFreq = sampleFreq;
        mAlarmFreqMin = alarmFreqMin;
        mAlarmFreqMax = alarmFreqMax;
        mSamplePeriod = samplePeriod;
        mWarnTime = warnTime;
        mAlarmThresh = alarmThresh;
        mAlarmRatioThresh = alarmRatioThresh;

        mFreqRes = 1.0 / mSamplePeriod;
        mFreqCutoff = mSampleFreq / 2.0;
        mNSamp = (int)(mSamplePeriod * mSampleFreq);
    }

    int freq2fftBin(double freq) {
        int n = (int)(freq/mFreqRes);
        return(n);
    }

    /**
     * doAnalysis() - analyse the data if the accelerometer data array mAccData
     * and populate the output data structure mSdData
     */
    void calculateSpectralPowers(double[] rawData) {
        // Set the frequency bounds for the analysis in fft output bin numbers.
        int nMin = freq2fftBin(mAlarmFreqMin);
        int nMax = freq2fftBin(mAlarmFreqMax);
        int nFreqCutoff = freq2fftBin(mFreqCutoff);

        DoubleFFT_1D fftDo = new DoubleFFT_1D(mNSamp);
        double[] fft = new double[mNSamp * 2];
        System.arraycopy(rawData, 0, fft, 0, mNSamp);
        fftDo.realForward(fft);

        // Calculate the whole spectrum power (well a value equivalent to it that avoids square root calculations
        // and zero any readings that are above the frequency cutoff.
        double specPower = 0;
        for (int i = 1; i < mNSamp / 2; i++) {
            if (i <= nFreqCutoff) {
                specPower = specPower + getMagnitude(fft, i);
            } else {
                fft[2 * i] = 0.;
                fft[2 * i + 1] = 0.;
            }
        }
        specPower = specPower / (mNSamp / 2);

        // Calculate the Region of Interest power and power ratio.
        double roiPower = 0;
        for (int i = nMin; i < nMax; i++) {
            roiPower = roiPower + getMagnitude(fft, i);
        }
        roiPower = roiPower / (nMax - nMin);
        double roiRatio = 10 * roiPower / specPower;

    }

    /**
     * Calculate the magnitude of entry i in the fft array fft
     *
     * @param fft
     * @param i
     * @return magnitude ( Re*Re + Im*Im )
     */
    double getMagnitude(double[] fft, int i) {
        double mag;
        mag = (fft[2 * i] * fft[2 * i] + fft[2 * i + 1] * fft[2 * i + 1]);
        return mag;
    }

    // Force the data stored in this datasource to update in line with the JSON string encoded data provided.
    // Used by webServer to update the GarminDatasource.
    // Returns a message string that is passed back to the watch.
    public String updateFromJSON(String jsonStr) {
        String retVal = "undefined";
        Log.v(TAG,"updateFromJSON - "+jsonStr);

        try {
            JSONObject mainObject = new JSONObject(jsonStr);
            //JSONObject dataObject = mainObject.getJSONObject("dataObj");
            JSONObject dataObject = mainObject;
            String dataTypeStr = dataObject.getString("dataType");
            Log.v(TAG,"updateFromJSON - dataType="+dataTypeStr);
            if (dataTypeStr.equals("raw")) {
                Log.v(TAG,"updateFromJSON - processing raw data");
                try {
                    mSdData.mHR = dataObject.getDouble("HR");
                } catch (JSONException e) {
                    // if we get 'null' HR (For example if the heart rate is not working)
                    mSdData.mHR = -1;
                }
                try {
                    mMute = dataObject.getInt("Mute");
                } catch (JSONException e) {
                    // if we get 'null' HR (For example if the heart rate is not working)
                    mMute = 0;
                }
                JSONArray accelVals = dataObject.getJSONArray("data");
                Log.v(TAG, "Received " + accelVals.length() + " acceleration values");
                int i;
                for (i = 0; i < accelVals.length(); i++) {
                    mSdData.rawData[i] = accelVals.getInt(i);
                }
                mSdData.mNsamp = accelVals.length();
                //mNSamp = accelVals.length();
                mWatchAppRunningCheck = true;
                doAnalysis();
                if (mSdData.haveSettings == false) {
                    retVal = "sendSettings";
                } else {
                    retVal = "OK";
                }
            } else if (dataTypeStr.equals("settings")){
                Log.v(TAG,"updateFromJSON - processing settings");
                mSamplePeriod = (short)dataObject.getInt("analysisPeriod");
                mSampleFreq = (short)dataObject.getInt("sampleFreq");
                mSdData.batteryPc = (short)dataObject.getInt("battery");
                Log.v(TAG,"updateFromJSON - mSamplePeriod="+mSamplePeriod+" mSampleFreq="+mSampleFreq);
                mSdData.haveSettings = true;
                mSdData.mSampleFreq = mSampleFreq;
                mWatchAppRunningCheck = true;
                retVal = "OK";
            } else {
                Log.e(TAG,"updateFromJSON - unrecognised dataType "+dataTypeStr);
                retVal = "ERROR";
            }
        } catch (Exception e) {
            Log.e(TAG,"updateFromJSON - Error Parsing JSON String - "+e.toString());
            e.printStackTrace();
            retVal = "ERROR";
        }
        return(retVal);
    }
}
