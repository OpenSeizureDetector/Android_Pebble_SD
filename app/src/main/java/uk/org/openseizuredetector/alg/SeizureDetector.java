package uk.org.openseizuredetector.alg;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

import uk.org.openseizuredetector.data.SdData;

/**
 * SeizureDetector - Coordinates all seizure detection algorithms.
 * Receives SdData, passes it to each active algorithm, and combines results.
 */
public class SeizureDetector {
    private final static String TAG = "SeizureDetector";

    private Context mContext;
    private SharedPreferences mSP;

    // Algorithm instances - only created if algorithm is active
    private SdAlgOsd mSdAlgOsd;
    private SdAlgFlap mSdAlgFlap;
    private SdAlgFall mSdAlgFall;
    private SdAlgNn mSdAlgNn;
    private SdAlgHr mSdAlgHr;

    // Track which algorithms are active
    private boolean mOsdAlarmActive;
    private boolean mFlapAlarmActive;
    private boolean mFallActive;
    private boolean mCnnAlarmActive;
    private boolean mHRAlarmActive;

    // State machine for WARNING/ALARM transitions
    private int mAlarmCount = 0;  // Time in alarm state (seconds)
    private short mWarnTime;
    private short mAlarmTime;
    private short mSamplePeriod;

    public SeizureDetector(Context context) {
        Log.i(TAG, "SeizureDetector Constructor");
        mContext = context;
        mSP = PreferenceManager.getDefaultSharedPreferences(mContext);

        // Load timing parameters and algorithm active flags
        updatePrefs();

        // Instantiate only the algorithms that are active
        if (mOsdAlarmActive) {
            mSdAlgOsd = new SdAlgOsd(mContext);
            Log.i(TAG, "SeizureDetector - SdAlgOsd initialized (ACTIVE)");
        }

        if (mFlapAlarmActive) {
            mSdAlgFlap = new SdAlgFlap(mContext);
            Log.i(TAG, "SeizureDetector - SdAlgFlap initialized (ACTIVE)");
        }

        if (mFallActive) {
            mSdAlgFall = new SdAlgFall(mContext);
            Log.i(TAG, "SeizureDetector - SdAlgFall initialized (ACTIVE)");
        }

        if (mCnnAlarmActive) {
            mSdAlgNn = new SdAlgNn(mContext);
            Log.i(TAG, "SeizureDetector - SdAlgNn initialized (ACTIVE)");
        }

        if (mHRAlarmActive) {
            mSdAlgHr = new SdAlgHr(mContext);
            Log.i(TAG, "SeizureDetector - SdAlgHr initialized (ACTIVE)");
        }

        Log.i(TAG, "SeizureDetector initialized - OsdAlarmActive:" + mOsdAlarmActive +
                " FlapAlarmActive:" + mFlapAlarmActive + " FallActive:" + mFallActive +
                " CnnAlarmActive:" + mCnnAlarmActive + " HRAlarmActive:" + mHRAlarmActive);
    }

    private void updatePrefs() {
        try {
            // Load timing parameters
            String warnTimeStr = mSP.getString("WarnTime", "5");
            mWarnTime = Short.parseShort(warnTimeStr);
            String alarmTimeStr = mSP.getString("AlarmTime", "15");
            mAlarmTime = Short.parseShort(alarmTimeStr);
            String samplePeriodStr = mSP.getString("DataUpdatePeriod", "5");
            mSamplePeriod = Short.parseShort(samplePeriodStr);

            // Load algorithm active flags from preferences (using correct keys from seizure_detector_prefs.xml)
            mOsdAlarmActive = mSP.getBoolean("OsdAlarmActive", true);
            mFlapAlarmActive = mSP.getBoolean("FlapAlarmActive", false);
            mFallActive = mSP.getBoolean("FallActive", false);
            mCnnAlarmActive = mSP.getBoolean("CnnAlarmActive", false);
            mHRAlarmActive = mSP.getBoolean("HRAlarmActive", false);

            Log.v(TAG, "updatePrefs(): mWarnTime=" + mWarnTime +
                    ", mAlarmTime=" + mAlarmTime +
                    ", mSamplePeriod=" + mSamplePeriod);
            Log.v(TAG, "updatePrefs(): OsdAlarmActive=" + mOsdAlarmActive +
                    ", FlapAlarmActive=" + mFlapAlarmActive +
                    ", FallActive=" + mFallActive +
                    ", CnnAlarmActive=" + mCnnAlarmActive +
                    ", HRAlarmActive=" + mHRAlarmActive);
        } catch (Exception ex) {
            Log.e(TAG, "updatePrefs() - Problem parsing preferences: " + ex.toString());
            mWarnTime = 5;
            mAlarmTime = 15;
            mSamplePeriod = 5;
            // Default to OSD only if preferences can't be read
            mOsdAlarmActive = true;
            mFlapAlarmActive = false;
            mFallActive = false;
            mCnnAlarmActive = false;
            mHRAlarmActive = false;
        }
    }

