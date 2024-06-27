package uk.org.openseizuredetector.algorithms;

import static uk.org.openseizuredetector.LogManager.mUtil;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.format.Time;
import android.util.Log;
import android.widget.Toast;

import com.android.volley.RequestQueue;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tflite.java.TfLite;

import org.jtransforms.fft.DoubleFFT_1D;
import org.tensorflow.lite.InterpreterApi;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.Arrays;

import uk.org.openseizuredetector.MlModelManager;
import uk.org.openseizuredetector.SdData;

public class SdAlgOsd extends SdAlg {
    private final static String TAG = "SdAlgOsd";


    public SdAlgOsd(Context context) {
        super(context);
        Log.d(TAG, "SdAlgOsd Constructor");
        Log.d(TAG, "constructor finished");
    }

    @Override
    public void close() {
        super.close();
        Log.d(TAG, "close()");
    }

    @Override
    public boolean initialiseFromSharedPreferences() {
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(mContext);
        try {
            String threshStr = SP.getString("CnnAlarmThreshold", "5");
            mSdThresh = Double.parseDouble(threshStr);
            Log.v(TAG, "SdAlgNn Constructor mSdThresh = " + mSdThresh);
            threshStr = SP.getString("CnnModelId", "1");
            mModelId = Integer.parseInt(threshStr);
            Log.v(TAG, "SdAlgNn Constructor mModelId = " + mModelId);
        } catch (Exception ex) {
            Log.v(TAG, "SdAlgNn Constructor - problem parsing preferences. " + ex.toString());
            Toast toast = Toast.makeText(mContext, "Problem Parsing ML Algorithm Preferences", Toast.LENGTH_SHORT);
            toast.show();
        }

        return true;
    }


    /**
     * Calculate the magnitude of entry i in the fft array fft
     *
     * @param fft
     * @param i
     * @return magnitude ( Re*Re + Im*Im )
     */
    private double getMagnitude(double[] fft, int i) {
        double mag;
        mag = (fft[2 * i] * fft[2 * i] + fft[2 * i + 1] * fft[2 * i + 1]);
        return mag;
    }

    /**
     * doAnalysis() - analyse the data if the accelerometer data array mAccData
     * and populate the output data structure mSdData
     */
    protected void doAnalysis() {
        int nMin = 0;
        int nMax = 0;
        int nFreqCutoff = 0;
        double[] fft = null;
        // Update phone battery level - it is done here so it is called for all data sources.
        mSdData.phoneBatteryPc = getPhoneBatteryLevel();
        mSdData.phoneBattBuff.add(mSdData.phoneBatteryPc);
        mSdData.watchBattBuff.add(mSdData.batteryPc);
        try {
            // FIXME - Use specified sampleFreq, not this hard coded one
            mSampleFreq = 25;
            double freqRes = 1.0 * mSampleFreq / mSdData.mNsamp;
            Log.v(TAG, "doAnalysis(): mSampleFreq=" + mSampleFreq + " mNSamp=" + mSdData.mNsamp + ": freqRes=" + freqRes);
            Log.v(TAG, "doAnalysis(): rawData=" + Arrays.toString(mSdData.rawData));
            // Set the frequency bounds for the analysis in fft output bin numbers.
            nMin = (int) (mAlarmFreqMin / freqRes);
            nMax = (int) (mAlarmFreqMax / freqRes);
            Log.v(TAG, "doAnalysis(): mAlarmFreqMin=" + mAlarmFreqMin + ", nMin=" + nMin
                    + ", mAlarmFreqMax=" + mAlarmFreqMax + ", nMax=" + nMax);
            // Calculate the bin number of the cutoff frequency
            nFreqCutoff = (int) (mFreqCutoff / freqRes);
            Log.v(TAG, "mFreqCutoff = " + mFreqCutoff + ", nFreqCutoff=" + nFreqCutoff);

            DoubleFFT_1D fftDo = new DoubleFFT_1D(mSdData.mNsamp);
            fft = new double[mSdData.mNsamp * 2];
            ///System.arraycopy(mAccData, 0, fft, 0, mNsamp);
            System.arraycopy(mSdData.rawData, 0, fft, 0, mSdData.mNsamp);
            fftDo.realForward(fft);

            // Calculate the whole spectrum power (well a value equivalent to it that avoids square root calculations
            // and zero any readings that are above the frequency cutoff.
            double specPower = 0;
            for (int i = 1; i < mSdData.mNsamp / 2; i++) {
                if (i <= nFreqCutoff) {
                    specPower = specPower + getMagnitude(fft, i);
                } else {
                    fft[2 * i] = 0.;
                    fft[2 * i + 1] = 0.;
                }
            }
            //Log.v(TAG,"specPower = "+specPower);
            //specPower = specPower/(mSdData.mNsamp/2);
            specPower = specPower / mSdData.mNsamp / 2;
            //Log.v(TAG,"specPower = "+specPower);

            // Calculate the Region of Interest power and power ratio.
            double roiPower = 0;
            for (int i = nMin; i < nMax; i++) {
                roiPower = roiPower + getMagnitude(fft, i);
            }
            roiPower = roiPower / (nMax - nMin);
            double roiRatio = 10 * roiPower / specPower;

            // Calculate the simplified spectrum - power in 1Hz bins.
            double[] simpleSpec = new double[SIMPLE_SPEC_FMAX + 1];
            for (int ifreq = 0; ifreq < SIMPLE_SPEC_FMAX; ifreq++) {
                int binMin = (int) (1 + ifreq / freqRes);    // add 1 to loose dc component
                int binMax = (int) (1 + (ifreq + 1) / freqRes);
                simpleSpec[ifreq] = 0;
                for (int i = binMin; i < binMax; i++) {
                    simpleSpec[ifreq] = simpleSpec[ifreq] + getMagnitude(fft, i);
                }
                simpleSpec[ifreq] = simpleSpec[ifreq] / (binMax - binMin);
            }

            // Populate the mSdData structure to communicate with the main SdServer service.
            mDataStatusTime.setToNow();
            mSdData.specPower = (long) specPower / ACCEL_SCALE_FACTOR;
            mSdData.roiPower = (long) roiPower / ACCEL_SCALE_FACTOR;
            Time tnow = new Time();
            tnow.setToNow();
            if (mSdData.dataTime != null) {
                mSdData.timeDiff = (tnow.toMillis(false)
                        - mSdData.dataTime.toMillis(false)) / 1000f;
            } else {
                mSdData.timeDiff = 0f;
            }
            mSdData.dataTime.setToNow();

            mSdData.dataTime.setToNow();
            mSdData.maxVal = 0;   // not used
            mSdData.maxFreq = 0;  // not used
            mSdData.haveData = true;
            mSdData.alarmThresh = mAlarmThresh;
            mSdData.alarmRatioThresh = mAlarmRatioThresh;
            mSdData.alarmFreqMin = mAlarmFreqMin;
            mSdData.alarmFreqMax = mAlarmFreqMax;
            // note mSdData.batteryPc is set from settings data in updateFromJSON()
            // FIXME - I haven't worked out why dividing by 1000 seems necessary to get the graph on scale - we don't seem to do that with the Pebble.
            for (int i = 0; i < SIMPLE_SPEC_FMAX; i++) {
                mSdData.simpleSpec[i] = (int) simpleSpec[i] / ACCEL_SCALE_FACTOR;
            }
            Log.v(TAG, "simpleSpec = " + Arrays.toString(mSdData.simpleSpec));

            // Because we have received data, set flag to show watch app running.
            mWatchAppRunningCheck = true;
        } catch (Exception e) {
            Log.e(TAG, "doAnalysis - Exception during Analysis");
            mUtil.writeToSysLogFile("doAnalysis - Exception during analysis - " + e.toString());
            mUtil.writeToSysLogFile("doAnalysis: Exception at Line Number: " + e.getCause().getStackTrace()[0].getLineNumber() + ", " + e.getCause().getStackTrace()[0].toString());
            mUtil.writeToSysLogFile("doAnalysis: mSdData.mNsamp=" + mSdData.mNsamp);
            mUtil.writeToSysLogFile("doAnalysis: alarmFreqMin=" + mAlarmFreqMin + " nMin=" + nMin);
            mUtil.writeToSysLogFile("doAnalysis: alarmFreqMax=" + mAlarmFreqMax + " nMax=" + nMax);
            mUtil.writeToSysLogFile("doAnalysis: nFreqCutoff.=" + nFreqCutoff);
            mUtil.writeToSysLogFile("doAnalysis: fft.length=" + fft.length);
            mWatchAppRunningCheck = false;
        }


        Log.v(TAG, "after fallCheck, mSdData.fallAlarmStanding=" + mSdData.fallAlarmStanding);
    }


