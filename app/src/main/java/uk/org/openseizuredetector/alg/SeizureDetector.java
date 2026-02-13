package uk.org.openseizuredetector.alg;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

import uk.org.openseizuredetector.data.SdData;
import uk.org.openseizuredetector.data.AlarmState;

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

    // Voting strategy
    private VotingStrategy mVotingStrategy;
    private boolean mUseVoting = false;

    // State machine for WARNING/ALARM transitions
    private int mAlarmCount = 0;  
    private short mWarnTime;
    private short mAlarmTime;
    private short mSamplePeriod;

    public SeizureDetector(Context context) {
        Log.i(TAG, "SeizureDetector Constructor");
        mContext = context;
        mSP = PreferenceManager.getDefaultSharedPreferences(mContext);

        updatePrefs();

        // Initialize voting strategy
        String votingStrategyStr = mSP.getString("VotingStrategy", "ANY");
        VotingStrategy.Strategy strategy = VotingStrategy.fromString(votingStrategyStr);
        mVotingStrategy = new VotingStrategy(strategy);

        int activeCount = 0;

        if (mOsdAlarmActive) {
            mSdAlgOsd = new SdAlgOsd(mContext);
            activeCount++;
        }

        if (mFlapAlarmActive) {
            mSdAlgFlap = new SdAlgFlap(mContext);
            activeCount++;
        }

        if (mFallActive) {
            mSdAlgFall = new SdAlgFall(mContext);
            activeCount++;
        }

        if (mCnnAlarmActive) {
            // Using SdAlgMl for all ML detection - handles any number of installed models
            mSdAlgMl = new SdAlgMl(mContext);
            Log.i(TAG, "SeizureDetector - SdAlgMl initialized (ACTIVE)");
            // Note: In voting, we'll count each evaluated model result
        }

        if (mHRAlarmActive) {
            mSdAlgHr = new SdAlgHr(mContext);
            activeCount++;
        }

        // Enable voting if we have multiple algorithms or multiple ML models
        mUseVoting = (activeCount > 1 || (mSdAlgMl != null));

        Log.i(TAG, "SeizureDetector initialized - ActiveCount:" + activeCount + " UseVoting:" + mUseVoting);
    }

    private void updatePrefs() {
        try {
            String warnTimeStr = mSP.getString("WarnTime", "5");
            mWarnTime = Short.parseShort(warnTimeStr);
            String alarmTimeStr = mSP.getString("AlarmTime", "15");
            mAlarmTime = Short.parseShort(alarmTimeStr);
            String samplePeriodStr = mSP.getString("DataUpdatePeriod", "5");
            mSamplePeriod = Short.parseShort(samplePeriodStr);

            mOsdAlarmActive = mSP.getBoolean("OsdAlarmActive", true);
            mFlapAlarmActive = mSP.getBoolean("FlapAlarmActive", false);
            mFallActive = mSP.getBoolean("FallActive", false);
            mCnnAlarmActive = mSP.getBoolean("CnnAlarmActive", false);
            mHRAlarmActive = mSP.getBoolean("HRAlarmActive", false);
        } catch (Exception ex) {
            Log.e(TAG, "updatePrefs() - Problem parsing preferences: " + ex.toString());
            mWarnTime = 5; mAlarmTime = 15; mSamplePeriod = 5;
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
            sdData.osdAlgState = osdResult;
            algorithmResults.add(new AlgorithmResult("OSD", osdResult, 1.0f, 1.0f));
            if (osdResult == AlarmState.ALARM) alarmCauses.add(mSdAlgOsd.getAlarmCause());
        }

        if (mSdAlgFlap != null) {
            int flapResult = mSdAlgFlap.processSdData(sdData);
            sdData.flapAlgState = flapResult;
            algorithmResults.add(new AlgorithmResult("FLAP", flapResult, 1.0f, 1.0f));
            if (flapResult == AlarmState.ALARM) alarmCauses.add(mSdAlgFlap.getAlarmCause());
        }

        if (mSdAlgFall != null) {
            int fallResult = mSdAlgFall.processSdData(sdData);
            sdData.fallAlgState = fallResult;
            algorithmResults.add(new AlgorithmResult("FALL", fallResult, 1.0f, 1.0f));
            if (fallResult == AlarmState.ALARM) {
                alarmCauses.add(mSdAlgFall.getAlarmCause());
                sdData.fallAlarmStanding = true;
            }
        }

        if (mSdAlgMl != null) {
            List<AlgorithmResult> mlResults = mSdAlgMl.evaluateAllModels(sdData);
            sdData.mlNumModels = Math.min(mlResults.size(), 5);
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
            }
            sdData.cnnAlgState = maxState;
            sdData.mPseizure = maxProb;

            algorithmResults.addAll(mlResults);
            for (AlgorithmResult result : mlResults) {
                if (result.alarmState == AlarmState.ALARM) alarmCauses.add(result.algorithmName);
            }
        }

        if (mSdAlgHr != null) {
            int hrResult = mSdAlgHr.processSdData(sdData);
            sdData.hrAlgState = hrResult;
            algorithmResults.add(new AlgorithmResult("HR", hrResult, 1.0f, 1.0f));
            if (hrResult == AlarmState.ALARM) {
                if (sdData.mHRAlarmStanding) alarmCauses.add("HR");
                if (sdData.mAdaptiveHrAlarmStanding) alarmCauses.add("HR_ADAPT");
                if (sdData.mAverageHrAlarmStanding) alarmCauses.add("HR_AVG");
            }
        }

        int combinedAlarmState;
        if (mUseVoting && algorithmResults.size() > 1) {
            combinedAlarmState = mVotingStrategy.vote(algorithmResults);
        } else {
            boolean anyAlarm = false;
            for (AlgorithmResult result : algorithmResults) {
                if (result.alarmState == AlarmState.ALARM) { anyAlarm = true; break; }
            }
            combinedAlarmState = anyAlarm ? AlarmState.ALARM : AlarmState.OK;
        }

        for (String cause : alarmCauses) {
            sdData.alarmCause += cause + " ";
        }

        int alarmState;
        if (combinedAlarmState == AlarmState.ALARM) {
            mAlarmCount += mSamplePeriod;
            if (mAlarmCount > mAlarmTime) {
                alarmState = AlarmState.ALARM;
                sdData.alarmStanding = true;
            } else if (mAlarmCount > mWarnTime) {
                alarmState = AlarmState.WARNING;
            } else {
                alarmState = AlarmState.OK;
            }
        } else {
            if (sdData.alarmState == AlarmState.ALARM) {
                alarmState = AlarmState.WARNING;
                mAlarmCount = mWarnTime + 1;
            } else {
                alarmState = AlarmState.OK;
                mAlarmCount = 0;
                sdData.alarmStanding = false;
            }
        }

        return alarmState;
    }

    public void resetAlarmState() {
        mAlarmCount = 0;
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
