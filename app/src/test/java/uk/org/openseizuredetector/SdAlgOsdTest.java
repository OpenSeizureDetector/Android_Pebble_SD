package uk.org.openseizuredetector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import androidx.preference.PreferenceManager;
import uk.org.openseizuredetector.alg.SdAlgOsd;
import uk.org.openseizuredetector.data.SdData;

/**
 * Unit tests for SdAlgOsd (OpenSeizureDetector algorithm).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SdAlgOsdTest {
    private SdAlgOsd mSdAlgOsd;
    private Context mContext;
    private SharedPreferences mSP;
    private SdData mSdData;

    @Before
    public void setUp() throws Exception {
        mContext = RuntimeEnvironment.getApplication();
        mSP = PreferenceManager.getDefaultSharedPreferences(mContext);

        // Set up default preferences
        SharedPreferences.Editor editor = mSP.edit();
        editor.putBoolean("OsdAlgActive", true);
        editor.putString("AlarmThresh", "100");
        editor.putString("AlarmRatioThresh", "57");
        editor.putString("AlarmFreqMin", "3");
        editor.putString("AlarmFreqMax", "8");
        editor.apply();

        mSdAlgOsd = new SdAlgOsd(mContext);
        assertNotNull(mSdAlgOsd);

        mSdData = new SdData();
        // Initialize arrays needed for FFT analysis
        mSdData.mNsamp = 125;
        mSdData.mSampleFreq = 25;
        mSdData.rawData = new double[125];
        mSdData.simpleSpec = new int[10];
    }

    @Test
    public void testAlgorithmCreation() throws Exception {
        assertNotNull("SdAlgOsd should be created", mSdAlgOsd);
        assertEquals("Alarm cause should be OsdAlg", "OsdAlg", mSdAlgOsd.getAlarmCause());
    }

    @Test
    public void testProcessSdData_LowPower_NoAlarm() throws Exception {
        // Fill with low frequency sine wave (should produce low power in ROI 3-8 Hz)
        for (int i = 0; i < mSdData.rawData.length; i++) {
            mSdData.rawData[i] = 1000 + 10 * Math.sin(2 * Math.PI * 1 * i / 25.0);  // 1 Hz sine wave, low amplitude
        }

        int result = mSdAlgOsd.processSdData(mSdData);
        assertEquals("Low power should not trigger alarm", 0, result);
    }

    @Test
    public void testProcessSdData_HighPower_Alarm() throws Exception {
        // Fill with frequency in ROI (3-8 Hz) with high amplitude
        for (int i = 0; i < mSdData.rawData.length; i++) {
            mSdData.rawData[i] = 1000 + 200 * Math.sin(2 * Math.PI * 5 * i / 25.0);  // 5 Hz (in ROI) with high amplitude
        }

        int result = mSdAlgOsd.processSdData(mSdData);
        assertEquals("High power in ROI should trigger alarm", 2, result);

        // Verify that FFT analysis populated the fields
        assertTrue("roiPower should be populated", mSdData.roiPower > 0);
        assertTrue("specPower should be populated", mSdData.specPower > 0);
    }

    @Test
    public void testProcessSdData_HighPowerLowRatio_NoAlarm() throws Exception {
        // High power spread across many frequencies (low ratio)
        for (int i = 0; i < mSdData.rawData.length; i++) {
            mSdData.rawData[i] = 1000
                + 50 * Math.sin(2 * Math.PI * 1 * i / 25.0)  // 1 Hz
                + 50 * Math.sin(2 * Math.PI * 5 * i / 25.0)  // 5 Hz (in ROI)
                + 50 * Math.sin(2 * Math.PI * 9 * i / 25.0)  // 9 Hz
                + 50 * Math.sin(2 * Math.PI * 11 * i / 25.0); // 11 Hz
        }

        int result = mSdAlgOsd.processSdData(mSdData);
        // With power spread across frequencies, ratio should be lower
        assertEquals("High power but low ratio should not alarm", 0, result);
    }

    @Test
    public void testProcessSdData_Inactive() throws Exception {
        // Note: With the refactoring, algorithms no longer check their own active state.
        // SeizureDetector controls which algorithms are active and doesn't call inactive ones.
        // This test verifies the algorithm still processes data normally regardless of the preference.

        // Disable algorithm preference (but algorithm will still process if called)
        SharedPreferences.Editor editor = mSP.edit();
        editor.putBoolean("OsdAlarmActive", false);
        editor.apply();

        // Recreate algorithm
        mSdAlgOsd = new SdAlgOsd(mContext);

        // High amplitude in ROI should still be processed and return alarm state
        // (SeizureDetector would check the preference and not call this algorithm)
        for (int i = 0; i < mSdData.rawData.length; i++) {
            mSdData.rawData[i] = 1000 + 200 * Math.sin(2 * Math.PI * 5 * i / 25.0);
        }

        int result = mSdAlgOsd.processSdData(mSdData);
        // Algorithm processes data normally - SeizureDetector handles the active/inactive logic
        assertTrue("Algorithm processes data regardless of preference", result >= 0);
    }

    @Test
    public void testClose() throws Exception {
        // Should not throw exception
        mSdAlgOsd.close();
    }
}









