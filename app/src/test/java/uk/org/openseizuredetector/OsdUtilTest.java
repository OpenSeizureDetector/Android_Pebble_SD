package uk.org.openseizuredetector;

import android.app.Activity;

import org.junit.Before;
import org.junit.Test;
import java.util.regex.Pattern;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.hamcrest.Matchers.is;

import static org.junit.Assert.*;

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
        OsdUtil util = new OsdUtil(null);
        assertThat(util.isServerRunning(), is(true));
        assertThat(true, is (true));
        //assertThat(true, is(false));
    }

    @Test
    public void testStopServer() throws Exception {

    }
}