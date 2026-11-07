package uk.org.openseizuredetector.alg;

import android.content.Context;
import android.content.SharedPreferences;
import uk.org.openseizuredetector.data.logging.Log;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

import uk.org.openseizuredetector.data.SdData;
import uk.org.openseizuredetector.data.AlarmState;
import uk.org.openseizuredetector.utils.PreferenceUtils;

/**
 * SeizureDetector - Coordinates all seizure detection algorithms.
 * Receives SdData, passes it to each active algorithm, and combines results using a voting strategy.
 * Now uses SdAlgMl exclusively for Machine Learning detection.
 */
public class SeizureDetector {
    private final static String TAG = "SeizureDetector";

    private Context mContext;
    private SharedPreferences mSP;

    // Algorithm instances - only created if algorithm is active
    private SdAlgOsd mSdAlgOsd;
    private SdAlgFlap mSdAlgFlap;
    private SdAlgFall mSdAlgFall;
    private SdAlgMl mSdAlgMl;  // Unified ML algorithm supporting single or multiple models
    private SdAlgHr mSdAlgHr;

    // Track which algorithms are active
    private boolean mOsdAlarmActive;
    private boolean mFlapAlarmActive;
    private boolean mFallActive;
    private boolean mCnnAlarmActive;
    private boolean mHRAlarmActive;

    // Advanced Mode option "show all algorithms": OSD, Flap, Fall and HR are then also run for
    // algorithms the user has NOT selected, in "display only" mode. Their results fill SdData so
    // the main display can show them, but they never vote, never raise an alarm and are never
    // an alarm cause. (ML models are not run this way - they are too heavy.)
    private boolean mShowAllAlgs;

    // Voting strategy
    private VotingStrategy mVotingStrategy;
    private boolean mUseVoting = false;

    // State machine for WARNING/ALARM transitions
    private int mAlarmTime = 0;
    private short mWarnTimeThreshold;
    private short mAlarmTimeThreshold;
    private short mSamplePeriod;

    public SeizureDetector(Context context) {
        Log.i(TAG, "SeizureDetector Constructor");
        mContext = context;
        mSP = PreferenceManager.getDefaultSharedPreferences(mContext);

        updatePrefs();

        // Initialize voting strategy
        String votingStrategyStr = mSP.getString("VotingStrategy", "SET_FROM_XML");
        VotingStrategy.Strategy strategy = VotingStrategy.fromString(votingStrategyStr);
        mVotingStrategy = new VotingStrategy(strategy);

        int activeCount = 0;

        if (mOsdAlarmActive || mShowAllAlgs) {
            mSdAlgOsd = new SdAlgOsd(mContext);
            if (mOsdAlarmActive) activeCount++;
        }

        if (mFlapAlarmActive || mShowAllAlgs) {
            mSdAlgFlap = new SdAlgFlap(mContext);
            if (mFlapAlarmActive) activeCount++;
        }

        if (mCnnAlarmActive) {
            // Using SdAlgMl for all ML detection - handles any number of installed models
            mSdAlgMl = new SdAlgMl(mContext);
            Log.i(TAG, "SeizureDetector - SdAlgMl initialized (ACTIVE)");
            // Note: In voting, we'll count each evaluated model result
        }

        if (mHRAlarmActive || mShowAllAlgs) {
            mSdAlgHr = new SdAlgHr(mContext);
            if (mHRAlarmActive) activeCount++;
        }

        if (mFallActive || mShowAllAlgs) {
            mSdAlgFall = new SdAlgFall(mContext);
            // NOTE: Fall is intentionally NOT counted in activeCount.
            // A fall alarm bypasses voting entirely and directly produces an ALARM state
            // (see processData). It therefore plays no role in the voting pool.
        }

        // Enable voting if we have multiple algorithms or multiple ML models
        mUseVoting = (activeCount > 1 || (mSdAlgMl != null));

        Log.i(TAG, "SeizureDetector initialized - ActiveCount:" + activeCount + " UseVoting:" + mUseVoting);
    }

