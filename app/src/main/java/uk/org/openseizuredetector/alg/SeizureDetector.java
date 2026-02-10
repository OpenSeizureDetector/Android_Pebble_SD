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
 * Receives SdData, passes it to each active algorithm, and combines results using a voting strategy.
 */
public class SeizureDetector {
    private final static String TAG = "SeizureDetector";

    private Context mContext;
    private SharedPreferences mSP;

    // Algorithm instances - only created if algorithm is active
    private SdAlgOsd mSdAlgOsd;
    private SdAlgFlap mSdAlgFlap;
    private SdAlgFall mSdAlgFall;
    private SdAlgNn mSdAlgNn;  // Keep for backward compatibility
    private SdAlgMl mSdAlgMl;  // New multi-model ML algorithm
    private SdAlgHr mSdAlgHr;

    // Track which algorithms are active
    private boolean mOsdAlarmActive;
    private boolean mFlapAlarmActive;
    private boolean mFallActive;
    private boolean mCnnAlarmActive;
    private boolean mHRAlarmActive;

    // Voting strategy
    private VotingStrategy mVotingStrategy;
    private boolean mUseVoting = false;  // Enable voting only when multiple algorithms are active

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

        // Initialize voting strategy
        String votingStrategyStr = mSP.getString("VotingStrategy", "ANY");
        VotingStrategy.Strategy strategy = VotingStrategy.fromString(votingStrategyStr);
        mVotingStrategy = new VotingStrategy(strategy);
        Log.i(TAG, "SeizureDetector - Voting strategy: " + mVotingStrategy.getStrategy());

        // Count active algorithms to determine if voting should be used
        int activeCount = 0;

        // Instantiate only the algorithms that are active
        if (mOsdAlarmActive) {
            mSdAlgOsd = new SdAlgOsd(mContext);
            Log.i(TAG, "SeizureDetector - SdAlgOsd initialized (ACTIVE)");
            activeCount++;
        }

        if (mFlapAlarmActive) {
            mSdAlgFlap = new SdAlgFlap(mContext);
            Log.i(TAG, "SeizureDetector - SdAlgFlap initialized (ACTIVE)");
            activeCount++;
        }

        if (mFallActive) {
            mSdAlgFall = new SdAlgFall(mContext);
            Log.i(TAG, "SeizureDetector - SdAlgFall initialized (ACTIVE)");
            activeCount++;
        }

        if (mCnnAlarmActive) {
            // Check if multi-model is enabled
            boolean useMultiModel = mSP.getBoolean("MlUseMultiModel", false);
            int numModels = 1;
            try {
                String numModelsStr = mSP.getString("MlNumModels", "1");
                numModels = Integer.parseInt(numModelsStr);
            } catch (Exception e) {
                Log.w(TAG, "Error parsing MlNumModels, using default: " + e.getMessage());
                numModels = 1;
            }

            if (useMultiModel && numModels > 1) {
                mSdAlgMl = new SdAlgMl(mContext);
                Log.i(TAG, "SeizureDetector - SdAlgMl initialized with " + numModels + " models (ACTIVE)");
                activeCount += numModels;  // Each model counts as an algorithm for voting
            } else {
                mSdAlgNn = new SdAlgNn(mContext);
                Log.i(TAG, "SeizureDetector - SdAlgNn initialized (ACTIVE)");
                activeCount++;
            }
        }

        if (mHRAlarmActive) {
            mSdAlgHr = new SdAlgHr(mContext);
            Log.i(TAG, "SeizureDetector - SdAlgHr initialized (ACTIVE)");
            activeCount++;
        }

        // Enable voting if multiple algorithms are active
        mUseVoting = (activeCount > 1);

