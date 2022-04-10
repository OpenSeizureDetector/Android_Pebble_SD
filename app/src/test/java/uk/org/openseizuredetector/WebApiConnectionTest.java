package uk.org.openseizuredetector;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.firebase.FirebaseApp;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;


@RunWith(RobolectricTestRunner.class)
public class WebApiConnectionTest {
    WebApiConnection mWac;
    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        FirebaseApp.initializeApp(context);
        mWac = new WebApiConnection(context);
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void isLoggedIn() {
        assertTrue(mWac.isLoggedIn());
        assertFalse(mWac.isLoggedIn());
    }

    @Test
    public void createEvent() {
    }

    @Test
    public void getEvent() {
    }

    @Test
    public void getEvents() {
    }

    @Test
    public void updateEvent() {
    }

    @Test
    public void createDatapoint() {
    }

    @Test
    public void getUserProfile() {
    }
}