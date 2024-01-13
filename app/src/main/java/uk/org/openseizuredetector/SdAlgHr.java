package uk.org.openseizuredetector;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;

import java.util.ArrayList;

public class SdAlgHr {
    private final static String TAG = "SdAlgHr";
    private Context mContext;
    private boolean mSimpleHrAlarmActive;
    private double mSimpleHrAlarmThreshMin;
    private double mSimpleHrAlarmThreshMax;

    private boolean mAdaptiveHrAlarmActive;
    private double mAdaptiveHrAlarmWindowSecs;
    private int mAdaptiveHrAlarmWindowDp;
    private double mAdaptiveHrAlarmThresh;
    private boolean mAverageHrAlarmActive;
    private double mAverageHrAlarmWindowSecs;
    private int mAverageHrAlarmWindowDp;
    private double mAverageHrAlarmThreshMin;
    private double mAverageHrAlarmThreshMax;

    private CircBuf mAdaptiveHrBuff;
    private CircBuf mAverageHrBuff;
    private CircBuf mHrHist;

    public SdAlgHr(Context context) {
        Log.i(TAG, "SdAlgHr Constructor");
        mContext = context;
        updatePrefs();
        mAdaptiveHrBuff = new CircBuf(mAdaptiveHrAlarmWindowDp, -1.0);
        mAverageHrBuff = new CircBuf(mAverageHrAlarmWindowDp, -1.0);
        // FIXME - this is a hard coded 3 hour period (at 5 second intervals)
        mHrHist = new CircBuf((int)(3 * 3600 / 5), -1);
    }

    public void close() {
        Log.d(TAG, "close()");
    }

    public float getAlarmState(SdData sdData) {
        return (0);
    }

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

    private void updatePrefs() {
        /**
         * updatePrefs() - update basic settings from the SharedPreferences
         * - defined in res/xml/prefs.xml
         */
        Log.i(TAG, "updatePrefs()");

        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(mContext);
        mSimpleHrAlarmActive = SP.getBoolean("HRAlarmActive", false);
        mSimpleHrAlarmThreshMin = readDoublePref(SP, "HRThreshMin", "20");
        mSimpleHrAlarmThreshMax = readDoublePref(SP, "HRThreshMax", "150");
        Log.d(TAG,"updatePrefs(): mSimpleHrAlarmActive="+mSimpleHrAlarmActive);
        Log.d(TAG,"updatePrefs(): mSimpleHrAlarmThreshMin="+mSimpleHrAlarmThreshMin);
        Log.d(TAG,"updatePrefs(): mSimpleHrAlarmThreshMax="+mSimpleHrAlarmThreshMax);

        mAdaptiveHrAlarmActive = SP.getBoolean("HRAdaptiveAlarmActive", false);
        mAdaptiveHrAlarmWindowSecs = readDoublePref(SP, "HRAdaptiveAlarmWindowSecs", "30");
        mAdaptiveHrAlarmWindowDp = (int)Math.round(mAdaptiveHrAlarmWindowSecs/5.0);
        mAdaptiveHrAlarmThresh = readDoublePref(SP, "HRAdaptiveAlarmThresh", "20");
        Log.d(TAG,"updatePrefs(): mAdaptiveHrAlarmActive="+mAdaptiveHrAlarmActive);
        Log.d(TAG,"updatePrefs(): mAdaptiveHrWindowSecs="+mAdaptiveHrAlarmWindowSecs);
        Log.d(TAG,"updatePrefs(): mAdaptiveHrWindowDp="+mAdaptiveHrAlarmWindowDp);
        Log.d(TAG,"updatePrefs(): mAdaptiveHrAlarmThresh="+mAdaptiveHrAlarmThresh);

        mAverageHrAlarmActive = SP.getBoolean("HRAverageAlarmActive", false);
        mAverageHrAlarmWindowSecs = readDoublePref(SP, "HRAverageAlarmWindowSecs", "120");
        mAverageHrAlarmWindowDp = (int)Math.round(mAverageHrAlarmWindowSecs/5.0);
        mAverageHrAlarmThreshMin = readDoublePref(SP, "HRAverageAlarmThreshMin", "40");
        mAverageHrAlarmThreshMax = readDoublePref(SP, "HRAverageAlarmThreshMax", "120");
        Log.d(TAG,"updatePrefs(): mAverageHrAlarmActive="+mAverageHrAlarmActive);
        Log.d(TAG,"updatePrefs(): mAverageHrAlarmWindowSecs="+mAverageHrAlarmWindowSecs);
        Log.d(TAG,"updatePrefs(): mAverageHrAlarmWindowDp="+mAverageHrAlarmWindowDp);
        Log.d(TAG,"updatePrefs(): mAverageHrAlarmThreshMin="+mAverageHrAlarmThreshMin);
        Log.d(TAG,"updatePrefs(): mAverageHrAlarmThreshMax="+mAverageHrAlarmThreshMax);

    }



