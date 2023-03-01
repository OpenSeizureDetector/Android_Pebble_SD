package uk.org.openseizuredetector;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;

public class SdAlgHr {
    private final static String TAG = "SdAlgHr";
    private Context mContext;
    private OsdUtil mUtil;
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


    public SdAlgHr(Context context) {
        Log.d(TAG, "SdAlgHr Constructor");
        mContext = context;
        mUtil = new OsdUtil(mContext, new Handler());
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
            mUtil.writeToSysLogFile(TAG+".readDoublePref() - Problem with  preference!");
            mUtil.showToast(TAG+":"+mContext.getString(R.string.problem_parsing_preferences));
        }
        return retVal;
    }

    private void updatePrefs() {
        /**
         * updatePrefs() - update basic settings from the SharedPreferences
         * - defined in res/xml/prefs.xml
         */
        Log.i(TAG, "updatePrefs()");
        mUtil.writeToSysLogFile(TAG+".updatePrefs()");

        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(mContext);
        mSimpleHrAlarmActive = SP.getBoolean("HRAlarmActive", false);
        mSimpleHrAlarmThreshMin = readDoublePref(SP, "HRThreshMin", "20");
        mSimpleHrAlarmThreshMax = readDoublePref(SP, "HRThreshMax", "150");


        mAdaptiveHrAlarmActive = SP.getBoolean("HRAdaptiveAlarmActive", false);
        mAdaptiveHrAlarmWindowSecs = readDoublePref(SP, "HRAdaptiveAlarmWindowSecs", "30");
        mAdaptiveHrAlarmWindowDp = (int)Math.round(mAdaptiveHrAlarmWindowSecs/5.0);
        mAdaptiveHrAlarmThresh = readDoublePref(SP, "HRAdaptiveAlarmThresh", "20");

        mAverageHrAlarmActive = SP.getBoolean("HRAverageAlarmActive", false);
        mAverageHrAlarmWindowSecs = readDoublePref(SP, "HRAverageAlarmWindowSecs", "120");
        mAverageHrAlarmWindowDp = (int)Math.round(mAverageHrAlarmWindowSecs/5.0);
        mAverageHrAlarmThreshMin = readDoublePref(SP, "HRAverageAlarmThreshMin", "40");
        mAverageHrAlarmThreshMax = readDoublePref(SP, "HRAverageAlarmThreshMax", "120");

    }
}
