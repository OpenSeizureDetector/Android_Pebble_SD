package uk.org.openseizuredetector.alg;

import android.content.Context;
import android.util.Log;

import org.jtransforms.fft.DoubleFFT_1D;

import java.util.Arrays;

import uk.org.openseizuredetector.data.SdData;

/**
 * OpenSeizureDetector (OSD) algorithm - frequency-based seizure detection.
 * Performs FFT analysis on raw accelerometer data and analyzes power in region of interest (ROI).
 */
public class SdAlgOsd extends SdAlgBase {
    private final static String TAG = "SdAlgOsd";
    private static final int ACCEL_SCALE_FACTOR = 1000;
    private static final int SIMPLE_SPEC_FMAX = 10;

    private short mAlarmThresh;
    private short mAlarmRatioThresh;
    private short mAlarmFreqMin;
    private short mAlarmFreqMax;
    private short mFreqCutoff = 12;
    private short mSampleFreq = 25;  // FIXME - should come from sdData

    public SdAlgOsd(Context context) {
        super(context);
        Log.d(TAG, "SdAlgOsd Constructor");
        updatePrefs();
    }

    private void updatePrefs() {
        Log.d(TAG, "updatePrefs()");
        try {
            String threshStr = mSP.getString("AlarmThresh", "100");
            mAlarmThresh = Short.parseShort(threshStr);
            String ratioStr = mSP.getString("AlarmRatioThresh", "57");
            mAlarmRatioThresh = Short.parseShort(ratioStr);
            String freqMinStr = mSP.getString("AlarmFreqMin", "3");
            mAlarmFreqMin = Short.parseShort(freqMinStr);
            String freqMaxStr = mSP.getString("AlarmFreqMax", "8");
            mAlarmFreqMax = Short.parseShort(freqMaxStr);
            Log.v(TAG, "updatePrefs(): mAlarmThresh=" + mAlarmThresh +
                    ", mAlarmRatioThresh=" + mAlarmRatioThresh +
                    ", mAlarmFreqMin=" + mAlarmFreqMin +
                    ", mAlarmFreqMax=" + mAlarmFreqMax);
        } catch (Exception ex) {
            Log.e(TAG, "updatePrefs() - Problem parsing preferences: " + ex.toString());
            mAlarmThresh = 100;
            mAlarmRatioThresh = 57;
            mAlarmFreqMin = 3;
            mAlarmFreqMax = 8;
        }
    }

    /**
     * Calculate the magnitude of entry i in the fft array
     */
    private double getMagnitude(double[] fft, int i) {
        return (fft[2 * i] * fft[2 * i] + fft[2 * i + 1] * fft[2 * i + 1]);
    }

    /**
     * Convert frequency in Hz to FFT bin number
     */
    private int freq2FftBin(double freqHz, double sampleFreq, int nSamp) {
        return (int)(nSamp * freqHz / sampleFreq);
    }

    @Override
    public String getAlarmCause() {
        return "OsdAlg";
    }

