package uk.org.openseizuredetector.alg;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import uk.org.openseizuredetector.data.logging.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import uk.org.openseizuredetector.data.SdData;

/**
 * Base class for all seizure detection algorithms.
 * Each algorithm should extend this class and implement processSdData() method.
 */
public abstract class SdAlgBase {
    private final static String TAG = "SdAlgBase";
    protected Context mContext;
    protected Handler mHandler;
    protected SharedPreferences mSP;

    public SdAlgBase(Context context) {
        Log.d(TAG, "SdAlgBase Constructor");
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        mSP = PreferenceManager.getDefaultSharedPreferences(mContext);
    }

    /**
     * Process seizure detector data and return alarm state.
     * @param sdData - the seizure detector data to analyze
     * @return alarm state code: 0=OK, 1=WARNING, 2=ALARM, or -1 if inactive/not applicable
     */
    public abstract int processSdData(SdData sdData);

    /**
     * Get a string describing the cause if this algorithm is in alarm state
     * @param return alarm cause string (e.g., "OsdAlg", "HR", "FALL") or empty string
     */
    public abstract String getAlarmCause();

    /**
     * Close/cleanup any resources used by the algorithm
     */
    public void close() {
        Log.d(TAG, "close()");
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }
    }

    /**
     * Helper method to read a double preference value
     */
    protected double readDoublePref(String prefName, String defVal) {
        String prefValStr;
        double retVal = -1;
        try {
            prefValStr = mSP.getString(prefName, defVal);
            retVal = Double.parseDouble(prefValStr);
        } catch (Exception ex) {
            Log.v(TAG, "readDoublePref() - Problem with preference " + prefName);
        }
        return retVal;
    }

    /**
     * Helper method to show Toast on the main thread safely
     */
    protected void showToast(String message) {
        mHandler.post(() -> Toast.makeText(mContext, message, Toast.LENGTH_LONG).show());
    }
}