    /****************************************************************
     * checkAlarm() - checks the current accelerometer data and uses
     * historical data to determine if we are in a fault, warning or ok
     * state.
     * Sets mSdData.alarmState and mSdData.hrAlarmStanding
     */
    private void alarmCheck() {
        boolean inAlarm = false;
        // Avoid potential divide by zero issue
        if (mSdData.specPower == 0)
            mSdData.specPower = 1;
        Log.v(TAG, "alarmCheck() - roiPower=" + mSdData.roiPower + " specPower=" + mSdData.specPower + " ratio=" + 10 * mSdData.roiPower / mSdData.specPower);

        if (mSdData.mOsdAlarmActive) {
            // Is the current set of data representing an alarm state?
            if ((mSdData.roiPower > mAlarmThresh) && ((10 * mSdData.roiPower / mSdData.specPower) > mAlarmRatioThresh)) {
                inAlarm = true;
                mSdData.alarmCause = mSdData.alarmCause + "OsdAlg ";
            }
        }

        if (mSdData.mCnnAlarmActive) {
            if (mSdData.mPseizure > 0.5) {
                inAlarm = true;
                mSdData.alarmCause = mSdData.alarmCause + "CnnAlg ";
            }
        }

        // set the alarmState to Alarm, Warning or OK, depending on the current state and previous ones.
        if (inAlarm) {
            mAlarmCount += mSamplePeriod;
            if (mAlarmCount > mAlarmTime) {
                // full alarm
                mSdData.alarmState = 2;
            } else if (mAlarmCount > mWarnTime) {
                // warning
                mSdData.alarmState = 1;
            }
        } else {
            // If we are not in an ALARM state, revert back to WARNING, otherwise
            // revert back to OK.
            if (mSdData.alarmState == 2) {
                // revert to warning
                mSdData.alarmState = 1;
                mAlarmCount = mWarnTime + 1;  // pretend we have only just entered warning state.
            } else {
                // revert to OK
                mSdData.alarmState = 0;
                mAlarmCount = 0;
            }
        }

        Log.v(TAG, "alarmCheck(): inAlarm=" + inAlarm + ", alarmCause="
                + mSdData.alarmCause + ", alarmState = " + mSdData.alarmState
                + " alarmCount=" + mAlarmCount + " mWarnTime=" + mWarnTime
                + " mAlarmTime=" + mAlarmTime);

    }


}