    private void updatePrefs() {
        try {
            String warnTimeStr = mSP.getString("WarnTime", "SET_FROM_XML");
            mWarnTimeThreshold = Short.parseShort(warnTimeStr);
            String alarmTimeStr = mSP.getString("AlarmTime", "SET_FROM_XML");
            mAlarmTimeThreshold = Short.parseShort(alarmTimeStr);
            mSamplePeriod = 5;
            Log.v(TAG,"updatePrefs() - WarnTime=" + mWarnTimeThreshold + " AlarmTime=" + mAlarmTimeThreshold + " SamplePeriod=" + mSamplePeriod + "");

            mOsdAlarmActive = PreferenceUtils.getBooleanFromXml(mSP, "OsdAlarmActive");
            mFlapAlarmActive = PreferenceUtils.getBooleanFromXml(mSP, "FlapAlarmActive");
            mFallActive = PreferenceUtils.getBooleanFromXml(mSP, "FallActive");
            mCnnAlarmActive = PreferenceUtils.getBooleanFromXml(mSP, "CnnAlarmActive");
            mHRAlarmActive = PreferenceUtils.getBooleanFromXml(mSP, "HRAlarmActive");
            // Keys are those of AdvancedSections.PREF_SHOW_ALL_ALGS / "pref_advanced_mode" (in
            // activity.main); literals are used here to keep this package free of UI classes.
            mShowAllAlgs = PreferenceUtils.getBooleanFromXml(mSP, "pref_advanced_mode")
                    && mSP.getBoolean("pref_advanced_show_all_algs", false);
            Log.v(TAG,"updatePrefs() - mShowAllAlgs=" + mShowAllAlgs);
            Log.v(TAG,"updatePrefs() - mOsdAlarmActive=" + mOsdAlarmActive + " mFlapAlarmActive=" + mFlapAlarmActive + " mFallActive=" + mFallActive + " mCnnAlarmActive=" + mCnnAlarmActive + " mHRAlarmActive=" + mHRAlarmActive + "");
        } catch (Exception ex) {
            Log.e(TAG, "updatePrefs() - Problem parsing preferences: " + ex.toString());
            mWarnTimeThreshold = 5; mAlarmTimeThreshold = 15; mSamplePeriod = 5;
            mOsdAlarmActive = true;
        }
    }

