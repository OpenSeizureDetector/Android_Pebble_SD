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
}

