package uk.org.openseizuredetector.alg;

import android.content.Context;
import android.util.Log;

import uk.org.openseizuredetector.data.SdData;

/**
 * Fall detection algorithm - uses simple threshold analysis on a sliding window
 * to detect rapid acceleration changes characteristic of a fall.
 */
public class SdAlgFall extends SdAlgBase {
    private final static String TAG = "SdAlgFall";

    private short mFallThreshMin;
    private short mFallThreshMax;
    private short mFallWindow;

    public SdAlgFall(Context context) {
        super(context);
        Log.d(TAG, "SdAlgFall Constructor");
        updatePrefs();
    }

    private void updatePrefs() {
        try {
            String minStr = mSP.getString("FallThreshMin", "500");
            mFallThreshMin = Short.parseShort(minStr);
            String maxStr = mSP.getString("FallThreshMax", "2000");
            mFallThreshMax = Short.parseShort(maxStr);
            String windowStr = mSP.getString("FallWindow", "500");
            mFallWindow = Short.parseShort(windowStr);
            Log.v(TAG, "updatePrefs(): mFallThreshMin=" + mFallThreshMin +
                    ", mFallThreshMax=" + mFallThreshMax +
                    ", mFallWindow=" + mFallWindow);
        } catch (Exception ex) {
            Log.e(TAG, "updatePrefs() - Problem parsing preferences: " + ex.toString());
            mFallThreshMin = 500;
            mFallThreshMax = 2000;
            mFallWindow = 500;
        }
    }

    @Override
    public String getAlarmCause() {
        return "FALL";
    }

    @Override
    public int processSdData(SdData sdData) {
        // ...existing code...

        int i, j;
        double minAcc, maxAcc;

        long fallWindowSamp = (mFallWindow * sdData.mSampleFreq) / 1000; // Convert ms to samples.
        Log.v(TAG, "processSdData() - fallWindowSamp=" + fallWindowSamp);

        // Move window through sample buffer, checking for fall.
        for (i = 0; i < sdData.mNsamp - fallWindowSamp; i++) {  // i = window start point
            // Find max and min acceleration within window.
            minAcc = sdData.rawData[i];
            maxAcc = sdData.rawData[i];
            for (j = 0; j < fallWindowSamp; j++) {  // j = position within window
                if (sdData.rawData[i + j] < minAcc) minAcc = sdData.rawData[i + j];
                if (sdData.rawData[i + j] > maxAcc) maxAcc = sdData.rawData[i + j];
            }
            Log.d(TAG, "processSdData() - minAcc=" + minAcc + " (mFallThreshMin=" + mFallThreshMin +
                    "), maxAcc=" + maxAcc + " (mFallThreshMax=" + mFallThreshMax + ")");

            if ((minAcc < mFallThreshMin) && (maxAcc > mFallThreshMax)) {
                Log.i(TAG, "processSdData() ****FALL DETECTED***** minAcc=" + minAcc + ", maxAcc=" + maxAcc);
                return 2;  // ALARM
            }
        }

        return 0;  // OK
    }
}