    private boolean checkSimpleHr(double hrVal) {
        /**
         * Check heart rate value against simple thresholds
         */
        boolean retVal = false;
        if (mSimpleHrAlarmActive) {
            if ((hrVal > mSimpleHrAlarmThreshMax)
                    || (hrVal <mSimpleHrAlarmThreshMin)) {
                retVal = true;
            }
        }
        return(retVal);
    }

    /**
     * Returns the average heart rate being used by the Adaptive heart rate algorithm
     * @return Average Heart reate in bpm.
     */
    public double getAdaptiveHrAverage() {
        return mAdaptiveHrBuff.getAverageVal();
    }

    public CircBuf getAverageHrBuff() {
        return mAverageHrBuff;
    }

    public CircBuf getAdaptiveHrBuff() {
        return mAdaptiveHrBuff;
    }

    public CircBuf getHrHistBuff() { return mHrHist; }

    /**
     * Returns the average heart rate being used by the Average heart rate algorithm
     * @return Average Heart rate in bpm.
     */
    public double getAverageHrAverage() {
        return mAverageHrBuff.getAverageVal();
    }


    private boolean checkAdaptiveHr(double hrVal) {
        boolean retVal;
        double hrThreshMin;
        double hrThreshMax;
        double avHr = getAdaptiveHrAverage();
        hrThreshMin = avHr - mAdaptiveHrAlarmThresh;
        hrThreshMax = avHr + mAdaptiveHrAlarmThresh;

        retVal = false;
        if (hrVal < hrThreshMin) {
            retVal = true;
        }
        if (hrVal > hrThreshMax) {
            retVal = true;
        }
        Log.d(TAG, "checkAdaptiveHr() - hrVal="+hrVal+", avHr="+avHr+", thresholds=("+hrThreshMin+", "+hrThreshMax+"): Alarm="+retVal);

        return(retVal);
    }

    private boolean checkAverageHr(double hrVal) {
        boolean retVal;
        double avHr = getAverageHrAverage();

        retVal = false;
        if (avHr < mAverageHrAlarmThreshMin) {
            retVal = true;
        }
        if (avHr > mAverageHrAlarmThreshMax) {
            retVal = true;
        }
        Log.d(TAG, "checkAverageHr() - hrVal="+hrVal+", avHr="+avHr+", thresholds=("+mAverageHrAlarmThreshMin+", "+mAverageHrAlarmThreshMin+"): Alarm="+retVal);
        return(retVal);
    }



    public ArrayList<Boolean> checkHr(double hrVal) {
        /**
         * Checks the current Heart Rate reading hrVal against the
         * three possible heart rate alarm algorithms (simple, adaptive, average)
         * and returns an ArrayList of the alarm status of each algorithm in the above order.
         * true=ALARM, false=OK.
         */
        Log.v(TAG, "checkHr("+hrVal+")");
        mAdaptiveHrBuff.add(hrVal);
        mAverageHrBuff.add(hrVal);
        mHrHist.add(hrVal);
        ArrayList<Boolean> retVal = new ArrayList<Boolean>();
        retVal.add(checkSimpleHr(hrVal));
        retVal.add(checkAdaptiveHr(hrVal));
        retVal.add(checkAverageHr(hrVal));
        return(retVal);
    }

}
