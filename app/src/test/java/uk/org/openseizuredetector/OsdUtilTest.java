package uk.org.openseizuredetector;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import androidx.test.platform.app.InstrumentationRegistry;
import java.util.regex.Pattern;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.hamcrest.Matchers.is;

import static org.junit.Assert.*;

/**
 * Created by graham on 01/01/16.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {Build.VERSION_CODES.O_MR1}, packageName = "uk.org.openseizuredetector")
public class OsdUtilTest {

    @Test
    public void testIsServerRunning() throws Exception {

    }

    @Test
    public void testStartServer() throws Exception {
        //Activity a = new Activity();
        assertTrue(true);
        Context testContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        assertEquals("uk.org.openseizuredetector", testContext.getPackageName());
        Looper looper = testContext.getMainLooper();
        Handler handler = new Handler(looper);
        OsdUtil util = new OsdUtil(testContext,handler);
        assertThat(util.isServerRunning(), is(true));
        assertThat(true, is (true));
        //assertThat(true, is(false));
    }

    @Test
    public void testStopServer() throws Exception {

    }
}