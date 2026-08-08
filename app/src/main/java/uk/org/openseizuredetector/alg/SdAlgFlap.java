package uk.org.openseizuredetector.alg;

import android.content.Context;
import uk.org.openseizuredetector.data.logging.Log;

import org.jtransforms.fft.DoubleFFT_1D;

import uk.org.openseizuredetector.data.SdData;

/**
 * Flap algorithm - detects flapping arm movements using narrow frequency band analysis.
 * Similar to OSD algorithm but over a specific frequency range.
 */
public class SdAlgFlap extends SdAlgBase {
    private final static String TAG = "SdAlgFlap";
    private static final int ACCEL_SCALE_FACTOR = 1000;  // Acceleration data scaling factor
    private static final int SIMPLE_SPEC_FMAX = 10;  // NEW (issue #238): number of 1Hz spectrum bins, matches SdAlgOsd

    private short mFlapThresh;
    private short mFlapRatioThresh;
    private double mFlapFreqMin;
    private double mFlapFreqMax;
    private short mFreqCutoff = 12;
    private short mSampleFreq = 25;  // FIXME - should come from sdData

    public SdAlgFlap(Context context) {
        super(context);
        Log.d(TAG, "SdAlgFlap Constructor");
        updatePrefs();
    }

    private void updatePrefs() {
        try {
            String threshStr = mSP.getString("FlapAlarmThresh", "SET_FROM_XML");
            mFlapThresh = Short.parseShort(threshStr);
            String ratioStr = mSP.getString("FlapAlarmRatioThresh", "SET_FROM_XML");
            mFlapRatioThresh = Short.parseShort(ratioStr);
            mFlapFreqMin = readDoublePref("FlapAlarmFreqMin", "SET_FROM_XML");
            mFlapFreqMax = readDoublePref("FlapAlarmFreqMax", "SET_FROM_XML");
            Log.v(TAG, "updatePrefs(): mFlapThresh=" + mFlapThresh +
                    ", mFlapRatioThresh=" + mFlapRatioThresh +
                    ", mFlapFreqMin=" + mFlapFreqMin +
                    ", mFlapFreqMax=" + mFlapFreqMax);
        } catch (Exception ex) {
            Log.e(TAG, "updatePrefs() - Problem parsing preferences: " + ex.toString());
            mFlapThresh = 10;
            mFlapRatioThresh = 55;
            mFlapFreqMin = 2.5;
            mFlapFreqMax = 4.0;
        }
    }

    @Override
    public String getAlarmCause() {
        return "Flap";
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
    public int processSdData(SdData sdData) {
        // ...existing code...

        boolean flapDetected = false;
        int nMin = 0;
        int nMax = 0;
        int nFreqCutoff = 0;
        double[] fft = null;
        double roiRatio;
        double roiPower;

        try {
            // FIXME - Use specified sampleFreq, not this hard coded one
            mSampleFreq = 25;
            double freqRes = 1.0 * mSampleFreq / sdData.mNsamp;
            Log.v(TAG, "processSdData(): mSampleFreq=" + mSampleFreq + " mNSamp=" + sdData.mNsamp + ": freqRes=" + freqRes);

            // Set the frequency bounds for the analysis in fft output bin numbers.
            nMin = freq2FftBin(mFlapFreqMin, mSampleFreq, sdData.mNsamp);
            nMax = freq2FftBin(mFlapFreqMax, mSampleFreq, sdData.mNsamp);
            nFreqCutoff = freq2FftBin(mFreqCutoff, mSampleFreq, sdData.mNsamp);

            Log.v(TAG, "processSdData(): flapFreqMin=" + mFlapFreqMin + ", nMin=" + nMin
                    + ", flapFreqMax=" + mFlapFreqMax + ", nMax=" + nMax);

            DoubleFFT_1D fftDo = new DoubleFFT_1D(sdData.mNsamp);
            fft = new double[sdData.mNsamp * 2];
            System.arraycopy(sdData.rawData, 0, fft, 0, sdData.mNsamp);
            fftDo.realForward(fft);

            // Calculate the whole spectrum power
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
            specPower = specPower / ACCEL_SCALE_FACTOR;

            // Calculate the Region of Interest power and power ratio.
            roiPower = 0;
            for (int i = nMin; i < nMax; i++) {
                roiPower = roiPower + getMagnitude(fft, i);
            }
            roiPower = roiPower / (nMax - nMin);
            roiPower = roiPower / ACCEL_SCALE_FACTOR;

            roiRatio = 10 * roiPower / specPower;

            Log.d(TAG, "processSdData() - roiPower=" + roiPower + ", roiRatio=" + roiRatio);

            // NEW (issue #238): calculate the simplified spectrum - power in 1Hz bins -
            // the same way SdAlgOsd.processSdData() does for the OSD algorithm. Previously
            // this algorithm computed roiPower/roiRatio for its own alarm decision below
            // but never exposed a spectrum, so there was nothing for a "Flap" graph tab
            // to plot. fft here already has the same nFreqCutoff filtering applied above.
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

            // NEW (issue #238): populate SdData so FragmentFlapAlg (the new "Flap" tab)
            // has data to display, exactly as SdAlgOsd does for FragmentOsdAlg/the "OSD"
            // tab. Note specPower/roiPower are NOT divided by ACCEL_SCALE_FACTOR again
            // here - that division already happened above (lines computing specPower and
            // roiPower), unlike SdAlgOsd where the scaling is applied later at assignment.
            sdData.flapSpecPower = (long) specPower;
            sdData.flapRoiPower = (long) roiPower;
            sdData.flapAlarmThresh = mFlapThresh;
            sdData.flapAlarmRatioThresh = mFlapRatioThresh;
            sdData.flapAlarmFreqMin = (long) mFlapFreqMin;
            sdData.flapAlarmFreqMax = (long) mFlapFreqMax;
            for (int i = 0; i < SIMPLE_SPEC_FMAX; i++) {
                // simpleSpec here is still on the raw/unscaled magnitude, so it does need
                // the ACCEL_SCALE_FACTOR division, to end up on the same scale as
                // flapRoiPower/flapAlarmThresh above (mirrors SdAlgOsd's simpleSpec population).
                sdData.flapSimpleSpec[i] = (int) simpleSpec[i] / ACCEL_SCALE_FACTOR;
            }
            Log.v(TAG, "flapSimpleSpec = " + java.util.Arrays.toString(sdData.flapSimpleSpec));

        } catch (Exception e) {
            Log.e(TAG, "processSdData() - Exception during Analysis: " + e.toString());
            roiRatio = 0;
            roiPower = 0;
        }

        if (roiPower > mFlapThresh) {
            if (roiRatio > mFlapRatioThresh) {
                Log.i(TAG, "processSdData() - *** flap detected ***");
                flapDetected = true;
            }
        }

        return flapDetected ? 2 : 0;  // 2=ALARM, 0=OK
    }
}
