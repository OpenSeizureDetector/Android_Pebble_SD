package uk.org.openseizuredetector.alg;
import uk.org.openseizuredetector.R;

import uk.org.openseizuredetector.data.SdData;
import uk.org.openseizuredetector.utils.CircBuf;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import androidx.preference.PreferenceManager;
import uk.org.openseizuredetector.data.logging.Log;

import java.util.ArrayList;
import uk.org.openseizuredetector.utils.PreferenceUtils;

public class SdAlgHr extends SdAlgBase {
    private final static String TAG = "SdAlgHr";
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
        super(context);
        Log.i(TAG, "SdAlgHr Constructor");
        updatePrefs();
        mAdaptiveHrBuff = new CircBuf(mAdaptiveHrAlarmWindowDp, -1.0);
        mAverageHrBuff = new CircBuf(mAverageHrAlarmWindowDp, -1.0);
        // 10 minute period (at 5 second intervals) to match ML seizure probability history
        mHrHist = new CircBuf((int) (10 * 60 / 5), -1);
    }

    @Override
    public void close() {
        Log.d(TAG, "close()");
        super.close();
    }

    @Override
    public String getAlarmCause() {
        // This will be determined in processSdData
        return "HR";
    }

    @Override
    public int processSdData(SdData sdData) {
        // ...existing code...

        Log.v(TAG, "processSdData()");
        ArrayList<Boolean> checkResults;
        checkResults = checkHr(sdData.mHR);

        // Populate mSdData so that the heart rate data is logged and is accessible to user interface components.
        sdData.mAdaptiveHrAverage = getAdaptiveHrAverage();
        sdData.mAverageHrAverage = getAverageHrAverage();
        sdData.mAdaptiveHrBuf = getAdaptiveHrBuff();
        sdData.mAverageHrBuf = getAverageHrBuff();

        /* Check for heart rate fault condition */
        if (mSimpleHrAlarmActive) {
            if (sdData.mHR < 0) {
                if (sdData.mHRNullAsAlarm) {
                    Log.i(TAG, "Heart Rate Null - Alarming");
                    sdData.mHRFaultStanding = false;
                    sdData.mHRAlarmStanding = true;
                    sdData.mAdaptiveHrAlarmStanding = false;
                    sdData.mAverageHrAlarmStanding = false;
                    return 2;  // ALARM
                } else {
                    Log.i(TAG, "Heart Rate Fault (HR<0)");
                    sdData.mHRFaultStanding = true;
                    sdData.mHRAlarmStanding = false;
                    sdData.mAdaptiveHrAlarmStanding = false;
                    sdData.mAverageHrAlarmStanding = false;
                    return 0;  // Fault but not alarming
                }
            } else {
                sdData.mHRFaultStanding = false;
                sdData.mHRAlarmStanding = checkResults.get(0);
                sdData.mAdaptiveHrAlarmStanding = checkResults.get(1);
                sdData.mAverageHrAlarmStanding = checkResults.get(2);

                // Show an ALARM state if any of the HR alarms is standing.
                if (sdData.mHRAlarmStanding | sdData.mAdaptiveHrAlarmStanding | sdData.mAverageHrAlarmStanding) {
                    return 2;  // ALARM
                }
            }
        } else {
            sdData.mHRFaultStanding = false;
            sdData.mHRAlarmStanding = false;
            sdData.mAdaptiveHrAlarmStanding = false;
            sdData.mAverageHrAlarmStanding = false;
        }

        return 0;  // OK
    }

    public float getAlarmState(SdData sdData) {
        return (0);
    }


    private void updatePrefs() {
        /**
         * updatePrefs() - update basic settings from the SharedPreferences
         * - defined in res/xml/prefs.xml
         */
        Log.i(TAG, "updatePrefs()");

        mSimpleHrAlarmActive = PreferenceUtils.getBooleanFromXml(mSP, "HRAlarmActive");
        mSimpleHrAlarmThreshMin = readDoublePref("HRThreshMin", "SET_FROM_XML");
        mSimpleHrAlarmThreshMax = readDoublePref("HRThreshMax", "SET_FROM_XML");
        Log.d(TAG, "updatePrefs(): mSimpleHrAlarmActive=" + mSimpleHrAlarmActive);
        Log.d(TAG, "updatePrefs(): mSimpleHrAlarmThreshMin=" + mSimpleHrAlarmThreshMin);
        Log.d(TAG, "updatePrefs(): mSimpleHrAlarmThreshMax=" + mSimpleHrAlarmThreshMax);

        mAdaptiveHrAlarmActive = PreferenceUtils.getBooleanFromXml(mSP, "HRAdaptiveAlarmActive");
        mAdaptiveHrAlarmWindowSecs = readDoublePref("HRAdaptiveAlarmWindowSecs", "SET_FROM_XML");
        mAdaptiveHrAlarmWindowDp = (int) Math.round(mAdaptiveHrAlarmWindowSecs / 5.0);
        mAdaptiveHrAlarmThresh = readDoublePref("HRAdaptiveAlarmThresh", "SET_FROM_XML");
        Log.d(TAG, "updatePrefs(): mAdaptiveHrAlarmActive=" + mAdaptiveHrAlarmActive);
        Log.d(TAG, "updatePrefs(): mAdaptiveHrWindowSecs=" + mAdaptiveHrAlarmWindowSecs);
        Log.d(TAG, "updatePrefs(): mAdaptiveHrWindowDp=" + mAdaptiveHrAlarmWindowDp);
        Log.d(TAG, "updatePrefs(): mAdaptiveHrAlarmThresh=" + mAdaptiveHrAlarmThresh);

        mAverageHrAlarmActive = PreferenceUtils.getBooleanFromXml(mSP, "HRAverageAlarmActive");
        mAverageHrAlarmWindowSecs = readDoublePref("HRAverageAlarmWindowSecs", "SET_FROM_XML");
        mAverageHrAlarmWindowDp = (int) Math.round(mAverageHrAlarmWindowSecs / 5.0);
        mAverageHrAlarmThreshMin = readDoublePref("HRAverageAlarmThreshMin", "SET_FROM_XML");
        mAverageHrAlarmThreshMax = readDoublePref("HRAverageAlarmThreshMax", "SET_FROM_XML");
        Log.d(TAG, "updatePrefs(): mAverageHrAlarmActive=" + mAverageHrAlarmActive);
        Log.d(TAG, "updatePrefs(): mAverageHrAlarmWindowSecs=" + mAverageHrAlarmWindowSecs);
        Log.d(TAG, "updatePrefs(): mAverageHrAlarmWindowDp=" + mAverageHrAlarmWindowDp);
        Log.d(TAG, "updatePrefs(): mAverageHrAlarmThreshMin=" + mAverageHrAlarmThreshMin);
        Log.d(TAG, "updatePrefs(): mAverageHrAlarmThreshMax=" + mAverageHrAlarmThreshMax);

    }


    private boolean checkSimpleHr(double hrVal) {
        /**
         * Check heart rate value against simple thresholds
         */
        boolean retVal = false;
        if (mSimpleHrAlarmActive) {
            if ((hrVal > mSimpleHrAlarmThreshMax)
                    || (hrVal < mSimpleHrAlarmThreshMin)) {
                retVal = true;
            }
        }
        return (retVal);
    }

    /**
     * Returns the average heart rate being used by the Adaptive heart rate algorithm
     *
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

    public CircBuf getHrHistBuff() {
        return mHrHist;
    }

    /**
     * Returns the average heart rate being used by the Average heart rate algorithm
     *
     * @return Average Heart rate in bpm.
     */
    public double getAverageHrAverage() {
        return mAverageHrBuff.getAverageVal();
    }


    private boolean checkAdaptiveHr(double hrVal) {
        boolean retVal;
        retVal = false;
        
        if (mAdaptiveHrAlarmActive) {
            double hrThreshMin;
            double hrThreshMax;
            double avHr = getAdaptiveHrAverage();
            hrThreshMin = avHr - mAdaptiveHrAlarmThresh;
            hrThreshMax = avHr + mAdaptiveHrAlarmThresh;
    
        
            if (hrVal < hrThreshMin) {
                retVal = true;
            }
            if (hrVal > hrThreshMax) {
                retVal = true;
            }
            Log.d(TAG, "checkAdaptiveHr() - hrVal=" + hrVal + ", avHr=" + avHr + ", thresholds=(" + hrThreshMin + ", " + hrThreshMax + "): Alarm=" + retVal);
        }
        return (retVal);
    }

    private boolean checkAverageHr(double hrVal) {
        boolean retVal;
        retVal = false;
        if (mAverageHrAlarmActive) {
            double avHr = getAverageHrAverage();
            if (avHr < mAverageHrAlarmThreshMin) {
                retVal = true;
            }
            if (avHr > mAverageHrAlarmThreshMax) {
                retVal = true;
            }
            Log.d(TAG, "checkAverageHr() - hrVal=" + hrVal + ", avHr=" + avHr + ", thresholds=(" + mAverageHrAlarmThreshMin + ", " + mAverageHrAlarmThreshMin + "): Alarm=" + retVal);
        }
        return (retVal);
    }


    public ArrayList<Boolean> checkHr(double hrVal) {
        /**
         * Checks the current Heart Rate reading hrVal against the
         * three possible heart rate alarm algorithms (simple, adaptive, average)
         * and returns an ArrayList of the alarm status of each algorithm in the above order.
         * true=ALARM, false=OK.
         */
        Log.v(TAG, "checkHr(" + hrVal + ")");
        mAdaptiveHrBuff.add(hrVal);
        mAverageHrBuff.add(hrVal);
        mHrHist.add(hrVal);
        ArrayList<Boolean> retVal = new ArrayList<Boolean>();
        retVal.add(checkSimpleHr(hrVal));
        retVal.add(checkAdaptiveHr(hrVal));
        retVal.add(checkAverageHr(hrVal));
        
        return (retVal);
    }

}
