package uk.org.openseizuredetector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import uk.org.openseizuredetector.data.SdDataHistory;

/**
 * Unit tests verifying that fall detection window statistics are correctly recorded
 * in the SdDataHistory rolling buffers.
 *
 * The fall-related buffers under test are:
 *   mFallWindowMinBuf – records the lowest window-minimum acceleration per analysis period
 *   mFallWindowMaxBuf – records the highest window-maximum acceleration per analysis period
 *   mFallEventBuf     – records 1.0 when a fall was detected, 0.0 otherwise
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SdDataHistoryFallTest {

    private SdDataHistory mHistory;

    /** Dummy ML probabilities (not under test here). */
    private static final double[] DUMMY_ML_PROBS = new double[]{0.0, 0.0, 0.0, 0.0, 0.0};

    @Before
    public void setUp() {
        mHistory = new SdDataHistory();
        assertNotNull("SdDataHistory should be created", mHistory);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /** Call addDataPoint with minimal non-fall parameters, supplying fall values explicitly. */
    private void addPoint(double fallMin, double fallMax, boolean fallDetected) {
        mHistory.addDataPoint(
                /*batteryPc*/ 80,
                /*phoneBatteryPc*/ 70,
                /*watchSignalStrength*/ -60.0,
                /*pSeizure*/ 0.0,
                /*accelMagStdDev*/ 0.0,
                /*heartRate*/ 60.0,
                /*mlModelProbs*/ DUMMY_ML_PROBS,
                fallMin, fallMax, fallDetected);
    }

    // -------------------------------------------------------------------------
    // Buffer creation / initialisation
    // -------------------------------------------------------------------------

    @Test
    public void testFallBuffersCreated() {
        assertNotNull("mFallWindowMinBuf should be non-null", mHistory.mFallWindowMinBuf);
        assertNotNull("mFallWindowMaxBuf should be non-null", mHistory.mFallWindowMaxBuf);
        assertNotNull("mFallEventBuf should be non-null",     mHistory.mFallEventBuf);
    }

    @Test
    public void testFallBuffersPreFilledWith120Zeros() {
        // Constructor pre-fills the buffers with 120 zeros so charts have initial data.
        assertEquals("mFallWindowMinBuf should start with 120 samples",
                120, mHistory.mFallWindowMinBuf.getNumVals());
        assertEquals("mFallWindowMaxBuf should start with 120 samples",
                120, mHistory.mFallWindowMaxBuf.getNumVals());
        assertEquals("mFallEventBuf should start with 120 samples",
                120, mHistory.mFallEventBuf.getNumVals());

        double[] minVals = mHistory.mFallWindowMinBuf.getVals();
        for (int i = 0; i < minVals.length; i++) {
            assertEquals("Pre-filled mFallWindowMinBuf entry " + i + " should be 0.0",
                    0.0, minVals[i], 0.001);
        }
    }

    // -------------------------------------------------------------------------
    // Normal data point recording
    // -------------------------------------------------------------------------

    @Test
    public void testAddPoint_ValidFallData_RecordedCorrectly() {
        // After construction the buffers are full (120 samples of 0.0); adding one more
        // point should push out the oldest zero and record the new values.
        addPoint(300.0, 2500.0, false);

        double[] minVals = mHistory.mFallWindowMinBuf.getVals();
        double[] maxVals = mHistory.mFallWindowMaxBuf.getVals();
        double[] eventVals = mHistory.mFallEventBuf.getVals();

        // Buffer capacity is 120; the newest value is always the last element.
        assertEquals("Most recent mFallWindowMin should be 300.0",
                300.0, minVals[minVals.length - 1], 0.001);
        assertEquals("Most recent mFallWindowMax should be 2500.0",
                2500.0, maxVals[maxVals.length - 1], 0.001);
        assertEquals("Most recent mFallEvent should be 0.0 (no fall)",
                0.0, eventVals[eventVals.length - 1], 0.001);
    }

    @Test
    public void testAddPoint_FallDetected_EventRecordedAs1() {
        addPoint(200.0, 2800.0, true);

        double[] eventVals = mHistory.mFallEventBuf.getVals();
        assertEquals("mFallEvent should be 1.0 when a fall is detected",
                1.0, eventVals[eventVals.length - 1], 0.001);
    }

    // -------------------------------------------------------------------------
    // Sentinel (-1.0) handling – invalid data should be stored as 0.0
    // -------------------------------------------------------------------------

    @Test
    public void testAddPoint_SentinelMin_StoredAsZero() {
        // -1.0 signals "window too large / no data"; should be stored as 0.0, not -1.0.
        addPoint(-1.0, -1.0, false);

        double[] minVals = mHistory.mFallWindowMinBuf.getVals();
        double[] maxVals = mHistory.mFallWindowMaxBuf.getVals();

        assertEquals("Sentinel mFallWindowMin (-1.0) should be stored as 0.0",
                0.0, minVals[minVals.length - 1], 0.001);
        assertEquals("Sentinel mFallWindowMax (-1.0) should be stored as 0.0",
                0.0, maxVals[maxVals.length - 1], 0.001);
    }

    // -------------------------------------------------------------------------
    // Multiple points and wrap-around
    // -------------------------------------------------------------------------

    @Test
    public void testAddMultiplePoints_LastValuesCorrect() {
        // Add a few distinct points and verify the most recent values.
        addPoint(100.0, 1000.0, false);
        addPoint(150.0, 1500.0, false);
        addPoint(200.0, 2000.0, true);  // <- most recent

        double[] minVals = mHistory.mFallWindowMinBuf.getVals();
        double[] maxVals = mHistory.mFallWindowMaxBuf.getVals();
        double[] eventVals = mHistory.mFallEventBuf.getVals();

        assertEquals("Most recent min should be 200.0", 200.0, minVals[minVals.length - 1], 0.001);
        assertEquals("Most recent max should be 2000.0", 2000.0, maxVals[maxVals.length - 1], 0.001);
        assertEquals("Most recent event should be 1.0", 1.0, eventVals[eventVals.length - 1], 0.001);

        // Second-most-recent
        assertEquals("Second-to-last min should be 150.0", 150.0, minVals[minVals.length - 2], 0.001);
        assertEquals("Second-to-last event should be 0.0", 0.0, eventVals[eventVals.length - 2], 0.001);
    }

    /**
     * Verify circular-buffer wrap: add 130 points (10 more than capacity = 120).
     * Buffer should still contain exactly 120 values, all from the most recent 120 additions.
     */
    @Test
    public void testBufferWrap_CapacityMaintained() {
        // Buffers already have 120 zeros from construction.  Adding 130 more overwrites all of them.
        for (int i = 0; i < 130; i++) {
            addPoint(i * 10.0, i * 10.0 + 1.0, (i % 10 == 0));
        }

        assertEquals("mFallWindowMinBuf should hold exactly 120 values after wrap",
                120, mHistory.mFallWindowMinBuf.getNumVals());
        assertEquals("mFallWindowMaxBuf should hold exactly 120 values after wrap",
                120, mHistory.mFallWindowMaxBuf.getNumVals());
        assertEquals("mFallEventBuf should hold exactly 120 values after wrap",
                120, mHistory.mFallEventBuf.getNumVals());

        // The most recent value (i=129) should be present at the end of the buffer.
        double[] minVals = mHistory.mFallWindowMinBuf.getVals();
        assertEquals("Most recent min after wrap should be 129*10=1290",
                1290.0, minVals[minVals.length - 1], 0.001);
    }
}

