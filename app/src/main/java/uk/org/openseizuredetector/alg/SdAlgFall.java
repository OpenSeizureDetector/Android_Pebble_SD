package uk.org.openseizuredetector.alg;

import android.content.Context;

import uk.org.openseizuredetector.data.AlarmState;
import uk.org.openseizuredetector.data.logging.Log;

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
            String minStr = mSP.getString("FallThreshMin", "SET_FROM_XML");
            mFallThreshMin = Short.parseShort(minStr);
            String maxStr = mSP.getString("FallThreshMax", "SET_FROM_XML");
            mFallThreshMax = Short.parseShort(maxStr);
            String windowStr = mSP.getString("FallWindow", "SET_FROM_XML");
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
        int i, j;
        double minAcc, maxAcc;
        boolean fallDetected = false;


        long fallWindowSamp = (mFallWindow * sdData.mSampleFreq) / 1000; // Convert ms to samples.
        Log.d(TAG, "processSdData() - mFallWindow="+mFallWindow+", sdData.mSampleFreq="+sdData.mSampleFreq+", fallWindowSamp=" + fallWindowSamp + " (mFallThreshMin=" + mFallThreshMin + ", mFallThreshMax=" + mFallThreshMax);

        // Move window through sample buffer, checking for fall.
        for (i = 0; i < sdData.mNsamp - fallWindowSamp; i++) {  // i = window start point
            // Find max and min acceleration within window.
            minAcc = sdData.rawData[i];
            maxAcc = sdData.rawData[i];
            for (j = 0; j < fallWindowSamp; j++) {  // j = position within window
                if (sdData.rawData[i + j] < minAcc) minAcc = sdData.rawData[i + j];
                if (sdData.rawData[i + j] > maxAcc) maxAcc = sdData.rawData[i + j];
            }
            Log.d(TAG, "processSdData() - i="+i+", minAcc=" + String.format("%.1f", minAcc) + ", maxAcc=" + String.format("%.1f", maxAcc));

            if ((minAcc < mFallThreshMin) && (maxAcc > mFallThreshMax)) {
                Log.i(TAG, "processSdData() ****FALL DETECTED***** minAcc=" + minAcc + ", maxAcc=" + maxAcc);
                fallDetected = true;
            }
        }

        if (fallDetected) {
            return AlarmState.ALARM;  // ALARM
        }
        return AlarmState.OK;  // OK

    }
}
