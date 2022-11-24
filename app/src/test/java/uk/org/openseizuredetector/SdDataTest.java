package uk.org.openseizuredetector;

import android.os.Build;

import junit.framework.TestCase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {Build.VERSION_CODES.O_MR1}, packageName = "uk.org.openseizuredetector")
public class SdDataTest extends TestCase {
    public void setUp() throws Exception {
        super.setUp();
    }

    public void tearDown() throws Exception {
    }

    @Test
    public void testConstructor() {
        SdData sd = new SdData();
        assertTrue(true);

    }

    public void testFromJSON() {
    }

    public void testTestToString() {
    }

    public void testToJSON() {
    }

    public void testToDataString() {
    }

    public void testToCSVString() {
    }
}