    @Override
    public int processSdData(SdData sdData) {
        Log.d(TAG, "processSdData()");
        // ...existing code...

        int nMin = 0;
        int nMax = 0;
        int nFreqCutoff = 0;
        double[] fft = null;

        try {
            // FIXME - Use specified sampleFreq, not this hard coded one
            mSampleFreq = 25;
            double freqRes = 1.0 * mSampleFreq / sdData.mNsamp;
            Log.v(TAG, "processSdData(): mSampleFreq=" + mSampleFreq + " mNSamp=" + sdData.mNsamp + ": freqRes=" + freqRes);
            Log.v(TAG, "processSdData(): rawData=" + Arrays.toString(sdData.rawData));

            // Set the frequency bounds for the analysis in fft output bin numbers.
            nMin = freq2FftBin(mAlarmFreqMin, mSampleFreq, sdData.mNsamp);
            nMax = freq2FftBin(mAlarmFreqMax, mSampleFreq, sdData.mNsamp);
            nFreqCutoff = freq2FftBin(mFreqCutoff, mSampleFreq, sdData.mNsamp);

            Log.v(TAG, "processSdData(): mAlarmFreqMin=" + mAlarmFreqMin + ", nMin=" + nMin
                    + ", mAlarmFreqMax=" + mAlarmFreqMax + ", nMax=" + nMax);
            Log.v(TAG, "mFreqCutoff = " + mFreqCutoff + ", nFreqCutoff=" + nFreqCutoff);

            // Perform FFT
            DoubleFFT_1D fftDo = new DoubleFFT_1D(sdData.mNsamp);
            fft = new double[sdData.mNsamp * 2];
            System.arraycopy(sdData.rawData, 0, fft, 0, sdData.mNsamp);
            fftDo.realForward(fft);

            // Calculate the whole spectrum power and zero frequencies above cutoff
            double specPower = 0;
            for (int i = 1; i < sdData.mNsamp / 2; i++) {
                if (i <= nFreqCutoff) {
                    specPower = specPower + getMagnitude(fft, i);
                } else {
                    fft[2 * i] = 0.;
                    fft[2 * i + 1] = 0.;
                }
            }
            specPower = specPower / sdData.mNsamp / 2;

            // Calculate the Region of Interest power and power ratio
            double roiPower = 0;
            for (int i = nMin; i < nMax; i++) {
                roiPower = roiPower + getMagnitude(fft, i);
            }
            roiPower = roiPower / (nMax - nMin);
            double roiRatio = 10 * roiPower / specPower;

            // Calculate the simplified spectrum - power in 1Hz bins
            double[] simpleSpec = new double[SIMPLE_SPEC_FMAX + 1];
            for (int ifreq = 0; ifreq < SIMPLE_SPEC_FMAX; ifreq++) {
                int binMin = (int) (1 + ifreq / freqRes);    // add 1 to lose dc component
                int binMax = (int) (1 + (ifreq + 1) / freqRes);
                simpleSpec[ifreq] = 0;
                for (int i = binMin; i < binMax; i++) {
                    simpleSpec[ifreq] = simpleSpec[ifreq] + getMagnitude(fft, i);
                }
                simpleSpec[ifreq] = simpleSpec[ifreq] / (binMax - binMin);
            }

            // Populate SdData with analysis results for UI/logging
            sdData.specPower = (long) specPower / ACCEL_SCALE_FACTOR;
            sdData.roiPower = (long) roiPower / ACCEL_SCALE_FACTOR;
            sdData.alarmThresh = mAlarmThresh;
            sdData.alarmRatioThresh = mAlarmRatioThresh;
            sdData.alarmFreqMin = mAlarmFreqMin;
            sdData.alarmFreqMax = mAlarmFreqMax;

            for (int i = 0; i < SIMPLE_SPEC_FMAX; i++) {
                sdData.simpleSpec[i] = (int) simpleSpec[i] / ACCEL_SCALE_FACTOR;
            }
            Log.v(TAG, "simpleSpec = " + Arrays.toString(sdData.simpleSpec));

            // Avoid potential divide by zero issue
            if (sdData.specPower == 0) {
                sdData.specPower = 1;
            }

            Log.v(TAG, "processSdData() - roiPower=" + sdData.roiPower +
                    " specPower=" + sdData.specPower +
                    " ratio=" + 10 * sdData.roiPower / sdData.specPower);

            // Check if current data represents an alarm state
            boolean inAlarm = false;
            if ((sdData.roiPower > mAlarmThresh) &&
                ((10 * sdData.roiPower / sdData.specPower) > mAlarmRatioThresh)) {
                inAlarm = true;
                Log.i(TAG, "processSdData() - OSD ALARM detected!");
            }

            return inAlarm ? 2 : 0;  // 2=ALARM, 0=OK

        } catch (Exception e) {
            Log.e(TAG, "processSdData() - Exception during Analysis: " + e.toString());
            return 0;
        }
    }
}