    public int processData(SdData sdData) {
        sdData.mOsdAlarmActive = mOsdAlarmActive;
        sdData.mFlapAlarmActive = mFlapAlarmActive;
        sdData.mCnnAlarmActive = mCnnAlarmActive;
        sdData.mHRAlarmActive = mHRAlarmActive;
        sdData.mFallActive = mFallActive;

        sdData.alarmCause = "";
        List<AlgorithmResult> algorithmResults = new ArrayList<>();
        List<String> alarmCauses = new ArrayList<>();

        if (mSdAlgOsd != null) {
            int osdResult = mSdAlgOsd.processSdData(sdData);
            Log.d(TAG,"osdResult=" + osdResult);
            if (mOsdAlarmActive) {   // otherwise display only: results are not used
                sdData.osdAlgState = osdResult;
                algorithmResults.add(new AlgorithmResult("OSD", osdResult, 1.0f, 1.0f));
                if (osdResult == AlarmState.ALARM) alarmCauses.add(mSdAlgOsd.getAlarmCause());
            }
        }

        if (mSdAlgFlap != null) {
            int flapResult = mSdAlgFlap.processSdData(sdData);
            Log.d(TAG,"flapResult=" + flapResult);
            if (mFlapAlarmActive) {   // otherwise display only: results are not used
                sdData.flapAlgState = flapResult;
                algorithmResults.add(new AlgorithmResult("FLAP", flapResult, 1.0f, 1.0f));
                if (flapResult == AlarmState.ALARM) alarmCauses.add(mSdAlgFlap.getAlarmCause());
            }
        }

        // -----------------------------------------------------------------------
        // Fall detection is handled SEPARATELY from the voting pool.
        // If a fall alarm fires it will directly override the final alarm state
        // (see the override check below), so we do NOT add its result to
        // algorithmResults and it does NOT participate in voting.
        // -----------------------------------------------------------------------
        boolean fallAlarmTriggered = false;
        if (mSdAlgFall != null) {
            int fallResult = mSdAlgFall.processSdData(sdData);   // also fills sdData.mFallWindowMin/Max
            Log.d(TAG,"fallResult=" + fallResult);
            if (mFallActive) sdData.fallAlgState = fallResult;   // display only: result is not used
            if (mFallActive && fallResult == AlarmState.ALARM) {
                // Mark that a fall alarm has fired - this will override voting below.
                fallAlarmTriggered = true;
                alarmCauses.add(mSdAlgFall.getAlarmCause());
                sdData.fallAlarmStanding = true;
                Log.i(TAG, "Fall alarm detected - will override voting and time thresholds");
            }
        }

        if (mSdAlgMl != null) {
            List<AlgorithmResult> mlResults = mSdAlgMl.evaluateAllModels(sdData);
            sdData.mlNumModels = mlResults.size();
            int maxState = AlarmState.OK;
            float maxProb = 0.0f;
            for (int i = 0; i < sdData.mlNumModels; i++) {
                AlgorithmResult result = mlResults.get(i);
                sdData.mlModelNames[i] = result.algorithmName;
                sdData.mlModelProbs[i] = result.confidence;
                sdData.mlModelStates[i] = result.alarmState;
                sdData.mlModelActive[i] = true;
                maxState = Math.max(maxState, result.alarmState);
                maxProb = Math.max(maxProb, result.confidence);
                Log.d(TAG, "ML Model " + sdData.mlModelNames[i] + " State=" + sdData.mlModelStates[i] + " Prob=" + sdData.mlModelProbs[i]);
            }
            // we set the old cnnAlgState and mPseizure to the maximum value of the active models.
            sdData.cnnAlgState = maxState;
            sdData.mPseizure = maxProb;

            algorithmResults.addAll(mlResults);
            for (AlgorithmResult result : mlResults) {
                if (result.alarmState == AlarmState.ALARM) alarmCauses.add(result.algorithmName);
            }
        }

        if (mSdAlgHr != null) {
            int hrResult = mSdAlgHr.processSdData(sdData);   // also fills sdData.mAdaptiveHrAverage
            Log.d(TAG,"hrResult=" + hrResult);
            if (mHRAlarmActive) {   // otherwise display only: results are not used
                sdData.hrAlgState = hrResult;
                algorithmResults.add(new AlgorithmResult("HR", hrResult, 1.0f, 1.0f));
                if (hrResult == AlarmState.ALARM) {
                    if (sdData.mHRAlarmStanding) alarmCauses.add("HR");
                    if (sdData.mAdaptiveHrAlarmStanding) alarmCauses.add("HR_ADAPT");
                    if (sdData.mAverageHrAlarmStanding) alarmCauses.add("HR_AVG");
                }
            }
        }

        int combinedAlarmState;
        if (mUseVoting && algorithmResults.size() > 1) {
            Log.d(TAG, "Applying voting strategy");
            combinedAlarmState = mVotingStrategy.vote(algorithmResults);
            Log.d(TAG, "Voting strategy returned " + combinedAlarmState);
        } else {
            Log.d(TAG, "Applying 'OR' strategy for alarms");
            boolean anyAlarm = false;
            for (AlgorithmResult result : algorithmResults) {
                if (result.alarmState == AlarmState.ALARM) { anyAlarm = true; break; }
            }
            combinedAlarmState = anyAlarm ? AlarmState.ALARM : AlarmState.OK;
            Log.d(TAG, "OR strategy returned " + combinedAlarmState);
        }

        for (String cause : alarmCauses) {
            sdData.alarmCause += cause + " ";
        }
        Log.d(TAG, "sdData.alarmCause=" + sdData.alarmCause);

        // Now Determine Alarm State based on recent history of algorithm results.
        int alarmState;
        if (combinedAlarmState == AlarmState.ALARM) {
            mAlarmTime += mSamplePeriod;
            Log.d(TAG, "mAlarmTime=" + mAlarmTime + " mWarnTimeThreshold=" + mWarnTimeThreshold + " mAlarmTimeThreshold=" + mAlarmTimeThreshold);
            if (mAlarmTime > mAlarmTimeThreshold) {
                Log.d(TAG,"Setting ALARM state");
                alarmState = AlarmState.ALARM;
                sdData.alarmStanding = true;
            } else if (mAlarmTime > mWarnTimeThreshold) {
                Log.d(TAG,"Setting WARNING state");
                alarmState = AlarmState.WARNING;
                sdData.alarmStanding = false;
            } else {
                Log.d(TAG,"Setting OK state");
                alarmState = AlarmState.OK;
                sdData.alarmStanding = false;
            }
        } else {
            if (sdData.alarmState == AlarmState.ALARM) {
                Log.d(TAG,"Setting WARMING state");
                alarmState = AlarmState.WARNING;
                mAlarmTime = mWarnTimeThreshold + 1;
                sdData.alarmStanding = false;
            } else {
                Log.d(TAG,"Setting OK state");
                alarmState = AlarmState.OK;
                mAlarmTime = 0;
                sdData.alarmStanding = false;
            }
        }

        // -----------------------------------------------------------------------
        // Fall alarm override: if the fall algorithm fired an alarm, bypass the
        // voting result and the warning/alarm time-accumulation state machine and
        // immediately return ALARM.  Fall events represent an acute physical event
        // that requires an instant response - they must never be down-graded to
        // WARNING or delayed by the warn/alarm time thresholds.
        // -----------------------------------------------------------------------
        if (fallAlarmTriggered) {
            Log.i(TAG, "processData() - Fall alarm OVERRIDE: returning ALARM directly (bypassing voting and time thresholds)");
            sdData.alarmStanding = true;
            // sdData.alarmCause is already populated by the alarmCauses loop above.
            return AlarmState.ALARM;
        }

        Log.i(TAG, "processData() - returning " + alarmState);
        return alarmState;
    }

    public void resetAlarmState() {
        mAlarmTime = 0;
    }

    public void close() {
        Log.d(TAG, "close()");
        if (mSdAlgOsd != null) mSdAlgOsd.close();
        if (mSdAlgFlap != null) mSdAlgFlap.close();
        if (mSdAlgFall != null) mSdAlgFall.close();
        if (mSdAlgMl != null) mSdAlgMl.close();
        if (mSdAlgHr != null) mSdAlgHr.close();
    }
}
