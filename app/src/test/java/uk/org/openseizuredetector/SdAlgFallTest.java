package uk.org.openseizuredetector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
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
import uk.org.openseizuredetector.alg.SdAlgFall;
import uk.org.openseizuredetector.data.SdData;

/**
 * Unit tests for SdAlgFall (Fall detection algorithm).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SdAlgFallTest {
    private SdAlgFall mSdAlgFall;
    private Context mContext;
    private SharedPreferences mSP;
    private SdData mSdData;

    @Before
    public void setUp() throws Exception {
        mContext = RuntimeEnvironment.getApplication();
        mSP = PreferenceManager.getDefaultSharedPreferences(mContext);

        // Set up default preferences
        SharedPreferences.Editor editor = mSP.edit();
        editor.putBoolean("FallActive", true);
        editor.putString("FallThreshMin", "500");
        editor.putString("FallThreshMax", "2000");
        editor.putString("FallWindow", "500");
        editor.apply();

        mSdAlgFall = new SdAlgFall(mContext);
        assertNotNull(mSdAlgFall);

        mSdData = new SdData();
        mSdData.mNsamp = 125;
        mSdData.mSampleFreq = 25;
        mSdData.rawData = new double[125];
    }

    @Test
    public void testAlgorithmCreation() throws Exception {
        assertNotNull("SdAlgFall should be created", mSdAlgFall);
        assertEquals("Alarm cause should be FALL", "FALL", mSdAlgFall.getAlarmCause());
    }

    @Test
    public void testProcessSdData_NormalMovement_NoAlarm() throws Exception {
        // Fill with normal acceleration values (around 1000 = 1g)
        for (int i = 0; i < mSdData.rawData.length; i++) {
            mSdData.rawData[i] = 1000;
        }

        int result = mSdAlgFall.processSdData(mSdData);
        assertEquals("Normal movement should not trigger fall alarm", 0, result);
    }

    @Test
    public void testProcessSdData_FallPattern_Alarm() throws Exception {
        // Simulate fall: period of low acceleration followed by high impact
        for (int i = 0; i < mSdData.rawData.length; i++) {
            if (i < 50) {
                // Free fall phase (very low acceleration)
                mSdData.rawData[i] = 200;  // Below FallThreshMin (500)
            } else {
                // Impact phase (very high acceleration)
                mSdData.rawData[i] = 2500;  // Above FallThreshMax (2000)
            }
        }

        int result = mSdAlgFall.processSdData(mSdData);
        assertEquals("Fall pattern should trigger alarm", 2, result);
    }

    @Test
    public void testProcessSdData_OnlyLowAccel_NoAlarm() throws Exception {
        // Only low acceleration without high impact
        for (int i = 0; i < mSdData.rawData.length; i++) {
            mSdData.rawData[i] = 300;  // Below FallThreshMin but no high impact
        }

        int result = mSdAlgFall.processSdData(mSdData);
        assertEquals("Low acceleration alone should not trigger fall", 0, result);
    }

    @Test
    public void testProcessSdData_OnlyHighAccel_NoAlarm() throws Exception {
        // Only high acceleration without low period
        for (int i = 0; i < mSdData.rawData.length; i++) {
            mSdData.rawData[i] = 2500;  // Above FallThreshMax but no low period
        }

        int result = mSdAlgFall.processSdData(mSdData);
        assertEquals("High acceleration alone should not trigger fall", 0, result);
    }

    @Test
    public void testProcessSdData_Inactive() throws Exception {
        // Disable algorithm
        SharedPreferences.Editor editor = mSP.edit();
        editor.putBoolean("FallActive", false);
        editor.apply();

        // Recreate algorithm
        mSdAlgFall = new SdAlgFall(mContext);

        // Note: With the refactoring, algorithms no longer check their own active state.
        // SeizureDetector controls which algorithms are active and doesn't call inactive ones.
        // Fall pattern should still be detected if the algorithm is called
        for (int i = 0; i < mSdData.rawData.length; i++) {
            if (i < 50) {
                mSdData.rawData[i] = 200;
            } else {
                mSdData.rawData[i] = 2500;
            }
        }

        int result = mSdAlgFall.processSdData(mSdData);
        // Algorithm processes data normally - SeizureDetector handles the active/inactive logic
        assertTrue("Algorithm processes data regardless of preference", result >= 0);
    }

    @Test
    public void testClose() throws Exception {
        // Should not throw exception
        mSdAlgFall.close();
    }

    // -------------------------------------------------------------------------
    // Tests for mFallWindowMin / mFallWindowMax / mFallDetected population
    // -------------------------------------------------------------------------

    /**
     * Normal movement (constant 1000 mg): no fall should be detected, and both window
     * statistics should be populated with valid (non-negative) values.
     */
    @Test
    public void testWindowStats_NormalMovement_PopulatedAndNoFall() throws Exception {
        for (int i = 0; i < mSdData.rawData.length; i++) {
            mSdData.rawData[i] = 1000.0;
        }
        int result = mSdAlgFall.processSdData(mSdData);

        // Window stats should be populated (not -1.0 sentinel)
        assertTrue("mFallWindowMin should be populated for normal data",
                mSdData.mFallWindowMin >= 0);
        assertTrue("mFallWindowMax should be populated for normal data",
                mSdData.mFallWindowMax >= 0);

        // Min <= Max always
        assertTrue("mFallWindowMin must be <= mFallWindowMax",
                mSdData.mFallWindowMin <= mSdData.mFallWindowMax);

        // No fall should be detected: processSdData returns OK and fallAlgState (set by the
        // caller SeizureDetector in production) is derived from the return value.
        assertNotEquals("Normal movement should not return ALARM state",
                uk.org.openseizuredetector.data.AlarmState.ALARM, result);

        // For constant 1000 mg data the min and max in any window should both equal 1000
        assertEquals("mFallWindowMin should equal the constant value", 1000.0, mSdData.mFallWindowMin, 0.01);
        assertEquals("mFallWindowMax should equal the constant value", 1000.0, mSdData.mFallWindowMax, 0.01);
    }

    /**
     * Fall pattern: free-fall phase (200 mg) followed by impact phase (2500 mg).
     * mFallWindowMin should be close to 200, mFallWindowMax close to 2500,
     * and processSdData() should return ALARM.
     */
    @Test
    public void testWindowStats_FallPattern_CorrectMinMaxAndDetected() throws Exception {
        for (int i = 0; i < mSdData.rawData.length; i++) {
            mSdData.rawData[i] = (i < 50) ? 200.0 : 2500.0;
        }
        int result = mSdAlgFall.processSdData(mSdData);

        // Overall minimum across all windows should track the free-fall value (200)
        assertEquals("mFallWindowMin should be 200 during free-fall phase",
                200.0, mSdData.mFallWindowMin, 1.0);

        // Overall maximum across all windows should track the impact value (2500)
        assertEquals("mFallWindowMax should be 2500 during impact phase",
                2500.0, mSdData.mFallWindowMax, 1.0);

        // processSdData() returns ALARM when a fall is detected.
        // SeizureDetector stores this return value in sdData.fallAlgState.
        assertEquals("Fall pattern should return ALARM",
                uk.org.openseizuredetector.data.AlarmState.ALARM, result);
    }

    /**
     * Only low acceleration (300 mg, below FallThreshMin) but no high impact:
     * mFallWindowMin should be ~300, mFallWindowMax should be ~300, no alarm.
     */
    @Test
    public void testWindowStats_OnlyLowAccel_MinMaxBothLowNoFall() throws Exception {
        for (int i = 0; i < mSdData.rawData.length; i++) {
            mSdData.rawData[i] = 300.0;
        }
        int result = mSdAlgFall.processSdData(mSdData);

        assertEquals("mFallWindowMin should be ~300", 300.0, mSdData.mFallWindowMin, 1.0);
        assertEquals("mFallWindowMax should be ~300", 300.0, mSdData.mFallWindowMax, 1.0);
        assertNotEquals("Low-only acceleration should not return ALARM",
                uk.org.openseizuredetector.data.AlarmState.ALARM, result);
    }

    /**
     * Only high acceleration (2500 mg) but no low (free-fall) phase:
     * mFallWindowMin and mFallWindowMax should both be ~2500, no alarm.
     */
    @Test
    public void testWindowStats_OnlyHighAccel_MinMaxBothHighNoFall() throws Exception {
        for (int i = 0; i < mSdData.rawData.length; i++) {
            mSdData.rawData[i] = 2500.0;
        }
        int result = mSdAlgFall.processSdData(mSdData);

        assertEquals("mFallWindowMin should be ~2500", 2500.0, mSdData.mFallWindowMin, 1.0);
        assertEquals("mFallWindowMax should be ~2500", 2500.0, mSdData.mFallWindowMax, 1.0);
        assertNotEquals("High-only acceleration should not return ALARM",
                uk.org.openseizuredetector.data.AlarmState.ALARM, result);
    }

    /**
     * Edge case: window size (500 ms window at 25 Hz = 12 samples) larger than
     * the sample buffer (set mNsamp = 5).  The loop body should never execute,
     * so the sentinel value -1.0 should be stored in both window-stat fields.
     */
    @Test
    public void testWindowStats_WindowLargerThanBuffer_SentinelValues() throws Exception {
        // Make the buffer smaller than the window so the loop body is never entered
        mSdData.mNsamp = 5;  // 5 samples < 12 samples (500ms window at 25 Hz)
        for (int i = 0; i < mSdData.rawData.length; i++) {
            mSdData.rawData[i] = 1000.0;
        }
        int result = mSdAlgFall.processSdData(mSdData);

        assertEquals("mFallWindowMin should be -1.0 when window > buffer",
                -1.0, mSdData.mFallWindowMin, 0.001);
        assertEquals("mFallWindowMax should be -1.0 when window > buffer",
                -1.0, mSdData.mFallWindowMax, 0.001);
        // No fall detected when no data was processed
        assertNotEquals("Should not return ALARM when no data processed",
                uk.org.openseizuredetector.data.AlarmState.ALARM, result);
    }
}