    /**
     * Process SdData through all active algorithms and determine overall alarm state.
     * Only algorithms that were initialized as active are processed.
     * Algorithms are OR'd together - if any algorithm reports ALARM, overall state is ALARM.
     *
     * @param sdData - The seizure detector data to analyze
     * @return overall alarm state (0=OK, 1=WARNING, 2=ALARM, etc.)
     */
    public int processData(SdData sdData) {
        Log.v(TAG, "processData()");

        // Store which algorithms are active in SdData for logging/UI purposes
        sdData.mOsdAlarmActive = mOsdAlarmActive;
        sdData.mFlapAlarmActive = mFlapAlarmActive;
        sdData.mCnnAlarmActive = mCnnAlarmActive;
        sdData.mHRAlarmActive = mHRAlarmActive;
        sdData.mFallActive = mFallActive;

        // Clear alarm cause before processing
        sdData.alarmCause = "";

        boolean anyAlarm = false;
        List<String> alarmCauses = new ArrayList<>();

        // Process OSD algorithm if initialized (active)
        if (mSdAlgOsd != null) {
            Log.v(TAG, "processData() - Running SdAlgOsd");
            int osdResult = mSdAlgOsd.processSdData(sdData);
            if (osdResult == 2) {
                anyAlarm = true;
                alarmCauses.add(mSdAlgOsd.getAlarmCause());
            }
        }

        // Process FLAP algorithm if initialized (active)
        if (mSdAlgFlap != null) {
            Log.v(TAG, "processData() - Running SdAlgFlap");
            int flapResult = mSdAlgFlap.processSdData(sdData);
            if (flapResult == 2) {
                anyAlarm = true;
                alarmCauses.add(mSdAlgFlap.getAlarmCause());
            }
        }

        // Process FALL algorithm if initialized (active)
        if (mSdAlgFall != null) {
            Log.v(TAG, "processData() - Running SdAlgFall");
            int fallResult = mSdAlgFall.processSdData(sdData);
            if (fallResult == 2) {
                anyAlarm = true;
                alarmCauses.add(mSdAlgFall.getAlarmCause());
                sdData.fallAlarmStanding = true;
            }
        }

        // Process NN (ML) algorithm if initialized (active)
        if (mSdAlgNn != null) {
            Log.v(TAG, "processData() - Running SdAlgNn");
            int nnResult = mSdAlgNn.processSdData(sdData);
            if (nnResult == 2) {
                anyAlarm = true;
                alarmCauses.add(mSdAlgNn.getAlarmCause());
            }
        }

        // Process HR algorithm if initialized (active)
        if (mSdAlgHr != null) {
            Log.v(TAG, "processData() - Running SdAlgHr");
            int hrResult = mSdAlgHr.processSdData(sdData);
            if (hrResult == 2) {
                anyAlarm = true;
                // HR algorithm sets its own alarm cause strings
                if (sdData.mHRAlarmStanding) {
                    alarmCauses.add("HR");
                }
                if (sdData.mAdaptiveHrAlarmStanding) {
                    alarmCauses.add("HR_ADAPT");
                }
                if (sdData.mAverageHrAlarmStanding) {
                    alarmCauses.add("HR_AVG");
                }
            }
        }

        // Build alarm cause string
        for (String cause : alarmCauses) {
            sdData.alarmCause += cause + " ";
        }

        // Apply state machine for WARNING/ALARM transitions
        int alarmState;
        if (anyAlarm) {
            mAlarmCount += mSamplePeriod;
            if (mAlarmCount > mAlarmTime) {
                // Full alarm
                alarmState = 2;
                sdData.alarmStanding = true;
            } else if (mAlarmCount > mWarnTime) {
                // Warning
                alarmState = 1;
            } else {
                // Not yet warning
                alarmState = 0;
            }
        } else {
            // If we are not in an ALARM state, revert back to WARNING, otherwise
            // revert back to OK.
            if (sdData.alarmState == 2) {
                // Revert to warning
                alarmState = 1;
                mAlarmCount = mWarnTime + 1;  // Pretend we have only just entered warning state
            } else {
                // Revert to OK
                alarmState = 0;
                mAlarmCount = 0;
                sdData.alarmStanding = false;
            }
        }

        Log.v(TAG, "processData(): anyAlarm=" + anyAlarm +
                ", alarmCause=" + sdData.alarmCause +
                ", alarmState=" + alarmState +
                ", alarmCount=" + mAlarmCount);

        return alarmState;
    }

    /**
     * Reset the alarm state machine (e.g., when user accepts alarm)
     */
    public void resetAlarmState() {
        Log.d(TAG, "resetAlarmState()");
        mAlarmCount = 0;
    }

    /**
     * Close and cleanup all algorithm instances
     */
    public void close() {
        Log.d(TAG, "close()");
        if (mSdAlgOsd != null) mSdAlgOsd.close();
        if (mSdAlgFlap != null) mSdAlgFlap.close();
        if (mSdAlgFall != null) mSdAlgFall.close();
        if (mSdAlgNn != null) mSdAlgNn.close();
        if (mSdAlgHr != null) mSdAlgHr.close();
    }
}