        Log.i(TAG, "SeizureDetector initialized - OsdAlarmActive:" + mOsdAlarmActive +
                " FlapAlarmActive:" + mFlapAlarmActive + " FallActive:" + mFallActive +
                " CnnAlarmActive:" + mCnnAlarmActive + " HRAlarmActive:" + mHRAlarmActive +
                " ActiveCount:" + activeCount + " UseVoting:" + mUseVoting);
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
     * Uses voting strategy to combine results when multiple algorithms are active.
     *
     * @param sdData - The seizure detector data to analyze
     * @return overall alarm state (0=OK, 1=WARNING, 2=ALARM, etc.)
     */
    public int processData(SdData sdData) {
        Log.v(TAG, "processData() - UseVoting=" + mUseVoting);

        // Store which algorithms are active in SdData for logging/UI purposes
        sdData.mOsdAlarmActive = mOsdAlarmActive;
        sdData.mFlapAlarmActive = mFlapAlarmActive;
        sdData.mCnnAlarmActive = mCnnAlarmActive;
        sdData.mHRAlarmActive = mHRAlarmActive;
        sdData.mFallActive = mFallActive;

        // Clear alarm cause before processing
        sdData.alarmCause = "";

        // Collect results from all algorithms for voting
        List<AlgorithmResult> algorithmResults = new ArrayList<>();
        List<String> alarmCauses = new ArrayList<>();

        // Process OSD algorithm if initialized (active)
        if (mSdAlgOsd != null) {
            Log.v(TAG, "processData() - Running SdAlgOsd");
            int osdResult = mSdAlgOsd.processSdData(sdData);
            sdData.osdAlgState = osdResult;
            algorithmResults.add(new AlgorithmResult("OSD", osdResult, 1.0f, 1.0f));
            if (osdResult == 2) {
                alarmCauses.add(mSdAlgOsd.getAlarmCause());
            }
        } else {
            sdData.osdAlgState = 0;
        }

        // Process FLAP algorithm if initialized (active)
        if (mSdAlgFlap != null) {
            Log.v(TAG, "processData() - Running SdAlgFlap");
            int flapResult = mSdAlgFlap.processSdData(sdData);
            sdData.flapAlgState = flapResult;
            algorithmResults.add(new AlgorithmResult("FLAP", flapResult, 1.0f, 1.0f));
            if (flapResult == 2) {
                alarmCauses.add(mSdAlgFlap.getAlarmCause());
            }
        } else {
            sdData.flapAlgState = 0;
        }

        // Process FALL algorithm if initialized (active)
        if (mSdAlgFall != null) {
            Log.v(TAG, "processData() - Running SdAlgFall");
            int fallResult = mSdAlgFall.processSdData(sdData);
            sdData.fallAlgState = fallResult;
            algorithmResults.add(new AlgorithmResult("FALL", fallResult, 1.0f, 1.0f));
            if (fallResult == 2) {
                alarmCauses.add(mSdAlgFall.getAlarmCause());
                sdData.fallAlarmStanding = true;
            }
        } else {
            sdData.fallAlgState = 0;
        }

        // Process ML algorithms
        if (mSdAlgMl != null) {
            Log.v(TAG, "processData() - Running SdAlgMl (multi-model)");
            List<AlgorithmResult> mlResults = mSdAlgMl.evaluateAllModels(sdData);

            // Populate ML model results in SdData for UI
            sdData.mlNumModels = Math.min(mlResults.size(), 5);
            int maxState = 0;
            for (int i = 0; i < sdData.mlNumModels; i++) {
                AlgorithmResult result = mlResults.get(i);
                sdData.mlModelNames[i] = result.algorithmName;
                sdData.mlModelProbs[i] = result.confidence;
                sdData.mlModelStates[i] = result.alarmState;
                sdData.mlModelActive[i] = true;
                maxState = Math.max(maxState, result.alarmState);
            }
            sdData.cnnAlgState = maxState;

            algorithmResults.addAll(mlResults);
            for (AlgorithmResult result : mlResults) {
                if (result.alarmState == 2) {
                    alarmCauses.add(result.algorithmName);
                }
            }
        } else if (mSdAlgNn != null) {
            Log.v(TAG, "processData() - Running SdAlgNn");
            int nnResult = mSdAlgNn.processSdData(sdData);
            sdData.cnnAlgState = nnResult;

            // Store single model result in first slot
            sdData.mlNumModels = 1;
            sdData.mlModelNames[0] = "CNN";
            sdData.mlModelProbs[0] = sdData.mPseizure;
            sdData.mlModelStates[0] = nnResult;
            sdData.mlModelActive[0] = true;

            // Use mPseizure as confidence for ML algorithm
            float confidence = sdData.mPseizure > 0 ? (float)sdData.mPseizure : 0.0f;
            algorithmResults.add(new AlgorithmResult("CNN", nnResult, confidence, 1.0f));
            if (nnResult == 2) {
                alarmCauses.add(mSdAlgNn.getAlarmCause());
            }
        } else {
            sdData.cnnAlgState = 0;
            sdData.mlNumModels = 0;
        }

        // Process HR algorithm if initialized (active)
        if (mSdAlgHr != null) {
            Log.v(TAG, "processData() - Running SdAlgHr");
            int hrResult = mSdAlgHr.processSdData(sdData);
            sdData.hrAlgState = hrResult;
            algorithmResults.add(new AlgorithmResult("HR", hrResult, 1.0f, 1.0f));
            if (hrResult == 2) {
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
        } else {
            sdData.hrAlgState = 0;
        }

        // Determine alarm state using voting or simple OR logic
        int combinedAlarmState;
        if (mUseVoting && algorithmResults.size() > 1) {
            combinedAlarmState = mVotingStrategy.vote(algorithmResults);
            Log.v(TAG, "processData() - Voting result: " + combinedAlarmState +
                       " from " + algorithmResults.size() + " algorithms");
        } else {
            // Single algorithm or voting disabled - use simple OR logic
            boolean anyAlarm = false;
            for (AlgorithmResult result : algorithmResults) {
                if (result.alarmState == 2) {
                    anyAlarm = true;
                    break;
                }
            }
            combinedAlarmState = anyAlarm ? 2 : 0;
        }

        // Build alarm cause string
        for (String cause : alarmCauses) {
            sdData.alarmCause += cause + " ";
        }

        // Apply state machine for WARNING/ALARM transitions
        int alarmState;
        if (combinedAlarmState == 2) {
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

        Log.v(TAG, "processData(): combinedAlarmState=" + combinedAlarmState +
                ", alarmCause=" + sdData.alarmCause +
                ", finalAlarmState=" + alarmState +
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
        if (mSdAlgMl != null) mSdAlgMl.close();
        if (mSdAlgHr != null) mSdAlgHr.close();
    }
}
