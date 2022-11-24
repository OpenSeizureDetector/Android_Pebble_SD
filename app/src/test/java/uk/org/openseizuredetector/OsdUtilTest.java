package uk.org.openseizuredetector;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import android.os.Handler;

import org.junit.Test;

/**
 * Created by graham on 01/01/16.
 */
public class OsdUtilTest {

    @Test
    public void testIsServerRunning() throws Exception {

    }

    @Test
    public void testStartServer() throws Exception {
        //Activity a = new Activity();
        Handler handler = new Handler();
        OsdUtil util = new OsdUtil(null,handler);
        assertThat(util.isServerRunning(), is(true));
        assertThat(true, is (true));
        //assertThat(true, is(false));
    }

    @Test
    public void testStopServer() throws Exception {

    }
}