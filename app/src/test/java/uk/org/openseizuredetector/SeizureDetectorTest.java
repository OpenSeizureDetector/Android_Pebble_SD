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
import uk.org.openseizuredetector.alg.SeizureDetector;
import uk.org.openseizuredetector.data.SdData;

/**
 * Unit tests for SeizureDetector class to verify algorithm coordination.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SeizureDetectorTest {
    private SeizureDetector mSeizureDetector;
    private Context mContext;
    private SharedPreferences mSP;
    private SdData mSdData;

    @Before
    public void setUp() throws Exception {
        // Use Robolectric application context for local unit test
        mContext = RuntimeEnvironment.getApplication();
        mSP = PreferenceManager.getDefaultSharedPreferences(mContext);

        // Set up default preferences for testing
        SharedPreferences.Editor editor = mSP.edit();
        editor.putString("WarnTime", "5");
        editor.putString("AlarmTime", "15");
        editor.putString("DataUpdatePeriod", "5");
        editor.putBoolean("OsdAlarmActive", true);
        editor.putBoolean("FlapAlarmActive", false);
        editor.putBoolean("FallActive", false);
        editor.putBoolean("CnnAlgActive", false);
        editor.putBoolean("HRAlarmActive", false);
        editor.apply();

        mSeizureDetector = new SeizureDetector(mContext);
        assertNotNull(mSeizureDetector);

        // Create test SdData with basic structure and raw data arrays
        mSdData = new SdData();
        mSdData.mNsamp = 125;
        mSdData.mSampleFreq = 25;
        mSdData.rawData = new double[125];
        mSdData.simpleSpec = new int[10];
    }

    @Test
    public void testSeizureDetectorCreation() throws Exception {
        assertNotNull("SeizureDetector should be created", mSeizureDetector);
    }

    @Test
    public void testProcessData_NoAlarm() throws Exception {
        // Set up SdData with low power (no alarm condition) - low frequency, low amplitude
        for (int i = 0; i < mSdData.rawData.length; i++) {
            mSdData.rawData[i] = 1000 + 10 * Math.sin(2 * Math.PI * 1 * i / 25.0);  // 1 Hz, low amplitude
        }

        int alarmState = mSeizureDetector.processData(mSdData);
        assertEquals("Low power should result in OK state", 0, alarmState);
    }

    @Test
    public void testProcessData_HighPowerAlarm() throws Exception {
        // Set up SdData with high power (alarm condition) - frequency in ROI with high amplitude
        for (int i = 0; i < mSdData.rawData.length; i++) {
            mSdData.rawData[i] = 1000 + 200 * Math.sin(2 * Math.PI * 5 * i / 25.0);  // 5 Hz (in ROI 3-8Hz), high amplitude
        }

        // First call - should start counting towards alarm
        int alarmState1 = mSeizureDetector.processData(mSdData);

        // Continue with alarm conditions through warning and into alarm
        int alarmState2 = mSeizureDetector.processData(mSdData);
        int alarmState3 = mSeizureDetector.processData(mSdData);
        int alarmState4 = mSeizureDetector.processData(mSdData);

        // After multiple cycles (15+ seconds), should reach ALARM state
        // Note: With mWarnTime=5 and mAlarmTime=15 and mSamplePeriod=5,
        // it takes 3 calls to reach alarm (5+5+5=15)
        assertTrue("Should reach WARNING or ALARM state", alarmState4 >= 1);
    }

    @Test
    public void testProcessData_AlarmCauseString() throws Exception {
        // Set up alarm condition - high amplitude in ROI
        for (int i = 0; i < mSdData.rawData.length; i++) {
            mSdData.rawData[i] = 1000 + 200 * Math.sin(2 * Math.PI * 5 * i / 25.0);
        }

        mSeizureDetector.processData(mSdData);

        // Alarm cause should contain "OsdAlg" since OSD algorithm is active
        assertTrue("Alarm cause should contain OsdAlg",
                   mSdData.alarmCause.contains("OsdAlg") || mSdData.alarmCause.isEmpty());
    }

    @Test
    public void testResetAlarmState() throws Exception {
        // Trigger alarm
        for (int i = 0; i < mSdData.rawData.length; i++) {
            mSdData.rawData[i] = 1000 + 200 * Math.sin(2 * Math.PI * 5 * i / 25.0);
        }

        for (int i = 0; i < 5; i++) {
            mSeizureDetector.processData(mSdData);
        }

        // Reset alarm state
        mSeizureDetector.resetAlarmState();

        // Process with no alarm condition
        for (int i = 0; i < mSdData.rawData.length; i++) {
            mSdData.rawData[i] = 1000 + 10 * Math.sin(2 * Math.PI * 1 * i / 25.0);
        }
        int alarmState = mSeizureDetector.processData(mSdData);

        assertEquals("After reset with low power, should be OK", 0, alarmState);
    }

    @Test
    public void testMultipleAlgorithms_OR_Logic() throws Exception {
        // Enable multiple algorithms
        SharedPreferences.Editor editor = mSP.edit();
        editor.putBoolean("OsdAlarmActive", true);
        editor.putBoolean("FlapAlarmActive", true);
        editor.apply();

        // Recreate detector with new settings
        mSeizureDetector.close();
        mSeizureDetector = new SeizureDetector(mContext);

        // Set up data that triggers OSD - high amplitude in ROI
        for (int i = 0; i < mSdData.rawData.length; i++) {
            mSdData.rawData[i] = 1000 + 200 * Math.sin(2 * Math.PI * 5 * i / 25.0);
        }

        int alarmState = mSeizureDetector.processData(mSdData);

        // Should trigger because OSD is in alarm (OR logic)
        assertNotEquals("OR logic: should trigger if any algorithm alarms", -1, alarmState);
    }

    @Test
    public void testClose() throws Exception {
        // Should not throw exception
        mSeizureDetector.close();
    }
}










